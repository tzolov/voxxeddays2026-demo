package org.springaicommunity.inspector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorEnvironmentPostProcessorTest {

	private HttpServer server;

	@AfterEach
	void stop() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	private String serve(String pingBody) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/api/ping", exchange -> {
			byte[] body = pingBody.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.server.start();
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	private StandardEnvironment environment(Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("app", new HashMap<>(properties)));
		new InspectorEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(Object.class));
		return environment;
	}

	@Test
	void aServiceThatIsNotTheInspectorIsNeverUsed() throws IOException {
		String url = serve("ok"); // e.g. some other service answering 200 on the same port

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url));

		assertThat(env.getProperty("spring.ai.inspector.active")).isNull();
		assertThat(env.getProperty("spring.ai.anthropic.base-url")).isNull();
	}

	@Test
	void routesThroughTheProxyAndRemembersTheOriginalUpstream() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\",\"api\":1}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.anthropic.base-url", "https://gateway.example.com/anthropic/"));

		assertThat(env.getProperty("spring.ai.inspector.active")).isEqualTo("true");
		assertThat(env.getProperty("spring.ai.anthropic.base-url")).startsWith(url + "/r/").endsWith("/anthropic");
		// The proxy forwards to the custom gateway, not to api.anthropic.com.
		assertThat(env.getProperty("spring.ai.inspector.upstream.anthropic"))
			.isEqualTo("https://gateway.example.com/anthropic");
		// OpenAI at its default: routed, with the SDK's /v1 segment kept on the proxy side.
		assertThat(env.getProperty("spring.ai.openai.base-url")).endsWith("/openai/v1");
		assertThat(env.getProperty("spring.ai.inspector.upstream.openai")).isEqualTo("https://api.openai.com");
	}

	@Test
	void leavesProviderSpecificEndpointsAlone() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.openai.base-url", "https://my-resource.openai.azure.com"));

		assertThat(env.getProperty("spring.ai.openai.base-url")).isEqualTo("https://my-resource.openai.azure.com");
		assertThat(env.getProperty("spring.ai.inspector.upstream.openai")).isNull();
	}

	@Test
	void canBeDisabled() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url, "spring.ai.inspector.enabled", "false"));

		assertThat(env.getProperty("spring.ai.inspector.active")).isNull();
	}

}
