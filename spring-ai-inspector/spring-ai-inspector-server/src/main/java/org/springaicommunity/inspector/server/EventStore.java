package org.springaicommunity.inspector.server;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory event log plus live fan-out to the browser over SSE.
 *
 * <p>It also tracks which ChatClient / model calls are currently open per run, so a
 * wire request arriving at the proxy can be attributed to the advisor call that caused
 * it. Advisor events are posted synchronously by the demos, so the order seen here is
 * the real order.
 *
 * <p>Events are recorded under a lock but sent to browsers from a single dispatcher
 * thread, so a slow or stalled browser tab never delays the applications posting events.
 *
 * <p>A run's upstreams are fixed by its first {@code run-start}: the proxy forwards the
 * run's API keys there, so a later event (from anyone able to reach the port) must not be
 * able to move them. Only {@code http(s)} upstreams are accepted. Imported runs register
 * nothing: their events are display only.
 */
@Component
public class EventStore {

	private static final int MAX_EVENTS = 20_000;

	/** Bytes a stored event costs beyond its text fields: the map, the small values. */
	private static final int EVENT_OVERHEAD = 512;

	private record Stored(Map<String, Object> event, int size) {
	}

	private final Deque<Stored> events = new ArrayDeque<>();

	private final long maxTotalBytes;

	private long totalBytes;

	private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

