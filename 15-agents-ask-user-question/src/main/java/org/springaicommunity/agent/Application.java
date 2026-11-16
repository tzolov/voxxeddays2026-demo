package org.springaicommunity.agent;

import java.util.Map;
import java.util.Scanner;

import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner(ChatClient.Builder chatClientBuilder) {

        return args -> {
            // @formatter:off
			ChatClient chatClient = chatClientBuilder
			
				// Ask user question tool
				.defaultTools(AskUserQuestionTool.builder()
					.questionHandler(new CommandLineQuestionHandler())
					.answersValidation(false)
					.build())

				// Session memory; the memory advisor runs inside the tool call advisor by default
				.defaultAdvisors(SessionAdvisors.builder(DefaultSessionService.builder()
					.sessionRepository(InMemorySessionRepository.builder().build())
					.build())
					// replay the last 500 events, extended to whole turns, so the prompt stays bounded
					.memory(memory -> memory.eventFilter(EventFilter.lastN(500)))
					.build())

				.build();
				// @formatter:on

            // Start the chat loop
            System.out.println("\nI am your assistant.\n");

            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("\nUSER: ");
                    String userInput = scanner.nextLine();
                    if (userInput.isBlank()) {
                        continue; // an empty line, e.g. Enter pressed after background log output
                    }
                    System.out.println("\nASSISTANT: " + chatClient.prompt(userInput)
                            .advisors(SessionAdvisors.session("conversation-id-1234"))
                            .toolContext(Map.of("tool", "context"))
                            .call()
                            .content());
                }
            }
        };
    }

}
