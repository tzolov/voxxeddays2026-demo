package org.springaicommunity.inspector;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where tools come from, by the name the model calls them: e.g. the MCP connection and
 * server of an MCP tool. Learned from each connection's {@code tools/list} responses (see
 * {@link InspectorMcpClientTransport}), which covers every MCP tool callback provider, and
 * from the auto-configured tool name generator (see
 * {@link InspectorMcpToolNamePostProcessor}), which knows the exact names when it renames
 * clashing tools. Added to tool runs by {@link InspectorToolObservationHandler}; a tool of
 * the application with the same name as an MCP tool is told apart by its description.
 *
 * <p>Also tracks the MCP tool runs in progress, so {@link InspectorMcpClientTransport} can
 * tell which tool run an MCP message belongs to: a {@code tools/call} goes to the running
 * tool of that name and its response to the same run (by JSON-RPC id); what the server
 * sends in between (logs, progress, sampling requests) goes to the connection's most
 * recently started tool run, since MCP does not tie those to a request at this level.
 */
public class InspectorToolOrigins {

	private final Map<String, Origin> origins = new ConcurrentHashMap<>();

	/** The MCP connection names (transport names) of the application. */
	private final Set<String> connections = ConcurrentHashMap.newKeySet();

	/** MCP tool runs in progress, per connection, most recently started first. */
	private final Map<String, Deque<OpenTool>> openTools = new ConcurrentHashMap<>();

	/** {@code <connection>|<JSON-RPC id>} of a tools/call request to the tool run that sent it. */
	private final Map<String, String> calls = new ConcurrentHashMap<>();

	private record OpenTool(String toolId, String tool, AtomicBoolean called) {
	}

	/** {@code exact}: recorded by the tool name generator, not learned from a tools/list. */
	private record Origin(Map<String, Object> origin, String description, boolean exact) {
	}

	/** The exact origin of a tool name, from the tool name generator. */
	void put(String toolName, Map<String, Object> origin, String description) {
		this.origins.put(toolName, new Origin(origin, description, true));
	}

	/**
	 * An origin learned from a tools/list response: replaces an earlier listing (e.g. after
	 * the server's tools changed), never the generator's exact names.
	 */
	void putListed(String toolName, Map<String, Object> origin, String description) {
		this.origins.compute(toolName,
				(name, old) -> old != null && old.exact() ? old : new Origin(origin, description, false));
	}

	/** An origin as tool runs report it. */
	static Map<String, Object> origin(String connection, String server, String serverVersion, String tool) {
		Map<String, Object> origin = new LinkedHashMap<>();
		origin.put("connection", connection);
		if (server != null) {
			origin.put("server", server);
			origin.put("serverVersion", serverVersion);
		}
		origin.put("tool", tool);
		return origin;
	}

	/**
	 * The origin of the tool with this name and description, or null for a tool of the
	 * application itself. An MCP tool without a description matches by name alone: Spring
	 * AI describes such a tool by its name ("poetic Weather Forecast").
	 */
	Map<String, Object> get(String toolName, String description) {
		Origin origin = toolName == null ? null : this.origins.get(toolName);
		if (origin == null) {
			return null;
		}
		return text(origin.description()).isEmpty() || origin.description().equals(description) ? origin.origin() : null;
	}

	private static String text(String text) {
		return text == null ? "" : text;
	}

	void addConnection(String name) {
		this.connections.add(name);
	}

	Set<String> connections() {
		return this.connections;
	}

	/** An MCP tool run started: {@code origin} as recorded by {@link #put}. */
	void started(String toolId, Map<String, Object> origin) {
		if (origin.get("connection") instanceof String connection) {
			this.openTools.computeIfAbsent(connection, c -> new ConcurrentLinkedDeque<>())
				.addFirst(new OpenTool(toolId, (String) origin.get("tool"), new AtomicBoolean()));
		}
	}

	void stopped(String toolId) {
		this.openTools.values().forEach(runs -> runs.removeIf(t -> t.toolId().equals(toolId)));
		this.calls.values().removeIf(toolId::equals);
	}

	/** The tool run of the connection that started most recently and is still running, or null. */
	String current(String connection) {
		Deque<OpenTool> runs = this.openTools.get(connection);
		OpenTool latest = runs == null ? null : runs.peekFirst();
		return latest == null ? null : latest.toolId();
	}

	/**
	 * The tool run sending a tools/call: the earliest started running tool of that name
	 * that has not called yet, or null when no tracked run matches (the call then belongs to
	 * the connection, not to some other tool run).
	 */
	String toolCall(String connection, Object id, String tool) {
		Deque<OpenTool> runs = this.openTools.get(connection);
		if (runs == null) {
			return null;
		}
		for (Iterator<OpenTool> it = runs.descendingIterator(); it.hasNext();) {
			OpenTool run = it.next();
			if (run.tool() != null && run.tool().equals(tool) && run.called().compareAndSet(false, true)) {
				this.calls.put(connection + "|" + id, run.toolId());
				return run.toolId();
			}
		}
		return null;
	}

	/** The tool run whose tools/call this response answers, or null. */
	String toolCallResponse(String connection, Object id) {
		return this.calls.remove(connection + "|" + id);
	}

}
