package org.springaicommunity.inspector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.util.JsonHelper;

/**
 * An MCP client transport that reports every JSON-RPC message it carries to the Spring AI
 * Inspector, in both directions: the client's requests and notifications (initialize,
 * tools/list, tools/call, replies to the server), and the server's responses, requests
 * (sampling, elicitation) and notifications (logging, progress, list changes). It wraps
 * whatever transport Spring AI configured (stdio, Streamable HTTP, SSE), so it works the
 * same for all of them.
 *
 * <p>Messages are sent and received on Reactor threads: each is classified and attributed
 * to its MCP tool run here (see {@link InspectorToolOrigins}), and serialized later, on the
 * inspector's sender thread.
 */
public class InspectorMcpClientTransport implements McpClientTransport {

	/** Longer strings inside a message are cut, so the reported payload stays valid JSON. */
	private static final int MAX_STRING = 4_000;

	/** Session-level methods: their messages belong to the connection, not to a tool run. */
	private static final Set<String> CONNECTION_METHODS = Set.of("initialize", "notifications/initialized", "ping",
			"tools/list", "prompts/list", "resources/list", "resources/templates/list", "notifications/tools/list_changed",
			"notifications/prompts/list_changed", "notifications/resources/list_changed");

	private final JsonHelper json = new JsonHelper();

	private final String connection;

	private final McpClientTransport delegate;

	private final InspectorClient client;

	private final InspectorToolOrigins origins;

	/** {@code <direction>|<id>} of a request in flight to its method, to name its response. */
	private final Map<String, String> pending = new ConcurrentHashMap<>();

	/** The server's name and version, from its initialize response. */
	private volatile Map<String, Object> serverInfo = Map.of();

	public InspectorMcpClientTransport(String connection, McpClientTransport delegate, InspectorClient client,
			InspectorToolOrigins origins) {
		this.connection = connection;
		this.delegate = delegate;
		this.client = client;
		this.origins = origins;
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return this.delegate.connect(message -> handler.apply(message.doOnNext(m -> report("in", m))));
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		report("out", message);
		return this.delegate.sendMessage(message);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.delegate.unmarshalFrom(data, typeRef);
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.delegate.setExceptionHandler(handler);
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	/** Reports one message; {@code in} is server to client, {@code out} client to server. */
	private void report(String direction, JSONRPCMessage message) {
		try {
			Map<String, Object> event = classify(direction, message);
			this.client.sendLater("mcp-message", () -> {
				event.put("payload", shorten(this.json.convertToMap(message)));
				return event;
			});
		}
		catch (RuntimeException | LinkageError ex) {
			// never break the MCP connection over inspection
		}
	}

	/** Kind, method (for a response, the method it answers), id and tool run of a message. */
	private Map<String, Object> classify(String direction, JSONRPCMessage message) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("connection", this.connection);
		event.put("direction", direction);
		String method = null;
		Object id = null;
		if (message instanceof McpSchema.JSONRPCRequest request) {
			event.put("kind", "request");
			method = request.method();
			id = request.id();
			this.pending.put(direction + "|" + id, method);
		}
		else if (message instanceof McpSchema.JSONRPCNotification notification) {
			event.put("kind", "notification");
			method = notification.method();
		}
		else if (message instanceof McpSchema.JSONRPCResponse response) {
			event.put("kind", "response");
			id = response.id();
			method = this.pending.remove(("in".equals(direction) ? "out" : "in") + "|" + id);
			if (response.error() != null) {
				event.put("error", response.error().message());
			}
			else if ("in".equals(direction)) {
				learn(method, response.result());
			}
		}
		event.put("method", method);
		event.put("id", id);
		event.put("toolId", toolRun(message, method, id));
		return event;
	}

	/** Remembers the server (initialize) and which tools come from it (tools/list). */
	private void learn(String method, Object result) {
		if (!"initialize".equals(method) && !("tools/list".equals(method) && this.origins != null)) {
			return;
		}
		Map<?, ?> map = result instanceof Map<?, ?> m ? m : (result != null ? this.json.convertToMap(result) : Map.of());
		if ("initialize".equals(method) && map.get("serverInfo") instanceof Map<?, ?> server) {
			Map<String, Object> info = new LinkedHashMap<>();
			info.put("server", server.get("name"));
			info.put("serverVersion", server.get("version"));
			this.serverInfo = info;
		}
		if ("tools/list".equals(method) && map.get("tools") instanceof List<?> tools) {
			for (Object t : tools) {
				if (t instanceof Map<?, ?> tool && tool.get("name") instanceof String name) {
					Map<String, Object> origin = new LinkedHashMap<>();
					origin.put("connection", this.connection);
					origin.putAll(this.serverInfo);
					origin.put("tool", name);
					// Named like the default generator does (e.g. get-weather -> get_weather); clashes it
					// renames are recorded by the generator itself.
					this.origins.putListed(McpToolUtils.format(name), origin,
							tool.get("description") instanceof String d ? d : null);
				}
			}
		}
	}

	private String toolRun(JSONRPCMessage message, String method, Object id) {
		if (this.origins == null || (method != null && CONNECTION_METHODS.contains(method))) {
			return null;
		}
		if (message instanceof McpSchema.JSONRPCRequest request && "tools/call".equals(method)) {
			return this.origins.toolCall(this.connection, id, toolName(request.params()));
		}
		if (message instanceof McpSchema.JSONRPCResponse && "tools/call".equals(method)) {
			return this.origins.toolCallResponse(this.connection, id);
		}
		return this.origins.current(this.connection);
	}

	private static String toolName(Object params) {
		if (params instanceof McpSchema.CallToolRequest call) {
			return call.name();
		}
		return params instanceof Map<?, ?> map && map.get("name") instanceof String name ? name : null;
	}

	/** Cuts long strings anywhere in a JSON tree. */
	static Object shorten(Object value) {
		if (value instanceof String text) {
			return InspectorClient.truncate(text, MAX_STRING);
		}
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> copy = new LinkedHashMap<>();
			map.forEach((k, v) -> copy.put(k, shorten(v)));
			return copy;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(InspectorMcpClientTransport::shorten).toList();
		}
		return value;
	}

}
