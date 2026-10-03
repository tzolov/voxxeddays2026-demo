package org.springaicommunity.inspector.server;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param upstreams provider name to upstream base URL, e.g.
 * {@code anthropic -> https://api.anthropic.com}
 * @param preloadDir optional folder of exported runs ({@code *.json}) loaded at startup,
 * e.g. recordings to fall back on when the network fails during a talk
 */
@ConfigurationProperties("spring.ai.inspector")
public record InspectorProperties(Map<String, String> upstreams, @Nullable String preloadDir) {
}
