package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
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
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.showAvailableTools(true)
					.build())
				.build();

			// TOOLS
			var output = chatClient.prompt()
				.tools(new WeatherTools())
				.advisors(ToolCallAdvisor.builder().build())
				.user("What should I wear today in Amsterdam and in Barcelona?")
				.call()
				.content();
				
			System.out.println("\n" + output);

		}; // @formatter:on
	}

}

