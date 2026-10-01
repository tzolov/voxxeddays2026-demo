package com.example.demo.agent.capabilities;

import java.util.Arrays;
import java.util.List;

import com.example.demo.agent.Agent;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * A capability that plugs plain Spring AI {@link Advisor}s into the agent's ChatClient —
 * the escape hatch that keeps advisors out of the {@link Agent} facade itself. Wrap
 * cross-cutting concerns (logging, guardrails, token counting) as capabilities:
 *
 * <pre>{@code
 * new AdvisorCapability<SupportDependencies>("logging", MyLoggingAdvisor.builder().build())
 * }</pre>
 */
public final class AdvisorCapability<D> implements Capability<D> {

	private final String id;

	private final List<Advisor> advisors;

	public AdvisorCapability(String id, Advisor... advisors) {
		this.id = id;
		this.advisors = List.copyOf(Arrays.asList(advisors));
	}

	@Override
	public String id() {
		return this.id;
	}

	@Override
	public void instrument(ChatClient.Builder chatClientBuilder) {
		chatClientBuilder.defaultAdvisors(this.advisors.toArray(Advisor[]::new));
	}

}
