package org.springaicommunity.inspector.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * order seen. Advisor events are posted synchronously by the starter, so that order is the
 * real one.
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

	private final Correlator correlator = new Correlator();

	private final SseBroadcaster broadcaster = new SseBroadcaster();

	/** Imported events already carry their links; don't infer new ones for them. */
	private boolean importing;

	@Autowired
	public EventStore(InspectorProperties properties) {
		this.log = new EventLog(properties.maxTotalBytes());
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
		this.log.append(event);
		this.broadcaster.broadcast(event);
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
		int newest = events.stream().mapToInt(EventStore::versionOf).max().orElse(0);
		if (newest > EVENTS_VERSION) {
			throw new IllegalArgumentException("recording in event format v" + newest + ", this inspector reads up to v"
					+ EVENTS_VERSION + " (see EVENTS.md)");
		}
		String runId = "imp-" + UUID.randomUUID().toString().substring(0, 6);
		this.importing = true;
		try {
			for (Map<String, Object> original : events) {
				if ("clear".equals(original.get("type"))) {
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

	/** The format version an event declares; 0 for one from before versioning. */
	static int versionOf(Map<String, Object> event) {
		return event.get("v") instanceof Number n ? n.intValue() : 0;
	}

	/** Forgets the events and the open calls (not the upstreams: a running app keeps being forwarded) and tells the browsers. */
	public synchronized void clear() {
		this.log.clear();
		this.correlator.clear();
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "clear");
		this.broadcaster.broadcast(event);
	}

}
