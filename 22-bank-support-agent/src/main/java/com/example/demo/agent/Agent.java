package com.example.demo.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.demo.agent.capabilities.Capability;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.util.StringUtils;

/**
 * A lightweight, typed agent definition — a facade over a fully assembled Spring AI
 * {@link ChatClient}, mirroring Pydantic AI's declarative {@code Agent(...)}:
 *
 * <pre>{@code
 * Agent<SupportDependencies, SupportOutput> supportAgent =
 *     Agent.builder(chatClientBuilder, SupportDependencies.class, SupportOutput.class)
 *         .name("bank-support")
 *         .model("claude-sonnet-4-6")
 *         .instructions("You are a support agent in our bank ...")
 *         .capabilities(customerContext, refundsSkills, sessionMemory)
 *         .build();
 *
 * SupportOutput out = supportAgent.run("What is my balance?", deps);
 * }</pre>
 *
 * The {@link Capability} plugs do the instrumentation: each one registers its tools and
 * advisors on the ChatClient at build time and contributes deps-resolved instructions
 * and request customizations at run time.
 *
 * @param <D> the dependencies type ({@code deps_type}), carried to tools via the tool
 * context and to the capabilities' run-time hooks
 * @param <O> the structured output type ({@code output_type}), enforced on every run
 */
public record Agent<D, O>(String name, String instructions, Class<D> depsType, Class<O> outputType,
		List<Capability<D>> capabilities, ChatClient chatClient) {

	/** The tool-context key under which the deps are exposed to the tools. */
	public static final String DEPS_TOOL_CONTEXT_KEY = "deps";

	/**
	 * Runs the agent: composes the system prompt from the base instructions and the
	 * capabilities' deps-resolved instructions, exposes the deps to the tools via the
	 * tool context, lets each capability customize the request, and validates the
	 * model's answer into the output type.
	 */
	public O run(String userPrompt, D deps) {

		String system = Stream
			.concat(Stream.of(this.instructions), this.capabilities.stream().map(c -> c.instructions(deps)))
			.filter(StringUtils::hasText)
			.collect(Collectors.joining("\n\n"));

		ChatClient.ChatClientRequestSpec spec = this.chatClient.prompt()
			.system(system)
			.user(userPrompt)
			.toolContext(Map.of(DEPS_TOOL_CONTEXT_KEY, deps));

		this.capabilities.forEach(capability -> capability.beforeRequest(spec, deps));

		return spec.call().entity(this.outputType, e -> e.useProviderStructuredOutput().validateSchema());
	}

	public static <D, O> Builder<D, O> builder(ChatClient.Builder chatClientBuilder, Class<D> depsType,
			Class<O> outputType) {
		return new Builder<>(chatClientBuilder, depsType, outputType);
	}

	/**
	 * Assembles an {@link Agent}: applies the optional model override and lets every
	 * {@link Capability} instrument the underlying ChatClient.
	 */
	public static final class Builder<D, O> {

		private final ChatClient.Builder chatClientBuilder;

		private final Class<D> depsType;

		private final Class<O> outputType;

		private String name = "agent";

		private String model;

		private String instructions = "";

		private final List<Capability<D>> capabilities = new ArrayList<>();

		private Builder(ChatClient.Builder chatClientBuilder, Class<D> depsType, Class<O> outputType) {
			this.chatClientBuilder = chatClientBuilder;
			this.depsType = depsType;
			this.outputType = outputType;
		}

		public Builder<D, O> name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * Overrides the model id. The provider itself is determined by the injected
		 * {@link ChatClient.Builder} (Pydantic's {@code 'provider:model'} prefix would
		 * map to a registry of builders, one per auto-configured ChatModel).
		 */
		public Builder<D, O> model(String model) {
			this.model = model;
			return this;
		}

		public Builder<D, O> instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		@SafeVarargs
		public final Builder<D, O> capabilities(Capability<D>... capabilities) {
			this.capabilities.addAll(Arrays.asList(capabilities));
			return this;
		}

		public Agent<D, O> build() {

			ChatClient.Builder builder = this.chatClientBuilder.clone();

			if (this.model != null) {
				builder.defaultOptions(ChatOptions.builder().model(this.model));
			}

			this.capabilities.forEach(capability -> capability.instrument(builder));

			return new Agent<>(this.name, this.instructions, this.depsType, this.outputType,
					List.copyOf(this.capabilities), builder.build());
		}

	}

}
