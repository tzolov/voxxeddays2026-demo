package org.springaicommunity.inspector;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorMcpToolNamePostProcessorTest {

	private final InspectorToolOrigins origins = new InspectorToolOrigins();

	private final InspectorMcpToolNamePostProcessor postProcessor = new InspectorMcpToolNamePostProcessor(
			new StaticListableBeanFactory(Map.of("origins", this.origins)).getBeanProvider(InspectorToolOrigins.class));

	private static McpConnectionInfo connection(McpSchema.Implementation clientInfo) {
		return McpConnectionInfo.builder()
			.clientCapabilities(McpSchema.ClientCapabilities.builder().build())
			.clientInfo(clientInfo)
			.initializeResult(McpSchema.InitializeResult
				.builder("2025-06-18", McpSchema.ServerCapabilities.builder().build(),
						McpSchema.Implementation.builder("mcp-server-voxxeddays-2026", "0.0.1").build())
				.build())
			.build();
	}

	private static McpSchema.Tool tool(String name) {
		return McpSchema.Tool.builder(name, Map.of("type", "object")).build();
	}

	@Test
	void remembersTheConnectionAndServerOfEachToolName() {
		McpToolNamePrefixGenerator generator = (McpToolNamePrefixGenerator) this.postProcessor
			.postProcessAfterInitialization(new DefaultMcpToolNamePrefixGenerator(), "generator");

		String name = generator.prefixedToolName(
				connection(McpSchema.Implementation.builder("spring-ai-mcp-client - poet-server", "1.0.0").title("poet-server").build()),
				tool("poeticWeatherForecast"));

		assertThat(name).isEqualTo("poeticWeatherForecast");
		assertThat(this.origins.get(name)).containsEntry("connection", "poet-server")
			.containsEntry("server", "mcp-server-voxxeddays-2026")
			.containsEntry("serverVersion", "0.0.1")
			.containsEntry("tool", "poeticWeatherForecast");
		assertThat(this.origins.get("someLocalTool")).isNull();
	}

	@Test
	void keysTheOriginByTheNameTheGeneratorReturned() {
		// e.g. a custom generator that prefixes, or the default one renaming a clash to alt_1_...
		McpToolNamePrefixGenerator generator = (McpToolNamePrefixGenerator) this.postProcessor
			.postProcessAfterInitialization((McpToolNamePrefixGenerator) (info, t) -> "poet_" + t.name(), "generator");

		generator.prefixedToolName(connection(McpSchema.Implementation.builder("spring-ai-mcp-client - poet-server", "1.0.0").build()),
				tool("hello"));

		// Async clients have no title: the connection comes from the client name.
		assertThat(this.origins.get("poet_hello")).containsEntry("connection", "poet-server").containsEntry("tool", "hello");
	}

	@Test
	void leavesOtherBeansAlone() {
		Object bean = new Object();
		assertThat(this.postProcessor.postProcessAfterInitialization(bean, "other")).isSameAs(bean);
	}

}
