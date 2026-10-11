package com.example.demo.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

/**
 * Logs the question and the answer, only while {@link Chaos#logContent()} is on. Each line
 * carries the trace id (Boot's log correlation), so a logged answer leads to its trace in
 * Tempo. Spring AI's own switches ({@code spring.ai.chat.observations.log-prompt} and
 * {@code log-completion}) put the full prompt and completion into the span events instead,
 * and are fixed at startup.
 */
class ContentLogAdvisor implements BaseAdvisor {

	private static final Logger log = LoggerFactory.getLogger("helpdesk.content");

	private final Chaos chaos;

	ContentLogAdvisor(Chaos chaos) {
		this.chaos = chaos;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 50;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		if (this.chaos.logContent()) {
			log.info("question: {}", request.prompt().getUserMessage().getText());
		}
		return request;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		if (this.chaos.logContent()) {
			log.info("answer: {}", HelpdeskAgent.text(response.chatResponse()));
		}
		return response;
	}

}
