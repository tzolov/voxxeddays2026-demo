package com.example.demo.a2a;

import java.util.List;
import java.util.stream.Stream;

import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.a2a.server.executor.DefaultAgentExecutor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * A2A agent for Airbnb accommodation search.
 *
 * http://localhost:10001/airbnb/.well-known/agent-card.json
 *
 * @author Christian Tzolov
 * @since 0.1.0
 */
@SpringBootApplication
public class A2AAgent {

	@Bean
	public AgentCard agentCard(@Value("${server.port:8080}") int port,
			@Value("${server.servlet.context-path:/}") String contextPath) {

		return new AgentCard.Builder().name("Airbnb Agent")
			.description("Helps with searching accommodation")
			.url("http://localhost:" + port + contextPath + "/")
			.version("1.0.0")
			.capabilities(new AgentCapabilities.Builder().streaming(false).pushNotifications(true).build())
			.defaultInputModes(List.of("text", "text/plain"))
			.defaultOutputModes(List.of("text", "text/plain"))
			.skills(List.of(new AgentSkill.Builder().id("airbnb_search")
				.name("Search airbnb accommodation")
				.description("Helps with accommodation search using airbnb")
				.tags(List.of("airbnb accommodation"))
				.examples(List.of("Please find a room in LA, CA, April 15, 2025, checkout date is april 18, 2 adults"))
				.build()))
			.protocolVersion("0.3.0")
			.build();
	}

	@Bean
	public AgentExecutor agentExecutor(ChatClient.Builder chatClientBuilder,
			ToolCallbackProvider toolCallbackProvider) {

		ChatClient chatClient = chatClientBuilder.clone().defaultSystem("""
				You are a specialized assistant for Airbnb accommodations.
				Your primary function is to utilize the provided tools to search for Airbnb listings
				and answer related questions.
				""")
				.defaultToolCallbacks(toolCallbackProvider)
				.build();

		return new DefaultAgentExecutor(chatClient, (chat, requestContext) -> {
			String userMessage = DefaultAgentExecutor.extractTextFromMessage(requestContext.getMessage());
			var response = chat.prompt().user(userMessage).call().content();
			return response;
		});
	}

}
