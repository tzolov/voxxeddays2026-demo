package org.springaicommunity.inspector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * Reports every tool execution (including MCP tools) to the Spring AI Inspector, using the
 * observation Spring AI already emits around each tool call. Tool calls run on the
 * thread of the ChatClient call that requested them, so they are attributed to the
 * innermost open {@link InspectorAdvisor} call.
 */
public class InspectorToolObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

	/**
	 * Some tools (e.g. MCP tools) are observed twice for one call. Key each execution by
	 * the model's tool-call id and report only the outermost start/stop.
	 */
	private final java.util.Map<Object, Active> active = new java.util.concurrent.ConcurrentHashMap<>();

	private static final class Active {

		final String toolId;

		final long start = System.currentTimeMillis();

		int depth;

		Active(String toolId) {
			this.toolId = toolId;
		}

	}

	private static final int MAX_RESULT = 20_000;

	private final InspectorClient client;

	private final InspectorToolOrigins origins;

	public InspectorToolObservationHandler(InspectorClient client) {
		this(client, new InspectorToolOrigins());
	}

	public InspectorToolObservationHandler(InspectorClient client, InspectorToolOrigins origins) {
		this.client = client;
		this.origins = origins;
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return context instanceof ToolCallingObservationContext;
	}

	@Override
	public void onStart(ToolCallingObservationContext context) {
		Active execution = this.active.computeIfAbsent(key(context),
				k -> new Active(UUID.randomUUID().toString().substring(0, 8)));
		synchronized (execution) {
			if (execution.depth++ > 0) {
				return; // the same tool call, observed again
			}
		}
		String toolId = execution.toolId;
		String clientCallId = InspectorAdvisor.currentCallId();
		String thread = Thread.currentThread().getName();
		// Where the tool comes from, e.g. the MCP connection and server of an MCP tool.
		Map<String, Object> mcp = this.origins.get(context.getToolDefinition().name());
		this.client.send("tool-start", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("toolId", toolId);
			event.put("clientCallId", clientCallId);
			event.put("toolCallId", context.getToolCallId());
			event.put("name", context.getToolDefinition().name());
			event.put("toolType", context.getToolType());
			event.put("description", truncate(context.getToolDefinition().description(), 500));
			event.put("arguments", context.getToolCallArguments());
			event.put("thread", thread);
			if (mcp != null) {
				event.put("mcp", mcp);
			}
			return event;
		});
	}

	@Override
	public void onStop(ToolCallingObservationContext context) {
		Object key = key(context);
		Active execution = this.active.get(key);
		if (execution == null) {
			return;
		}
		synchronized (execution) {
			if (--execution.depth > 0) {
				return; // an inner observation of the same tool call
			}
		}
		this.active.remove(key);
		long durationMs = System.currentTimeMillis() - execution.start;
		this.client.send("tool-end", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("toolId", execution.toolId);
			event.put("durationMs", durationMs);
			event.put("result", truncate(context.getToolCallResult(), MAX_RESULT));
			if (context.getError() != null) {
				event.put("error", context.getError().getClass().getSimpleName() + ": " + context.getError().getMessage());
			}
			return event;
		});
	}

	/** The model's tool-call id, or the context itself when there is none. */
	private static Object key(ToolCallingObservationContext context) {
		String id = context.getToolCallId();
		return id == null || id.isBlank() ? context : id;
	}

	private static String truncate(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
	}

}
