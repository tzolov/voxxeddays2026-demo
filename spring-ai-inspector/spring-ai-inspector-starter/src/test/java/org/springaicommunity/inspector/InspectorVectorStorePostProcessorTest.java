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

		assertThat(this.events).singleElement().satisfies(e -> {
			assertThat(e.get("type")).isEqualTo("vector-search");
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

		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("vector-search", "vector-add", "vector-add");
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
