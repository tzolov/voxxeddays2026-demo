package org.springaicommunity.inspector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.micrometer.observation.Observation;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationContext;
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
 *
 * <p>Calls are correlated by observation parentage (see {@link InspectorCorrelation}): the
 * CLIENT advisor tags its observation with the call's id, the MODEL advisor with the model
 * call's id. Tool runs, vector store operations, embedding calls, the model's HTTP
 * requests and nested ChatClient calls (sub-agents) find those tags up their own
 * observation's parents, on whatever thread they run, so a streamed call is attributed like
 * a blocking one.
 */
public class InspectorAdvisor implements CallAdvisor, StreamAdvisor {

	public enum Phase {

		CLIENT, MODEL

	}

	static final String CALL_ID = InspectorCorrelation.CALL_ID;

	/**
	 * Advisor context keys whose values are never reported: the context is the app's own
	 * (a ToolContext, a tenant's credentials) and it leaves the JVM with the event.
	 */
	static final Pattern SECRET_KEY = Pattern
		.compile("(?i)api[-_ ]?key|secret|password|passwd|credential|authorization|access[-_ ]?token|auth[-_ ]?token|bearer");

	private final InspectorClient client;

	private final Phase phase;

	private final InspectorMemoryReader memoryReader;

	/** Providers whose calls are on the wire: their media is shown from there, not uploaded again. */
	private final java.util.Set<String> routed;


	/**
	 * The observation Spring AI opened around this very advisor, or null: only that one is
	 * tagged. Whatever else may be current (a ChatClient without a registry called inside an
	 * HTTP request's or a tool's observation) is still walked for the parent, never tagged,
	 * or every later call would nest under this one.
	 */
	private Observation own(Observation observation) {
		return observation != null && observation.getContext() instanceof AdvisorObservationContext context
				&& getName().equals(context.getAdvisorName()) ? observation : null;
	}

	public InspectorAdvisor(InspectorClient client, Phase phase) {
		this(client, phase, null, java.util.Set.of());
	}

	InspectorAdvisor(InspectorClient client, Phase phase, InspectorMemoryReader memoryReader) {
		this(client, phase, memoryReader, java.util.Set.of());
	}

