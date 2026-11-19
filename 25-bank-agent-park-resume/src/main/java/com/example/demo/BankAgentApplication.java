package com.example.demo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.session.turn.ToolConfirmationQuestion;
import org.springaicommunity.session.turn.TurnService;
import org.springaicommunity.session.turn.TurnStatus;
import org.springaicommunity.session.turn.advisor.TurnAdvisors;
import org.springaicommunity.session.turn.advisor.TurnStatusAdvisor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

/**
 * A bank support agent under control.
 *
 * One {@link ChatClient}. The model decides which of the bank's services to call: they
 * are the existing {@link AccountService} and {@link MortgageService} beans. The
 * framework decides the rest:
 * <ul>
 * <li>what the model may see: the customer id travels in the tool context, and
 * {@link PiiMaskingAdvisor} replaces account identifiers before the message leaves the
 * bank;</li>
 * <li>what it may do on its own: {@code retryMortgagePaymentNow} moves money, so it is
 * marked {@code @Tool(requiresConfirmation = true)} and the tool loop pauses on it
 * (Spring AI 2.1);</li>
 * <li>where the conversation lives: in the session log, in a database (Spring AI
 * 2.1);</li>
 * <li>what happens to a paused turn: the turn advisors of Spring AI Session Extensions
 * park it in the session log and resume it later from the approver's decision, in another
 * process;</li>
 * <li>what leaves the bank: the exchange rates come from an MCP server, and nothing of
 * the tool context, so not the customer id either, is sent to it.</li>
 * </ul>
 *
 * Run it twice. The first run talks to the agent until the turn parks, and exits. The
 * second run finds the parked turn in the database, asks for the approval and resumes the
 * turn. The conversation then goes on with a question that needs a skill and one that
 * needs the MCP server.
 */
@SpringBootApplication
public class BankAgentApplication {

	private static final String INSTRUCTIONS = """
			You are a customer support agent at a bank. The customer's name is '{customerName}'; \
			use it. Today is {today}. Amounts are in CHF.

			Answer only from the facts the tools return; never guess amounts, dates or reasons. \
			When a payment failed, say why and what happens next. Keep replies to three sentences.""";

	public static void main(String[] args) {
		// The MCP client's threads keep the JVM alive, so close the context when the
		// conversation is
		// over, and stop the MCP server process that npx started
		int exitCode = SpringApplication.exit(SpringApplication.run(BankAgentApplication.class, args));
		ProcessHandle.current().descendants().forEach(ProcessHandle::destroy);
		System.exit(exitCode);
	}

	/**
	 * By default the MCP client sends the tool context to the MCP server, as the metadata
	 * of each tool call. Here the tool context holds the customer's identity, which must
	 * stay in the bank: nothing is sent.
	 */
	@Bean
	ToolContextToMcpMetaConverter toolContextToMcpMetaConverter() {
		return ToolContextToMcpMetaConverter.noOp();
	}

