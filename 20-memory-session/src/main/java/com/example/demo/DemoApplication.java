package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
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
	SessionService sessionService() {
		return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
	}

	@Bean
	SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
		return SessionMemoryAdvisor.builder(sessionService)
			.defaultUserId("alice")
			// Compact when 20 turns accumulate, keeping the last 10 events
			.compactionTrigger(new TurnCountTrigger(20))
			.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(10).build())
			.build();
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, SessionMemoryAdvisor sessionMemoryAdvisor) {
		return args -> { // @formatter:off

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.showConversationHistory(true)
					.build())
				.defaultAdvisors(sessionMemoryAdvisor)
				.build();
			
			System.out.println("Name introduction: " + chatClient.prompt("My name is Christian Tzolov").call().content());
			
			System.out.println("Asking for the name: " + chatClient.prompt("What is my name?").call().content());

		}; // @formatter:on
	}

}
