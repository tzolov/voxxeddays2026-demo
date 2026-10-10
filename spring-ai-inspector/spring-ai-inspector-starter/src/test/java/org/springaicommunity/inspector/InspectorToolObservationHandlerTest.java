package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.util.JsonHelper;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorToolObservationHandlerTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final ObservationRegistry registry = ObservationRegistry.create();

	private final InspectorToolOrigins origins = new InspectorToolOrigins();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)));

	InspectorToolObservationHandlerTest() {
		this.registry.observationConfig().observationHandler(new InspectorToolObservationHandler(this.client, this.origins));
	}

	private ChatClient chatClient(ChatModel model) {
		return ChatClient.builder(model, this.registry, null, null)
			.defaultAdvisors(new InspectorAdvisor(this.client, InspectorAdvisor.Phase.CLIENT), new InspectorAdvisor(this.client, InspectorAdvisor.Phase.MODEL))
			.build();
	}

	private static ChatResponse answer(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static ToolCallingObservationContext toolContext(String toolCallId) {
		return ToolCallingObservationContext.builder()
			.toolDefinition(ToolDefinition.builder().name("airbnb_search").description("search").inputSchema("{}").build())
			.toolCallId(toolCallId)
			.toolCallArguments("{\"location\":\"Lisbon\"}")
			.toolCallResult("[]")
			.build();
	}

	private Observation observe(String toolCallId) {
		return Observation.createNotStarted("tool", () -> toolContext(toolCallId), this.registry).start();
	}

	@Test
	void reportsStartAndEnd() {
		observe("call-1").stop();

		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("tool-start", "tool-end");
		assertThat(this.events.get(0).get("name")).isEqualTo("airbnb_search");
		assertThat(this.events.get(1).get("toolId")).isEqualTo(this.events.get(0).get("toolId"));
	}

	@Test
	void aSubAgentCalledByAToolNamesTheToolRun() {
		ChatClient subAgent = chatClient(prompt -> answer("sunny"));
		// The agent's model runs a Task-like tool that calls the sub-agent, then calls it once more after the tool ended.
		chatClient(prompt -> {
			Observation tool = observe("call-1");
			try (Observation.Scope scope = tool.openScope()) {
				subAgent.prompt("weather?").call().content();
			}
			finally {
				tool.stop();
			}
			subAgent.prompt("again, not from the tool").call().content();
			return answer("done");
		}).prompt("plan my trip").call().content();

		List<Map<String, Object>> requests = this.events.stream().filter(e -> "client-request".equals(e.get("type"))).toList();
		Map<String, Object> toolStart = this.events.stream().filter(e -> "tool-start".equals(e.get("type"))).findFirst().orElseThrow();
		assertThat(requests).hasSize(3);
		assertThat(requests.get(0)).doesNotContainKey("parentToolId");
		assertThat(toolStart).containsEntry("clientCallId", requests.get(0).get("callId"));
		assertThat(requests.get(1)).containsEntry("parentId", requests.get(0).get("callId")).containsEntry("parentToolId", toolStart.get("toolId"));
		assertThat(requests.get(2)).containsEntry("parentId", requests.get(0).get("callId")).doesNotContainKey("parentToolId");
	}

	@Test
	void aStreamedCallAttributesItsToolRunsLikeABlockingOne() {
		// Spring AI's tool loop in stream() runs on Reactor threads and parents the tool observation
		// from the Reactor context, which the stream advisors fill in.
		ChatModel model = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				return answer("blocking");
			}

			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.deferContextual(ctx -> {
					Observation parent = ctx.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
					Observation tool = Observation.createNotStarted("tool", () -> toolContext("call-9"), InspectorToolObservationHandlerTest.this.registry)
						.parentObservation(parent)
						.start();
					tool.stop();
					return Flux.just(answer("streamed"));
				}).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
			}
		};

		String text = chatClient(model).prompt("hi").stream().content().blockLast();
		this.client.awaitBackground();

		assertThat(text).isEqualTo("streamed");
		Map<String, Object> request = this.events.stream().filter(e -> "client-request".equals(e.get("type"))).findFirst().orElseThrow();
		Map<String, Object> modelRequest = this.events.stream().filter(e -> "model-request".equals(e.get("type"))).findFirst().orElseThrow();
		Map<String, Object> toolStart = this.events.stream().filter(e -> "tool-start".equals(e.get("type"))).findFirst().orElseThrow();
		assertThat(modelRequest).containsEntry("parentId", request.get("callId"));
		assertThat(toolStart).containsEntry("clientCallId", request.get("callId"));
		assertThat(this.events).extracting(e -> e.get("type")).contains("tool-end", "model-response", "client-response");
	}

	@Test
	void anMcpToolCarriesItsConnectionAndServer() {
		this.origins.put("airbnb_search", Map.of("connection", "airbnb", "server", "airbnb-mcp", "tool", "airbnb_search"),
				"search");

		observe("call-1").stop();

		assertThat(this.events.get(0).get("mcp")).isEqualTo(Map.of("connection", "airbnb", "server", "airbnb-mcp", "tool", "airbnb_search"));
	}

	@Test
	void aLocalToolNamedLikeAnMcpToolIsNotTaggedAsMcp() {
		this.origins.put("airbnb_search", Map.of("connection", "airbnb", "tool", "airbnb_search"), "the MCP tool");

		observe("call-1").stop(); // described "search"

		assertThat(this.events.get(0)).doesNotContainKey("mcp");
	}

	@Test
	void anMcpToolWithoutDescriptionMatchesByName() {
		// Spring AI describes such a tool by its name, so its description never matches the server's (none).
		this.origins.put("airbnb_search", Map.of("connection", "airbnb", "tool", "airbnb_search"), null);

		observe("call-1").stop();

		assertThat(this.events.get(0)).containsKey("mcp");
	}

	@Test
	void aLocalToolHasNoMcpOrigin() {
		observe("call-1").stop();

		assertThat(this.events.get(0)).doesNotContainKey("mcp");
	}

	@Test
	void aToolCallObservedTwiceIsReportedOnce() {
		// MCP tools are observed twice per call, each with its own context.
		Observation outer = observe("call-1");
		Observation inner = observe("call-1");
		inner.stop();
		outer.stop();

		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("tool-start", "tool-end");
	}

}
