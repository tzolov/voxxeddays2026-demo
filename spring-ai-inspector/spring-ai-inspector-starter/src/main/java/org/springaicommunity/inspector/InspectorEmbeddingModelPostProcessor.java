package org.springaicommunity.inspector;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

/**
 * Wraps {@link EmbeddingModel} beans so the Spring AI Inspector sees every embedding call,
 * including those of models running in the JVM (e.g. jinfer), which make no HTTP calls for
 * its recording proxy to see. Reported once done: provider, model, inputs, vectors and their
 * dimensions, usage and duration. The inspector shows them where no HTTP round-trip was
 * recorded for the same call, so remote models are not counted twice.
 *
 * <p>Only the outermost call on the bean is reported: the default {@code embed(...)} methods
 * delegate to {@code call(...)} on the model itself, past the proxy.
 */
public class InspectorEmbeddingModelPostProcessor implements BeanPostProcessor {

	private static final Set<String> EMBEDDING_METHODS = Set.of("call", "embed", "embedForResponse");

	private static final int MAX_SAMPLE = 300;

	private final ObjectProvider<InspectorClient> client;

	private final Environment environment;

	public InspectorEmbeddingModelPostProcessor(ObjectProvider<InspectorClient> client, Environment environment) {
		this.client = client;
		this.environment = environment;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof EmbeddingModel model)) {
			return bean;
		}
		String provider = provider(model);
		String configuredModel = configuredModel(model, provider);
		ProxyFactory factory = new ProxyFactory(model);
		// Proxy the concrete class when possible, so injection points typed as the
		// implementation keep working; fall back to an interface proxy for final classes.
		factory.setProxyTargetClass(!Modifier.isFinal(model.getClass().getModifiers()));
		factory.addAdvice((MethodInterceptor) invocation -> {
			if (!EMBEDDING_METHODS.contains(invocation.getMethod().getName())) {
				return invocation.proceed();
			}
			String clientCallId = InspectorAdvisor.currentCallId();
			Object[] args = invocation.getArguments();
			long start = System.currentTimeMillis();
			try {
				Object result = invocation.proceed();
				report(provider, configuredModel, args, result, null, System.currentTimeMillis() - start, clientCallId);
				return result;
			}
			catch (Throwable ex) {
				report(provider, configuredModel, args, null, ex.getClass().getSimpleName() + ": " + ex.getMessage(),
						System.currentTimeMillis() - start, clientCallId);
				throw ex;
			}
		});
		return factory.getProxy();
	}

	private void report(String provider, String configuredModel, Object[] args, Object result, String error,
			long durationMs, String clientCallId) {
		this.client.ifAvailable(c -> c.send("embedding-call", () -> {
			List<String> inputs = inputs(args);
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("embeddingId", UUID.randomUUID().toString().substring(0, 8));
			event.put("clientCallId", clientCallId);
			event.put("provider", provider);
			event.put("inputs", inputs.size());
			event.put("sample", inputs.stream().limit(3).map(t -> InspectorClient.truncate(t, MAX_SAMPLE)).toList());
			String model = configuredModel;
			if (result instanceof EmbeddingResponse response) {
				event.put("vectors", response.getResults().size());
				event.put("dimensions", response.getResults().isEmpty() ? null : response.getResults().get(0).getOutput().length);
				if (response.getMetadata() != null) {
					if (response.getMetadata().getModel() != null && !response.getMetadata().getModel().isBlank()) {
						model = response.getMetadata().getModel();
					}
					Integer promptTokens = response.getMetadata().getUsage() != null
							? response.getMetadata().getUsage().getPromptTokens() : null;
					if (promptTokens != null) {
						event.put("usage", Map.of("input", promptTokens));
					}
				}
			}
			else if (result instanceof float[] vector) {
				event.put("vectors", 1);
				event.put("dimensions", vector.length);
			}
			else if (result instanceof List<?> vectors) {
				event.put("vectors", vectors.size());
				event.put("dimensions", !vectors.isEmpty() && vectors.get(0) instanceof float[] v ? v.length : null);
			}
			event.put("model", model);
			event.put("durationMs", durationMs);
			if (error != null) {
				event.put("error", error);
			}
			return event;
		}));
	}

	/** The texts a call embeds, whichever embedding method was used. */
	static List<String> inputs(Object[] args) {
		if (args.length == 0) {
			return List.of();
		}
		Object first = args[0];
		if (first instanceof EmbeddingRequest request) {
			return request.getInstructions();
		}
		if (first instanceof String text) {
			return List.of(text);
		}
		if (first instanceof Document document) {
			return List.of(String.valueOf(document.getText()));
		}
		if (first instanceof List<?> list) {
			return list.stream()
				.map(o -> o instanceof Document document ? String.valueOf(document.getText()) : String.valueOf(o))
				.toList();
		}
		return List.of();
	}

	/** e.g. JinferEmbeddingModel -> jinfer, OpenAiEmbeddingModel -> openai. */
	static String provider(EmbeddingModel model) {
		Class<?> type = model.getClass();
		while ((type.isAnonymousClass() || type.getName().contains("$$")) && type.getSuperclass() != null) {
			type = type.getSuperclass(); // an anonymous subclass or a generated proxy: its declared class
		}
		String name = type.getSimpleName().replaceAll("(Embedding)?Model$", "").toLowerCase(java.util.Locale.ROOT);
		return name.isEmpty() ? "embedding" : name;
	}

	/**
	 * The model as configured: the bean's default options when it exposes them, else the
	 * provider's {@code spring.ai.<provider>.embedding[.options].model} property.
	 */
	private String configuredModel(EmbeddingModel model, String provider) {
		try {
			Method getter = model.getClass().getMethod("getDefaultOptions");
			Object options = getter.invoke(model);
			if (options != null) {
				Object name = options.getClass().getMethod("getModel").invoke(options);
				if (name instanceof String s && !s.isBlank()) {
					return s;
				}
			}
		}
		catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
			// no default options: fall back to the properties
		}
		try {
			String name = this.environment.getProperty("spring.ai." + provider + ".embedding.options.model",
					this.environment.getProperty("spring.ai." + provider + ".embedding.model"));
			return name == null || name.isBlank() ? null : name;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
