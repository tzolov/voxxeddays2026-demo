package org.springaicommunity.inspector.server;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards every request. The event log holds prompts, tool results and memory contents, and
 * a {@code run-start} event decides where the proxy forwards a run's API keys, so:
 * <ul>
 * <li>The {@code Host} header must name this server. Listening on {@code 127.0.0.1} keeps
 * other machines out, but not a page in the user's own browser whose DNS name resolves to
 * 127.0.0.1 (DNS rebinding); such a page sends its own hostname. Loopback names are always
 * accepted, plus {@code server.address} and {@code spring.ai.inspector.allowed-hosts}. On a
 * non-loopback address with no allowed hosts configured, any host is accepted.</li>
 * <li>When {@code spring.ai.inspector.token} is set, {@code /api/**} (except the
 * {@code /api/ping} signature) needs it as {@code X-Inspector-Token} or {@code ?token=}.
 * The proxy route carries no token (the model SDKs can't add one) and instead serves only
 * runs registered with the token, see {@link ProxyController}.</li>
 * <li>Request bodies are capped: events at {@code max-request-bytes}, recordings posted to
 * {@code /api/import} at {@code max-import-bytes}, media posted to {@code /api/blobs} at
 * {@code max-blob-size}.</li>
 * </ul>
 */
@Component
public class AccessFilter extends OncePerRequestFilter {

	static final String TOKEN_HEADER = "X-Inspector-Token";

	static final String TOKEN_PARAM = "token";

	private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");

	/** Accepted {@code Host} names (without port), or null for any. */
	private final @Nullable Set<String> allowedHosts;

	private final byte @Nullable [] token;

	private final long maxRequestBytes;

	private final long maxImportBytes;

	private final long maxBlobSize;

	public AccessFilter(InspectorProperties properties, @Value("${server.address:}") String address) {
		this.allowedHosts = allowedHosts(properties, address);
		this.token = properties.token() == null || properties.token().isBlank() ? null
				: properties.token().getBytes(StandardCharsets.UTF_8);
		this.maxRequestBytes = properties.maxRequestBytes();
		this.maxImportBytes = properties.maxImportBytes();
		this.maxBlobSize = properties.maxBlobSize();
	}

	private static @Nullable Set<String> allowedHosts(InspectorProperties properties, String address) {
		Set<String> hosts = new LinkedHashSet<>(LOOPBACK);
		String bound = address == null ? "" : address.trim();
		boolean loopback = bound.isEmpty() || isLoopback(bound);
		if (!loopback) {
			hosts.add(bound.toLowerCase(Locale.ROOT));
		}
		if (properties.allowedHosts() != null) {
			properties.allowedHosts().stream().filter(h -> h != null && !h.isBlank())
				.forEach(h -> hosts.add(h.trim().toLowerCase(Locale.ROOT)));
		}
		else if (!loopback) {
			return null; // deliberately exposed, no names given: accept any host
		}
		return hosts;
	}

	private static boolean isLoopback(String address) {
		try {
			return InetAddress.getByName(address).isLoopbackAddress();
		}
		catch (Exception ex) {
			return false;
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
		if (this.allowedHosts != null && !this.allowedHosts.contains(host)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Host '" + host
					+ "' is not this inspector; set spring.ai.inspector.allowed-hosts to accept it");
			return;
		}
		String path = request.getRequestURI();
		if (path.startsWith("/api/")) {
			if (this.token != null && !"/api/ping".equals(path) && !tokenMatches(request)) {
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "spring.ai.inspector.token required");
				return;
			}
			Limit limit = limitFor(path);
			if (request.getContentLengthLong() > limit.bytes()) {
				response.sendError(413, "request body exceeds spring.ai.inspector." + limit.property());
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private record Limit(String property, long bytes) {
	}

	/** The body cap of a path: recordings, media and events have their own. */
	private Limit limitFor(String path) {
		if ("/api/import".equals(path)) {
			return new Limit("max-import-bytes", this.maxImportBytes);
		}
		if (path.startsWith("/api/blobs")) {
			return new Limit("max-blob-size", this.maxBlobSize);
		}
		return new Limit("max-request-bytes", this.maxRequestBytes);
	}

	private boolean tokenMatches(HttpServletRequest request) {
		String given = request.getHeader(TOKEN_HEADER);
		if (given == null) {
			given = request.getParameter(TOKEN_PARAM);
		}
		return given != null && MessageDigest.isEqual(this.token, given.getBytes(StandardCharsets.UTF_8));
	}

}
