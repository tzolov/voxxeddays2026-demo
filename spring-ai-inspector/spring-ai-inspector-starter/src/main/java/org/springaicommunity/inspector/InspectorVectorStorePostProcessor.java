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
			SearchRequest search = !"similaritySearch".equals(method) || args.length != 1 ? null
					: args[0] instanceof SearchRequest request ? request
					// The default similaritySearch(String) calls the target directly, bypassing the
					// proxy, so it is reported here (with the defaults it searches with).
					: args[0] instanceof String query ? SearchRequest.builder().query(query).build() : null;
			// add, and the DocumentWriter entry points accept/write that delegate to it.
			List<?> documents = ("add".equals(method) || "accept".equals(method) || "write".equals(method))
					&& args.length == 1 && args[0] instanceof List<?> list ? list : null;
			if (search == null && documents == null) {
				return invocation.proceed();
			}
			// Reported when it starts too, so the inspector can draw the embedding calls the store
			// makes meanwhile inside the operation as they happen.
			String opId = UUID.randomUUID().toString().substring(0, 8);
			String clientCallId = InspectorAdvisor.currentCallId();
			reportStart(opId, storeName, search, documents, clientCallId);
			long start = System.currentTimeMillis();
			try {
				Object result = invocation.proceed();
				long durationMs = System.currentTimeMillis() - start;
				if (search != null) {
					reportSearch(opId, storeName, search, result, durationMs, clientCallId, null);
				}
				else {
					reportAdd(opId, storeName, documents, durationMs, clientCallId, null);
				}
				return result;
			}
			catch (Throwable ex) { // any failure ends the operation, so it isn't shown as running for good
				long durationMs = System.currentTimeMillis() - start;
				String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
				if (search != null) {
					reportSearch(opId, storeName, search, List.of(), durationMs, clientCallId, error);
				}
				else {
					reportAdd(opId, storeName, documents, durationMs, clientCallId, error);
				}
				throw ex;
			}
		});
		return factory.getProxy();
	}

	/** A search or an add starting: the ChatClient call making it, if any, and what it is. */
	private void reportStart(String opId, String store, SearchRequest search, List<?> documents, String clientCallId) {
		this.client.ifAvailable(c -> c.send("vector-start", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("opId", opId);
			event.put("op", search != null ? "search" : "add");
			event.put("clientCallId", clientCallId);
			event.put("store", store);
			if (search != null) {
				event.put("query", search.getQuery());
			}
			else {
				event.put("count", documents.size());
			}
			return event;
		}));
	}

	private void reportSearch(String opId, String store, SearchRequest request, Object result, long durationMs,
			String clientCallId, String error) {
		String thread = Thread.currentThread().getName();
		this.client.ifAvailable(c -> c.send("vector-search", () -> {
			Map<String, Object> event = searchEvent(store, request, result, durationMs, clientCallId, thread);
			event.put("opId", opId);
			if (error != null) {
				event.put("error", error);
			}
			return event;
		}));
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

	/** {@code clientCallId}: the ChatClient call adding them, if any (e.g. a tool search indexing its tools). */
	private void reportAdd(String opId, String store, List<?> documents, long durationMs, String clientCallId,
			String error) {
		this.client.ifAvailable(c -> c.send("vector-add", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("opId", opId);
			if (error != null) {
				event.put("error", error);
			}
			event.put("clientCallId", clientCallId);
			event.put("store", store);
			event.put("count", documents.size());
			event.put("durationMs", durationMs);
			event.put("sample", InspectorDocuments.of(documents.subList(0, Math.min(3, documents.size())), 300));
			return event;
		}));
	}

}
