package org.springaicommunity.inspector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.util.JsonHelper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter against the application's observation setup: it provides a registry when
 * there is none, attaches to Boot's when there is one, and reports each tool call once
 * either way.
 */
class InspectorAutoConfigurationTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)));

	/** The starter's auto-configuration, posting to a list instead of an inspector. */
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(InspectorAutoConfiguration.class))
		.withPropertyValues("spring.ai.inspector.active=true", "spring.ai.inspector.run-id=run-1")
		.withAllowBeanDefinitionOverriding(true) // the recording client replaces the auto-configured one
		.withBean("inspectorClient", InspectorClient.class, () -> this.client);

	private static Observation toolCall(ObservationRegistry registry, String toolCallId) {
		ToolCallingObservationContext context = ToolCallingObservationContext.builder()
			.toolDefinition(ToolDefinition.builder().name("airbnb_search").description("search").inputSchema("{}").build())
			.toolCallId(toolCallId)
			.toolCallArguments("{\"location\":\"Lisbon\"}")
			.toolCallResult("[]")
			.build();
		return Observation.createNotStarted("tool", () -> context, registry).start();
	}

	/** The tool events posted so far (the context also posts {@code run-start} when it refreshes). */
	private List<Object> toolEvents() {
		this.client.awaitBackground();
		return this.events.stream().map(e -> e.get("type")).filter(t -> String.valueOf(t).startsWith("tool-")).toList();
	}

	/** The handlers registered on {@code registry}. */
	@SuppressWarnings("unchecked")
	private static Collection<Object> handlers(ObservationRegistry registry) {
		// Micrometer keeps the list package-private; the handler itself tolerates a second
		// registration (it reports each tool call once), so only the list shows it.
		return (Collection<Object>) ReflectionTestUtils.invokeMethod(registry.observationConfig(), "getObservationHandlers");
	}

	/** How many times the starter's handler is registered on {@code registry}. */
	private static long inspectorHandlers(ObservationRegistry registry) {
		return handlers(registry).stream().filter(InspectorToolObservationHandler.class::isInstance).count();
	}

	@Test
	void providesRegistryWhenAppHasNone() {
		this.runner.run(context -> {
			assertThat(context).hasSingleBean(ObservationRegistry.class).hasBean("inspectorObservationRegistry");
			ObservationRegistry registry = context.getBean(ObservationRegistry.class);
			assertThat(inspectorHandlers(registry)).isEqualTo(1);

			toolCall(registry, "call-1").stop();

			assertThat(toolEvents()).containsExactly("tool-start", "tool-end");
		});
	}

	@Test
	void attachesToBootsRegistryOnce() {
		this.runner.withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class)).run(context -> {
			// Boot's registry wins; the starter's backs off.
			assertThat(context).hasSingleBean(ObservationRegistry.class)
				.hasBean("observationRegistry")
				.doesNotHaveBean("inspectorObservationRegistry");
			ObservationRegistry registry = context.getBean(ObservationRegistry.class);
			// Boot registers every ObservationHandler bean, so the handler must not be one,
			// or Boot and the starter would both register it.
			assertThat(context).doesNotHaveBean(InspectorToolObservationHandler.class);
			assertThat(inspectorHandlers(registry)).isEqualTo(1);

			toolCall(registry, "call-1").stop();

			assertThat(toolEvents()).containsExactly("tool-start", "tool-end");
		});
	}

	@Test
	void bootRegistersHandlerBeans() {
		// Why the handler is not a bean: Boot would add it next to the starter's registration.
		InspectorToolObservationHandler extra = new InspectorToolObservationHandler(this.client, new InspectorToolOrigins());
		this.runner.withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class))
			.withBean("extraHandler", InspectorToolObservationHandler.class, () -> extra)
			.run(context -> {
				Collection<Object> handlers = handlers(context.getBean(ObservationRegistry.class));
				assertThat(handlers).contains(extra);
				assertThat(handlers.stream().filter(InspectorToolObservationHandler.class::isInstance)).hasSize(2);
			});
	}

}
