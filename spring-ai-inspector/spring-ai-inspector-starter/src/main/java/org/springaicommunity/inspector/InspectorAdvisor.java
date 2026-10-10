package org.springaicommunity.inspector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;

/**
 * Reports ChatClient traffic to the Spring AI Inspector. Two instances are registered on
 * every {@code ChatClient.Builder}:
 * <ul>
 * <li>{@link Phase#CLIENT} runs first in the chain: it sees the prompt exactly as the
 * application wrote it and the final answer.</li>
 * <li>{@link Phase#MODEL} runs last, right before the model: it sees the prompt after
 * every other advisor (memory, RAG, guardrails, ...) has had its say.</li>
 * </ul>
 * Comparing the two is what lets the inspector show what the advisors added.
 */
public class InspectorAdvisor implements CallAdvisor, StreamAdvisor {

	public enum Phase {

		CLIENT, MODEL

	}

	static final String CALL_ID = "inspector.callId";

	/**
	 * Advisor context keys whose values are never reported: the context is the app's own
	 * (a ToolContext, a tenant's credentials) and it leaves the JVM with the event.
	 */
	static final Pattern SECRET_KEY = Pattern
		.compile("(?i)api[-_ ]?key|secret|password|passwd|credential|authorization|access[-_ ]?token|auth[-_ ]?token|bearer");

	/** Open CLIENT calls on this thread, so nested ChatClient calls (sub-agents) form a tree. */
	private static final ThreadLocal<Deque<String>> OPEN_CALLS = ThreadLocal.withInitial(ArrayDeque::new);

	/**
	 * Tool runs in progress on this thread, innermost first, with the call that ran each, so a
	 * ChatClient call made by a tool (a sub-agent) names the tool that started it.
	 */
	private static final ThreadLocal<Deque<ToolRun>> OPEN_TOOLS = ThreadLocal
		.withInitial(java.util.concurrent.ConcurrentLinkedDeque::new);

	record ToolRun(String toolId, String clientCallId) {
	}

	private final InspectorClient client;

	private final Phase phase;

	private final InspectorMemoryReader memoryReader;

	/** The innermost ChatClient call running on this thread, if any. */
	static String currentCallId() {
		return OPEN_CALLS.get().peek();
	}

	/**
	 * A tool run starting on this thread. Returns the thread's open tool runs, from which the
	 * run is removed when it ends, even if its end is observed on another thread.
	 */
	static Deque<ToolRun> toolStarted(String toolId) {
		Deque<ToolRun> open = OPEN_TOOLS.get();
		open.push(new ToolRun(toolId, currentCallId()));
		return open;
	}

	/**
	 * The tool run that a call made now with this parent was started by: the innermost tool
	 * running on this thread, if the parent call ran it (not some outer call's tool).
	 */
	static String parentToolId(String parentId) {
		ToolRun tool = parentId == null ? null : OPEN_TOOLS.get().peek();
		return tool != null && parentId.equals(tool.clientCallId()) ? tool.toolId() : null;
	}

	public InspectorAdvisor(InspectorClient client, Phase phase) {
		this(client, phase, null);
	}

	InspectorAdvisor(InspectorClient client, Phase phase, InspectorMemoryReader memoryReader) {
		this.client = client;
		this.phase = phase;
		this.memoryReader = memoryReader;
	}

	@Override
	public String getName() {
		return "InspectorAdvisor(" + this.phase + ")";
	}

