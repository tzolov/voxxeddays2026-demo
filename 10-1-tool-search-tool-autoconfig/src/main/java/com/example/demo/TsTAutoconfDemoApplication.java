package com.example.demo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.toolsearch.JevToolIndex;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@SpringBootApplication
public class TsTAutoconfDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(TsTAutoconfDemoApplication.class, args);
	}

	@Bean
	VectorStore vectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	// tool-index-type=jev: TypeSafe's Jev picks the tools. Unlike the regex, lucene and
	// vector indexes it can also answer "no tool applies". The built-in indexes back off
	// when a ToolIndex bean exists.
	@Bean
	@ConditionalOnProperty(name = "spring.ai.chat.client.tool-search-advisor.tool-index-type", havingValue = "jev")
	ToolIndex jevToolIndex(TypeSafeClient typeSafeClient) {
		return JevToolIndex.builder(typeSafeClient)
			.applicabilityThreshold(0.5) // below this, no tool is returned at all
			.minimumRelevance(0.05) // don't pad maxResults with tools Jev ruled out
			.build();
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			var loggingAdvisor = MyLoggingAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 2000)
					.showAvailableTools(true)
					.build();
			
			ChatClient chatClient = chatClientBuilder
				.defaultTools(new MyTools(), new DummyTools())
				.defaultAdvisors(loggingAdvisor)
				.defaultAdvisors(new TokenCounterAdvisor())
				.defaultAdvisors(a -> a.param(ChatMemory.CONVERSATION_ID, "chat_memory_conversation_id"))
				.build();

			var answer = chatClient.prompt("""
					Help me buy clothes today in Landsmeer, NL.
					I need to wear something suitable for the weather.
					Please suggest clothing shops that are open right now in the area.					
					""")
					.call().content();

			System.out.println(answer);

		}; // @formatter:on
	}

	static class MyTools {

		@Tool(description = "Get the weather for a given location and at a given time")
		public String weather(String location, @ToolParam(description = "YYYY-MM-DDTHH:mm:ss") String atTime) {
			// A different temperature on each run, so the clothing advice changes too.
			int temperature = ThreadLocalRandom.current().nextInt(-2, 29);
			String conditions = temperature < 5 ? "cold and overcast, with a chance of sleet"
					: temperature < 12 ? "cool and windy, with light rain"
							: temperature < 20 ? "mild and partly cloudy" : "sunny and warm";
			return "The weather in " + location + " is " + conditions + ", " + temperature + "°C.";
		}

		@Tool(description = "Get the names of clothing shops in a location that are open at a given time")
		public List<String> clothing(String location,
				@ToolParam(description = "YYYY-MM-DDTHH:mm:ss") String openAtTime) {
			return List.of("De Kledingkast, Dorpsstraat 12 (open 09:30-18:00) - casual and outdoor wear",
					"Mode Landsmeer, Zuideinde 48 (open 10:00-17:30) - women's and men's fashion",
					"Buitensport Waterland, Noordeinde 5 (open 09:00-18:00) - rain jackets and boots");
		}

		@Tool(description = "Provides the current date and time (as date-time string) for a given location")
		public String currentTime(String location) {
			return LocalDateTime.now().toString();
		}

	}

}
