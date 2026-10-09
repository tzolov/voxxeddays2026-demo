package org.springaicommunity.inspector;

import java.util.List;

import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps the MCP client transports Spring AI auto-configures (the
 * {@code List<NamedClientMcpTransport>} beans of the stdio, Streamable HTTP and SSE
 * auto-configurations) in an {@link InspectorMcpClientTransport}, before the MCP clients
 * are built from them, so the inspector sees every MCP message of every connection.
 */
public class InspectorMcpTransportPostProcessor implements BeanPostProcessor {

	private final ObjectProvider<InspectorClient> client;

	private final ObjectProvider<InspectorToolOrigins> origins;

	public InspectorMcpTransportPostProcessor(ObjectProvider<InspectorClient> client,
			ObjectProvider<InspectorToolOrigins> origins) {
		this.client = client;
		this.origins = origins;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof List<?> list) || list.isEmpty()
				|| !list.stream().allMatch(NamedClientMcpTransport.class::isInstance)) {
			return bean;
		}
		InspectorClient inspector = this.client.getIfAvailable();
		if (inspector == null) {
			return bean;
		}
		InspectorToolOrigins toolOrigins = this.origins.getIfAvailable();
		return list.stream().map(NamedClientMcpTransport.class::cast).map(t -> {
			if (toolOrigins != null) {
				toolOrigins.addConnection(t.name());
			}
			return t.transport() instanceof InspectorMcpClientTransport ? t : new NamedClientMcpTransport(t.name(),
					new InspectorMcpClientTransport(t.name(), t.transport(), inspector, toolOrigins));
		}).toList();
	}

}
