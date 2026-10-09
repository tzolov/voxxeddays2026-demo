package com.example.demo;

import java.util.Random;
import java.util.function.Function;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@SpringBootApplication
public class LlmJudgeDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(LlmJudgeDemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(AnthropicChatModel mainChatModel, OllamaChatModel ollamaChatModel,
			ObjectProvider<ObservationRegistry> observationRegistry,
			ObjectProvider<ChatClientBuilderCustomizer> customizers) {
		return args -> { // @formatter:off

			// Builders made from a model get neither the observation registry (which reports tool runs)
			// nor the registered customizers (e.g. the inspector's advisors), as the auto-configured one does.
			Function<ChatModel, ChatClient.Builder> builderFor = model -> {
				ChatClient.Builder builder = ChatClient.builder(model, observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP), null, null);
				customizers.orderedStream().forEach(c -> c.customize(builder));
				return builder;
			};
			ChatClient.Builder judgeBuilder = builderFor.apply(ollamaChatModel);
			ChatClient.Builder mainBuilder = builderFor.apply(mainChatModel);

			ChatClient chatClient = mainBuilder

				.defaultTools(new MyTools())
				
				.defaultAdvisors(SelfRefineEvaluationAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 100)
					.chatClientBuilder(judgeBuilder)
					.maxRepeatAttempts(3)
					.successRating(3)
					.build())

				.defaultAdvisors(MyLoggingAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 150)
					.build())				

				.build();

			var answer = chatClient
				.prompt("What is current weather in Paris?")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conversation-id-1234"))
				.call()
				.content();

			System.out.println("\n -------------------------------- \n" + answer);

		}; // @formatter:on
	}

	static class MyTools {

		final int[] temperatures = { -125, 15, -255 };

		private final Random random = new Random();

		@Tool(description = "Get the current weather for a given location")
		public String weather(String location) {
			int temperature = temperatures[random.nextInt(temperatures.length)];
			System.out.println("[TOOL] weather tool response temperature: " + temperature);
			return "The current weather in " + location + " is sunny with a temperature of " + temperature + "°C.";
		}

	}

}
