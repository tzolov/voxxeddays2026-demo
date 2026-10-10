package org.springaicommunity.inspector.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The server's event pipeline, in one place for the controllers, the recorder and the
 * preloader: an event posted by a starter or recorded by the proxy is completed with what the
 * server knows ({@link Correlator}), kept ({@link EventLog}) and sent to the browsers
 * ({@link SseBroadcaster}), in that order and under one lock, so the order recorded is the
 * order seen. The starter posts from one background thread, so a run's own events arrive in
 * the order they were built; wire events, recorded here as the proxy forwards, may come a
 * moment earlier than the starter's event for the same call (the UI orders by time).
 *
 * <p>Imported runs (a file exported from the UI, a preloaded recording) are display only:
 * they keep the links they have, register no upstreams, and get a fresh run id so the same
 * file can be imported more than once.
 */
@Component
public class EventStore {

	/**
	 * The event format this server writes (wire events) and reads (imports, preloads), see
	 * EVENTS.md. Events without {@code v} are from before versioning and are read as they are;
	 * a recording with a higher version is refused rather than shown wrong.
	 */
	public static final int EVENTS_VERSION = 1;

	private final EventLog log;

	/** The media the events point at: an item goes when the last event naming it is dropped. */
	private final BlobStore blobs;

	/**
	 * How many events in the log name each blob. The starter uploads a media item once and names
	 * it from every view and turn that carries it, so a blob outlives the first of those events.
	 */
	private final Map<String, Integer> blobRefs = new java.util.HashMap<>();

	/** Where an event names a blob: a marker in a body, or a {@code blobId} field, at any depth. */
	private static final java.util.regex.Pattern BLOB_REF = java.util.regex.Pattern
		.compile("blob:([a-f0-9]{8,32})>|\"blobId\":\"([a-f0-9]{8,32})\"");

	private final Correlator correlator = new Correlator();

	private final SseBroadcaster broadcaster = new SseBroadcaster();

	/** Imported events already carry their links; don't infer new ones for them. */
	private boolean importing;

	@Autowired
	public EventStore(InspectorProperties properties, BlobStore blobs) {
		this.log = new EventLog(properties.maxTotalBytes());
		this.blobs = blobs;
	}

	EventStore(InspectorProperties properties) {
		this(properties, new BlobStore(properties));
	}

	EventStore() {
		this(InspectorProperties.defaults());
	}

	public synchronized void add(Map<String, Object> event) {
		event.putIfAbsent("ts", System.currentTimeMillis());
		if (!this.importing) {
			this.correlator.attribute(event);
			this.correlator.track(event);
		}
		for (String id : blobIdsOf(event)) {
			this.blobRefs.merge(id, 1, Integer::sum);
		}
		for (Map<String, Object> dropped : this.log.append(event)) {
			forgetBlobsOf(dropped);
		}
		this.broadcaster.broadcast(event);
	}

	/** The blobs a dropped event pointed at go once no event in the log names them: they'd only hold the budget. */
	private void forgetBlobsOf(Map<String, Object> event) {
		for (String id : blobIdsOf(event)) {
			Integer left = this.blobRefs.merge(id, -1, Integer::sum);
			if (left == null || left <= 0) {
				this.blobRefs.remove(id);
				this.blobs.remove(id);
			}
		}
	}

	/** The blobs an event names: in body markers and {@code blobId} fields, in nested maps and lists too. */
	static Set<String> blobIdsOf(Object value) {
		Set<String> ids = new java.util.LinkedHashSet<>();
		collectBlobIds(value, ids);
		return ids;
	}

	private static void collectBlobIds(Object value, Set<String> ids) {
		if (value instanceof String s) {
			if (s.contains("blob")) {
				java.util.regex.Matcher m = BLOB_REF.matcher(s);
				while (m.find()) {
					ids.add(m.group(1) != null ? m.group(1) : m.group(2));
				}
			}
		}
		else if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if ("blobId".equals(entry.getKey()) && entry.getValue() instanceof String id && BlobStore.CLIENT_ID.matcher(id).matches()) {
					ids.add(id);
				}
				else {
					collectBlobIds(entry.getValue(), ids);
				}
			}
		}
		else if (value instanceof Iterable<?> list) {
			for (Object item : list) {
				collectBlobIds(item, ids);
			}
		}
	}

	/** A copy of the recorded events, oldest first. */
	public synchronized List<Map<String, Object>> events() {
		return this.log.events();
	}

	/** The original base URL a run's provider calls should be forwarded to, if it reported one. */
	public synchronized String upstream(String runId, String provider) {
		return this.correlator.upstream(runId, provider);
	}

	/** The innermost ChatClient and model call open for the run right now. */
	public synchronized Map<String, Object> openCalls(String runId) {
		return this.correlator.openCalls(runId);
	}

	/** A new browser: it gets the history, then the live events. */
	public SseEmitter subscribe() {
		return this.broadcaster.subscribe(this::events);
	}

	@PreDestroy
	void shutdown() {
		this.broadcaster.shutdown();
	}

	/**
	 * Adds the events of an exported run under a fresh run id, so the same file can be
	 * imported more than once.
	 * @return the new run id
	 * @throws IllegalArgumentException for a recording in a newer format than this server reads
	 */
	public synchronized String importRun(List<Map<String, Object>> events, String source) {
		requireReadable(events);
		String runId = "imp-" + UUID.randomUUID().toString().substring(0, 6);
		this.importing = true;
		try {
			for (Map<String, Object> original : events) {
				// Blob events carry a recording's media (see RunImporter), not something to show.
				if ("clear".equals(original.get("type")) || "blob".equals(original.get("type"))) {
					continue;
				}
				Map<String, Object> event = new LinkedHashMap<>(original);
				event.remove("seq");
				event.put("runId", runId);
				if ("run-start".equals(event.get("type"))) {
					event.put("imported", source);
				}
				add(event);
			}
		}
		finally {
			this.importing = false;
		}
		return runId;
	}

	/** Refuses a recording in a newer format than this server reads, before anything of it is kept. */
	static void requireReadable(List<Map<String, Object>> events) {
		int newest = events.stream().mapToInt(EventStore::versionOf).max().orElse(0);
		if (newest > EVENTS_VERSION) {
			throw new IllegalArgumentException("recording in event format v" + newest + ", this inspector reads up to v"
					+ EVENTS_VERSION + " (see EVENTS.md)");
		}
	}

	/** The format version an event declares; 0 for one from before versioning. */
	static int versionOf(Map<String, Object> event) {
		return event.get("v") instanceof Number n ? n.intValue() : 0;
	}

	/** Forgets the events and the open calls (not the upstreams: a running app keeps being forwarded) and tells the browsers. */
	public synchronized void clear() {
		this.log.clear();
		this.blobRefs.clear();
		this.correlator.clear();
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "clear");
		this.broadcaster.broadcast(event);
	}

}