	/** Sends to browsers, in event order, off the recording lock. */
	private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "inspector-sse-dispatcher");
		thread.setDaemon(true);
		return thread;
	});

	/** Original provider base URLs per run, reported by the starter in run-start. */
	private final Map<String, Map<String, String>> runUpstreams = new HashMap<>();

	private final Map<String, Deque<String>> openClientCalls = new HashMap<>();

	private final Map<String, Deque<String>> openModelCalls = new HashMap<>();

	/** Tool calls that have started but not ended, in start order, across all runs. */
	private final Map<String, OpenTool> openTools = new LinkedHashMap<>();

	private long seq;

	/** Imported events already carry their links; don't infer new ones for them. */
	private boolean importing;

	private record OpenTool(String toolId, String runId, Object clientCallId) {
	}

	/** A browser connection, and the last event it received (to skip duplicates). */
	private static final class Subscriber {

		final SseEmitter emitter;

		long lastSeq = -1;

		Subscriber(SseEmitter emitter) {
			this.emitter = emitter;
		}

	}

	@Autowired
	public EventStore(InspectorProperties properties) {
		this.maxTotalBytes = properties.maxTotalBytes();
	}

	EventStore() {
		this(InspectorProperties.defaults());
	}

	public synchronized void add(Map<String, Object> event) {
		event.put("seq", ++this.seq);
		event.putIfAbsent("ts", System.currentTimeMillis());
		if (!this.importing) {
			attribute(event);
			track(event);
		}
		int size = sizeOf(event);
		this.events.addLast(new Stored(event, size));
		this.totalBytes += size;
		while (this.events.size() > MAX_EVENTS || (this.totalBytes > this.maxTotalBytes && this.events.size() > 1)) {
			this.totalBytes -= this.events.pollFirst().size();
		}
		this.dispatcher.execute(() -> broadcast(event));
	}

	/** Roughly what an event costs in memory: its text fields (bodies, results) plus overhead. */
	private static int sizeOf(Map<String, Object> event) {
		long size = EVENT_OVERHEAD;
		for (Object value : event.values()) {
			if (value instanceof String s) {
				size += 2L * s.length();
			}
		}
		return (int) Math.min(size, Integer.MAX_VALUE);
	}

	/** A copy of the recorded events, oldest first. */
	public synchronized List<Map<String, Object>> events() {
		List<Map<String, Object>> copy = new ArrayList<>(this.events.size());
		for (Stored stored : this.events) {
			copy.add(stored.event());
		}
		return copy;
	}

	/** The original base URL a run's provider calls should be forwarded to, if it reported one. */
	public synchronized String upstream(String runId, String provider) {
		Map<String, String> upstreams = this.runUpstreams.get(runId);
		return upstreams == null ? null : upstreams.get(provider);
	}

	/** The innermost ChatClient and model call open for the run right now. */
	public synchronized Map<String, Object> openCalls(String runId) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("clientCallId", peek(this.openClientCalls, runId));
		result.put("modelCallId", peek(this.openModelCalls, runId));
		return result;
	}

	/**
	 * Replays the history to a new browser, then streams live events. Both happen on the
	 * dispatcher thread, in order; {@link Subscriber#lastSeq} drops events that are both in
	 * the replayed history and queued for broadcast.
	 */
	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(0L);
		Subscriber subscriber = new Subscriber(emitter);
		emitter.onCompletion(() -> this.subscribers.remove(subscriber));
		emitter.onTimeout(() -> this.subscribers.remove(subscriber));
		emitter.onError(ex -> this.subscribers.remove(subscriber));
		this.dispatcher.execute(() -> {
			List<Map<String, Object>> history = events();
			try {
				for (Map<String, Object> event : history) {
					send(subscriber, event);
				}
				this.subscribers.add(subscriber);
			}
			catch (IOException | RuntimeException ex) {
				emitter.completeWithError(ex);
			}
		});
		return emitter;
	}

	@PreDestroy
	void shutdown() {
		this.dispatcher.shutdownNow();
	}

	/**
	 * Adds the events of an exported run under a fresh run id, so the same file can be
	 * imported more than once.
	 * @return the new run id
	 */
	public synchronized String importRun(List<Map<String, Object>> events, String source) {
		String runId = "imp-" + UUID.randomUUID().toString().substring(0, 6);
		this.importing = true;
		try {
			importEvents(events, source, runId);
		}
		finally {
			this.importing = false;
		}
		return runId;
	}

	private void importEvents(List<Map<String, Object>> events, String source, String runId) {
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

	public synchronized void clear() {
		this.events.clear();
		this.totalBytes = 0;
		this.openClientCalls.clear();
		this.openModelCalls.clear();
		this.openTools.clear();
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "clear");
		this.dispatcher.execute(() -> broadcast(event));
	}

	/**
	 * Vector searches and tool runs can execute on other threads (e.g. 05-1 retrieves on a
	 * TaskExecutor), where the demo can't tell which ChatClient call they belong to.
	 * Attribute them to the call open for the run right now, as for wire calls.
	 */
	private void attribute(Map<String, Object> event) {
		Object type = event.get("type");
		if (!(event.get("runId") instanceof String runId)) {
			return;
		}
		// Searches and tool runs belong to the open call even when made on another thread. Adds
		// and embedding calls carry their call from the thread that made them: without one they
		// stay at run level (e.g. ingesting in the background while a chat call is open).
		boolean search = "vector-search".equals(type) || ("vector-start".equals(type) && "search".equals(event.get("op")));
		if ((search || "tool-start".equals(type)) && event.get("clientCallId") == null) {
			event.put("clientCallId", peek(this.openClientCalls, runId));
		}
		if ("client-request".equals(type) && event.get("parentId") == null) {
			inferParent(event, runId);
		}
	}

	/**
	 * A ChatClient call without a parent may still have been triggered by another call:
	 * <ul>
	 * <li>in the same run, from another thread, while a tool of that run is running (e.g. a
	 * background sub-agent started by the {@code Task} tool): nest it under the call that
	 * owns the tool, and name the tool ({@code parentToolId}). Without an open tool it is
	 * left top-level, so concurrent requests in a server application are not nested under
	 * each other;</li>
	 * <li>in another JVM (e.g. an A2A remote agent): link it to the tool call that is open
	 * right now in another run, such as the caller's {@code Task} tool.</li>
	 * </ul>
	 * Both are inferred by timing, not propagated headers, so they assume one agent
	 * conversation is active at a time.
	 */
	private void inferParent(Map<String, Object> event, String runId) {
		OpenTool sameRun = null;
		OpenTool caller = null;
		for (OpenTool tool : this.openTools.values()) { // in start order: keep the latest
			if (tool.runId().equals(runId)) {
				sameRun = tool;
			}
			else {
				caller = tool;
			}
		}
		if (sameRun != null && sameRun.clientCallId() != null) {
			event.put("parentId", sameRun.clientCallId());
			event.put("parentToolId", sameRun.toolId());
			event.put("parentInferred", true);
			return;
		}
		if (caller != null) {
			Map<String, Object> link = new LinkedHashMap<>();
			link.put("runId", caller.runId());
			link.put("toolId", caller.toolId());
			link.put("clientCallId", caller.clientCallId());
			event.put("linkedFrom", link);
		}
	}

	private void track(Map<String, Object> event) {
		String runId = (String) event.get("runId");
		Object type = event.get("type");
		if ("tool-start".equals(type) && runId != null && event.get("toolId") instanceof String toolId) {
			this.openTools.put(toolId, new OpenTool(toolId, runId, event.get("clientCallId")));
		}
		if ("tool-end".equals(type)) {
			this.openTools.remove(event.get("toolId"));
		}
		if ("client-response".equals(type) && runId != null) {
			// A finished call has no running tools; drop any whose end we never saw, so a
			// stale "open" tool can't attract links from later, unrelated runs.
			Object finished = event.get("callId");
			this.openTools.values().removeIf(t -> t.runId().equals(runId) && Objects.equals(finished, t.clientCallId()));
		}
		if ("run-end".equals(type) && runId != null) {
			this.openTools.values().removeIf(t -> t.runId().equals(runId));
		}
		if ("run-start".equals(type) && runId != null && event.get("upstreams") instanceof Map<?, ?> upstreams
				&& !this.runUpstreams.containsKey(runId)) {
			// First run-start wins; only http(s) URLs, so the proxy never forwards anywhere odd.
			Map<String, String> byProvider = new HashMap<>();
			upstreams.forEach((k, v) -> {
				String url = String.valueOf(v);
				if (url.startsWith("http://") || url.startsWith("https://")) {
					byProvider.put(String.valueOf(k), url);
				}
			});
			this.runUpstreams.put(runId, byProvider);
		}
		String callId = (String) event.get("callId");
		if (runId == null || callId == null) {
			return;
		}
		switch (String.valueOf(event.get("type"))) {
			case "client-request" -> this.openClientCalls.computeIfAbsent(runId, k -> new ArrayDeque<>()).push(callId);
			case "client-response" -> remove(this.openClientCalls, runId, callId);
			case "model-request" -> this.openModelCalls.computeIfAbsent(runId, k -> new ArrayDeque<>()).push(callId);
			case "model-response" -> remove(this.openModelCalls, runId, callId);
			default -> {
			}
		}
	}

	private static String peek(Map<String, Deque<String>> calls, String runId) {
		Deque<String> deque = calls.get(runId);
		return deque == null ? null : deque.peek();
	}

	private static void remove(Map<String, Deque<String>> calls, String runId, String callId) {
		Deque<String> deque = calls.get(runId);
		if (deque != null) {
			deque.remove(callId);
		}
	}

	/** Runs on the dispatcher thread only. */
	private void broadcast(Map<String, Object> event) {
		for (Subscriber subscriber : this.subscribers) {
			try {
				send(subscriber, event);
			}
			catch (Exception ex) {
				this.subscribers.remove(subscriber);
			}
		}
	}

	private static void send(Subscriber subscriber, Map<String, Object> event) throws IOException {
		Object seq = event.get("seq");
		if (seq instanceof Long s) {
			if (s <= subscriber.lastSeq) {
				return; // already sent as part of the replayed history
			}
			subscriber.lastSeq = s;
		}
		subscriber.emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
	}

}
