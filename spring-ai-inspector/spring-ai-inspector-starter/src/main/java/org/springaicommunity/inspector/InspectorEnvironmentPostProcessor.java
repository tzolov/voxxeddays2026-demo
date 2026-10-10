package org.springaicommunity.inspector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
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
 * working. Which providers, through which properties and when, is the routing table
 * ({@link Route}, {@link #BUILT_IN}): Anthropic and TypeSafe always, OpenAI, Ollama, Mistral,
 * DeepSeek, ElevenLabs and Stability at their own endpoints, and whatever the application adds
 * or changes under {@code spring.ai.inspector.routes.<provider>.*}. Models running in the JVM
 * and SDK clients without a base-url property (Google GenAI, Bedrock) make no HTTP calls the
 * proxy sees: they are shown from the advisors and the model beans instead.
 *
 * <p>The run id in the URL lets the inspector attribute every wire call to the demo
 * that made it. When the inspector is not running nothing changes and the demo talks to
 * the configured base-url as before.
 *
 * <p>When active, {@code spring.reactor.context-propagation=auto} is set unless the app set
 * it, so streamed calls keep their observation on Reactor threads;
 * {@code spring.ai.inspector.reactor-context-propagation=false} leaves it alone.
 *
 * <p>Set {@code spring.ai.inspector.enabled=false} to opt out, or
 * {@code spring.ai.inspector.url} to use a different inspector address.
 */
public class InspectorEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

	static final String PROXY_PREFIX = "spring.ai.inspector.proxy.";

	/**
	 * e.g. spring.ai.anthropic.chat.model, spring.ai.jinfer.chat.model, or the model of
	 * OpenAI's Responses API: spring.ai.openai.responses.model
	 */
	private static final Pattern CHAT_MODEL = Pattern
		.compile("spring\\.ai\\.([a-z0-9.-]+)\\.(?:chat\\.(options\\.)?|(responses)\\.)model");

	/** The provider of a spring.ai property: spring.ai.openai.base-url -> openai. */
	private static final Pattern PROPERTY_OWNER = Pattern.compile("spring\\.ai\\.([a-z0-9-]+)\\.");

	/** The providers routed through the proxy, reported with the run (see InspectorAutoConfiguration). */
	static final String ROUTED = "spring.ai.inspector.routed";

	/** Logging isn't set up this early: Boot replays what is logged here once it is. */
	private final Log log;

	public InspectorEnvironmentPostProcessor() {
		this.log = LogFactory.getLog(InspectorEnvironmentPostProcessor.class);
	}

	public InspectorEnvironmentPostProcessor(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(InspectorEnvironmentPostProcessor.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getProperty("spring.ai.inspector.enabled", Boolean.class, true)) {
			return;
		}
		String url = environment.getProperty("spring.ai.inspector.url", "http://localhost:9001");
		if (!isInspectorUp(url)) {
			return;
		}
		// Streamed calls run advisors and tools on Reactor threads: with automatic context
		// propagation they still find the observation they belong to (see InspectorCorrelation).
		if (environment.getProperty("spring.ai.inspector.reactor-context-propagation", Boolean.class, true)
				&& property(environment, "spring.reactor.context-propagation") == null) {
			environment.getPropertySources().addFirst(new MapPropertySource("spring-ai-inspector-reactor",
					Map.of("spring.reactor.context-propagation", "auto")));
		}

		Map<String, Object> props = new LinkedHashMap<>();
		props.put("spring.ai.inspector.active", "true");
		props.put("spring.ai.inspector.run-id", RUN_ID);
		props.put("spring.ai.inspector.app", appName(application));
		props.put("spring.ai.inspector.models", models(environment));

		String proxy = url + "/r/" + RUN_ID;
		for (Route route : routes(environment).values()) {
			if (!route.enabled() || route.properties().isEmpty()) {
				continue;
			}
			// At its default endpoints (or routed whatever it points at): forwarded to where it pointed.
			String[] hosts = route.defaultHosts().toArray(String[]::new);
			boolean atDefault = route.properties().stream().allMatch(property -> isDefault(environment, property, hosts));
			if (route.always() || atDefault) {
				route(props, environment, url, proxy, route);
			}
		}

		// addFirst: overrides any base-url in application.properties.
		environment.getPropertySources().addFirst(new MapPropertySource("spring-ai-inspector", props));
	}

	/**
	 * A provider's route through the proxy, from the built-in table
	 * ({@link #BUILT_IN}) or from {@code spring.ai.inspector.routes.<provider>.*}:
	 * <ul>
	 * <li>{@code properties}: the base-url properties to point at the proxy (comma-separated);</li>
	 * <li>{@code default-hosts}: the provider's own endpoints ({@code host} or {@code host:port});</li>
	 * <li>{@code mode}: {@code default} routes only when every property is unset or at a default host
	 * (a base URL that implies provider-specific paths, Azure or GitHub Models for OpenAI, is left
	 * alone); {@code always} routes whatever the properties say. Unset: {@code default} when the
	 * route names default hosts, {@code always} otherwise;</li>
	 * <li>{@code upstream}: where the proxy forwards when no property is set;</li>
	 * <li>{@code suffix}: a path segment the provider's client appends to the base URL (OpenAI's
	 * {@code /v1}), kept on the proxy side and taken off the upstream;</li>
	 * <li>{@code enabled}: {@code false} leaves a built-in provider alone.</li>
	 * </ul>
	 * Properties set for a built-in provider replace that attribute; an unknown provider is a new
	 * route. {@code spring.ai.inspector.proxy.<name>=<properties>} (mode {@code always}) and
	 * {@code spring.ai.inspector.route.openai=always} are the older spellings and still work.
	 */
	record Route(String provider, List<String> properties, List<String> defaultHosts, String upstream, String suffix,
			String mode, boolean enabled) {

		boolean always() {
			return this.mode == null || this.mode.isBlank() ? this.defaultHosts.isEmpty() : "always".equalsIgnoreCase(this.mode);
		}

		/** This route with the given attributes replaced. */
		Route with(Map<String, String> attributes) {
			return new Route(this.provider, attributes.containsKey("properties") ? list(attributes.get("properties")) : this.properties,
					attributes.containsKey("default-hosts") ? list(attributes.get("default-hosts")) : this.defaultHosts,
					attributes.getOrDefault("upstream", this.upstream), attributes.getOrDefault("suffix", this.suffix),
					attributes.getOrDefault("mode", this.mode),
					attributes.containsKey("enabled") ? Boolean.parseBoolean(attributes.get("enabled")) : this.enabled);
		}

		private static List<String> list(String value) {
			return java.util.Arrays.stream(value == null ? new String[0] : value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
		}

	}

	static final String ROUTES_PREFIX = "spring.ai.inspector.routes.";

	/**
	 * The providers routed out of the box. Anthropic and TypeSafe (Jev) always: their paths are
	 * simply appended to the base URL, so e.g. TypeSafe served by a local Ollama is recorded too.
	 * The others only at their own endpoints. OpenAI's and Stability's clients append {@code /v1}
	 * to the base URL. Mistral presets a base-url per model type, each of which wins over the
	 * common one; OpenAI's Responses API client has one of its own.
	 */
	static final List<Route> BUILT_IN = List.of(
			new Route("anthropic", List.of("spring.ai.anthropic.base-url"), List.of(), "https://api.anthropic.com", "", null, true),
			new Route("openai", List.of("spring.ai.openai.base-url", "spring.ai.openai.responses.base-url"),
					List.of("api.openai.com"), "https://api.openai.com", "/v1", null, true),
			new Route("ollama", List.of("spring.ai.ollama.base-url"), List.of("localhost:11434", "127.0.0.1:11434"),
					"http://localhost:11434", "", null, true),
			new Route("mistralai", List.of("spring.ai.mistralai.base-url", "spring.ai.mistralai.chat.base-url",
					"spring.ai.mistralai.embedding.base-url", "spring.ai.mistralai.moderation.base-url",
					"spring.ai.mistralai.ocr.base-url"), List.of("api.mistral.ai"), "https://api.mistral.ai", "", null, true),
			new Route("deepseek", List.of("spring.ai.deepseek.base-url"), List.of("api.deepseek.com"), "https://api.deepseek.com", "",
					null, true),
			new Route("typesafe", List.of("spring.ai.typesafe.base-url"), List.of(), "https://api.typesafe.ai", "", null, true),
			new Route("elevenlabs", List.of("spring.ai.elevenlabs.base-url"), List.of("api.elevenlabs.io"), "https://api.elevenlabs.io",
					"", null, true),
			new Route("stabilityai", List.of("spring.ai.stabilityai.base-url", "spring.ai.stabilityai.image.base-url"),
					List.of("api.stability.ai"), "https://api.stability.ai", "/v1", null, true));

	/** The routing table: the built-in routes, changed or added to by the application's properties. */
	static Map<String, Route> routes(ConfigurableEnvironment environment) {
		Map<String, Route> routes = new LinkedHashMap<>();
		for (Route route : BUILT_IN) {
			routes.put(route.provider(), route);
		}
		Map<String, Map<String, String>> given = new LinkedHashMap<>();
		for (String key : propertyNames(environment)) {
			if (key.startsWith(ROUTES_PREFIX) && key.length() > ROUTES_PREFIX.length()) {
				String rest = key.substring(ROUTES_PREFIX.length());
				int dot = rest.lastIndexOf('.');
				if (dot <= 0 || dot == rest.length() - 1) {
					continue;
				}
				String provider = rest.substring(0, dot);
				String attribute = rest.substring(dot + 1);
				if ("hosts".equals(attribute) && provider.endsWith(".default")) {
					// an environment variable: SPRING_AI_INSPECTOR_ROUTES_OPENAI_DEFAULT_HOSTS
					provider = provider.substring(0, provider.length() - ".default".length());
					attribute = "default-hosts";
				}
				String value = property(environment, key);
				if (value != null) { // an unresolvable placeholder leaves the attribute as it is
					given.computeIfAbsent(provider, k -> new LinkedHashMap<>()).put(attribute, value);
				}
			}
		}
		// The older spellings.
		if ("always".equalsIgnoreCase(property(environment, "spring.ai.inspector.route.openai"))) {
			given.computeIfAbsent("openai", k -> new LinkedHashMap<>()).putIfAbsent("mode", "always");
		}
		for (String key : propertyNames(environment)) {
			if (key.startsWith(PROXY_PREFIX) && key.length() > PROXY_PREFIX.length()) {
				String value = property(environment, key);
				if (value == null) {
					continue;
				}
				Map<String, String> attributes = given.computeIfAbsent(key.substring(PROXY_PREFIX.length()), k -> new LinkedHashMap<>());
				attributes.putIfAbsent("properties", value);
				attributes.putIfAbsent("mode", "always");
			}
		}
		given.forEach((provider, attributes) -> routes.put(provider,
				routes.getOrDefault(provider, new Route(provider, List.of(), List.of(), null, "", null, true)).with(attributes)));
		return routes;
	}

	/** A property's value, or null when unset or when its placeholders can't be resolved. */
	private static String property(ConfigurableEnvironment environment, String name) {
		try {
			return environment.getProperty(name);
		}
		catch (IllegalArgumentException ex) {
			return null; // Boot reports the unresolvable placeholder itself when it binds the property
		}
	}

	/**
	 * Points the route's base-url properties at the proxy and records where the proxy should
	 * forward to: the first configured value (minus the suffix the proxy adds back), or the
	 * route's upstream. A route with neither is left alone, with a warning.
	 */
	private void route(Map<String, Object> props, ConfigurableEnvironment environment, String inspectorUrl, String proxy,
			Route route) {
		String original = null;
		for (String property : route.properties()) {
			String value = property(environment, property);
			// Only an http(s) URL can be forwarded to (and the inspector only accepts those as upstreams).
			if (value != null && value.trim().startsWith("http") && !value.startsWith(inspectorUrl)) {
				original = value.trim();
				break;
			}
		}
		if (original == null && (route.upstream() == null || route.upstream().isBlank())) {
			this.log.warn("Spring AI Inspector: not routing '" + route.provider() + "', none of "
					+ String.join(", ", route.properties()) + " is set and the route names no upstream");
			return;
		}
		String suffix = route.suffix() == null ? "" : route.suffix();
		String upstream = (original == null ? route.upstream() : original).replaceAll("/+$", "");
		if (!suffix.isEmpty() && upstream.endsWith(suffix)) {
			upstream = upstream.substring(0, upstream.length() - suffix.length());
		}
		Set<String> routed = new LinkedHashSet<>();
		routed.add(route.provider());
		for (String property : route.properties()) {
			props.put(property, proxy + "/" + route.provider() + suffix);
			// The provider the property belongs to (spring.ai.openai.base-url -> openai): its model
			// beans' calls are on the wire, under this route's name.
			java.util.regex.Matcher owner = PROPERTY_OWNER.matcher(property);
			if (owner.lookingAt()) {
				routed.add(owner.group(1));
			}
		}
		props.put("spring.ai.inspector.upstream." + route.provider(), upstream);
		props.merge(ROUTED, String.join(",", routed), (a, b) -> a + "," + b);
	}

	/**
	 * Whether a base-url property is unset or points at one of the provider's default hosts
	 * ({@code host} or {@code host:port}): a gateway whose URL merely mentions the host
	 * ({@code https://gateway.example/api.openai.com/}) is not the default.
	 */
	private static boolean isDefault(ConfigurableEnvironment environment, String property, String... defaults) {
		String value = property(environment, property);
		if (value == null || value.isBlank()) {
			return true;
		}
		String host;
		String hostPort;
		try {
			URI uri = URI.create(value.trim());
			if (uri.getHost() == null) {
				return false;
			}
			host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
			hostPort = uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
		for (String d : defaults) {
			if (d.equalsIgnoreCase(host) || d.equalsIgnoreCase(hostPort)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The configured chat models, of any provider: {@code spring.ai.<provider>.chat.model} or
	 * {@code spring.ai.<provider>.chat.model} (e.g. jinfer's in-JVM models too), and
	 * {@code spring.ai.<provider>.responses.model} (OpenAI's Responses API).
	 */
	static String models(ConfigurableEnvironment environment) {
		// Per provider: chat.options.model (the older spelling) wins over chat.model, as Spring AI resolves it. A Responses
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
	 * spring.ai.openai.chat.model), which the environment resolves by relaxed
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
