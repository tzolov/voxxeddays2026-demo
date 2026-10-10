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
 * <li>{@link #send} is synchronous (localhost, ~1ms) so the inspector sees advisor events
 * and wire calls in their real order. {@link #sendAsync} posts from one background thread,
 * in order, for callers that must not block (e.g. Reactor threads); {@link #sendLater}
 * also builds the event there, for payloads too costly to build on such a thread.</li>
 * <li>After a failed post, publishing pauses for {@link #BACKOFF} and then resumes, so a
 * stopped inspector costs at most one short timeout per pause and a restarted one is
 * picked up again.</li>
 * </ul>
 */
public class InspectorClient {

	static final Duration BACKOFF = Duration.ofSeconds(5);

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

	private final ExecutorService asyncSender = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "spring-ai-inspector-sender");
		thread.setDaemon(true);
		return thread;
	});

	private final JsonHelper json = new JsonHelper();

	private final URI eventsUri;

	private final String runId;

	/** Shared secret the inspector requires on its event API, if it has one configured. */
	private final String token;

	private volatile long pausedUntil;

	/** Delivers a serialized event; HTTP POST to the inspector, replaceable in tests. */
	private final Consumer<String> transport;

	public InspectorClient(String url, String runId) {
		this(url, runId, null);
	}

	public InspectorClient(String url, String runId, String token) {
		this.eventsUri = URI.create(url + "/api/events");
		this.runId = runId;
		this.token = token == null || token.isBlank() ? null : token;
		this.transport = this::post;
	}

	InspectorClient(String runId, Consumer<String> transport) {
		this.eventsUri = null;
		this.runId = runId;
		this.token = null;
		this.transport = transport;
	}

	/** Builds and posts an event on the calling thread. Never throws. */
	public void send(String type, Supplier<Map<String, Object>> payload) {
		String body = build(type, payload);
		if (body != null) {
			this.transport.accept(body);
		}
	}

	/** Like {@link #send(String, Supplier)}, but posts from a background thread, in order. */
	public void sendAsync(String type, Supplier<Map<String, Object>> payload) {
		String body = build(type, payload);
		if (body != null) {
			this.asyncSender.execute(() -> this.transport.accept(body));
		}
	}

	/**
	 * Like {@link #sendAsync}, but also builds the event on the background thread, for
	 * payloads that are costly to build (e.g. serializing a large MCP message). The
	 * timestamp is still taken now; the payload must not depend on the calling thread.
	 */
	public void sendLater(String type, Supplier<Map<String, Object>> payload) {
		long ts = System.currentTimeMillis();
		if (ts < this.pausedUntil) {
			return;
		}
		this.asyncSender.execute(() -> {
			String body = build(type, payload, ts);
			if (body != null) {
				this.transport.accept(body);
			}
		});
	}

	public void send(String type, Map<String, Object> payload) {
		send(type, () -> payload);
	}

	private String build(String type, Supplier<Map<String, Object>> payload) {
		return build(type, payload, System.currentTimeMillis());
	}

	private String build(String type, Supplier<Map<String, Object>> payload, long ts) {
		if (System.currentTimeMillis() < this.pausedUntil) {
			return null;
		}
		try {
			Map<String, Object> event = new LinkedHashMap<>();
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

	private void post(String body) {
		if (System.currentTimeMillis() < this.pausedUntil) {
			return;
		}
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(this.eventsUri)
				.timeout(Duration.ofSeconds(1))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
			if (this.token != null) {
				request.header("X-Inspector-Token", this.token);
			}
			this.httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (Exception ex) {
			this.pausedUntil = System.currentTimeMillis() + BACKOFF.toMillis();
			System.err.println("Spring AI Inspector unreachable (" + ex.getMessage() + "), pausing events for "
					+ BACKOFF.toSeconds() + "s");
		}
	}

}
