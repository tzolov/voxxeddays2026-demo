package com.example.demo;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
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
					.build())
				.build();

			record ActorsFilms(String actor, List<String> movies) {}

			var validationAdvisor = StructuredOutputValidationAdvisor.builder()
				.outputType(ActorsFilms.class)
				.maxRepeatAttempts(3)
				.build();

			ActorsFilms actorsFilms = chatClient.prompt()
				.advisors(validationAdvisor)
				.user("Generate the filmography of 5 movies for Tom Hanks.")
				.call()
				.entity(ActorsFilms.class);
	
			System.out.println(actorsFilms);

		}; // @formatter:on
	}

}
