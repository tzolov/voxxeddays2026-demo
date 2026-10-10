package org.springaicommunity.inspector.server;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param upstreams provider name to upstream base URL, e.g.
 * {@code anthropic -> https://api.anthropic.com}
 * @param preloadDir optional folder of exported runs ({@code *.json}) loaded at startup,
 * e.g. recordings to fall back on when the network fails during a talk
 * @param token optional shared secret: when set, the event API ({@code /api/**} except
 * {@code /api/ping}) requires it as the {@code X-Inspector-Token} header or {@code token}
 * query parameter, and the proxy serves only runs that registered with it
 * @param allowedHosts {@code Host} header values accepted next to the loopback names; when
 * the server listens on a non-loopback address and this is empty, any host is accepted
 * @param maxBodyChars longest request or response body recorded on the wire, in characters;
 * longer ones are cut and marked {@code truncated}
 * @param maxTotalBytes rough byte budget of the in-memory event log; the oldest events are
 * dropped beyond it
 * @param maxRequestBytes largest event accepted on {@code /api/events}
 * @param maxImportBytes largest recording accepted on {@code /api/import}
 */
@ConfigurationProperties("spring.ai.inspector")
public record InspectorProperties(Map<String, String> upstreams, @Nullable String preloadDir, @Nullable String token,
		@Nullable List<String> allowedHosts, @DefaultValue("512000") int maxBodyChars,
		@DefaultValue("268435456") long maxTotalBytes, @DefaultValue("16777216") long maxRequestBytes,
		@DefaultValue("268435456") long maxImportBytes) {

	/** The defaults, for code paths that have no bound properties (tests). */
	static InspectorProperties defaults() {
		return new InspectorProperties(Map.of(), null, null, null, 512_000, 268_435_456L, 16_777_216L, 268_435_456L);
	}

	/** Whether a token is configured. */
	boolean hasToken() {
		return this.token != null && !this.token.isBlank();
	}

}
