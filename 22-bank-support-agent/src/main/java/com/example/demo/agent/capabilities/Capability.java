package com.example.demo.agent.capabilities;

import com.example.demo.agent.Agent;

import org.springframework.ai.chat.client.ChatClient;

/**
 * A capability is a plug that knows how to instrument the agent's {@link ChatClient} —
 * the analog of Pydantic AI's harness capabilities ({@code Memory}, {@code Skills},
 * {@code Capability[Deps]}).
 *
 * It contributes through three hooks, all optional:
 * <ul>
 * <li>{@link #instrument(ChatClient.Builder)} — build-time: register tools, advisors, or
 * options on the agent's ChatClient (e.g. Memory injects a
 * {@code SessionMemoryAdvisor}, Skills injects the {@code SkillsTool})</li>
 * <li>{@link #instructions(Object)} — run-time: an instruction fragment for the system
 * prompt, resolved from the typed deps (Pydantic's {@code get_instructions()})</li>
 * <li>{@link #beforeRequest(ChatClient.ChatClientRequestSpec, Object)} — run-time:
 * customize the outgoing request, e.g. advisor params (Pydantic's
 * {@code before_model_request()})</li>
 * </ul>
 *
 * @param <D> the agent's dependencies type
 */
public interface Capability<D> {

	/** Short identifier of the capability. */
	String id();

	/**
	 * Build-time hook: instrument the agent's {@link ChatClient.Builder} with tools,
	 * advisors or default options. Called once when the {@link Agent} is built.
	 */
	default void instrument(ChatClient.Builder chatClientBuilder) {
	}

	/**
	 * Run-time hook: the instruction fragment this capability contributes to the system
	 * prompt, resolved from the deps on every run. Return an empty string for none.
	 */
	default String instructions(D deps) {
		return "";
	}

	/**
	 * Run-time hook: customize the outgoing request before it is sent, e.g. set advisor
	 * params derived from the deps.
	 */
	default void beforeRequest(ChatClient.ChatClientRequestSpec spec, D deps) {
	}

	/** A builder for a simple declarative capability bundling instructions and tools. */
	static <D> DefaultCapability.Builder<D> builder(String id) {
		return DefaultCapability.builder(id);
	}

}
