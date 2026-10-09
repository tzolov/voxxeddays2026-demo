package org.springaicommunity.inspector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Detects a running Spring AI Inspector (see the {@code spring-ai-inspector} module) and, if one
 * is reachable, routes the model traffic of this demo through its recording proxy,
 * e.g. {@code spring.ai.anthropic.base-url=<inspector>/r/<runId>/anthropic}.
 *
 * <p>The original base URL of every routed provider is reported to the inspector
 * ({@code spring.ai.inspector.upstream.<provider>}, sent with {@code run-start}), and the
 * proxy forwards there, so a custom gateway or a mitmweb in front of the provider keeps
 * working. Anthropic and TypeSafe (Jev) are always routed: their paths are simply appended to
 * the base URL, so e.g. TypeSafe served by a local Ollama is recorded too. OpenAI, Ollama,
 * Mistral and DeepSeek are routed only when they point at their default endpoints, because
 * their base URLs imply provider-specific paths (Azure, GitHub Models, ...) that are left alone.
 * {@code spring.ai.inspector.route.openai=always} also routes an OpenAI-compatible
 * endpoint whose base URL ends in the {@code /v1} segment, e.g. Amazon Bedrock mantle.
 *
 * <p>Any other HTTP model provider can be routed too, by naming its base-url property:
 * {@code spring.ai.inspector.proxy.<name>=<property>[,<property>...]}, e.g.
 * {@code spring.ai.inspector.proxy.groq=spring.ai.openai.base-url}. The proxy forwards to
 * that property's value; the inspector recognizes the wire format by the request path
 * (OpenAI-compatible, Anthropic, Ollama). Models running in the JVM make no HTTP calls:
 * they are shown from the advisor's model calls instead.
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

	static final String PROXY_PREFIX = "spring.ai.inspector.proxy.";

	/**
	 * e.g. spring.ai.anthropic.chat.options.model, spring.ai.jinfer.chat.model, or the model of
	 * OpenAI's Responses API: spring.ai.openai.responses.model
	 */
	private static final Pattern CHAT_MODEL = Pattern
		.compile("spring\\.ai\\.([a-z0-9.-]+)\\.(?:chat\\.(options\\.)?|(responses)\\.)model");

	/** The provider of a spring.ai property: spring.ai.openai.base-url -> openai. */
	private static final Pattern PROPERTY_OWNER = Pattern.compile("spring\\.ai\\.([a-z0-9-]+)\\.");

	/** The providers routed through the proxy, reported with the run (see InspectorAutoConfiguration). */
	static final String ROUTED = "spring.ai.inspector.routed";

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
		if ((isDefault(environment, "spring.ai.openai.base-url", "api.openai.com")
				&& isDefault(environment, "spring.ai.openai.responses.base-url", "api.openai.com"))
				|| "always".equalsIgnoreCase(environment.getProperty("spring.ai.inspector.route.openai"))) {
			// The OpenAI SDK's base URL includes the /v1 version segment. The Responses API client
			// (spring.ai.openai.chat.api=responses) has a base-url of its own, which wins when set.
			route(props, environment, url, proxy, "openai", "/v1", "https://api.openai.com", "spring.ai.openai.base-url",
					"spring.ai.openai.responses.base-url");
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
		// TypeSafe Jev systemOne calls (guardrails, judges, RAG filters): always routed, forwarded to
		// whatever it pointed at before (api.typesafe.ai, a local Ollama serving Jev models, ...).
		route(props, environment, url, proxy, "typesafe", "", "https://api.typesafe.ai", "spring.ai.typesafe.base-url");

		// Other HTTP providers, routed on request: spring.ai.inspector.proxy.<name>=<base-url property>[,...]
		for (String key : propertyNames(environment)) {
			if (key.startsWith(PROXY_PREFIX) && key.length() > PROXY_PREFIX.length()) {
				try {
					routeOnRequest(props, environment, url, proxy, key);
				}
				catch (RuntimeException ex) {
					// e.g. an unresolvable placeholder: that provider stays unrouted
				}
			}
		}

		// addFirst: overrides any base-url in application.properties.
		environment.getPropertySources().addFirst(new MapPropertySource("spring-ai-inspector", props));
	}

	/** spring.ai.inspector.proxy.<name>=<base-url property>[,...]: routes that provider through the proxy. */
	private static void routeOnRequest(Map<String, Object> props, ConfigurableEnvironment environment, String url,
			String proxy, String key) {
		String provider = key.substring(PROXY_PREFIX.length());
		String[] properties = environment.getProperty(key, "").split("\\s*,\\s*");
		// No default upstream: the first of the properties that is set is where the calls go.
		String upstream = java.util.Arrays.stream(properties)
			.filter(p -> !p.isBlank())
			.map(p -> environment.getProperty(p, ""))
			.filter(v -> v.startsWith("http"))
			.findFirst()
			.orElse(null);
		if (upstream != null) {
			route(props, environment, url, proxy, provider, "", upstream, properties);
		}
		else {
			System.err.println("Spring AI Inspector: not routing '" + provider + "', none of "
					+ String.join(", ", properties) + " is set to an http(s) base URL");
		}
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
		Set<String> routed = new LinkedHashSet<>();
		routed.add(provider);
		for (String property : properties) {
			props.put(property, proxy + "/" + provider + suffix);
			// The provider the property belongs to (spring.ai.openai.base-url -> openai): its model
			// beans' calls are on the wire, under this route's name.
			java.util.regex.Matcher owner = PROPERTY_OWNER.matcher(property);
			if (owner.lookingAt()) {
				routed.add(owner.group(1));
			}
		}
		props.put("spring.ai.inspector.upstream." + provider, upstream);
		props.merge(ROUTED, String.join(",", routed), (a, b) -> a + "," + b);
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

	/**
	 * The configured chat models, of any provider: {@code spring.ai.<provider>.chat.model} or
	 * {@code spring.ai.<provider>.chat.options.model} (e.g. jinfer's in-JVM models too), and
	 * {@code spring.ai.<provider>.responses.model} (OpenAI's Responses API).
	 */
	static String models(ConfigurableEnvironment environment) {
		// Per provider: chat.options.model wins over chat.model, as Spring AI resolves it. A Responses
		// API model is listed on its own.
		Map<String, String> models = new LinkedHashMap<>();
		for (String key : propertyNames(environment)) {
			java.util.regex.Matcher matcher = CHAT_MODEL.matcher(key);
			if (matcher.matches()) {
				try {
					String model = environment.getProperty(key);
					String owner = matcher.group(3) != null ? matcher.group(1) + ".responses" : matcher.group(1);
					if (model != null && !model.isBlank() && (matcher.group(2) != null || !models.containsKey(owner))) {
						models.put(owner, model);
					}
				}
				catch (IllegalArgumentException ex) {
					// an unresolvable placeholder: not a model name
				}
			}
		}
		return String.join(", ", new LinkedHashSet<>(models.values()));
	}

	/**
	 * The names of all enumerable properties, in property source order. Environment
	 * variables are named as properties too (SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL ->
	 * spring.ai.openai.chat.options.model), which the environment resolves by relaxed
	 * binding. A source that can't list its names (e.g. a composite one wrapping a
	 * non-enumerable source) is skipped: inspection must never stop the application.
	 */
	static Set<String> propertyNames(ConfigurableEnvironment environment) {
		Set<String> names = new LinkedHashSet<>();
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
				continue;
			}
			try {
				for (String name : enumerable.getPropertyNames()) {
					names.add(source instanceof SystemEnvironmentPropertySource
							? name.toLowerCase(java.util.Locale.ROOT).replace('_', '.') : name);
				}
			}
			catch (RuntimeException ex) {
				// not listable: its properties are still resolved, just not discovered
			}
		}
		return names;
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
	 * main class: {@code .../03-chat-memory/target/classes},
	 * {@code .../03-chat-memory/target/03-chat-memory.jar}, or Gradle's
	 * {@code .../03-chat-memory/build/classes/kotlin/main}.
	 */
	private String appName(SpringApplication application) {
		Class<?> mainClass = application.getMainApplicationClass();
		if (mainClass == null) {
			return "unknown";
		}
		try {
			String location = mainClass.getProtectionDomain().getCodeSource().getLocation().toString();
			int target = location.indexOf("/target/");
			if (target < 0) {
				target = location.indexOf("/build/");
			}
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
