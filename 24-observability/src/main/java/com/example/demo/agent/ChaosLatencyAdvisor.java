package com.example.demo.agent;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

/**
 * Sleeps for the configured time before each call. An advisor rather than a sleep in the
 * controller, so the delay shows up where a slow guardrail or memory lookup would: on the
 * advisor latency panel, by name, inside the ChatClient span.
 */
class ChaosLatencyAdvisor implements BaseAdvisor {

	private final Chaos chaos;

	ChaosLatencyAdvisor(Chaos chaos) {
		this.chaos = chaos;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 100;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
		long ms = this.chaos.latencyMs();
		if (ms > 0) {
			try {
				Thread.sleep(ms);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while delaying the call", ex);
			}
		}
		return request;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
		return response;
	}

}