	InspectorAdvisor(InspectorClient client, Phase phase, InspectorMemoryReader memoryReader, java.util.Set<String> routed) {
		this.client = client;
		this.phase = phase;
		this.memoryReader = memoryReader;
		this.routed = routed;
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
		// The observation current here: this advisor's own when the ChatClient is observed, which
		// is then tagged with the call's id so everything that runs inside finds it.
		Observation observation = InspectorCorrelation.current();

		// Every event is built lazily inside InspectorClient's guard: a failure while
		// describing the call drops the event, never the application's call.
		if (this.phase == Phase.CLIENT) {
			request = request.mutate().context(CALL_ID, callId).build();
			ChatClientRequest clientRequest = request;
			InspectorCorrelation.Parent parent = InspectorCorrelation.parentOf(observation);
			InspectorCorrelation.tag(own(observation), CALL_ID, callId);
			this.client.send("client-request", () -> clientRequestEvent(callId, parent, clientRequest, chain.getCallAdvisors()));
			sendMemorySnapshot(callId, "before", chain.getCallAdvisors(), request.context(), false);
		}
		else {
			ChatClientRequest modelRequest = request;
			InspectorCorrelation.tag(own(observation), InspectorCorrelation.MODEL_CALL_ID, callId);
			this.client.send("model-request",
					() -> requestEvent(callId, (String) modelRequest.context().get(CALL_ID), modelRequest, null));
		}

		long start = System.currentTimeMillis();
		try {
			ChatClientResponse response = chain.nextCall(request);
			if (this.phase == Phase.CLIENT) {
				sendMemorySnapshot(callId, "after", chain.getCallAdvisors(), request.context(), false);
			}
			long durationMs = System.currentTimeMillis() - start;
			ChatClientRequest sent = request;
			this.client.send(responseType(), () -> responseEvent(callId, sent, response, durationMs));
			return response;
		}
		catch (RuntimeException ex) {
			long durationMs = System.currentTimeMillis() - start;
			this.client.send(responseType(), () -> errorEvent(callId, ex, durationMs));
			throw ex;
		}
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		String callId = newId();
		ChatClientRequest streamRequest = this.phase == Phase.CLIENT ? request.mutate().context(CALL_ID, callId).build()
				: request;
		// The advisor chain puts this advisor's observation into the Reactor context; the
		// request is reported at subscription, when that context is known.
		return Flux.deferContextual(context -> {
			Observation observation = InspectorCorrelation.fromContext(context);
			if (this.phase == Phase.CLIENT) {
				InspectorCorrelation.Parent parent = InspectorCorrelation.parentOf(observation);
				InspectorCorrelation.tag(own(observation), CALL_ID, callId);
				this.client.send("client-request",
						() -> clientRequestEvent(callId, parent, streamRequest, chain.getStreamAdvisors()));
				sendMemorySnapshot(callId, "before", chain.getStreamAdvisors(), streamRequest.context(), false);
			}
			else {
				InspectorCorrelation.tag(own(observation), InspectorCorrelation.MODEL_CALL_ID, callId);
				this.client.send("model-request",
						() -> requestEvent(callId, (String) streamRequest.context().get(CALL_ID), streamRequest, null));
			}
			long start = System.currentTimeMillis();
			// The completion and error callbacks may run on Reactor threads: post without blocking.
			return new ChatClientMessageAggregator()
				.aggregateChatClientResponse(chain.nextStream(streamRequest), response -> {
					if (this.phase == Phase.CLIENT) {
						sendMemorySnapshot(callId, "after", chain.getStreamAdvisors(), streamRequest.context(), true);
					}
					this.client.sendAsync(responseType(), () -> responseEvent(callId, streamRequest, response, System.currentTimeMillis() - start));
				})
				.doOnError(ex -> this.client.sendAsync(responseType(), () -> errorEvent(callId,
						ex instanceof RuntimeException rex ? rex : new RuntimeException(ex), System.currentTimeMillis() - start)));
		});
	}

	/** The CLIENT request: with its parent call and tool, the advisor chain and the RAG setup. */
	private Map<String, Object> clientRequestEvent(String callId, InspectorCorrelation.Parent parent,
			ChatClientRequest request, List<? extends Advisor> advisors) {
		Map<String, Object> event = requestEvent(callId, parent.callId(), request, advisorNames(advisors));
		if (parent.toolId() != null) {
			event.put("parentToolId", parent.toolId());
		}
		List<Map<String, Object>> rag = InspectorRagDescriber.describe(advisors);
		if (!rag.isEmpty()) {
			event.put("rag", rag);
		}
		return event;
	}

