package org.springaicommunity.inspector;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps {@link VectorStore} beans so the Spring AI Inspector sees every ingestion and
 * similarity search, with the query and the scored results. The demos build their
 * stores without an ObservationRegistry, so Spring AI's vector store observations
 * never fire; wrapping works regardless.
 */
public class InspectorVectorStorePostProcessor implements BeanPostProcessor {

	private final ObjectProvider<InspectorClient> client;

	public InspectorVectorStorePostProcessor(ObjectProvider<InspectorClient> client) {
		this.client = client;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof VectorStore store)) {
			return bean;
		}
		String storeName = store.getClass().getSimpleName();
		ProxyFactory factory = new ProxyFactory(store);
		// Proxy the concrete class when possible, so injection points typed as the
		// implementation (e.g. SimpleVectorStore) keep working; fall back to an interface
		// proxy for final classes.
		factory.setProxyTargetClass(!Modifier.isFinal(store.getClass().getModifiers()));
		factory.addAdvice((MethodInterceptor) invocation -> {
			String method = invocation.getMethod().getName();
			Object[] args = invocation.getArguments();
			if ("similaritySearch".equals(method) && args.length == 1 && args[0] instanceof SearchRequest request) {
				long start = System.currentTimeMillis();
				Object result = invocation.proceed();
				reportSearch(storeName, request, result, System.currentTimeMillis() - start);
				return result;
			}
			// The default similaritySearch(String) calls the target directly, bypassing the
			// proxy, so it is reported here (with the defaults it searches with).
			if ("similaritySearch".equals(method) && args.length == 1 && args[0] instanceof String query) {
				long start = System.currentTimeMillis();
				Object result = invocation.proceed();
				reportSearch(storeName, SearchRequest.builder().query(query).build(), result,
						System.currentTimeMillis() - start);
				return result;
			}
			// add, and the DocumentWriter entry points accept/write that delegate to it.
			if (("add".equals(method) || "accept".equals(method) || "write".equals(method)) && args.length == 1
					&& args[0] instanceof List<?> documents) {
				long start = System.currentTimeMillis();
				Object result = invocation.proceed();
				reportAdd(storeName, documents, System.currentTimeMillis() - start);
				return result;
			}
			return invocation.proceed();
		});
		return factory.getProxy();
	}

	private void reportSearch(String store, SearchRequest request, Object result, long durationMs) {
		String clientCallId = InspectorAdvisor.currentCallId();
		String thread = Thread.currentThread().getName();
		this.client.ifAvailable(c -> c.send("vector-search", () -> searchEvent(store, request, result, durationMs,
				clientCallId, thread)));
	}

	private static Map<String, Object> searchEvent(String store, SearchRequest request, Object result, long durationMs,
			String clientCallId, String thread) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("searchId", UUID.randomUUID().toString().substring(0, 8));
		event.put("clientCallId", clientCallId);
		event.put("store", store);
		event.put("query", request.getQuery());
		event.put("topK", request.getTopK());
		event.put("threshold", request.getSimilarityThreshold());
		event.put("filter", request.getFilterExpression() == null ? null : request.getFilterExpression().toString());
		event.put("durationMs", durationMs);
		event.put("thread", thread);
		event.put("results", result instanceof List<?> docs ? InspectorDocuments.of(docs, 1500) : List.of());
		return event;
	}

	private void reportAdd(String store, List<?> documents, long durationMs) {
		this.client.ifAvailable(c -> c.send("vector-add", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("store", store);
			event.put("count", documents.size());
			event.put("durationMs", durationMs);
			event.put("sample", InspectorDocuments.of(documents.subList(0, Math.min(3, documents.size())), 300));
			return event;
		}));
	}

}
