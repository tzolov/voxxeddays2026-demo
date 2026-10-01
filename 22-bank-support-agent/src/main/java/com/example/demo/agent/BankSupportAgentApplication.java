package com.example.demo.agent;

import java.util.List;

import com.example.demo.DatabaseConn;
import com.example.demo.MyLoggingAdvisor;
import com.example.demo.SupportDependencies;
import com.example.demo.SupportOutput;
import com.example.demo.SupportTools;
import com.example.demo.agent.capabilities.AdvisorCapability;
import com.example.demo.agent.capabilities.Capability;
import com.example.demo.agent.capabilities.McpCapability;
import com.example.demo.agent.capabilities.SessionMemoryCapability;
import com.example.demo.agent.capabilities.SkillsCapability;
import com.example.demo.agent.capabilities.WebFetchCapability;
import com.example.demo.agent.capabilities.WebSearchCapability;

import io.modelcontextprotocol.client.McpSyncClient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The bank support agent re-expressed through the declarative {@link Agent} /
 * {@link Capability} facade — a 1:1 mock of Pydantic AI's:
 *
 * <pre>
 * support_agent = Agent(
 *   'openai:gpt-5.6-sol',
 *   deps_type=SupportDependencies,
 *   output_type=SupportOutput,
 *   instructions='You are a support agent in our bank, ...',
 *   capabilities=[customer_context, Skills('.agents/skills'), Memory(store),
 *                 MCPServerStdio('npx', ['-y', '@cyanheads/exchange-rates-mcp-server'])],
 * )
 * </pre>
 *
 * Each capability is a plug that instruments the ChatClient: {@code customer_context}
 * contributes dynamic instructions and tools, {@link SkillsCapability} injects the
 * on-demand SkillsTool, {@link SessionMemoryCapability} injects the spring-ai-session
 * SessionMemoryAdvisor, and {@link McpCapability} exposes the tools of the exchange-rate
 * MCP server declared in {@code mcp-servers-config.json}.
 *
 * Run with: {@code mvn spring-boot:run
 * -Dspring-boot.run.main-class=com.example.demo.agent.BankSupportAgentApplication}
 *
 * The whole configuration is gated on the 'agent-facade' profile (activated by this main)
 * so that the classic {@code BankSupportApplication} is unaffected.
 */
@Profile("agent-facade")
@SpringBootApplication
public class BankSupportAgentApplication {

	public static void main(String[] args) {
		new SpringApplicationBuilder(BankSupportAgentApplication.class).profiles("agent-facade").run(args);
	}

	@Bean
	DatabaseConn databaseConn(JdbcClient jdbcClient) {
		return new DatabaseConn(jdbcClient);
	}

	@Bean
	SessionService sessionService() {
		return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
	}

	@Bean
	CommandLineRunner cli(ChatClient.Builder chatClientBuilder, DatabaseConn db, SessionService sessionService,
			@Value("${agent.skills.dirs}") List<Resource> skillsDirs,
			@Value("${BRAVE_API_KEY:#{null}}") String braveApiKey, List<McpSyncClient> mcpClients) {

		return args -> { // @formatter:off

			var customerContext = Capability.<SupportDependencies>builder("customer-context")
				.description("Who the customer is and what's on their account.")
				.instructions(deps -> "The customer's name is '%s'. Reply using the customer's name."
					.formatted(deps.db().customerName(deps.customerId()).orElse("unknown")))
				.instructions("Account balances are in USD.")
				.tools(new SupportTools())
				.build();

			var refundsSkills = new SkillsCapability<SupportDependencies>(skillsDirs);

			var memory = new SessionMemoryCapability<SupportDependencies>(sessionService, "john",
					deps -> "support-session-customer-" + deps.customerId());

			var logging = new AdvisorCapability<SupportDependencies>("logging", MyLoggingAdvisor.builder().build());

			var webFetch = new WebFetchCapability<SupportDependencies>(chatClientBuilder.clone().build());

			var webSearch = new WebSearchCapability<SupportDependencies>(braveApiKey);

			// MCP toolset: the exchange-rate server from mcp-servers-config.json, minus its
			// dataframe analytics tools (present when its DuckDB canvas is enabled)
			var exchangeRates = new McpCapability<SupportDependencies>("exchange-rates", mcpClients,
					toolName -> !toolName.startsWith("fx_dataframe_"));

			var agentBuilder =
				Agent.builder(chatClientBuilder, SupportDependencies.class, SupportOutput.class)
					.name("bank-support")
					.model("claude-sonnet-4-6")
					.instructions("You are a support agent in our bank, give the "
							+ "customer support and judge the risk level of their query.")
					.capabilities(customerContext, refundsSkills, memory, logging, webFetch, webSearch, exchangeRates);


			Agent<SupportDependencies, SupportOutput> supportAgent = agentBuilder.build();

			var deps = new SupportDependencies(123, db);

			for (String query : List.of(
					"What is my balance?",
					"I just lost my card!",
					"I was charged twice for the same coffee. Where is my refund?",
					"What is the current ECB main refinancing interest rate?",
					"What is my balance in euros?"
				)) {

				System.out.println("\nUSER: " + query + "\n" + supportAgent.run(query, deps) + "\n");
			}
		}; // @formatter:on
	}

}