	@Override
	public int getOrder() {
		return this.phase == Phase.CLIENT ? Ordered.HIGHEST_PRECEDENCE : Ordered.LOWEST_PRECEDENCE - 1;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		String callId = newId();
		Deque<String> open = OPEN_CALLS.get();

		// Every event is built lazily inside InspectorClient's guard: a failure while
		// describing the call drops the event, never the application's call.
		if (this.phase == Phase.CLIENT) {
			request = request.mutate().context(CALL_ID, callId).build();
			ChatClientRequest clientRequest = request;
			String parentId = open.peek();
			String parentToolId = parentToolId(parentId);
			this.client.send("client-request", () -> {
				Map<String, Object> event = requestEvent(callId, parentId, clientRequest, advisorNames(chain));
				if (parentToolId != null) {
					event.put("parentToolId", parentToolId);
				}
				List<Map<String, Object>> rag = InspectorRagDescriber.describe(chain.getCallAdvisors());
				if (!rag.isEmpty()) {
					event.put("rag", rag);
				}
				return event;
			});
			sendMemorySnapshot(callId, "before", chain, request);
			open.push(callId);
		}
		else {
			ChatClientRequest modelRequest = request;
			this.client.send("model-request",
					() -> requestEvent(callId, (String) modelRequest.context().get(CALL_ID), modelRequest, null));
		}

		long start = System.currentTimeMillis();
		try {
			ChatClientResponse response = chain.nextCall(request);
			if (this.phase == Phase.CLIENT) {
				sendMemorySnapshot(callId, "after", chain, request);
			}
			long durationMs = System.currentTimeMillis() - start;
			this.client.send(responseType(), () -> responseEvent(callId, response, durationMs));
			return response;
		}
		catch (RuntimeException ex) {
			long durationMs = System.currentTimeMillis() - start;
			this.client.send(responseType(), () -> errorEvent(callId, ex, durationMs));
			throw ex;
		}
		finally {
			if (this.phase == Phase.CLIENT) {
				open.remove(callId);
				// A tool whose end was never observed must not name later calls' sub-agents.
				OPEN_TOOLS.get().removeIf(t -> callId.equals(t.clientCallId()));
			}
		}
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		String callId = newId();
		if (this.phase == Phase.CLIENT) {
			request = request.mutate().context(CALL_ID, callId).build();
		}
		ChatClientRequest streamRequest = request;
		this.client.send(this.phase == Phase.CLIENT ? "client-request" : "model-request",
				() -> requestEvent(callId,
						this.phase == Phase.CLIENT ? null : (String) streamRequest.context().get(CALL_ID),
						streamRequest, this.phase == Phase.CLIENT ? advisorNames(chain) : null));
		long start = System.currentTimeMillis();
		// The completion and error callbacks may run on Reactor threads: post without blocking.
		return new ChatClientMessageAggregator()
			.aggregateChatClientResponse(chain.nextStream(request),
					response -> this.client.sendAsync(responseType(),
							() -> responseEvent(callId, response, System.currentTimeMillis() - start)))
			.doOnError(ex -> this.client.sendAsync(responseType(), () -> errorEvent(callId,
					ex instanceof RuntimeException rex ? rex : new RuntimeException(ex), System.currentTimeMillis() - start)));
	}

	private void sendMemorySnapshot(String callId, String phase, CallAdvisorChain chain, ChatClientRequest request) {
		if (this.memoryReader == null) {
			return;
		}
		try {
			List<Map<String, Object>> stores = this.memoryReader.snapshot(chain.getCallAdvisors(), request.context());
			if (!stores.isEmpty()) {
				Map<String, Object> event = new LinkedHashMap<>();
				event.put("clientCallId", callId);
				event.put("phase", phase);
				event.put("stores", stores);
				this.client.send("memory-snapshot", () -> event);
			}
		}
		catch (RuntimeException | LinkageError ex) {
			// never break the demo
		}
	}

	private String responseType() {
		return this.phase == Phase.CLIENT ? "client-response" : "model-response";
	}

	private static String newId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static List<Map<String, Object>> advisorNames(CallAdvisorChain chain) {
		return advisorNames(chain.getCallAdvisors());
	}

	private static List<Map<String, Object>> advisorNames(StreamAdvisorChain chain) {
		return advisorNames(chain.getStreamAdvisors());
	}

	private static List<Map<String, Object>> advisorNames(List<? extends org.springframework.ai.chat.client.advisor.api.Advisor> chain) {
		List<Map<String, Object>> advisors = new ArrayList<>();
		for (var advisor : chain) {
			if (advisor instanceof InspectorAdvisor) {
				continue;
			}
			Map<String, Object> a = new LinkedHashMap<>();
			a.put("name", advisor.getName());
			a.put("order", advisor.getOrder());
			advisors.add(a);
		}
		return advisors;
	}

