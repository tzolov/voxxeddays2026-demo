package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Value("classpath:wikipedia-hurricane-milton-page.pdf")
	Resource hurricaneDocs;

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
		return args -> { // @formatter:off

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 2000)
					.showConversationHistory(true)
					.build())
				.build();

			// RAG
			vectorStore.add(				
				TokenTextSplitter.builder().build().split(
					new PagePdfDocumentReader(hurricaneDocs).read()));

			var answer = chatClient.prompt()
				.advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
				.user("Was Florida hit by the Hurricane Milton?")
				.call()
				.content();

			System.out.println(answer);

		}; // @formatter:on
	}

	@Bean
	VectorStore vectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

}
