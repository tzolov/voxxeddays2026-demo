package org.springaicommunity.inspector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Detects a running Spring AI Inspector (see the {@code spring-ai-inspector} module) and, if one
 * is reachable, routes the model traffic of this demo through its recording proxy,
 * e.g. {@code spring.ai.anthropic.base-url=<inspector>/r/<runId>/anthropic}.
 *
 * <p>The original base URL of every routed provider is reported to the inspector
 * ({@code spring.ai.inspector.upstream.<provider>}, sent with {@code run-start}), and the
 * proxy forwards there, so a custom gateway or a mitmweb in front of the provider keeps
 * working. Anthropic is always routed. OpenAI, Ollama, Mistral, DeepSeek and TypeSafe (Jev)
 * are routed only when they point at their default endpoints, because their base URLs
 * imply provider-specific paths (Azure, GitHub Models, ...) that are left alone.
 *
 * <p>The run id in the URL lets the inspector attribute every wire call to the demo
 * that made it. When the inspector is not running nothing changes and the demo talks to
 * the configured base-url as before.
 *
 * <p>Set {@code spring.ai.inspector.enabled=false} to opt out, or
 * {@code spring.ai.inspector.url} to use a different inspector address.
 */
public class InspectorEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getProperty("spring.ai.inspector.enabled", Boolean.class, true)) {
			return;
		}
		String url = environment.getProperty("spring.ai.inspector.url", "http://localhost:9001");
		if (!isInspectorUp(url)) {
			return;
		}

		Map<String, Object> props = new LinkedHashMap<>();
		props.put("spring.ai.inspector.active", "true");
		props.put("spring.ai.inspector.run-id", RUN_ID);
		props.put("spring.ai.inspector.app", appName(application));
		props.put("spring.ai.inspector.models", models(environment));

		String proxy = url + "/r/" + RUN_ID;
		// Anthropic: always routed, forwarded to whatever it pointed at before.
		route(props, environment, url, proxy, "anthropic", "", "https://api.anthropic.com",
				"spring.ai.anthropic.base-url");
		if (isDefault(environment, "spring.ai.openai.base-url", "api.openai.com")) {
			// The OpenAI SDK's base URL includes the /v1 version segment.
			route(props, environment, url, proxy, "openai", "/v1", "https://api.openai.com", "spring.ai.openai.base-url");
		}
		if (isDefault(environment, "spring.ai.ollama.base-url", "localhost:11434", "127.0.0.1:11434")) {
			route(props, environment, url, proxy, "ollama", "", "http://localhost:11434", "spring.ai.ollama.base-url");
		}
		if (isDefault(environment, "spring.ai.mistralai.base-url", "api.mistral.ai")
				&& isDefault(environment, "spring.ai.mistralai.chat.base-url", "api.mistral.ai")) {
			// MistralAiChatProperties presets its own base-url, which wins over the common one.
			route(props, environment, url, proxy, "mistralai", "", "https://api.mistral.ai",
					"spring.ai.mistralai.base-url", "spring.ai.mistralai.chat.base-url");
		}
		if (isDefault(environment, "spring.ai.deepseek.base-url", "api.deepseek.com")) {
			route(props, environment, url, proxy, "deepseek", "", "https://api.deepseek.com",
					"spring.ai.deepseek.base-url");
		}
		if (isDefault(environment, "spring.ai.typesafe.base-url", "api.typesafe.ai")) {
			// TypeSafe Jev systemOne calls (guardrails, judges, RAG filters).
			route(props, environment, url, proxy, "typesafe", "", "https://api.typesafe.ai",
					"spring.ai.typesafe.base-url");
		}

		// addFirst: overrides any base-url in application.properties.
		environment.getPropertySources().addFirst(new MapPropertySource("spring-ai-inspector", props));
	}

	/**
	 * Points the provider's base-url properties at the proxy and records where the proxy
	 * should forward to: the first configured value (minus the {@code suffix} the proxy
	 * adds back), or the provider's default.
	 */
	private static void route(Map<String, Object> props, ConfigurableEnvironment environment, String inspectorUrl,
			String proxy, String provider, String suffix, String defaultUpstream, String... properties) {
		String original = null;
		for (String property : properties) {
			String value = environment.getProperty(property);
			if (value != null && !value.isBlank() && !value.startsWith(inspectorUrl)) {
				original = value;
				break;
			}
		}
		String upstream = original == null ? defaultUpstream : original.replaceAll("/+$", "");
		if (!suffix.isEmpty() && upstream.endsWith(suffix)) {
			upstream = upstream.substring(0, upstream.length() - suffix.length());
		}
		for (String property : properties) {
			props.put(property, proxy + "/" + provider + suffix);
		}
		props.put("spring.ai.inspector.upstream." + provider, upstream);
	}

	private static boolean isDefault(ConfigurableEnvironment environment, String property, String... defaults) {
		String value = environment.getProperty(property);
		if (value == null || value.isBlank()) {
			return true;
		}
		for (String d : defaults) {
			if (value.contains(d)) {
				return true;
			}
		}
		return false;
	}

	private static String models(ConfigurableEnvironment environment) {
		StringBuilder models = new StringBuilder();
		for (String provider : new String[] { "anthropic", "openai", "ollama", "mistralai", "deepseek", "google.genai" }) {
			String model = environment.getProperty("spring.ai." + provider + ".chat.options.model");
			if (model != null && !model.isBlank()) {
				models.append(models.isEmpty() ? "" : ", ").append(model);
			}
		}
		return models.toString();
	}

	/**
	 * True only when the server at {@code url} identifies itself as the inspector: routing
	 * model traffic to some other service that answers 200 (MinIO, SonarQube, ... often
	 * use port 9000/9001) would break the application.
	 */
	static boolean isInspectorUp(String url) {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
			HttpResponse<String> response = client.send(
					HttpRequest.newBuilder(URI.create(url + "/api/ping")).timeout(Duration.ofMillis(500)).build(),
					HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 && response.body().contains("\"name\":\"spring-ai-inspector\"");
		}
		catch (Exception ex) {
			return false;
		}
	}

	/**
	 * Derives a readable name such as {@code 03-chat-memory} from the location of the
	 * main class: {@code .../03-chat-memory/target/classes} or
	 * {@code .../03-chat-memory/target/03-chat-memory.jar}.
	 */
	private String appName(SpringApplication application) {
		Class<?> mainClass = application.getMainApplicationClass();
		if (mainClass == null) {
			return "unknown";
		}
		try {
			String location = mainClass.getProtectionDomain().getCodeSource().getLocation().toString();
			int target = location.indexOf("/target/");
			if (target > 0) {
				String module = Path.of(location.substring(0, target).replaceFirst("^[a-z:]+:", ""))
					.getFileName()
					.toString();
				return module + " · " + mainClass.getSimpleName();
			}
		}
		catch (Exception ex) {
			// fall through
		}
		return mainClass.getSimpleName();
	}

}
