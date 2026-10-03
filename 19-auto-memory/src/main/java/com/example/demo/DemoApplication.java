package com.example.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Scanner;
import java.util.UUID;

import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.dream.AutoDreamAdvisor;
import org.springaicommunity.agent.dream.AutoDreamService;
import org.springaicommunity.agent.dream.DreamResult;
import org.springaicommunity.agent.utils.AgentEnvironment;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;

@SpringBootApplication
public class DemoApplication {

	Instant lastInteraction = Instant.now();

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	CommandLineRunner commandLineRunner(ChatClient.Builder chatClientBuilder,
			@Value("${agent.model:Unknown}") String agentModel,
			@Value("${agent.model.knowledge.cutoff:Unknown}") String agentModelKnowledgeCutoff,
			@Value("classpath:/prompt/MAIN_AGENT_SYSTEM_PROMPT_V2.md") Resource systemPrompt,
			@Value("${agent.memory.dir}") String memoryDir,
			@Value("${agent.user-id:alice}") String userId) throws IOException {

		return args -> {

			seedDemoMemoriesIfAbsent(Path.of(memoryDir));

			// Short-term conversation history. In-memory, so it resets on every run.
			SessionService sessionService = DefaultSessionService.builder()
				.sessionRepository(InMemorySessionRepository.builder().build())
				.build();
			seedPastSession(sessionService, userId);

			// The Dreamer: a background subagent that consolidates the whole memory store.
			// Clone the builder BEFORE the main agent's system prompt and tools are added,
			// so the Dreamer only gets the memory tools and cross_session_search.
			AutoDreamService dreamService = AutoDreamService.builder(chatClientBuilder.clone())
				.sessionService(sessionService) // enables cross-session recall
				.build();

			var sessionId = "session-" + UUID.randomUUID().toString();

			ChatClient chatClient = chatClientBuilder // @formatter:off
				// system prompt
				.defaultSystem(p -> p.text(systemPrompt) // system prompt
					.param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
					.param(AgentEnvironment.GIT_STATUS_KEY, AgentEnvironment.gitStatus())
					.param(AgentEnvironment.AGENT_MODEL_KEY, agentModel)
					.param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, agentModelKnowledgeCutoff))

				.defaultAdvisors(
					// Long-term memory advisor
					AutoMemoryToolsAdvisor.builder()
						.memoriesRootDirectory(memoryDir)
						// In-band consolidation: a cheap nudge added to the live agent's current turn
						.memoryConsolidationTrigger((request, instant) -> {
							var previousInteraction = lastInteraction;
							lastInteraction = Instant.now();
							if (instant.isAfter(previousInteraction.plusSeconds(60))) {
								// Consolidate on the first message after 60 seconds of silence
								return true;
							}

							// Trigger memory consolidation when the user says "bye" in their last message
							var userMessage = request.prompt().getLastUserOrToolResponseMessage().getText();
							return userMessage != null && userMessage.toLowerCase().contains("bye");
						})
						.build(),

					// Out-of-band consolidation ("dreaming"): runs in the background after the
					// response is returned, so it never blocks the user. Every 3 turns for the demo;
					// DreamTriggers.hoursAndSessions(24, 5) is the realistic setting.
					AutoDreamAdvisor.builder()
						.memoriesRootDirectory(memoryDir)
						.dreamService(dreamService)
						.dreamTrigger((state, now) -> state.sessionsSinceLastDream() >= 3)
						.userId(userId) // the Dreamer may search this user's past sessions
						.build(),

					// Short-term memory, backed by the same SessionService the Dreamer searches
					SessionMemoryAdvisor.builder(sessionService)
						.defaultUserId(userId)
						.compactionTrigger(new TurnCountTrigger(20))
						.compactionStrategy(SlidingWindowCompactionStrategy.builder().maxEvents(10).build())
						.order(Ordered.HIGHEST_PRECEDENCE + 1000) // after tool calling advisor (+300) and logging advisor (+600)
						.build(),

					// Custom logging advisor
					MyLoggingAdvisor.builder()
						.showAvailableTools(false)
						.showSystemMessage(false)
						.order(Ordered.HIGHEST_PRECEDENCE + 1600) // after session memory advisor (+1000)
						.build())
				.build();
				// @formatter:on

			// Start the chat loop
			System.out.println("\nI am your assistant. Memory is stored at " + memoryDir);
			System.out.println("Type /dream to consolidate memory now. Every 3rd turn dreams in the background.\n");

			try (Scanner scanner = new Scanner(System.in)) {
				while (true) {
					System.out.print("\n\033[1;34mUSER>\033[0m ");
					String userInput = scanner.nextLine();

					if ("/dream".equalsIgnoreCase(userInput.trim())) {
						System.out.println("\n\033[1;35mDREAM>\033[0m consolidating memory...");
						DreamResult result = dreamService.runDreamCycle(memoryDir, userId);
						System.out.println("\033[1;35mDREAM>\033[0m " + result.status() + ": " + result.summary());
						continue;
					}

					System.out.println("\n\033[1;34mASSISTANT>\033[0m " + chatClient.prompt(userInput)
						.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
						.call()
						.content());
				}
			}
		};

	}

	/**
	 * Seeds two overlapping feedback memories and a MEMORY.md link to a missing file, so
	 * the first dream has something to clean up. Only when the memory store is empty.
	 */
	private static void seedDemoMemoriesIfAbsent(Path memoriesDir) throws IOException {
		Path memoryIndex = memoriesDir.resolve("MEMORY.md");
		if (Files.exists(memoryIndex)) {
			return;
		}
		Files.createDirectories(memoriesDir);

		Files.writeString(memoriesDir.resolve("feedback_testing_a.md"), """
				---
				name: testing-preference-a
				description: Prefer a real database in integration tests
				type: feedback
				---

				Always use a real database in integration tests, never mock it.

				**Why:** Mocked tests passed but the prod migration failed last quarter.
				**How to apply:** Any new integration test touching persistence.
				""", StandardCharsets.UTF_8);

		Files.writeString(memoriesDir.resolve("feedback_testing_b.md"), """
				---
				name: testing-preference-b
				description: Integration tests must hit a real database, not mocks
				type: feedback
				---

				Integration tests must hit a real database, not mocks.

				**Why:** A mock/prod divergence masked a broken migration once.
				**How to apply:** Whenever writing or reviewing integration tests.
				""", StandardCharsets.UTF_8);

		Files.writeString(memoryIndex, """
				- [Testing Preference A](feedback_testing_a.md) — always use a real DB in integration tests
				- [Testing Preference B](feedback_testing_b.md) — integration tests must hit a real database, not mocks
				- [Old Sprint Notes](project_old_sprint.md) — sprint 12 deadline notes
				""", StandardCharsets.UTF_8);

		System.out.println("Seeded demo memories: two duplicates and one dangling MEMORY.md link.");
	}

	/**
	 * Seeds a past session, two days old, with a decision and a correction: the signals
	 * the Dreamer looks for with cross_session_search. Runs every time, since the session
	 * store is in-memory.
	 */
	private static void seedPastSession(SessionService sessionService, String userId) {
		var pastSession = sessionService.create(CreateSessionRequest.builder().userId(userId).build());
		Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));

		sessionService.appendEvent(SessionEvent.builder()
			.sessionId(pastSession.id())
			.timestamp(twoDaysAgo)
			.message(new UserMessage("We decided to use PostgreSQL instead of MySQL for the new service."))
			.build());
		sessionService.appendEvent(SessionEvent.builder()
			.sessionId(pastSession.id())
			.timestamp(twoDaysAgo.plusSeconds(30))
			.message(new AssistantMessage("Got it, Postgres is the choice going forward."))
			.build());
		sessionService.appendEvent(SessionEvent.builder()
			.sessionId(pastSession.id())
			.timestamp(twoDaysAgo.plusSeconds(120))
			.message(new UserMessage("Actually, let's go with blue-green deployment instead of canary."))
			.build());
	}

}
