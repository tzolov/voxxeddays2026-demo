package com.example.demo;

import java.util.List;

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

			// Desired ouput
			record ActorsFilms(String actor, List<String> movies) {}
			
			ActorsFilms actorsFilms = chatClient.prompt()
				.user("Generate the filmography of 5 movies for Tom Hanks.")
				.call()
				// Request the output to be deserialized into the ActorsFilms record
				.entity(ActorsFilms.class,
					// Uses LLM's native structured output capabilities if available
					// or fall back to Spring AI's generic support
					e -> e.useProviderStructuredOutput());

			System.out.println("Answer: \n" + actorsFilms);

		}; // @formatter:on
	}

}
