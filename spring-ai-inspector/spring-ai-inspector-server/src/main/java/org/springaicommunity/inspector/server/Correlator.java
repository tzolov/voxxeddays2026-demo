package org.springaicommunity.inspector.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the server adds to the events of a live run (see EVENTS.md): which ChatClient and model
 * calls are open per run, so a wire request at the proxy or a search on another thread can be
 * attributed to the call that caused it; which tool runs are open, so a call started while a
 * tool runs (a sub-agent, an A2A remote agent in another run) gets its parent; and each run's
 * upstreams, fixed by its first {@code run-start}, where the proxy forwards the run's API keys.
 * Not thread-safe; {@link EventStore} serializes the calls.
 */
final class Correlator {

	private record OpenTool(String toolId, String runId, Object clientCallId) {
	}

	/** Original provider base URLs per run, reported by the starter in run-start. */
	private final Map<String, Map<String, String>> runUpstreams = new HashMap<>();

	private final Map<String, Deque<String>> openClientCalls = new HashMap<>();

	private final Map<String, Deque<String>> openModelCalls = new HashMap<>();

	/** Tool calls that have started but not ended, in start order, across all runs. */
	private final Map<String, OpenTool> openTools = new LinkedHashMap<>();

	/** The original base URL a run's provider calls should be forwarded to, if it reported one. */
	String upstream(String runId, String provider) {
		Map<String, String> upstreams = this.runUpstreams.get(runId);
		return upstreams == null ? null : upstreams.get(provider);
	}

	/** The innermost ChatClient and model call open for the run right now. */
	Map<String, Object> openCalls(String runId) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("clientCallId", peek(this.openClientCalls, runId));
		result.put("modelCallId", peek(this.openModelCalls, runId));
		return result;
	}

	/**
	 * Vector searches and tool runs can execute on other threads (e.g. 05-1 retrieves on a
	 * TaskExecutor), where the demo can't tell which ChatClient call they belong to.
	 * Attribute them to the call open for the run right now, as for wire calls.
	 */
	void attribute(Map<String, Object> event) {
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

	/** Keeps up with what the event opens or closes: calls, tool runs, a run's upstreams. */
	void track(Map<String, Object> event) {
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

	/** Forgets the open calls and tools. The upstreams stay: a run still running keeps being forwarded. */
	void clear() {
		this.openClientCalls.clear();
		this.openModelCalls.clear();
		this.openTools.clear();
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

}
