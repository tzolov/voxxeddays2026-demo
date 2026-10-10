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
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

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
	void routesOpenAisResponsesApiClientToo() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url, "spring.ai.openai.chat.api", "responses",
				"spring.ai.openai.responses.model", "gpt-6-luna"));

		// Its own base-url, if set, would win over the common one: both point at the proxy.
		assertThat(env.getProperty("spring.ai.openai.base-url")).startsWith(url + "/r/").endsWith("/openai/v1");
		assertThat(env.getProperty("spring.ai.openai.responses.base-url")).isEqualTo(env.getProperty("spring.ai.openai.base-url"));
		assertThat(env.getProperty("spring.ai.inspector.models")).isEqualTo("gpt-6-luna");
	}

	@Test
	void leavesAResponsesApiClientAtAProviderSpecificEndpointAlone() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.openai.responses.base-url", "https://my-resource.openai.azure.com"));

		assertThat(env.getProperty("spring.ai.openai.responses.base-url")).isEqualTo("https://my-resource.openai.azure.com");
		assertThat(env.getProperty("spring.ai.inspector.upstream.openai")).isNull();
		assertThat(env.getProperty("spring.ai.inspector.routed").split(",")).doesNotContain("openai");
	}

	@Test
	void routesAnOpenAiCompatibleEndpointWhenAsked() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.route.openai", "always",
				"spring.ai.openai.base-url", "https://bedrock-mantle.us-east-1.api.aws/v1"));

		assertThat(env.getProperty("spring.ai.openai.base-url")).startsWith(url + "/r/").endsWith("/openai/v1");
		assertThat(env.getProperty("spring.ai.inspector.upstream.openai"))
			.isEqualTo("https://bedrock-mantle.us-east-1.api.aws");
	}

	@Test
	void routesTypeSafeServedByOllama() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.typesafe.base-url", "http://localhost:11434"));

		assertThat(env.getProperty("spring.ai.typesafe.base-url")).startsWith(url + "/r/").endsWith("/typesafe");
		assertThat(env.getProperty("spring.ai.inspector.upstream.typesafe")).isEqualTo("http://localhost:11434");
	}

	@Test
	void theRoutingTableIsChangedByProperties() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.routes.ollama.default-hosts", "ollama.local:11434", // another default endpoint
				"spring.ai.ollama.base-url", "http://ollama.local:11434",
				"spring.ai.inspector.routes.anthropic.enabled", "false", // a built-in left alone
				"spring.ai.anthropic.base-url", "https://gateway.example.com/anthropic",
				"spring.ai.inspector.routes.openai.mode", "always", // the newer spelling of route.openai=always
				"spring.ai.openai.base-url", "https://bedrock-mantle.us-east-1.api.aws/v1"));

		assertThat(env.getProperty("spring.ai.ollama.base-url")).startsWith(url + "/r/").endsWith("/ollama");
		assertThat(env.getProperty("spring.ai.inspector.upstream.ollama")).isEqualTo("http://ollama.local:11434");
		assertThat(env.getProperty("spring.ai.anthropic.base-url")).isEqualTo("https://gateway.example.com/anthropic");
		assertThat(env.getProperty("spring.ai.inspector.upstream.anthropic")).isNull();
		assertThat(env.getProperty("spring.ai.openai.base-url")).endsWith("/openai/v1");
		assertThat(env.getProperty("spring.ai.inspector.upstream.openai")).isEqualTo("https://bedrock-mantle.us-east-1.api.aws");
	}

	@Test
	void aProviderAddedToTheTableIsRoutedLikeABuiltInOne() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		// At its default endpoint with nothing set: routed, forwarded to the route's upstream.
		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.routes.groq.properties", "spring.ai.groq.base-url",
				"spring.ai.inspector.routes.groq.default-hosts", "api.groq.com",
				"spring.ai.inspector.routes.groq.upstream", "https://api.groq.com/openai"));
		assertThat(env.getProperty("spring.ai.groq.base-url")).startsWith(url + "/r/").endsWith("/groq");
		assertThat(env.getProperty("spring.ai.inspector.upstream.groq")).isEqualTo("https://api.groq.com/openai");
		assertThat(env.getProperty("spring.ai.inspector.routed")).contains("groq");

		// Pointed elsewhere: left alone, as for the built-in providers with default hosts.
		env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.routes.groq.properties", "spring.ai.groq.base-url",
				"spring.ai.inspector.routes.groq.default-hosts", "api.groq.com",
				"spring.ai.groq.base-url", "https://gateway.example.com/groq"));
		assertThat(env.getProperty("spring.ai.groq.base-url")).isEqualTo("https://gateway.example.com/groq");

		// A value that isn't an http(s) URL can't be forwarded to: not routed, as before the table.
		env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.routes.groq.properties", "spring.ai.groq.base-url",
				"spring.ai.groq.base-url", "api.groq.com/openai"));
		assertThat(env.getProperty("spring.ai.groq.base-url")).isEqualTo("api.groq.com/openai");
		assertThat(env.getProperty("spring.ai.inspector.upstream.groq")).isNull();

		// Without default hosts and upstream: routed when set, as spring.ai.inspector.proxy.<name> does.
		env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.routes.groq.properties", "spring.ai.groq.base-url",
				"spring.ai.groq.base-url", "https://gateway.example.com/groq"));
		assertThat(env.getProperty("spring.ai.groq.base-url")).endsWith("/groq");
		assertThat(env.getProperty("spring.ai.inspector.upstream.groq")).isEqualTo("https://gateway.example.com/groq");
	}

	@Test
	void routesElevenLabsStabilityAndMistralsOtherModelsAtTheirDefaults() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url));

		assertThat(env.getProperty("spring.ai.elevenlabs.base-url")).endsWith("/elevenlabs");
		assertThat(env.getProperty("spring.ai.inspector.upstream.elevenlabs")).isEqualTo("https://api.elevenlabs.io");
		assertThat(env.getProperty("spring.ai.stabilityai.base-url")).endsWith("/stabilityai/v1"); // its client appends /v1
		assertThat(env.getProperty("spring.ai.inspector.upstream.stabilityai")).isEqualTo("https://api.stability.ai");
		assertThat(env.getProperty("spring.ai.mistralai.moderation.base-url")).endsWith("/mistralai");
		assertThat(env.getProperty("spring.ai.mistralai.ocr.base-url")).endsWith("/mistralai");
	}

	@Test
	void routesAnyOtherHttpProviderWhenAsked() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.proxy.groq", "spring.ai.groq.base-url",
				"spring.ai.groq.base-url", "https://api.groq.com/openai/"));

		assertThat(env.getProperty("spring.ai.groq.base-url")).startsWith(url + "/r/").endsWith("/groq");
		assertThat(env.getProperty("spring.ai.inspector.upstream.groq")).isEqualTo("https://api.groq.com/openai");
	}

	@Test
	void reportsTheConfiguredChatModelsOfAnyProvider() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.jinfer.chat.model", "LiquidAI/LFM2.5-8B-A1B-GGUF:Q8_0",
				"spring.ai.anthropic.chat.model", "claude-sonnet-5-5",
				"spring.ai.jinfer.embedding.model", "Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0"));

		assertThat(env.getProperty("spring.ai.inspector.models").split(", "))
			.containsExactlyInAnyOrder("LiquidAI/LFM2.5-8B-A1B-GGUF:Q8_0", "claude-sonnet-5-5");
	}

	@Test
	void readsModelsAndRoutesFromEnvironmentVariablesToo() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", Map.of(
				"SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL", "gpt-5", "SPRING_AI_INSPECTOR_PROXY_GROQ", "spring.ai.groq.base-url",
				"SPRING_AI_GROQ_BASE_URL", "https://api.groq.com/openai")));
		env.getPropertySources().addFirst(new MapPropertySource("app", new HashMap<>(Map.of("spring.ai.inspector.url", url))));

		new InspectorEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication(Object.class));

		assertThat(env.getProperty("spring.ai.inspector.models")).contains("gpt-5");
		assertThat(env.getProperty("spring.ai.inspector.upstream.groq")).isEqualTo("https://api.groq.com/openai");
	}

	@Test
	void aPropertySourceThatCantListItsNamesNeverStopsTheApplication() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");
		StandardEnvironment env = new StandardEnvironment();
		CompositePropertySource composite = new CompositePropertySource("config-server");
		composite.addPropertySource(new PropertySource<Object>("remote") { // not enumerable
			@Override
			public Object getProperty(String name) {
				return null;
			}
		});
		env.getPropertySources().addFirst(composite);
		env.getPropertySources().addFirst(new MapPropertySource("app", new HashMap<>(Map.of("spring.ai.inspector.url", url,
				"spring.ai.anthropic.chat.model", "claude-sonnet-5-5"))));

		new InspectorEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication(Object.class));

		assertThat(env.getProperty("spring.ai.inspector.models")).isEqualTo("claude-sonnet-5-5");
	}

	@Test
	void routesByTheFirstOfItsPropertiesThatIsSet() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.proxy.mistral2", "spring.ai.x.chat.base-url,spring.ai.x.base-url",
				"spring.ai.x.base-url", "https://api.example.com"));

		assertThat(env.getProperty("spring.ai.x.chat.base-url")).endsWith("/mistral2");
		assertThat(env.getProperty("spring.ai.x.base-url")).endsWith("/mistral2");
		assertThat(env.getProperty("spring.ai.inspector.upstream.mistral2")).isEqualTo("https://api.example.com");
	}

	@Test
	void reportsTheRoutedProvidersIncludingTheOwnerOfACustomRoutesProperty() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.inspector.route.openai", "always", "spring.ai.openai.base-url", "https://api.groq.com/openai/v1",
				"spring.ai.inspector.proxy.groq", "spring.ai.openai.base-url"));

		// openai's model beans' calls are on the wire, under the groq route too.
		assertThat(env.getProperty("spring.ai.inspector.routed").split(",")).contains("anthropic", "openai", "groq");
	}

	@Test
	void prefersAProvidersOptionsModelOverItsChatModel() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.ollama.chat.model", "llama3", "spring.ai.ollama.chat.options.model", "qwen3")); // the older spelling still wins

		assertThat(env.getProperty("spring.ai.inspector.models")).isEqualTo("qwen3");
	}

	@Test
	void aGatewayWhoseUrlMentionsTheDefaultHostIsNotTheDefault() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.openai.base-url", "https://gateway.example/api.openai.com/",
				"spring.ai.ollama.base-url", "http://LOCALHOST:11434/",
				"spring.ai.deepseek.base-url", "not a url"));

		assertThat(env.getProperty("spring.ai.openai.base-url")).isEqualTo("https://gateway.example/api.openai.com/");
		assertThat(env.getProperty("spring.ai.ollama.base-url")).startsWith(url + "/r/"); // the default, any case or slash
		assertThat(env.getProperty("spring.ai.deepseek.base-url")).isEqualTo("not a url");
	}

	@Test
	void anUnresolvablePlaceholderNeverStopsTheApplication() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url,
				"spring.ai.anthropic.base-url", "${ANTHROPIC_GATEWAY}", "spring.ai.openai.base-url", "${OPENAI_GATEWAY}"));

		// Anthropic is always routed: the proxy forwards to the default, Boot reports the placeholder itself.
		assertThat(env.getProperty("spring.ai.inspector.upstream.anthropic")).isEqualTo("https://api.anthropic.com");
		assertThat(env.getProperty("spring.ai.inspector.active")).isEqualTo("true");
	}

	@Test
	void turnsOnReactorContextPropagationUnlessTheAppDecided() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		assertThat(environment(Map.of("spring.ai.inspector.url", url)).getProperty("spring.reactor.context-propagation"))
			.isEqualTo("auto");
		assertThat(environment(Map.of("spring.ai.inspector.url", url, "spring.reactor.context-propagation", "limited"))
			.getProperty("spring.reactor.context-propagation")).isEqualTo("limited");
		assertThat(environment(Map.of("spring.ai.inspector.url", url, "spring.ai.inspector.reactor-context-propagation", "false"))
			.getProperty("spring.reactor.context-propagation")).isNull();
	}

	@Test
	void canBeDisabled() throws IOException {
		String url = serve("{\"name\":\"spring-ai-inspector\"}");

		StandardEnvironment env = environment(Map.of("spring.ai.inspector.url", url, "spring.ai.inspector.enabled", "false"));

		assertThat(env.getProperty("spring.ai.inspector.active")).isNull();
	}

}
