package com.example.demo;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.demo.agent.HelpdeskAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks the helpdesk one question per interval, alternating providers, so the dashboards
 * have a steady baseline to show the failure modes against. Questions mix tool use
 * (schedule, rooms, shuttle) with FAQ retrieval, plus one neither can answer.
 */
@Component
public class UserQuestionGenerator {

	private static final Logger log = LoggerFactory.getLogger(UserQuestionGenerator.class);

	static final List<String> QUESTIONS = List.of("When is the Spring AI talk and in which room?",
			"Which sessions are about Kotlin?", "Where is the opening keynote?",
			"What time does the shuttle leave from the Hilton in the morning?", "Is there a session on Testcontainers?",
			"What is the Wi-Fi password?", "Is lunch included in the ticket?", "Where can I park near the venue?",
			"Will the talks be recorded?", "How do I get a certificate of attendance?",
			"Which room is the MCP servers talk in?", "What's on in the Main Hall after lunch?", "Can I bring my dog?",
			"Who do I talk to about a code of conduct concern?",
			"Is there a shuttle from the Inntel back to the hotel in the evening?",
			"Where can I buy a ticket for next year's edition?");

	public record Status(boolean running, int sent, int failed, String lastQuestion, String lastAnswer) {
	}

	private final HelpdeskAgent agent;

	private final AtomicBoolean running;

	private final AtomicInteger sent = new AtomicInteger();

	private final AtomicInteger failed = new AtomicInteger();

	private volatile String lastQuestion = "";

	private volatile String lastAnswer = "";

	UserQuestionGenerator(HelpdeskAgent agent, @Value("${helpdesk.traffic.enabled:true}") boolean enabled) {
		this.agent = agent;
		this.running = new AtomicBoolean(enabled);
	}

	@Scheduled(fixedDelayString = "${helpdesk.traffic.interval:4s}", initialDelayString = "3s")
	void tick() {
		if (!this.running.get()) {
			return;
		}
		int n = this.sent.getAndIncrement();
		String question = QUESTIONS.get(n % QUESTIONS.size());
		// Providers alternate, and swap parity on each pass through the list, so both see
		// every question and the "by model" panels compare like with like.
		String provider = (n + n / QUESTIONS.size()) % 2 == 0 ? HelpdeskAgent.ANTHROPIC : HelpdeskAgent.OPENAI;
		this.lastQuestion = question;
		long start = System.currentTimeMillis();
		try {
			HelpdeskAgent.Answer answer = this.agent.ask(question, provider);
			this.lastAnswer = answer.answer();
			log.info("[{}] {} ms | {} -> {}", provider, System.currentTimeMillis() - start, question,
					first(answer.answer(), 90));
		}
		catch (RuntimeException ex) {
			// Already on the error panels; the next tick carries on.
			this.failed.incrementAndGet();
			this.lastAnswer = "ERROR " + ex.getMessage();
			log.warn("[{}] {} ms | {} -> {}: {}", provider, System.currentTimeMillis() - start, question,
					ex.getClass().getSimpleName(), first(ex.getMessage(), 120));
		}
	}

	public Status start() {
		this.running.set(true);
		return status();
	}

	public Status stop() {
		this.running.set(false);
		return status();
	}

	public Status status() {
		return new Status(this.running.get(), this.sent.get(), this.failed.get(), this.lastQuestion, this.lastAnswer);
	}

	private static String first(String text, int max) {
		if (text == null) {
			return "";
		}
		String oneLine = text.replace('\n', ' ');
		return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
	}

}
