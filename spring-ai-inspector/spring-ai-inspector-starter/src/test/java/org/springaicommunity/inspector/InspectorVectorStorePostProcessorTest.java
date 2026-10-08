package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.util.JsonHelper;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorVectorStorePostProcessorTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)));

	private FakeStore wrap(FakeStore store) {
		var provider = new StaticListableBeanFactory(Map.of("client", this.client)).getBeanProvider(InspectorClient.class);
		return (FakeStore) new InspectorVectorStorePostProcessor(provider).postProcessAfterInitialization(store, "store");
	}

	@Test
	void keepsTheConcreteTypeSoTypedInjectionStillWorks() {
		FakeStore proxy = wrap(new FakeStore());

		assertThat(proxy).isInstanceOf(FakeStore.class).isInstanceOf(VectorStore.class);
	}

	@Test
	void reportsSearchesWithQueryAndScoredResults() {
		FakeStore proxy = wrap(new FakeStore());

		proxy.similaritySearch(SearchRequest.builder().query("milton").topK(2).build());

		// Reported when it starts and when it ends, with one id.
		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("vector-start", "vector-search");
		assertThat(this.events.get(0)).containsEntry("op", "search").containsEntry("query", "milton");
		assertThat(this.events.get(1).get("opId")).isEqualTo(this.events.get(0).get("opId"));
		assertThat(this.events.get(1)).satisfies(e -> {
			assertThat(e.get("query")).isEqualTo("milton");
			assertThat(e.get("topK")).isEqualTo(2);
			assertThat(e.get("results").toString()).contains("landfall").contains("0.9");
		});
	}

	@Test
	void reportsEveryEntryPointExactlyOnce() {
		FakeStore proxy = wrap(new FakeStore());
		List<Document> docs = List.of(new Document("a"), new Document("b"));

		proxy.similaritySearch("milton"); // default method delegating to the target
		proxy.add(docs);
		proxy.accept(docs); // DocumentWriter entry point delegating to add

		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("vector-start", "vector-search",
				"vector-start", "vector-add", "vector-start", "vector-add");
		assertThat(this.events.get(2)).containsEntry("op", "add").containsEntry("count", 2);
	}

	@Test
	void reportsAFailedOperationWithItsError() {
		FakeStore proxy = wrap(new FakeStore() {
			@Override
			public void add(List<Document> documents) {
				throw new IllegalStateException("embedding model down");
			}
		});

		org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(() -> proxy.add(List.of(new Document("a"))));

		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("vector-start", "vector-add");
		assertThat(this.events.get(1)).containsEntry("error", "IllegalStateException: embedding model down");
		// Any failure ends the operation, an Error too: it isn't shown as running for good.
		FakeStore failing = wrap(new FakeStore() {
			@Override
			public List<Document> similaritySearch(SearchRequest request) {
				throw new StackOverflowError();
			}
		});
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> failing.similaritySearch(SearchRequest.builder().query("q").build()))
			.isInstanceOf(StackOverflowError.class);
		assertThat(this.events.get(3)).containsEntry("type", "vector-search").containsEntry("error", "StackOverflowError: null");
	}

	/** Non-final, like SimpleVectorStore. */
	public static class FakeStore implements VectorStore {

		@Override
		public void add(List<Document> documents) {
		}

		@Override
		public void delete(List<String> idList) {
		}

		@Override
		public void delete(Filter.Expression filterExpression) {
		}

		@Override
		public List<Document> similaritySearch(SearchRequest request) {
			return List.of(Document.builder().text("Milton made landfall").score(0.9).build());
		}

	}

}
