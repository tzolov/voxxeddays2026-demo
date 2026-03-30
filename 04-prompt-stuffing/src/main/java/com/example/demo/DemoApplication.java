package com.example.demo;

import java.nio.charset.Charset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Value("classpath:spring-io-2025-schedule.md")
	Resource conferenceAgenda;

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder().build())
				.build();

			// SYSTEM INSTRUCTIONS & PROMPT STUFFING & STRUCTURED OUTPUT
			record Track(String name, List<Talk> talks) {
				record Talk(String time, String session, String location, String track, List<String> authors) {}
			}

			List<Track> talks = chatClient.prompt()
				.system("You are a useful assistant. Follow the user instructions.")
				.user(u -> u.text("""
						Get the list of talks grouped by tracks :\n {additionalContext}.
						List only the sessions with more than 1 speakers""")
					.param("additionalContext", asText(conferenceAgenda)))
				.call()
				.entity(new ParameterizedTypeReference<List<Track>>() {});

			System.out.println(talks);
			
			System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(talks));

		}; // @formatter:on
	}

	static String asText(Resource resource) {
		try {
			return resource.getContentAsString(Charset.defaultCharset());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
