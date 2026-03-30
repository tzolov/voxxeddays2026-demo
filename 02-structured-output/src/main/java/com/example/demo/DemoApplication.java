package com.example.demo;

import java.util.List;

import org.springframework.ai.chat.client.AdvisorParams;
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

			// STRUCTURED OUTPUT
			record ActorsFilms(String actor, List<String> movies) {}

			ActorsFilms actorsFilms = chatClient.prompt()
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.user("Generate the filmography of 5 movies for Tom Hanks.")
				.call()
				.entity(ActorsFilms.class);

			System.out.println("\n -------------------------- \n" + actorsFilms);

		}; // @formatter:on
	}

}
