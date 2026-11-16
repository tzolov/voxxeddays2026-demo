package com.example.demo.agent.capabilities;

import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

/**
 * The Memory capability — the analog of Pydantic AI harness's
 * {@code Memory(FileStore(...), namespace=lambda ctx: ctx.deps.user_id)}: it injects
 * Spring AI's session advisors ({@link SessionAdvisors}) into the agent's ChatClient and,
 * per run, resolves the session id from the typed deps (Pydantic's namespace resolver).
 * Sessions created on first use are owned by the user
 * ({@link SessionAttributes#PRINCIPAL_NAME}).
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
		chatClientBuilder.defaultAdvisors(SessionAdvisors.builder(this.sessionService).build())
			.defaultAdvisors(a -> a.param(SessionMemoryAdvisor.SESSION_METADATA_CONTEXT_KEY,
					Map.of(SessionAttributes.PRINCIPAL_NAME, this.userId)));
	}

	@Override
	public void beforeRequest(ChatClient.ChatClientRequestSpec spec, D deps) {
		spec.advisors(SessionAdvisors.session(this.sessionIdResolver.apply(deps)));
	}

}
