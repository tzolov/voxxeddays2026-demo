package com.example.demo;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.session.turn.Answer;
import org.springaicommunity.session.turn.Question;
import org.springaicommunity.session.turn.ToolConfirmationQuestion;
import org.springaicommunity.session.turn.TurnEventTypes;
import org.springaicommunity.session.turn.TurnService;
import org.springaicommunity.session.turn.TurnStatus;
import org.springaicommunity.session.turn.advisor.TurnAdvisors;
import org.springaicommunity.session.turn.advisor.TurnStatusAdvisor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.ai.model.tool.ToolCallDecision;
import org.springframework.ai.model.tool.ToolCallDecisions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StringUtils;

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
 * <li>what leaves the bank ('full' profile): the exchange rates come from an MCP server,
 * and nothing of the tool context, so not the customer id either, is sent to it.</li>
 * </ul>
 *
 * Run it twice. The first run talks to the agent until the turn parks, and exits. The
 * second run finds the parked turn in the database, asks for the approval and resumes the
 * turn. With the 'full' profile the conversation then goes on with a question that needs
 * a skill and one that needs the MCP server.
 */
@SpringBootApplication
public class BankAgentApplication {

	private static final String INSTRUCTIONS = """
			You are a customer support agent at a bank. The customer's name is '{customerName}'; \
			use it. Today is {today}. Amounts are in CHF.

			Answer only from the facts the tools return; never guess amounts, dates or reasons. \
			When a payment failed, say why and what happens next. Keep replies to three sentences.""";

	public static void main(String[] args) {
		// Close the context when the conversation is over. On the 'full' profile the MCP
		// client's threads keep the JVM alive, and the MCP server process that npx
		// started must be stopped
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
			ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider, DataSource dataSource,
			@Value("${demo.customer-id}") int customerId, @Value("${demo.today}") String today,
			@Value("${demo.full:false}") boolean full, @Value("${demo.new-message:}") String newMessage,
			@Value("${agent.skills.dirs}") List<Resource> skillsDirs) {

		return args -> { // @formatter:off

			var sessionId = "support-session-customer-" + customerId;

			var turns = new TurnService(sessionService);

			// All a new process knows is the session id. Does a turn of this session wait for a person?
			boolean parked = turns.status(sessionId).isParked();

			if (!parked) {
				// A new conversation starts clean. The bank's data and the session log are in the database
				// and outlive the process
				new ResourceDatabasePopulator(new ClassPathResource("bank-data.sql")).execute(dataSource);
				sessionService.delete(sessionId);
			}

			// The authenticated customer, as the channel (app, IVR) would hand it over
			var customer = new CustomerIdentity(customerId);
			String customerName = accounts.customerName(customerId);

			ChatClient agent = chatClientBuilder
				.defaultSystem(s -> s.text(INSTRUCTIONS).param("customerName", customerName).param("today", today))
				// the bank's services
				.defaultTools(accounts, mortgages)
				// an MCP server: exchange rates ('full' profile; no tools without it)
				.defaultTools(mcpToolCallbackProvider.getIfAvailable(() -> ToolCallbackProvider.from(List.of())))
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

			if (!parked) {
				// First run: a new conversation, until a turn parks
				boolean paused = talk(agent, sessionId,
						"My mortgage payment from CH93 0076 2011 6238 5295 7 did not go out this month, what happened?",
						"I have the money now. Please collect it today.");

				if (paused) {
					printLog(sessionService, sessionId, newestTurnStart(sessionService, sessionId));
					System.out.println("\nThe pause is in the session log, not in this process. Run the demo again to decide.");
				}
			}
			else {
				// Second run: another process, later
				int parkedTurnStart = newestTurnStart(sessionService, sessionId);

				if (StringUtils.hasText(newMessage)) {
					// The customer writes something new instead of waiting for the approver. New text moves a
					// parked session on: the turn advisor denies the open calls, and the text goes to the model
					talk(agent, sessionId, newMessage);
				}
				else {
					// Nothing ran while the turn was parked
					System.out.println("\nThe account balance is CHF " + accounts.balance(customerId) + ". The parked turn waits on:");
					resume(agent, turns, sessionId);
				}

				// The whole turn, as the two processes wrote it
				printLog(sessionService, sessionId, parkedTurnStart);

				// The conversation goes on: the session log holds every turn, also the ones of the first process
				talk(agent, sessionId, "And next month?");

				if (full) {
					// A question about a procedure: the model loads the 'refunds' skill, only now
					talk(agent, sessionId, "I was charged twice for the same CHF 620 purchase. When do I get my money back?");

					// A question the bank's own services cannot answer: the model calls the MCP server
					talk(agent, sessionId, "How much is one payment in euros, at the latest rate?");
				}
			}
		}; // @formatter:on
	}

