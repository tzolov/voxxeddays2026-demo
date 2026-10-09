package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.util.JsonHelper;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class InspectorEmbeddingModelPostProcessorTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)));

	private EmbeddingModel wrap(EmbeddingModel model, MockEnvironment environment) {
		var provider = new StaticListableBeanFactory(Map.of("client", this.client)).getBeanProvider(InspectorClient.class);
		return (EmbeddingModel) new InspectorEmbeddingModelPostProcessor(provider, environment)
			.postProcessAfterInitialization(model, "embeddingModel");
	}

	@Test
	void reportsEachOutermostCallOnceWithItsInputsVectorsAndUsage() {
		EmbeddingModel model = wrap(new JinferEmbeddingModel(), new MockEnvironment());

		model.embed("one text"); // default method delegating to call() on the model itself
		model.embed(List.of("a", "b"));

		assertThat(this.events).extracting(e -> e.get("type")).containsExactly("embedding-call", "embedding-call");
		// A bare vector carries no metadata: the model is the configured one (none here).
		assertThat(this.events.get(0)).containsEntry("provider", "jinfer")
			.containsEntry("model", null)
			.containsEntry("inputs", 1)
			.containsEntry("sample", List.of("one text"))
			.containsEntry("vectors", 1)
			.containsEntry("dimensions", 3);
		assertThat(this.events.get(1)).containsEntry("inputs", 2).containsEntry("vectors", 2);
	}

	@Test
	void reportsTheModelAndUsageOfAResponseAndAFailedCall() {
		EmbeddingModel model = wrap(new JinferEmbeddingModel(), new MockEnvironment());

		model.call(new EmbeddingRequest(List.of("x"), null));
		assertThatIllegalStateException().isThrownBy(() -> model.call(new EmbeddingRequest(List.of("boom"), null)));

		assertThat(this.events.get(0)).containsEntry("model", "qwen3-embedding")
			.containsEntry("usage", Map.of("input", 4));
		assertThat(this.events.get(1)).containsEntry("error", "IllegalStateException: model not loaded");
	}

	@Test
	void aResponseWithoutPromptTokensIsStillReported() {
		EmbeddingModel model = wrap(new JinferEmbeddingModel() {
			@Override
			public EmbeddingResponse call(EmbeddingRequest request) {
				return new EmbeddingResponse(List.of(new Embedding(new float[] { 1 }, 0)),
						new EmbeddingResponseMetadata("m", new org.springframework.ai.chat.metadata.Usage() {
							@Override
							public Integer getPromptTokens() {
								return null; // a provider that doesn't report it
							}

							@Override
							public Integer getCompletionTokens() {
								return null;
							}

							@Override
							public Object getNativeUsage() {
								return null;
							}
						}));
			}
		}, new MockEnvironment());

		model.call(new EmbeddingRequest(List.of("x"), null));

		assertThat(this.events).singleElement().satisfies(e -> assertThat(e).containsEntry("model", "m").doesNotContainKey("usage"));
	}

	@Test
	void fallsBackToTheConfiguredModelProperty() {
		EmbeddingModel model = wrap(new JinferEmbeddingModel() {
			@Override
			public EmbeddingResponse call(EmbeddingRequest request) {
				return new EmbeddingResponse(List.of(new Embedding(new float[] { 1 }, 0)));
			}
		}, new MockEnvironment().withProperty("spring.ai.jinfer.embedding.model", "Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0"));

		model.embed("x");

		assertThat(this.events.get(0)).containsEntry("model", "Qwen/Qwen3-Embedding-0.6B-GGUF:Q8_0");
	}

	/** Non-final, like most embedding models; named like jinfer's. */
	public static class JinferEmbeddingModel implements EmbeddingModel {

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			if (request.getInstructions().contains("boom")) {
				throw new IllegalStateException("model not loaded");
			}
			List<Embedding> embeddings = request.getInstructions()
				.stream()
				.map(text -> new Embedding(new float[] { 0.1f, 0.2f, 0.3f }, 0))
				.toList();
			return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata("qwen3-embedding", new DefaultUsage(4, 0)));
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

	}

}
