package com.example.demo;

/**
 * The typed, per-run dependencies of the support agent — the analog of Pydantic AI's
 * {@code deps_type=SupportDependencies}. Passed to tools via the ChatClient tool context.
 */
public record SupportDependencies(int customerId, DatabaseConn db) {

	/**
	 * Key under which the dependencies travel in the {@code ToolContext}. Same value as
	 * the Agent facade's fixed {@code Agent.DEPS_TOOL_CONTEXT_KEY}, so the tools work
	 * unchanged in both the classic and the facade variant.
	 */
	public static final String TOOL_CONTEXT_KEY = "deps";

}