	/**
	 * Sends the customer's messages, one turn each, until a turn parks.
	 * @return true when a turn parked
	 */
	private static boolean talk(ChatClient agent, String sessionId, String... messages) {
		for (String message : messages) {
			ChatResponse response = agent.prompt()
				.user(message)
				.advisors(SessionAdvisors.session(sessionId))
				.call()
				.chatResponse();

			// A paused reply names the tool calls that wait for a person
			if (!ToolCallDecisions.awaitingDecision(response).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reads the parked turn from the session log, asks for the decision and resumes the
	 * turn.
	 */
	private static void resume(ChatClient agent, TurnService turns, String sessionId) {

		ToolConfirmationQuestion question = ToolConfirmationQuestion.from(turns.pendingQuestion(sessionId));

		for (ToolConfirmationQuestion.ToolCall call : question.toolCalls()) {
			System.out.println("  " + call.name() + " " + call.arguments()
					+ (call.awaitingDecision() ? "   <- needs a decision" : ""));
		}

		// 'y' allows. Anything else declines, and a typed sentence is the reason: it goes to
		// the model as the result of the call
		String typed = askApprover();
		boolean approved = typed.equalsIgnoreCase("y") || typed.equalsIgnoreCase("yes");
		String reason = (typed.isEmpty() || typed.equalsIgnoreCase("n") || typed.equalsIgnoreCase("no"))
				? "The approver declined the collection." : typed;

		// One decision for each tool call of the paused reply. A call that needs no
		// confirmation has not run either, so it is allowed here
		List<ToolCallDecision> decisions = question.toolCalls()
			.stream()
			.map(call -> !call.awaitingDecision() || approved ? ToolCallDecision.allow(call.key())
					: ToolCallDecision.deny(call.key(), reason))
			.toList();

		// A call with the decisions and no user message: the allowed tools run, and the
		// turn goes on
		agent.prompt()
			.advisors(TurnStatusAdvisor.decisions(sessionId, new ToolCallDecisions(decisions)))
			// Optional: keeps the run id of the parked turn. Without it the resume runs
			// under a new one
			.advisors(TurnStatusAdvisor.runId(turns.status(sessionId).runId()))
			.call()
			.content();
	}

	/**
	 * The approver's answer, typed on the console.
	 */
	private static String askApprover() {
		System.out.print("\nApprove? [y/N, or a reason to decline]: ");
		System.out.flush();
		Scanner console = new Scanner(System.in);
		return console.hasNextLine() ? console.nextLine().trim() : "";
	}

	/**
	 * The index of the event that starts the newest turn: the turn status in front of the
	 * last user message.
	 */
	private static int newestTurnStart(SessionService sessionService, String sessionId) {
		List<SessionEvent> events = sessionService.getEvents(sessionId);
		for (int i = events.size() - 1; i >= 0; i--) {
			if (events.get(i).hasMessage() && events.get(i).getMessageType() == MessageType.USER) {
				return (i > 0 && events.get(i - 1).getType().equals(TurnEventTypes.STATUS)) ? i - 1 : i;
			}
		}
		return 0;
	}

	/**
	 * Prints the session log from one event on: the messages Spring AI session memory
	 * stored, and the turn events Session Extensions wrote next to them.
	 */
	private static void printLog(SessionService sessionService, String sessionId, int from) {
		List<SessionEvent> events = sessionService.getEvents(sessionId);
		System.out.println("\nThe turn in the session log:");
		for (SessionEvent event : events.subList(from, events.size())) {
			String line = describe(event).replace('\n', ' ');
			System.out.println("  %-14s %s".formatted(event.getType().name(),
					line.length() > 110 ? line.substring(0, 110) + " ..." : line));
		}
	}

	private static String describe(SessionEvent event) {
		if (event.getType().equals(TurnEventTypes.STATUS)) {
			TurnStatus status = TurnStatus.from(event);
			return status.status() + (status.stopReason() != null ? " / " + status.stopReason() : "")
					+ (status.isParked() ? "   <- parked" : "");
		}
		if (event.getType().equals(TurnEventTypes.QUESTION)) {
			return Question.from(event).kind();
		}
		if (event.getType().equals(TurnEventTypes.ANSWER)) {
			return String.valueOf(Answer.from(event).value());
		}
		if (event.getMessage() instanceof AssistantMessage reply && reply.hasToolCalls()) {
			return "ASSISTANT calls " + reply.getToolCalls()
				.stream()
				.map(call -> call.name() + " " + call.arguments())
				.collect(Collectors.joining(", "));
		}
		if (event.getMessage() instanceof ToolResponseMessage results) {
			return "TOOL " + results.getResponses()
				.stream()
				.map(result -> result.name() + ": " + result.responseData())
				.collect(Collectors.joining(", "));
		}
		return event.getMessageType() + " " + event.getMessage().getText();
	}

}
