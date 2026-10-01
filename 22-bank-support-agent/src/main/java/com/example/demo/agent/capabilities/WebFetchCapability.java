package com.example.demo.agent.capabilities;

import org.springaicommunity.agent.tools.SmartWebFetchTool;

import org.springframework.ai.chat.client.ChatClient;

/**
 * The WebFetch capability: injects spring-ai-agent-utils' {@link SmartWebFetchTool} so
 * the agent can fetch a web page and distill it against a prompt. The tool uses its own
 * {@link ChatClient} for the distillation, kept separate from the agent's own client so
 * the fetch summarization runs without the agent's tools and advisors.
 */
public final class WebFetchCapability<D> implements Capability<D> {

	private final ChatClient distillationChatClient;

	public WebFetchCapability(ChatClient distillationChatClient) {
		this.distillationChatClient = distillationChatClient;
	}

	@Override
	public String id() {
		return "web-fetch";
	}

	@Override
	public void instrument(ChatClient.Builder chatClientBuilder) {
		chatClientBuilder.defaultTools(SmartWebFetchTool.builder(this.distillationChatClient).build());
	}

	@Override
	public String instructions(D deps) {
		return "You can fetch the content of a web page with the web fetch tool when a URL is relevant to the request.";
	}

}
