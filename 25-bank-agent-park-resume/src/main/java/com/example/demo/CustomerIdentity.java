package com.example.demo;

import org.springframework.ai.chat.model.ToolContext;

/**
 * The per-conversation context the tools read from the {@link ToolContext}: the authenticated
 * customer. The model never sees or chooses the customer id, so it cannot ask for someone else's
 * data.
 */
public record CustomerIdentity(int customerId) {

	public static final String TOOL_CONTEXT_KEY = "customer";

	public static CustomerIdentity from(ToolContext toolContext) {
		return (CustomerIdentity) toolContext.getContext().get(TOOL_CONTEXT_KEY);
	}

}
