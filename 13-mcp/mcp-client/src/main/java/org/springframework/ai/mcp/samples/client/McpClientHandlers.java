package org.springframework.ai.mcp.samples.client;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;

@Service
public class McpClientHandlers {

	private static final Logger logger = LoggerFactory.getLogger(McpClientHandlers.class);

	@Autowired
	private ChatClient.Builder chatClientBuilder;

	@McpProgress(clients = "poet-server")
	public void progressHandler(ProgressNotification progressNotification) {
		logger.info("MCP PROGRESS: [{}] progress: {} total: {} message: {}", progressNotification.progressToken(),
				progressNotification.progress(), progressNotification.total(), progressNotification.message());
	}

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
