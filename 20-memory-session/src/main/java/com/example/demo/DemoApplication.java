package com.example.demo;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
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
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, SessionService sessionService) {
		return args -> { // @formatter:off

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.showConversationHistory(true)
					.build())
				// The session turn advisor (outside the tool loop) and the session memory
				// advisor (inside it). Compact when 20 turns accumulate, keeping the last 10 events
				.defaultAdvisors(SessionAdvisors.builder(sessionService)
					.compaction(new TurnCountTrigger(20), WindowCompactionStrategy.builder().lastEvents(10).build())
					.build())
				// Sessions created on first use are tagged with their owner
				.defaultAdvisors(a -> a.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
					Map.of(SessionAttributes.PRINCIPAL_NAME, "alice")))
				.build();

			System.out.println("Name introduction: " + chatClient.prompt("My name is Christian Tzolov")
				.advisors(SessionAdvisors.session("alice"))
				.call().content());

			System.out.println("Asking for the name: " + chatClient.prompt("What is my name?")
				.advisors(SessionAdvisors.session("alice"))
				.call().content());

		}; // @formatter:on
	}

}
