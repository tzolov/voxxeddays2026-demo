package org.springaicommunity.inspector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * Reports every tool execution (including MCP tools) to the Spring AI Inspector, using the
 * observation Spring AI already emits around each tool call. The tool observation's parent
 * is the ChatClient call that requested it (Spring AI sets it explicitly, also for
 * streamed calls whose tools run on other threads), which names the call the run belongs
 * to; the observation is tagged with the run's id, so a ChatClient call the tool makes
 * meanwhile (a sub-agent) finds it as its {@code parentToolId} (see
 * {@link InspectorCorrelation}).
 */
public class InspectorToolObservationHandler implements ObservationHandler<ToolCallingObservationContext> {

	/**
	 * One execution may be observed more than once with the same tool-call id, e.g. by a
	 * {@code ToolCallingManager} that wraps the default one and observes again. Spring AI
	 * itself starts exactly one observation per execution. Each execution is keyed by the
	 * model's tool-call id and only the outermost start and stop are reported.
	 */
	private final Map<Object, Active> active = new java.util.concurrent.ConcurrentHashMap<>();

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
		context.put(InspectorCorrelation.TOOL_ID, toolId); // a sub-agent it calls names it
		String clientCallId = InspectorCorrelation.find(context.getParentObservation(), InspectorCorrelation.CALL_ID);
		if (clientCallId == null) {
			clientCallId = InspectorCorrelation.currentCallId();
		}
		String callId = clientCallId;
		String thread = Thread.currentThread().getName();
		// Where the tool comes from, e.g. the MCP connection and server of an MCP tool.
		Map<String, Object> mcp = this.origins.get(context.getToolDefinition().name(),
				context.getToolDefinition().description());
		if (mcp != null) {
			this.origins.started(toolId, mcp); // its MCP messages are attributed to this run
		}
		this.client.send("tool-start", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("toolId", toolId);
			event.put("clientCallId", callId);
			event.put("toolCallId", context.getToolCallId());
			event.put("name", context.getToolDefinition().name());
			event.put("toolType", context.getToolType());
			event.put("description", InspectorClient.truncate(context.getToolDefinition().description(), 500));
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
		this.origins.stopped(execution.toolId);
		long durationMs = System.currentTimeMillis() - execution.start;
		this.client.send("tool-end", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("toolId", execution.toolId);
			event.put("durationMs", durationMs);
			event.put("result", InspectorClient.truncate(context.getToolCallResult(), MAX_RESULT));
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

}
