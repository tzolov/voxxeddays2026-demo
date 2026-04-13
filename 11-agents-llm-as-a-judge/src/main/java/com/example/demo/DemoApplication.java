package com.example.demo;

import java.util.Random;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(AnthropicChatModel mainChatModel, OllamaChatModel ollamaChatModel) {
		return args -> { // @formatter:off

			ChatClient chatClient = ChatClient.builder(mainChatModel)

				.defaultTools(new MyTools())
				
				.defaultAdvisors(SelfRefineEvaluationAdvisor.builder()
					.order(0)
					.chatClientBuilder(ChatClient.builder(ollamaChatModel))
					.maxRepeatAttempts(15)
					.successRating(4)
					.build())

				.defaultAdvisors(ToolCallAdvisor.builder()
					.advisorOrder(1)
					.disableInternalConversationHistory()
					.build())

				.defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build())
					.order(2)
					.build())

				.defaultAdvisors(MyLoggingAdvisor.builder()
					.order(3)
					.build())				

				.build();

			var answer = chatClient
				.prompt("What is current weather in Paris?")
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
			System.out.println("              responseTemp: " + temperature);
			return "The current weather in " + location + " is sunny with a temperature of " + temperature + "°C.";
		}

	}

}