	private static Map<String, Object> requestEvent(String callId, String parentId, ChatClientRequest request,
			List<Map<String, Object>> advisors) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("callId", callId);
		event.put("parentId", parentId);
		event.put("thread", Thread.currentThread().getName());
		if (advisors != null) {
			event.put("advisors", advisors);
		}
		event.put("messages", request.prompt().getInstructions().stream().map(InspectorAdvisor::message).toList());
		event.put("options", options(request.prompt().getOptions()));
		event.put("context", context(request.context()));
		return event;
	}

	private static Map<String, Object> responseEvent(String callId, ChatClientResponse response, long durationMs) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("callId", callId);
		event.put("durationMs", durationMs);
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse != null) {
			List<Map<String, Object>> generations = new ArrayList<>();
			for (Generation generation : chatResponse.getResults()) {
				Map<String, Object> g = message(generation.getOutput());
				g.put("finishReason", generation.getMetadata().getFinishReason());
				// Spring AI's Anthropic model returns each thinking block as a generation of its own,
				// before the answer: marked by its signature, or by the encrypted data when redacted.
				Map<String, Object> properties = generation.getOutput().getMetadata();
				if (properties.containsKey("signature")) {
					g.put("thinking", "signed");
				}
				else if (properties.containsKey("data")) {
					g.put("thinking", "redacted");
				}
				generations.add(g);
			}
			event.put("generations", generations);
			var metadata = chatResponse.getMetadata();
			event.put("model", metadata.getModel());
			if (metadata.getUsage() != null) {
				Map<String, Object> usage = new LinkedHashMap<>();
				usage.put("input", metadata.getUsage().getPromptTokens());
				usage.put("output", metadata.getUsage().getCompletionTokens());
				usage.put("cacheRead", metadata.getUsage().getCacheReadInputTokens());
				usage.put("cacheWrite", metadata.getUsage().getCacheWriteInputTokens());
				event.put("usage", usage);
			}
		}
		event.put("context", context(response.context()));
		return event;
	}

	private static Map<String, Object> errorEvent(String callId, RuntimeException ex, long durationMs) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("callId", callId);
		event.put("durationMs", durationMs);
		event.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
		return event;
	}

	static Map<String, Object> message(Message message) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("role", message.getMessageType().getValue());
		m.put("text", message.getText());
		if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null
				&& !assistant.getToolCalls().isEmpty()) {
			m.put("toolCalls", assistant.getToolCalls()
				.stream()
				.map(tc -> entry("id", tc.id(), "name", tc.name(), "arguments", tc.arguments()))
				.toList());
		}
		if (message instanceof ToolResponseMessage toolResponse) {
			m.put("toolResponses", toolResponse.getResponses()
				.stream()
				.map(r -> entry("id", r.id(), "name", r.name(), "data", r.responseData()))
				.toList());
		}
		if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
			m.put("media", user.getMedia().stream().map(media -> media.getMimeType().toString()).toList());
		}
		return m;
	}

	private static Map<String, Object> options(ChatOptions options) {
		Map<String, Object> o = new LinkedHashMap<>();
		if (options == null) {
			return o;
		}
		o.put("type", options.getClass().getSimpleName());
		o.put("model", options.getModel());
		o.put("maxTokens", options.getMaxTokens());
		o.put("temperature", options.getTemperature());
		if (options instanceof ToolCallingChatOptions toolOptions && toolOptions.getToolCallbacks() != null) {
			o.put("tools", toolOptions.getToolCallbacks()
				.stream()
				.map(tc -> entry("name", tc.getToolDefinition().name(), "description",
						truncate(tc.getToolDefinition().description(), 300)))
				.toList());
		}
		return o;
	}

	private static Map<String, Object> context(Map<String, Object> context) {
		Map<String, Object> c = new LinkedHashMap<>();
		context.forEach((key, value) -> {
			if (CALL_ID.equals(key)) {
				return;
			}
			if (SECRET_KEY.matcher(key).find()) {
				c.put(key, "…redacted");
				return;
			}
			// Retrieved documents (RAG) keep their structure: id, score, text, metadata.
			c.put(key, InspectorDocuments.isDocuments(value) ? InspectorDocuments.of((java.util.Collection<?>) value, 1500)
					: truncate(String.valueOf(value), 2000));
		});
		return c;
	}

	/** A small map that, unlike {@code Map.of}, tolerates null values. */
	private static Map<String, Object> entry(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(k1, v1);
		m.put(k2, v2);
		return m;
	}

	private static Map<String, Object> entry(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
		Map<String, Object> m = entry(k1, v1, k2, v2);
		m.put(k3, v3);
		return m;
	}

	private static String truncate(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max) + "…";
	}

}
