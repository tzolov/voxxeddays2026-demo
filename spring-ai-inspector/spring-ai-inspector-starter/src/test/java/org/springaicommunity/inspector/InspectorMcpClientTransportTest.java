package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.util.JsonHelper;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class InspectorMcpClientTransportTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)));

	/** A transport that records what is sent and lets the test deliver server messages. */
	static class FakeTransport implements McpClientTransport {

		final List<JSONRPCMessage> sent = new CopyOnWriteArrayList<>();

		final AtomicReference<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handler = new AtomicReference<>();

		@Override
		public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			this.handler.set(handler);
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			this.sent.add(message);
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return null;
		}

		JSONRPCMessage receive(JSONRPCMessage message) {
			return this.handler.get().apply(Mono.just(message)).block();
		}

	}

	private final InspectorToolOrigins origins = new InspectorToolOrigins();

	private List<Map<String, Object>> mcpEvents() {
		this.client.awaitBackground();
		return this.events.stream().filter(e -> "mcp-message".equals(e.get("type"))).toList();
	}

	private static McpSchema.JSONRPCRequest toolsCall(int id, String tool) {
		return new McpSchema.JSONRPCRequest("2.0", "tools/call", id, Map.of("name", tool, "arguments", Map.of()));
	}

	private static McpSchema.JSONRPCNotification log(String text) {
		return new McpSchema.JSONRPCNotification("2.0", "notifications/message", Map.of("level", "info", "data", text));
	}

	@Test
	void reportsMessagesInBothDirectionsAndPassesThemOn() {
		FakeTransport fake = new FakeTransport();
		InspectorMcpClientTransport transport = new InspectorMcpClientTransport("poet-server", fake, this.client,
				this.origins);
		transport.connect(m -> m).block();

		transport.sendMessage(toolsCall(3, "poeticWeatherForecast")).block();
		JSONRPCMessage log = log("Start sampling");
		JSONRPCMessage passedOn = fake.receive(log);
		fake.receive(new McpSchema.JSONRPCResponse("2.0", 3, null,
				new McpSchema.JSONRPCResponse.JSONRPCError(-32603, "boom", null)));

		assertThat(fake.sent).hasSize(1);
		assertThat(passedOn).isSameAs(log);
		List<Map<String, Object>> mcp = mcpEvents();
		// A response is named after the request it answers.
		assertThat(mcp).extracting(e -> e.get("direction"), e -> e.get("kind"), e -> e.get("method"))
			.containsExactly(tuple("out", "request", "tools/call"), tuple("in", "notification", "notifications/message"),
					tuple("in", "response", "tools/call"));
		assertThat(mcp).allMatch(e -> "poet-server".equals(e.get("connection")));
		assertThat(mcp.get(0).get("id")).isEqualTo(3);
		assertThat(mcp.get(0).get("payload")).isEqualTo(Map.of("jsonrpc", "2.0", "method", "tools/call", "id", 3, "params",
				Map.of("name", "poeticWeatherForecast", "arguments", Map.of())));
		assertThat(mcp.get(2).get("error")).isEqualTo("boom");
	}

	@Test
	void attributesMessagesToTheirToolRun() {
		FakeTransport fake = new FakeTransport();
		InspectorMcpClientTransport transport = new InspectorMcpClientTransport("poet-server", fake, this.client,
				this.origins);
		transport.connect(m -> m).block();
		this.origins.started("tool-a", Map.of("connection", "poet-server", "tool", "hello"));
		this.origins.started("tool-b", Map.of("connection", "poet-server", "tool", "poeticWeatherForecast"));

		transport.sendMessage(toolsCall(5, "hello")).block(); // the older run, by tool name
		transport.sendMessage(toolsCall(6, "poeticWeatherForecast")).block();
		fake.receive(log("Start sampling")); // in between: the latest run
		fake.receive(new McpSchema.JSONRPCResponse("2.0", 5, Map.of("content", List.of()), null)); // by id
		transport.sendMessage(new McpSchema.JSONRPCRequest("2.0", "tools/list", 7, Map.of())).block(); // the connection's
		this.origins.stopped("tool-b");
		fake.receive(log("after"));

		assertThat(mcpEvents()).extracting(e -> e.get("toolId"))
			.containsExactly("tool-a", "tool-b", "tool-b", "tool-a", null, "tool-a");
	}

	@Test
	void parallelRunsOfOneToolGetTheirOwnCallsAndAnUnknownCallBelongsToTheConnection() {
		FakeTransport fake = new FakeTransport();
		InspectorMcpClientTransport transport = new InspectorMcpClientTransport("poet-server", fake, this.client,
				this.origins);
		transport.connect(m -> m).block();
		this.origins.started("first", Map.of("connection", "poet-server", "tool", "getTemperature"));
		this.origins.started("second", Map.of("connection", "poet-server", "tool", "getTemperature"));

		transport.sendMessage(toolsCall(1, "getTemperature")).block(); // the run that started first calls first
		transport.sendMessage(toolsCall(2, "getTemperature")).block();
		transport.sendMessage(toolsCall(3, "untrackedTool")).block();
		fake.receive(new McpSchema.JSONRPCResponse("2.0", 2, Map.of(), null));
		fake.receive(new McpSchema.JSONRPCResponse("2.0", 1, Map.of(), null));
		fake.receive(new McpSchema.JSONRPCResponse("2.0", 3, Map.of(), null));

		assertThat(mcpEvents()).extracting(e -> e.get("toolId"))
			.containsExactly("first", "second", null, "second", "first", null);
	}

	@Test
	void learnsWhereToolsComeFromFromToolsList() {
		FakeTransport fake = new FakeTransport();
		InspectorMcpClientTransport transport = new InspectorMcpClientTransport("poet-server", fake, this.client,
				this.origins);
		transport.connect(m -> m).block();
		// The generator's exact name for a clash is never replaced by a listing.
		this.origins.put("hello", Map.of("connection", "other", "tool", "hello"), "Greeting");

		transport.sendMessage(new McpSchema.JSONRPCRequest("2.0", "initialize", 0, Map.of())).block();
		fake.receive(new McpSchema.JSONRPCResponse("2.0", 0,
				Map.of("serverInfo", Map.of("name", "mcp-server-voxxeddays-2026", "version", "0.0.1")), null));
		transport.sendMessage(new McpSchema.JSONRPCRequest("2.0", "tools/list", 1, Map.of())).block();
		fake.receive(new McpSchema.JSONRPCResponse("2.0", 1, Map.of("tools",
				List.of(Map.of("name", "poetic-weather", "description", "Poem"), Map.of("name", "hello", "description", "Greeting"))),
				null));

		// Named like Spring AI names MCP tools for the model (hyphens become underscores).
		assertThat(this.origins.get("poetic_weather", "Poem")).isEqualTo(Map.of("connection", "poet-server", "server",
				"mcp-server-voxxeddays-2026", "serverVersion", "0.0.1", "tool", "poetic-weather"));
		assertThat(this.origins.get("poetic_weather", "a local tool of the same name")).isNull();
		// And under its own name, for providers that keep it (e.g. with noPrefix()).
		assertThat(this.origins.get("poetic-weather", "Poem")).containsEntry("tool", "poetic-weather");
		assertThat(this.origins.get("hello", "Greeting")).containsEntry("connection", "other");
	}

	@Test
	void shortensLongStringsSoThePayloadStaysValidJson() {
		InspectorMcpClientTransport transport = new InspectorMcpClientTransport("poet-server", new FakeTransport(),
				this.client, this.origins);

		transport.sendMessage(log("x".repeat(10_000))).block();

		@SuppressWarnings("unchecked")
		Map<String, Object> params = (Map<String, Object>) ((Map<String, Object>) mcpEvents().get(0).get("payload"))
			.get("params");
		assertThat((String) params.get("data")).hasSize(4_001).endsWith("…");
	}

	@Test
	void wrapsTheAutoConfiguredTransportsOnce() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory(
				Map.of("client", this.client, "origins", this.origins));
		InspectorMcpTransportPostProcessor postProcessor = new InspectorMcpTransportPostProcessor(
				beans.getBeanProvider(InspectorClient.class), beans.getBeanProvider(InspectorToolOrigins.class));
		List<NamedClientMcpTransport> transports = List.of(new NamedClientMcpTransport("poet-server", new FakeTransport()));

		Object wrapped = postProcessor.postProcessAfterInitialization(transports, "streamableHttpWebFluxClientTransports");
		Object again = postProcessor.postProcessAfterInitialization(wrapped, "again");

		assertThat(wrapped).asInstanceOf(InstanceOfAssertFactories.LIST)
			.singleElement()
			.satisfies(t -> {
				assertThat(((NamedClientMcpTransport) t).name()).isEqualTo("poet-server");
				assertThat(((NamedClientMcpTransport) t).transport()).isInstanceOf(InspectorMcpClientTransport.class);
			});
		assertThat(((List<?>) again).get(0)).isSameAs(((List<?>) wrapped).get(0));
		assertThat(this.origins.connections()).containsExactly("poet-server");
		Object otherList = List.of("a");
		assertThat(postProcessor.postProcessAfterInitialization(otherList, "other")).isSameAs(otherList);
	}

}
