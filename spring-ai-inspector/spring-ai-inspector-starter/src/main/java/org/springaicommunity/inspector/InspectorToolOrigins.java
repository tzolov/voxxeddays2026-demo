package org.springaicommunity.inspector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where tools come from, by the name the model calls them: e.g. the MCP connection and
 * server of an MCP tool (see {@link InspectorMcpToolNamePostProcessor}). Recorded while
 * the tool callbacks are built, added to tool runs by
 * {@link InspectorToolObservationHandler}.
 */
public class InspectorToolOrigins {

	private final Map<String, Map<String, Object>> origins = new ConcurrentHashMap<>();

	void put(String toolName, Map<String, Object> origin) {
		this.origins.put(toolName, origin);
	}

	/** The origin of the tool with this name, or null for a tool of the application itself. */
	Map<String, Object> get(String toolName) {
		return toolName == null ? null : this.origins.get(toolName);
	}

}
