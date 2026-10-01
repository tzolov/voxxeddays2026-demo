package com.example.demo;

import java.util.List;
import java.util.Map;

import org.springaicommunity.agent.tools.SkillsTool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

/**
 * Spring AI port of the Pydantic AI "bank support agent" example
 * (https://pydantic.dev/docs/ai/overview/#putting-it-together-a-bank-support-agent).
 *
 * Mapping of concepts:
 * <ul>
 * <li>{@code Agent(...)} → a configured {@link ChatClient}</li>
 * <li>{@code deps_type=SupportDependencies} → {@link SupportDependencies} passed via the
 * tool context</li>
 * <li>{@code output_type=SupportOutput} → {@code .entity(SupportOutput.class)} structured
 * output</li>
 * <li>{@code instructions=...} → {@code .defaultSystem(...)}</li>
 * <li>{@code @support_agent.instructions} (dynamic customer name) → system prompt
 * template parameter resolved per run</li>
 * <li>{@code @support_agent.tool customer_balance} → {@link SupportTools} with
 * {@code ToolContext}</li>
 * <li>deferred {@code refunds} capability → {@code refunds} skill loaded on demand via
 * {@link SkillsTool}</li>
 * <li>{@code MCPServerStdio(...)} toolset → the auto-configured {@link ToolCallbackProvider}
 * over the stdio MCP server(s) declared in {@code mcp-servers-config.json}</li>
 * <li>(bonus) conversation continuity → {@link SessionMemoryAdvisor} from
 * spring-ai-session</li>
 * </ul>
 */
@SpringBootApplication
public class BankSupportApplication {

	private static final String INSTRUCTIONS = """
			You are a support agent in our bank, give the \
			customer support and judge the risk level of their query. \
			Reply using the customer's name.

			The customer's name is '{customerName}'. Account balances are in USD.

			For refund related questions, first load the 'refunds' skill and follow it.""";

	public static void main(String[] args) {
		SpringApplication.run(BankSupportApplication.class, args);
	}

	@Bean
	SessionService sessionService() {
		return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
	}

	/**
	 * Hides the exchange-rate server's dataframe analytics tools, which are irrelevant for a
	 * support agent, from the auto-configured MCP tool callback provider.
	 */
	@Bean
	McpToolFilter mcpToolFilter() {
		return (connection, tool) -> !tool.name().startsWith("fx_dataframe_");
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, SessionService sessionService, DatabaseConn db,
			@Value("${agent.skills.dirs}") List<Resource> skillsDirs, ToolCallbackProvider mcpToolCallbackProvider) {

		return args -> { // @formatter:off

			// The typed, per-run dependencies (like Pydantic's deps=SupportDependencies(...))
			var deps = new SupportDependencies(123, db);

			// Dynamic instructions: look up the customer name from the database
			String customerName = db.customerName(deps.customerId()).orElse("unknown");

			ChatClient supportAgent = chatClientBuilder
				.defaultSystem(INSTRUCTIONS)
				.defaultTools(
					new SupportTools(),
					// On-demand 'capability': the refunds skill is disclosed to the
					// model only when it decides to load it
					SkillsTool.builder().addSkillsResources(skillsDirs).build())
				// MCP toolset: the exchange-rate server from mcp-servers-config.json
				.defaultTools(mcpToolCallbackProvider)
				.defaultToolContext(Map.of(SupportDependencies.TOOL_CONTEXT_KEY, deps))
				.defaultAdvisors(
					SessionMemoryAdvisor.builder(sessionService)
						.defaultUserId("john")
						.build(),
					MyLoggingAdvisor.builder().build())
				.build();

			var sessionId = "support-session-customer-" + deps.customerId();

			for (String query : List.of(
					"What is my balance?",
					"I just lost my card!",
					"I was charged twice for the same coffee. Where is my refund?",
					"What is my balance in euros?")) {

				SupportOutput result = supportAgent.prompt()
					.system(s -> s.param("customerName", customerName))
					.user(query)
					.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
					.call()
					.entity(SupportOutput.class, e -> e.useProviderStructuredOutput().validateSchema());

				System.out.println("\nUSER: " + query + "\n" + result + "\n");
			}
		}; // @formatter:on
	}

}
