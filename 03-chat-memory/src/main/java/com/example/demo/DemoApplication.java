package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			// CHAT MEMORY
			var chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.showConversationHistory(true)
					.build())
				.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
				.build();
			
			System.out.println("Name introduction: " + chatClient.prompt("My name is Christian Tzolov").call().content());
			
			System.out.println("Asking for the name: " + chatClient.prompt("What is my name?").call().content());

		}; // @formatter:on
	}

}
