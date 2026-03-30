package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
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

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder().build())
				.build();

			// SYSTEM INSTRUCTIONS
			String answer = chatClient.prompt()
				.system("Impersonate Yoda (from Star Wars). Keep the jokes clean, short and family friendly.")
				.user("Tell me a joke?")
				.call()
				.content();
			System.out.println("\n -------------------------------- \n" + answer);

		}; // @formatter:on
	}

}