	/**
	 * Hides the exchange-rate server's data analysis tools, which a support agent does
	 * not need.
	 */
	@Bean
	McpToolFilter mcpToolFilter() {
		return (connection, tool) -> !tool.name().startsWith("fx_dataframe_");
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, SessionService sessionService,
			ToolCallingManager toolCallingManager, AccountService accounts, MortgageService mortgages,
			ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider, @Value("${demo.customer-id}") int customerId,
			@Value("${demo.today}") String today, @Value("${agent.skills.dirs}") List<Resource> skillsDirs) {

		return args -> { // @formatter:off

			// The authenticated customer, as the channel (app, IVR) would hand it over
			var customer = new CustomerIdentity(customerId);
			String customerName = accounts.customerName(customerId);

			ChatClient agent = chatClientBuilder
				.defaultSystem(s -> s.text(INSTRUCTIONS).param("customerName", customerName).param("today", today))
				// the bank's services
				.defaultTools(accounts, mortgages)
				// an MCP server: exchange rates
				.defaultTools(mcpToolCallbackProvider.getIfAvailable())
				// the skills from the classpath (community)
				.defaultTools(SkillsTool.builder().addSkillsResources(skillsDirs).build())
				// who the customer is
				.defaultToolContext(Map.of(CustomerIdentity.TOOL_CONTEXT_KEY, customer))
				// before anything leaves the system, apply PII masking and other advisors
				.defaultAdvisors(new PiiMaskingAdvisor())
				// session memory + park and resume
				.defaultAdvisors(TurnAdvisors.builder(sessionService)
					.toolCallingManager(toolCallingManager)
					.build())
				// logging advisor for debugging and visibility
				.defaultAdvisors(MyLoggingAdvisor.builder().showSystemMessage(false).showAvailableTools(true).build())
				.build();

			var sessionId = "support-session-customer-" + customerId;
			
			var turns = new TurnService(sessionService);

			if (!turns.status(sessionId).isParked()) {				
				// First run: a new conversation
				// Start clean: the session log is in the database and outlives the process
				sessionService.delete(sessionId);

				talk(agent, turns, sessionId,
						"My mortgage payment from CH93 0076 2011 6238 5295 7 did not go out this month, what happened?",
						"I have the money now. Please collect it today.");

			}
			else {
				// Second run: another process, later. All it knows is the session id.
				resume(agent, turns, sessionId);

				// The conversation goes on: the session log holds every turn, also the ones of the first process
				talk(agent, turns, sessionId, "And next month?");

				// A question about a procedure: the model loads the 'refunds' skill, only now
				talk(agent, turns, sessionId, "I was charged twice for the same CHF 620 purchase. When do I get my money back?");

				// A question the bank's own services cannot answer: the model calls the MCP server
				talk(agent, turns, sessionId, "How much is one payment in euros, at the latest rate?");

			}
		}; // @formatter:on
	}

	/** Sends the customer's messages, one turn each, until a turn parks. */
	private static void talk(ChatClient agent, TurnService turns, String sessionId, String... messages) {
		for (String message : messages) {
			ChatResponse response = agent.prompt()
				.user(message)
				.advisors(SessionAdvisors.session(sessionId))
				.call()
				.chatResponse();

			// A paused reply names the tool calls that wait for a person
			if (!ToolCallDecisions.awaitingDecision(response).isEmpty()) {
				System.out
					.println("\nThe pause is in the session log, not in this process. Run the demo again to decide.");
				return;
			}
		}
	}

	/**
	 * Reads the parked turn from the session log, asks for the decision and resumes the
	 * turn.
	 */
	private static void resume(ChatClient agent, TurnService turns, String sessionId) {

		ToolConfirmationQuestion question = printPendingCalls(turns, sessionId);

		boolean approved = askApprover();

		// One decision for each tool call of the paused reply
		List<ToolCallDecision> decisions = question.toolCalls()
			.stream()
			.map(call -> !call.awaitingDecision() || approved ? ToolCallDecision.allow(call.key())
					: ToolCallDecision.deny(call.key(), "The approver declined the collection."))
			.toList();

		// A call with the decisions and no user message: the allowed tools run, and the
		// turn goes on
		String answer = agent.prompt()
			.advisors(TurnStatusAdvisor.decisions(sessionId, new ToolCallDecisions(decisions)))
			.advisors(TurnStatusAdvisor.runId(turns.status(sessionId).runId()))
			.call()
			.content();

		// The parked turn has ended
		// TurnStatus status = turns.status(sessionId);
		// System.out.println("\nstatus: " + status.status() + " / " +
		// status.stopReason());
	}

	private static ToolConfirmationQuestion printPendingCalls(TurnService turns, String sessionId) {
		ToolConfirmationQuestion question = ToolConfirmationQuestion.from(turns.pendingQuestion(sessionId));
		for (ToolConfirmationQuestion.ToolCall call : question.toolCalls()) {
			System.out.println("  " + call.name() + " " + call.arguments()
					+ (call.awaitingDecision() ? "   <- needs a decision" : ""));
		}
		return question;
	}

	/**
	 * The decision: the answer typed on the console.
	 */
	private static boolean askApprover() {
		System.out.print("\nApprove? [y/N]: ");
		System.out.flush();
		Scanner console = new Scanner(System.in);
		return console.hasNextLine() && console.nextLine().trim().toLowerCase().startsWith("y");
	}

}
