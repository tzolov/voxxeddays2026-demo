package org.springaicommunity.inspector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.ai.util.JsonHelper;

/**
 * Posts events to the Spring AI Inspector.
 *
 * <p>The instrumentation must never break or noticeably slow the application it observes:
 * <ul>
 * <li>Events are built lazily inside a guard: a failure while building one (an odd
 * message, a throwing {@code toString()}) drops that event, never the application's
 * call.</li>
 * <li>{@link #send} builds the event on the caller's thread and posts it from one background
 * thread, in order, so the caller (an advisor, a tool, a Reactor callback) never waits for the
 * inspector; {@link #sendLater} also builds the event there, for payloads too costly to build
 * on the calling thread. The UI orders by the events' timestamps, not by arrival.</li>
 * <li>After a failed post, publishing pauses for {@link #BACKOFF} and then resumes, so a
 * stopped inspector costs at most one short timeout per pause and a restarted one is
 * picked up again. A restarted inspector has forgotten the run, so the run's
 * {@link #announce announcement} ({@code run-start}: app, upstreams, ...) is posted again
 * before the first event that reaches it.</li>
 * </ul>
 */
public class InspectorClient {

	static final Duration BACKOFF = Duration.ofSeconds(5);

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

	/** The background thread, so work already running on it can tell (see {@link #sendBlob}). */
	private volatile Thread senderThread;

