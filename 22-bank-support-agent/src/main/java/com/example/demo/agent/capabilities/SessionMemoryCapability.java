package com.example.demo.agent.capabilities;

import java.util.function.Function;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

/**
 * The Memory capability — the analog of Pydantic AI harness's
 * {@code Memory(FileStore(...), namespace=lambda ctx: ctx.deps.user_id)}: it injects a
 * spring-ai-session {@link SessionMemoryAdvisor} into the agent's ChatClient and, per
 * run, resolves the session id from the typed deps (Pydantic's namespace resolver).
 */
public final class SessionMemoryCapability<D> implements Capability<D> {

	private final SessionService sessionService;

	private final String userId;

	private final Function<D, String> sessionIdResolver;

	public SessionMemoryCapability(SessionService sessionService, String userId,
			Function<D, String> sessionIdResolver) {
		this.sessionService = sessionService;
		this.userId = userId;
		this.sessionIdResolver = sessionIdResolver;
	}

	@Override
	public String id() {
		return "session-memory";
	}

	@Override
	public void instrument(ChatClient.Builder chatClientBuilder) {
		chatClientBuilder
			.defaultAdvisors(SessionMemoryAdvisor.builder(this.sessionService).defaultUserId(this.userId).build());
	}

	@Override
	public void beforeRequest(ChatClient.ChatClientRequestSpec spec, D deps) {
		spec.advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, this.sessionIdResolver.apply(deps)));
	}

}
