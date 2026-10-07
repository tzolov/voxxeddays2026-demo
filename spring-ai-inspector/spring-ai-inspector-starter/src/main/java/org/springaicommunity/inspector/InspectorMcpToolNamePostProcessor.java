package org.springaicommunity.inspector;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps the auto-configured {@link McpToolNamePrefixGenerator}, which Spring AI's MCP tool
 * callback providers ask for the name of each MCP tool they hand to the model, to remember
 * which MCP connection and server each name comes from: the names alone don't say (by
 * default they are the plain MCP tool names, renamed {@code alt_<n>_<name>} on a clash).
 * Providers built by hand use their own generator; their tools are known from the
 * connection's {@code tools/list} responses instead (see {@link InspectorToolOrigins}).
 * The generator is wrapped, not called again, because the default one is stateful (it
 * renames clashing tools only the first time it sees them).
 */
public class InspectorMcpToolNamePostProcessor implements BeanPostProcessor {

	private final ObjectProvider<InspectorToolOrigins> origins;

	public InspectorMcpToolNamePostProcessor(ObjectProvider<InspectorToolOrigins> origins) {
		this.origins = origins;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof McpToolNamePrefixGenerator generator)) {
			return bean;
		}
		ProxyFactory factory = new ProxyFactory(generator);
		// Proxy the concrete class when possible, so injection points typed as the
		// implementation keep working; fall back to an interface proxy (e.g. for lambdas).
		factory.setProxyTargetClass(!Modifier.isFinal(generator.getClass().getModifiers()));
		factory.addAdvice((MethodInterceptor) invocation -> {
			Object result = invocation.proceed();
			Object[] args = invocation.getArguments();
			if ("prefixedToolName".equals(invocation.getMethod().getName()) && result instanceof String toolName
					&& args.length == 2 && args[0] instanceof McpConnectionInfo info
					&& args[1] instanceof McpSchema.Tool tool) {
				try {
					this.origins.ifAvailable(o -> o.put(toolName, origin(info, tool, o.connections()), tool.description()));
				}
				catch (RuntimeException | LinkageError ex) {
					// Never break tool registration over inspection.
				}
			}
			return result;
		});
		return factory.getProxy();
	}

	static Map<String, Object> origin(McpConnectionInfo info, McpSchema.Tool tool, Set<String> connections) {
		Map<String, Object> origin = new LinkedHashMap<>();
		origin.put("connection", connectionName(info.clientInfo(), connections));
		McpSchema.InitializeResult init = info.initializeResult();
		if (init != null && init.serverInfo() != null) {
			origin.put("server", init.serverInfo().name());
			origin.put("serverVersion", init.serverInfo().version());
		}
		origin.put("tool", tool.name());
		return origin;
	}

	/**
	 * The {@code spring.ai.mcp.client.*.connections.<name>} key, which also names the
	 * connection's transport (and so its MCP messages): Spring AI names each client
	 * {@code "<client name> - <connection>"}, and also sets the title to the connection for
	 * sync clients. Matched against the known connection names first, then parsed.
	 */
	static String connectionName(McpSchema.Implementation clientInfo, Set<String> connections) {
		if (clientInfo == null) {
			return null;
		}
		for (String connection : connections) {
			if (connection.equals(clientInfo.title())) {
				return connection;
			}
		}
		for (String connection : connections) {
			if (clientInfo.name() != null && clientInfo.name().endsWith(" - " + connection)) {
				return connection;
			}
		}
		if (clientInfo.title() != null && !clientInfo.title().isBlank()) {
			return clientInfo.title();
		}
		String name = clientInfo.name();
		if (name == null) {
			return null;
		}
		int dash = name.lastIndexOf(" - ");
		return dash < 0 ? name : name.substring(dash + 3);
	}

}