	/**
	 * What the memory stores hold now; {@code async} posts from the background thread (the
	 * snapshot itself is still read here), for callbacks on Reactor threads.
	 */
	private void sendMemorySnapshot(String callId, String phase, List<? extends Advisor> advisors,
			Map<String, Object> context, boolean async) {
		if (this.memoryReader == null) {
			return;
		}
		try {
			List<Map<String, Object>> stores = this.memoryReader.snapshot(advisors, context);
			if (!stores.isEmpty()) {
				Map<String, Object> event = new LinkedHashMap<>();
				event.put("clientCallId", callId);
				event.put("phase", phase);
				event.put("stores", stores);
				if (async) {
					this.client.sendAsync("memory-snapshot", () -> event);
				}
				else {
					this.client.send("memory-snapshot", () -> event);
				}
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

	private static List<Map<String, Object>> advisorNames(List<? extends Advisor> chain) {
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

	private Map<String, Object> requestEvent(String callId, String parentId, ChatClientRequest request,
			List<Map<String, Object>> advisors) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("callId", callId);
		event.put("parentId", parentId);
		event.put("thread", Thread.currentThread().getName());
		if (advisors != null) {
			event.put("advisors", advisors);
		}
		boolean keep = keepsMedia(request.prompt().getOptions());
		event.put("messages", request.prompt().getInstructions().stream().map(m -> message(m, keep)).toList());
		event.put("options", options(request.prompt().getOptions()));
		event.put("context", context(request.context()));
		return event;
	}

	/**
	 * Whether this call's media is uploaded for previews: yes unless the provider (named by
	 * the options type, e.g. {@code AnthropicChatOptions}) is routed through the proxy, where
	 * the wire recording already keeps it.
	 */
	private boolean keepsMedia(ChatOptions options) {
		if (options == null) {
			return true;
		}
		// OpenAiChatOptions -> openai, OpenAiResponsesChatOptions -> openairesponses: the routed name is a prefix.
		String provider = options.getClass().getSimpleName().replaceAll("(Chat)?Options$", "").toLowerCase(java.util.Locale.ROOT);
		return this.routed.stream().noneMatch(provider::startsWith);
	}

	private Map<String, Object> responseEvent(String callId, ChatClientRequest request, ChatClientResponse response, long durationMs) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("callId", callId);
		event.put("durationMs", durationMs);
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse != null) {
			// Output media (an image a Gemini image model drew): uploaded once; the CLIENT view reuses it.
			boolean keep = keepsMedia(request.prompt().getOptions());
			List<Map<String, Object>> generations = new ArrayList<>();
			for (Generation generation : chatResponse.getResults()) {
				Map<String, Object> g = message(generation.getOutput(), keep);
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

	/** A message with its media described by type only (memory snapshots). */
	static Map<String, Object> message(Message message) {
		return describe(message, null, false);
	}

	/** A message with its media described, and uploaded for previews when {@code keep}. */
	private Map<String, Object> message(Message message, boolean keep) {
		return describe(message, this.client, keep);
	}

	private static Map<String, Object> describe(Message message, InspectorClient client, boolean keep) {
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
		List<org.springframework.ai.content.Media> media = message instanceof UserMessage user ? user.getMedia()
				: message instanceof AssistantMessage assistant ? assistant.getMedia() : List.of();
		if (!media.isEmpty()) {
			m.put("media", media.stream().map(item -> mediaEntry(item, client, keep)).toList());
		}
		return m;
	}

	/**
	 * A media item of a message: its type and size, and the blob it was uploaded under when
	 * it is kept for a preview. The bytes are read from the item (a byte array, a resource,
	 * a data URL); a URL to a remote file is described as a link.
	 */
	static Map<String, Object> mediaEntry(org.springframework.ai.content.Media media, InspectorClient client, boolean keep) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("type", media.getMimeType() == null ? "" : media.getMimeType().toString());
		Object data = media.getData();
		if (data instanceof java.net.URL || data instanceof java.net.URI) {
			entry.put("url", data.toString());
			return entry;
		}
		byte[] bytes = bytes(data);
		if (bytes == null) {
			if (data instanceof String s) {
				entry.put("url", s);
			}
			return entry;
		}
		entry.put("size", bytes.length);
		if (keep && client != null) {
			InspectorClient.Media kept = client.sendBlob(data, bytes, (String) entry.get("type"));
			if (kept != null) {
				entry.put("type", kept.contentType());
				entry.put("blobId", kept.id());
			}
		}
		return entry;
	}

	/** The bytes of a media item's data: a byte array (a Resource is read into one), or an inline data URL. */
	private static byte[] bytes(Object data) {
		if (data instanceof byte[] bytes) {
			return bytes;
		}
		if (data instanceof String s && s.startsWith("data:") && s.contains(";base64,")) {
			try {
				return java.util.Base64.getDecoder().decode(s.substring(s.indexOf(";base64,") + 8));
			}
			catch (IllegalArgumentException ex) {
				return null;
			}
		}
		return null;
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
