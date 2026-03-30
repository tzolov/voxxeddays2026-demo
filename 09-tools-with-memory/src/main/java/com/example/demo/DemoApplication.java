package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 2000)
					.showConversationHistory(true)
					.showAvailableTools(true)
					.build())
				.build();

			// TOOL CALL & Memory
			var output = chatClient.prompt()
				.tools(new WeatherTools())
				.advisors(
					ToolCallAdvisor.builder().disableInternalConversationHistory().build(),
					MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build())
						.order(Ordered.HIGHEST_PRECEDENCE + 1000).build())
				.user("What should I wear today in Amsterdam and in Barcelona?")
				.call().content();
			System.out.println("\n" + output);

		}; // @formatter:on
	}

}
