package org.springaicommunity.inspector;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springaicommunity.inspector.InspectorAdvisor.Phase;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Active only when {@link InspectorEnvironmentPostProcessor} found a running Demo
 * Inspector. Adds the two {@link InspectorAdvisor}s to every auto-configured
 * {@code ChatClient.Builder}, so the demos need no code changes.
 */
// After Boot's observation auto-configuration, so ours only fills in a missing registry.
@AutoConfiguration(afterName = { "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration",
		"org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration" })
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(name = "spring.ai.inspector.active", havingValue = "true")
public class InspectorAutoConfiguration {

	@Bean
	InspectorClient inspectorClient(Environment env) {
		return new InspectorClient(env.getProperty("spring.ai.inspector.url", "http://localhost:9001"),
				env.getRequiredProperty("spring.ai.inspector.run-id"));
	}

	@Bean
	InspectorMemoryReader inspectorMemoryReader(Environment env) {
		// Memory directories to snapshot; defaults to 19-auto-memory's agent.memory.dir.
		String dirs = env.getProperty("spring.ai.inspector.memory-dirs", env.getProperty("agent.memory.dir", ""));
		return new InspectorMemoryReader(java.util.Arrays.stream(dirs.split(","))
			.map(String::trim)
			.filter(d -> !d.isEmpty())
			.map(java.nio.file.Path::of)
			.toList());
	}

	@Bean
	ChatClientBuilderCustomizer inspectorChatClientCustomizer(InspectorClient client, InspectorMemoryReader memoryReader) {
		return builder -> builder.defaultAdvisors(new InspectorAdvisor(client, Phase.CLIENT, memoryReader),
				new InspectorAdvisor(client, Phase.MODEL));
	}

	@Bean
	InspectorToolOrigins inspectorToolOrigins() {
		return new InspectorToolOrigins();
	}

	@Bean
	@ConditionalOnClass(ToolCallingObservationContext.class)
	InspectorToolObservationHandler inspectorToolObservationHandler(InspectorClient client,
			InspectorToolOrigins origins) {
		return new InspectorToolObservationHandler(client, origins);
	}

	/**
	 * Spring AI only emits tool-call observations when an {@link ObservationRegistry}
	 * exists. The demos don't include Boot's observation support, so provide one.
	 */
	@Bean
	@ConditionalOnMissingBean
	ObservationRegistry inspectorObservationRegistry() {
		return ObservationRegistry.create();
	}

	/** Registers the tool handler with whichever registry the application ends up with. */
	@Bean
	static BeanPostProcessor inspectorObservationRegistryPostProcessor(
			ObjectProvider<InspectorToolObservationHandler> handler) {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof ObservationRegistry registry) {
					handler.ifAvailable(h -> registry.observationConfig().observationHandler(h));
				}
				return bean;
			}
		};
	}

	/** Reports vector store ingestion and searches (RAG demos). */
	@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.ai.vectorstore.VectorStore")
	static class VectorStoreInspection {

		@Bean
		static InspectorVectorStorePostProcessor inspectorVectorStorePostProcessor(
				ObjectProvider<InspectorClient> client) {
			return new InspectorVectorStorePostProcessor(client);
		}

	}

	/** Tags MCP tools with the MCP connection and server they come from. */
	@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.ai.mcp.McpToolNamePrefixGenerator")
	static class McpInspection {

		@Bean
		static InspectorMcpToolNamePostProcessor inspectorMcpToolNamePostProcessor(
				ObjectProvider<InspectorToolOrigins> origins) {
			return new InspectorMcpToolNamePostProcessor(origins);
		}

		/** Records the MCP messages of every auto-configured MCP client connection. */
		@Bean
		@ConditionalOnClass(name = "org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport")
		static InspectorMcpTransportPostProcessor inspectorMcpTransportPostProcessor(
				ObjectProvider<InspectorClient> client, ObjectProvider<InspectorToolOrigins> origins) {
			return new InspectorMcpTransportPostProcessor(client, origins);
		}

	}

	@Bean
	RunLifecycle inspectorRunLifecycle(InspectorClient client, Environment env) {
		return new RunLifecycle(client, env);
	}

	static class RunLifecycle implements InitializingBean, DisposableBean {

		private final InspectorClient client;

		private final Environment env;

		RunLifecycle(InspectorClient client, Environment env) {
			this.client = client;
			this.env = env;
		}

		@Override
		public void afterPropertiesSet() {
			this.client.send("run-start", () -> {
				Map<String, Object> event = new LinkedHashMap<>();
				event.put("app", this.env.getProperty("spring.ai.inspector.app"));
				event.put("model", this.env.getProperty("spring.ai.inspector.models"));
				event.put("pid", ProcessHandle.current().pid());
				// Where the proxy should forward each provider's calls (see InspectorEnvironmentPostProcessor).
				Map<String, String> upstreams = new LinkedHashMap<>();
				for (String provider : new String[] { "anthropic", "openai", "ollama", "mistralai", "deepseek", "typesafe" }) {
					String upstream = this.env.getProperty("spring.ai.inspector.upstream." + provider);
					if (upstream != null) {
						upstreams.put(provider, upstream);
					}
				}
				event.put("upstreams", upstreams);
				return event;
			});
		}

		@Override
		public void destroy() {
			this.client.send("run-end", Map.of());
		}

	}

}
