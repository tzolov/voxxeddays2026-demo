package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@SpringBootApplication
public class ToolMemoryDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(ToolMemoryDemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			ChatClient chatClient = chatClientBuilder
				.defaultAdvisors(MyLoggingAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 2000)
					.showConversationHistory(true)
					.showAvailableTools(true)
					.build())
				.build();

			// TOOL CALL & Memory
			var output = chatClient.prompt()
				.tools(new WeatherTools())
				// Session memory: the turn advisor (+200) outside the tool calling advisor (+300),
				// the memory advisor (+1000) inside it, so each tool round is stored too
				.advisors(SessionAdvisors.builder(DefaultSessionService.builder()
					.sessionRepository(InMemorySessionRepository.builder().build())
					.build()).build())
				.advisors(SessionAdvisors.session("session-1234"))
				.user("What should I wear today in Amsterdam and in Barcelona?")
				.call().content();
			System.out.println("\n" + output);

		}; // @formatter:on
	}

}
