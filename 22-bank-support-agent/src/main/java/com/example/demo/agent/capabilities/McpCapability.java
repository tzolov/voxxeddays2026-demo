package com.example.demo.agent.capabilities;

import java.util.List;
import java.util.function.Predicate;

import io.modelcontextprotocol.client.McpSyncClient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

/**
 * The MCP capability — the analog of a Pydantic AI {@code MCPServerStdio(...)} toolset
 * passed to the agent, read as {@code Mcp('exchange-rates')}: it exposes the tools of one
 * MCP server to the agent, optionally narrowed to a subset of its tools.
 *
 * The server itself is declared in {@code mcp-servers-config.json} and connected by the
 * Spring AI MCP client starter, which hands us the ready {@link McpSyncClient}s. The
 * capability builds its own {@link SyncMcpToolCallbackProvider} scoped to the named
 * server, so one instance per server can be composed and the tool filter stays local to
 * the agent instead of becoming a global bean.
 */
public final class McpCapability<D> implements Capability<D> {

	private final String serverName;

	private final List<McpSyncClient> mcpClients;

	private final Predicate<String> toolNameFilter;

	/**
	 * @param serverName the server key as declared in {@code mcp-servers-config.json}
	 * @param mcpClients the auto-configured MCP clients (all servers)
	 */
	public McpCapability(String serverName, List<McpSyncClient> mcpClients) {
		this(serverName, mcpClients, toolName -> true);
	}

	/**
	 * @param serverName the server key as declared in {@code mcp-servers-config.json}
	 * @param mcpClients the auto-configured MCP clients (all servers)
	 * @param toolNameFilter which of the server's tools to expose to the model
	 */
	public McpCapability(String serverName, List<McpSyncClient> mcpClients, Predicate<String> toolNameFilter) {
		this.serverName = serverName;
		this.mcpClients = List.copyOf(mcpClients);
		this.toolNameFilter = toolNameFilter;
	}

	@Override
	public String id() {
		return "mcp:" + this.serverName;
	}

	@Override
	public void instrument(ChatClient.Builder chatClientBuilder) {
		var provider = SyncMcpToolCallbackProvider.builder()
			.mcpClients(this.mcpClients)
			.toolFilter((connection, tool) -> isServer(connection) && this.toolNameFilter.test(tool.name()))
			.build();
		chatClientBuilder.defaultTools(provider);
	}

	@Override
	public String instructions(D deps) {
		return "Tools from the '%s' MCP server are available. Use them when the request needs the data they provide."
			.formatted(this.serverName);
	}

	/**
	 * The MCP client starter names each connection "&lt;client name&gt; - &lt;server key&gt;" and
	 * sets the server key as the client title.
	 */
	private boolean isServer(McpConnectionInfo connection) {
		var clientInfo = connection.clientInfo();
		return this.serverName.equals(clientInfo.title())
				|| (clientInfo.name() != null && clientInfo.name().endsWith(" - " + this.serverName));
	}

}
