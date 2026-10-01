package com.example.demo.agent.capabilities;

import org.springaicommunity.agent.tools.BraveWebSearchTool;

import org.springframework.ai.chat.client.ChatClient;

/**
 * The WebSearch capability: injects spring-ai-agent-utils' {@link BraveWebSearchTool} so
 * the agent can search the web for current information. Requires a Brave Search API key
 * (https://brave.com/search/api/).
 */
public final class WebSearchCapability<D> implements Capability<D> {

	private static final int DEFAULT_RESULT_COUNT = 10;

	private final String braveApiKey;

	private final int resultCount;

	public WebSearchCapability(String braveApiKey) {
		this(braveApiKey, DEFAULT_RESULT_COUNT);
	}

	public WebSearchCapability(String braveApiKey, int resultCount) {
		this.braveApiKey = braveApiKey;
		this.resultCount = resultCount;
	}

	@Override
	public String id() {
		return "web-search";
	}

	@Override
	public void instrument(ChatClient.Builder chatClientBuilder) {
		chatClientBuilder
			.defaultTools(BraveWebSearchTool.builder(this.braveApiKey).resultCount(this.resultCount).build());
	}

	@Override
	public String instructions(D deps) {
		return "You can search the web with the web search tool when the request needs current, public information.";
	}

}
