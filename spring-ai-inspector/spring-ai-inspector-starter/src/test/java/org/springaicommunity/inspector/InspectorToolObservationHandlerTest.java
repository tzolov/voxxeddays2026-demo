package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.util.JsonHelper;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorToolObservationHandlerTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final ObservationRegistry registry = ObservationRegistry.create();

	InspectorToolObservationHandlerTest() {
		InspectorClient client = new InspectorClient("run-1", body -> this.events.add(new JsonHelper().fromJsonToMap(body)));
		this.registry.observationConfig().observationHandler(new InspectorToolObservationHandler(client));
	}

	private Observation observe(String toolCallId) {
		ToolCallingObservationContext context = ToolCallingObservationContext.builder()
			.toolDefinition(ToolDefinition.builder().name("airbnb_search").description("search").inputSchema("{}").build())
			.toolCallId(toolCallId)
			.toolCallArguments("{\"location\":\"Lisbon\"}")
			.toolCallResult("[]")
			.build();
		return Observation.createNotStarted("tool", () -> context, this.registry).start();
	}

	@Test
	void reportsStartAndEnd() {
		observe("call-1").stop();

		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("tool-start", "tool-end");
		assertThat(this.events.get(0).get("name")).isEqualTo("airbnb_search");
		assertThat(this.events.get(1).get("toolId")).isEqualTo(this.events.get(0).get("toolId"));
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
