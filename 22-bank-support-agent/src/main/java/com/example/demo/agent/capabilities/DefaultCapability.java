package com.example.demo.agent.capabilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.StringUtils;

/**
 * A simple declarative {@link Capability}: a bundle of (dynamic) instructions and
 * {@code @Tool}-annotated objects — the analog of a plain Pydantic AI
 * {@code Capability[Deps]} with {@code @capability.instructions} and
 * {@code @capability.tool} members.
 */
public final class DefaultCapability<D> implements Capability<D> {

	private final String id;

	private final String description;

	private final List<Function<D, String>> instructions;

	private final List<Object> tools;

	private DefaultCapability(Builder<D> builder) {
		this.id = builder.id;
		this.description = builder.description;
		this.instructions = List.copyOf(builder.instructions);
		this.tools = List.copyOf(builder.tools);
	}

	@Override
	public String id() {
		return this.id;
	}

	public String description() {
		return this.description;
	}

	@Override
	public void instrument(ChatClient.Builder chatClientBuilder) {
		if (!this.tools.isEmpty()) {
			chatClientBuilder.defaultTools(this.tools.toArray());
		}
	}

	@Override
	public String instructions(D deps) {
		return this.instructions.stream()
			.map(f -> f.apply(deps))
			.filter(StringUtils::hasText)
			.collect(Collectors.joining("\n\n"));
	}

	public static <D> Builder<D> builder(String id) {
		return new Builder<>(id);
	}

	public static final class Builder<D> {

		private final String id;

		private String description = "";

		private final List<Function<D, String>> instructions = new ArrayList<>();

		private final List<Object> tools = new ArrayList<>();

		private Builder(String id) {
			this.id = id;
		}

		public Builder<D> description(String description) {
			this.description = description;
			return this;
		}

		/** Adds a dynamic instruction, resolved from the deps on every run. */
		public Builder<D> instructions(Function<D, String> instructions) {
			this.instructions.add(instructions);
			return this;
		}

		/** Adds a static instruction. */
		public Builder<D> instructions(String instructions) {
			this.instructions.add(deps -> instructions);
			return this;
		}

		public Builder<D> tools(Object... tools) {
			this.tools.addAll(Arrays.asList(tools));
			return this;
		}

		public DefaultCapability<D> build() {
			return new DefaultCapability<>(this);
		}

	}

}
