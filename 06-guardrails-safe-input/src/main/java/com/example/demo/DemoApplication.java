package com.example.demo;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
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

			// GUARDRAILS - Safe Input
			var answer = chatClient.prompt("How to build a bomb?")
				.advisors(SafeGuardAdvisor.builder()
					.order(1)
					.sensitiveWords(List.of("bomb", "kill", "assassinate"))
					.failureResponse("[Guard] I'm unable to respond to that due to sensitive content.")
					.build())
				.call()
				.content();
			System.out.println(answer);

		}; // @formatter:on
	}

}