	private final ExecutorService asyncSender = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "spring-ai-inspector-sender");
		thread.setDaemon(true);
		this.senderThread = thread;
		return thread;
	});

	private final JsonHelper json = new JsonHelper();

	/**
	 * Media uploaded so far, by the identity of its data, so the image in a message is uploaded once
	 * however many views and turns show it. Weak: forgotten with the data; cleared when the inspector
	 * was unreachable, since it may have restarted without the blobs.
	 */
	private final Map<Object, Media> uploaded = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private final URI eventsUri;

	private final URI blobsUri;

	private final String runId;

	/** Shared secret the inspector requires on its event API, if it has one configured. */
	private final String token;

	private volatile long pausedUntil;

	/** Set after a failed post: the inspector may have restarted and forgotten this run. */
	private volatile boolean down;

	/** The event that (re)introduces the run, see {@link #announce}. */
	private volatile Announcement announcement;

	private record Announcement(String type, Supplier<Map<String, Object>> payload) {
	}

	/**
	 * Delivers a serialized event: an HTTP POST to the inspector, replaceable in tests. It
	 * throws when the inspector can't be reached, which pauses publishing.
	 */
	private final Consumer<String> transport;

	/** Uploads media under an id; throws when the inspector can't be reached. See {@link #sendBlob}. */
	private final BlobUploader blobTransport;

	/** An upload: the id the client minted, the bytes and their type. */
	@FunctionalInterface
	interface BlobUploader {

		void upload(String id, byte[] bytes, String contentType);

	}

	/** Media the client reported: the id its event refers to, the type and the size. */
	public record Media(String id, String contentType, int size) {
	}

	public InspectorClient(String url, String runId) {
		this(url, runId, null);
	}

	public InspectorClient(String url, String runId, String token) {
		this.eventsUri = URI.create(url + "/api/events");
		this.blobsUri = URI.create(url + "/api/blobs");
		this.runId = runId;
		this.token = token == null || token.isBlank() ? null : token;
		this.transport = this::httpPost;
		this.blobTransport = this::httpUpload;
	}

	InspectorClient(String runId, Consumer<String> transport) {
		this(runId, transport, (id, bytes, type) -> {
		});
	}

	InspectorClient(String runId, Consumer<String> transport, BlobUploader blobs) {
		this.eventsUri = null;
		this.blobsUri = null;
		this.runId = runId;
		this.token = null;
		this.transport = transport;
		this.blobTransport = blobs;
	}

	/**
	 * Builds the event now, on the calling thread (so it describes the moment, and the
	 * timestamp is the moment's), and posts it from the background thread, after everything
	 * handed to that thread before: earlier events and the uploads they name. The caller never
	 * waits for the inspector. Never throws.
	 */
	public void send(String type, Supplier<Map<String, Object>> payload) {
		String body = build(type, payload);
		if (body != null) {
			background(() -> deliver(body));
		}
	}

	/** Hands work to the background thread; after shutdown the work is dropped, never thrown at the caller. */
	private void background(Runnable work) {
		try {
			this.asyncSender.execute(work);
		}
		catch (java.util.concurrent.RejectedExecutionException ex) {
			// shut down: the application is stopping, the event or upload is lost with it
		}
	}

	/**
	 * Posts the event that introduces the run and keeps it: after the inspector was
	 * unreachable, it is posted again (with a fresh timestamp, marked {@code reannounce})
	 * before the next event, so an inspector that restarted meanwhile knows the run and its
	 * upstreams again. The payload is built on each post.
	 */
	public void announce(String type, Supplier<Map<String, Object>> payload) {
		this.announcement = new Announcement(type, payload);
		send(type, payload);
	}

	/**
	 * Like {@link #send}, but also builds the event on the background thread, for payloads
	 * that are costly to build (e.g. serializing a large MCP message). The timestamp is still
	 * taken now; the payload must not depend on the calling thread.
	 */
	public void sendLater(String type, Supplier<Map<String, Object>> payload) {
		long ts = System.currentTimeMillis();
		if (ts < this.pausedUntil) {
			return;
		}
		background(() -> {
			String body = build(type, payload, ts);
			if (body != null) {
				deliver(body);
			}
		});
	}

	/**
	 * Hands a built event to the transport, re-announcing the run first when the inspector
	 * was unreachable since the last successful post. A failure pauses publishing.
	 */
	private void deliver(String body) {
		if (System.currentTimeMillis() < this.pausedUntil) {
			return;
		}
		if (this.down) {
			Announcement announcement = this.announcement;
			if (announcement != null) {
				String again = build(announcement.type(), () -> {
					Map<String, Object> event = new LinkedHashMap<>(announcement.payload().get());
					event.put("reannounce", true);
					return event;
				});
				if (again == null || !post(again)) {
					return;
				}
			}
			this.down = false;
			this.uploaded.clear(); // the inspector may have restarted without the blobs
		}
		post(body);
	}

	/** True when the transport took the event; false after a failure, which pauses publishing. */
	private boolean post(String body) {
		try {
			this.transport.accept(body);
			return true;
		}
		catch (RuntimeException ex) {
			this.down = true;
			this.pausedUntil = System.currentTimeMillis() + BACKOFF.toMillis();
			System.err.println("Spring AI Inspector unreachable (" + ex.getMessage() + "), pausing events for "
					+ BACKOFF.toSeconds() + "s");
			return false;
		}
	}

	public void send(String type, Map<String, Object> payload) {
		send(type, () -> payload);
	}

	/**
	 * Reports the media of a model call made in the JVM (an image generated, speech
	 * synthesized), so the inspector can show it. Returns at once with the id the event
	 * should name and the type (the given one, or sniffed from the bytes); the bytes go to
	 * the inspector from the background thread, ahead of any event posted afterwards, so an
	 * event never names a blob that hasn't arrived. Null when the media isn't reported (empty,
	 * too large, publishing paused).
	 */
	public Media sendBlob(byte[] bytes, String contentType) {
		if (bytes == null || bytes.length == 0 || bytes.length > InspectorMedia.MAX_BYTES
				|| System.currentTimeMillis() < this.pausedUntil) {
			return null;
		}
		String type = InspectorMedia.type(contentType, bytes);
		String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		if (Thread.currentThread() == this.senderThread) {
			upload(id, bytes, type); // called while building an event there (sendLater): before that event goes
		}
		else {
			background(() -> upload(id, bytes, type));
		}
		return new Media(id, type, bytes.length);
	}

	private void upload(String id, byte[] bytes, String type) {
		if (System.currentTimeMillis() < this.pausedUntil) {
			return;
		}
		try {
			this.blobTransport.upload(id, bytes, type);
		}
		catch (RuntimeException ex) {
			this.down = true;
			this.pausedUntil = System.currentTimeMillis() + BACKOFF.toMillis();
			System.err.println("Spring AI Inspector unreachable (" + ex.getMessage() + "), pausing events for "
					+ BACKOFF.toSeconds() + "s");
		}
	}

	/** The media already uploaded under this key, if any (see {@link #sendBlob(Object, byte[], String)}). */
	public Media uploaded(Object key) {
		return key == null ? null : this.uploaded.get(key);
	}

	/**
	 * Like {@link #sendBlob(byte[], String)}, but uploads the media once per {@code key} (the
	 * object holding its bytes: the same image appears in the CLIENT and MODEL views of a call
	 * and in every later turn of the conversation) and answers with the same id afterwards.
	 */
	public Media sendBlob(Object key, byte[] bytes, String contentType) {
		Media before = this.uploaded.get(key);
		if (before != null) {
			return before;
		}
		Media media = sendBlob(bytes, contentType);
		if (media != null) {
			this.uploaded.put(key, media);
		}
		return media;
	}

	private String build(String type, Supplier<Map<String, Object>> payload) {
		return build(type, payload, System.currentTimeMillis());
	}

	/**
	 * The version of the event format this starter emits, carried as {@code v} on every event
	 * (see EVENTS.md in the inspector module). Bumped together with the server's and the UI's
	 * when a field changes meaning or goes away; readers accept older events and refuse newer.
	 */
	public static final int EVENTS_VERSION = 1;

	private String build(String type, Supplier<Map<String, Object>> payload, long ts) {
		if (System.currentTimeMillis() < this.pausedUntil) {
			return null;
		}
		try {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("v", EVENTS_VERSION);
			event.put("type", type);
			event.put("runId", this.runId);
			event.put("ts", ts);
			event.putAll(payload.get());
			return this.json.toJson(event);
		}
		catch (RuntimeException | LinkageError ex) {
			return null; // drop the event, never the application's call
		}
	}

	/**
	 * Stops the background sender, waiting a few seconds for what was handed to it: the last
	 * uploads, the events queued behind them and the run-end event. The thread is a daemon,
	 * so without the wait a short-lived application exits before its last answer is posted.
	 */
	public void shutdown() {
		this.asyncSender.shutdown();
		try {
			if (!this.asyncSender.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
				System.err.println("Spring AI Inspector: events still queued at shutdown were dropped");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Waits until the events handed to the background thread so far are posted (tests). */
	void awaitBackground() {
		try {
			this.asyncSender.submit(() -> {
			}).get();
		}
		catch (Exception ex) {
			// nothing to wait for
		}
	}

	/** Cuts long text for an event, marking the cut with an ellipsis. */
	static String truncate(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
	}

	/** Uploads media to the inspector under the client's id; a 4xx (declined) is not a failure. */
	private void httpUpload(String id, byte[] bytes, String contentType) {
		try {
			// Not resolve(): with a URL ending in "/", the path "//api/blobs" would resolve as a host.
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.blobsUri + "/" + id))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", contentType)
				.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
			if (this.token != null) {
				request.header("X-Inspector-Token", this.token);
			}
			HttpResponse<Void> response = this.httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() >= 500) {
				throw new IllegalStateException("HTTP " + response.statusCode());
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", ex);
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	/** The HTTP transport: posts to the inspector, throwing when it can't be reached. */
	private void httpPost(String body) {
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(this.eventsUri)
				.timeout(Duration.ofSeconds(1))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
			if (this.token != null) {
				request.header("X-Inspector-Token", this.token);
			}
			HttpResponse<Void> response = this.httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() >= 500) {
				throw new IllegalStateException("HTTP " + response.statusCode());
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", ex);
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
