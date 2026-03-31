package org.springframework.ai.mcp.samples.client;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class McpClientHandlers {

	private static final Logger logger = LoggerFactory.getLogger(McpClientHandlers.class);

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@McpLogging(clients = "poet-server")
	public void loggingHandler(LoggingMessageNotification loggingMessage) {
		logger.info("MCP LOGGING: [{}] {}", loggingMessage.level(), loggingMessage.data());
	}

	@McpSampling(clients = "poet-server")
	public CreateMessageResult samplingHandler(CreateMessageRequest llmRequest) {

		logger.info("MCP SAMPLING: {}", llmRequest);

		Content content = llmRequest.messages().get(0).content();

		var userPrompt = ((McpSchema.TextContent) content).text();

		String response = chatClientBuilder.build()
			.prompt()
			.system(llmRequest.systemPrompt())
			.user(userPrompt)
			.call()
			.content();

		return CreateMessageResult.builder().content(new McpSchema.TextContent(response)).build();
	};

}
