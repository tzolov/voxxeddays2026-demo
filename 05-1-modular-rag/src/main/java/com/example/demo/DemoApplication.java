package com.example.demo;

import java.util.function.Function;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.rag.JevDocumentFilter;
import org.springaicommunity.typesafe.rag.JevDocumentReranker;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Value("classpath:wikipedia-hurricane-milton-page.pdf")
	Resource hurricaneDocs;

	@Bean
	VectorStore vectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, VectorStore vectorStore,
			TypeSafeClient typeSafeClient, TaskExecutor taskExecutor) {
		return args -> { // @formatter:off

			// Load the PDF document, split it into chunks, and add to the vector store
			// (Onetime, offline operation)
			vectorStore.add(
				TokenTextSplitter.builder().build().split(
					new PagePdfDocumentReader(hurricaneDocs).read()));

			// Separate ChatClient for the LLM-backed RAG stages (rewrite, expand),
			// so they don't inherit the advisors of the main client
			var ragClientBuilder = chatClientBuilder.clone();

			// Modular RAG: each stage is a pluggable building block
			var modularRag = RetrievalAugmentationAdvisor.builder()
				// 1. Pre-retrieval: rewrite the verbose user question into a search-friendly query
				.queryTransformers(RewriteQueryTransformer.builder()
					.chatClientBuilder(ragClientBuilder)
					.build())
				// 2. Pre-retrieval: expand it into several semantically diverse queries (original included)
				.queryExpander(MultiQueryExpander.builder()
					.chatClientBuilder(ragClientBuilder)
					.numberOfQueries(3)
					.build())
				// 3. Retrieval: similarity search per query (the default joiner dedups the results)
				.documentRetriever(logging(VectorStoreDocumentRetriever.builder()
					.vectorStore(vectorStore)
					.similarityThreshold(0.5)
					.topK(4)
					.build()))
				// 4. Post-retrieval: print the chunks, let TypeSafe's Jev drop injected/irrelevant passages,
				//    rerank the rest by "could this answer the query?", keep the top 3 and print again
				.documentPostProcessors(
					print("[retrieved]", Document::getScore),
					JevDocumentFilter.builder(typeSafeClient).build(),
					JevDocumentReranker.builder(typeSafeClient).topK(3).build(),
					print("[jev-reranked]", d -> d.getMetadata().get(JevDocumentReranker.SCORE_METADATA_KEY)))
				// 5. Generation: how the context is stuffed into the prompt
				.queryAugmenter(ContextualQueryAugmenter.builder()
					.allowEmptyContext(true)
					.build())
				// Run the per-query retrievals on Boot's managed (virtual-thread) executor instead of the
				// advisor's default non-daemon pool, which would keep this CLI app alive after the answer
				.taskExecutor(taskExecutor)
				.build();

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder().order(1).build())
				.build();

			var answer = chatClient.prompt()
				.advisors(modularRag)
				.user("I'm a Florida resident and heard a lot about storms last fall. " +
					  "Did Milton actually make landfall in my state, and where?")
				.call()
				.content();

			System.out.println(answer);

		}; // @formatter:on
	}

	private static final String GREEN = "\033[38;5;82m";

	private static final String RESET = "\033[0m";

	// Post-processor that prints each document's score and a snippet, then passes the
	// list on unchanged
	private static DocumentPostProcessor print(String label, Function<Document, Object> score) {
		return (query, docs) -> {
			docs.forEach(d -> System.out.printf(GREEN + "%s %s  %s..." + RESET + "%n", label, score.apply(d),
					first(d.getText(), 80)));
			return docs;
		};
	}

	// Wraps a retriever to print every (rewritten and expanded) query that reaches the
	// vector store
	private static DocumentRetriever logging(DocumentRetriever delegate) {
		return query -> {
			System.out.println(GREEN + "[retrieve] " + query.text() + RESET);
			return delegate.retrieve(query);
		};
	}

	private static String first(String text, int n) {
		return text.length() <= n ? text : text.substring(0, n).replace('\n', ' ');
	}

}
