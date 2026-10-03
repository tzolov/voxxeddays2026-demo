package org.springaicommunity.inspector.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recording reverse proxy (a built-in replacement for mitmweb). Demos call
 * {@code /r/<runId>/<provider>/v1/...}; the request is forwarded to the provider's
 * upstream, the response is streamed back unchanged, and both are published as
 * {@code wire-request} / {@code wire-response} events with API keys redacted.
 *
 * <p>The upstream is the base URL the application originally had for that provider (the
 * starter reports it in {@code run-start}, so custom gateways keep working), falling back
 * to {@code spring.ai.inspector.upstreams.<provider>}. Because the run decides where its
 * traffic goes, the server listens on localhost only by default.
 */
@RestController
public class ProxyController {

	private static final Set<String> SKIP_REQUEST_HEADERS = Set.of("host", "content-length", "connection",
			"accept-encoding", "transfer-encoding", "expect", "upgrade", "keep-alive", "te", "trailer",
			"http2-settings");

	private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("content-length", "connection",
			"transfer-encoding", "keep-alive", ":status");

	private static final Set<String> SECRET_HEADERS = Set.of("x-api-key", "authorization", "api-key",
			"x-goog-api-key", "openai-organization", "openai-project");

	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final EventStore store;

	private final Map<String, String> upstreams;

	public ProxyController(EventStore store, InspectorProperties properties) {
		this.store = store;
		this.upstreams = properties.upstreams() == null ? Map.of() : properties.upstreams();
	}

	@RequestMapping("/r/{runId}/{provider}/**")
	public void proxy(@PathVariable String runId, @PathVariable String provider, HttpServletRequest request,
			HttpServletResponse response) throws IOException {

		String upstream = this.store.upstream(runId, provider);
		if (upstream == null) {
			upstream = this.upstreams.get(provider);
		}
		if (upstream == null) {
			response.sendError(404, "Unknown provider '" + provider + "', configure spring.ai.inspector.upstreams." + provider);
			return;
		}

		String prefix = "/r/" + runId + "/" + provider;
		String path = request.getRequestURI().substring(request.getRequestURI().indexOf(prefix) + prefix.length());
		String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
		byte[] body = request.getInputStream().readAllBytes();

		HttpRequest.Builder upstreamRequest = HttpRequest.newBuilder(URI.create(upstream + path + query))
			.timeout(Duration.ofMinutes(10))
			.method(request.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofByteArray(body));

		Map<String, Object> requestHeaders = new LinkedHashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
				continue;
			}
			for (String value : Collections.list(request.getHeaders(name))) {
				upstreamRequest.header(name, value);
			}
			requestHeaders.put(name, redact(name, request.getHeader(name)));
		}

		String wireId = UUID.randomUUID().toString().substring(0, 8);
		Map<String, Object> wireRequest = new LinkedHashMap<>();
		wireRequest.put("type", "wire-request");
		wireRequest.put("runId", runId);
		wireRequest.put("wireId", wireId);
		wireRequest.put("provider", provider);
		wireRequest.put("method", request.getMethod());
		wireRequest.put("url", upstream + path + query);
		wireRequest.put("path", path);
		wireRequest.put("headers", requestHeaders);
		wireRequest.put("body", new String(body, StandardCharsets.UTF_8));
		wireRequest.putAll(this.store.openCalls(runId));
		this.store.add(wireRequest);

		long start = System.currentTimeMillis();
		Map<String, Object> wireResponse = new LinkedHashMap<>();
		wireResponse.put("type", "wire-response");
		wireResponse.put("runId", runId);
		wireResponse.put("wireId", wireId);

		HttpResponse<InputStream> upstreamResponse;
		try {
			upstreamResponse = this.httpClient.send(upstreamRequest.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (Exception ex) {
			wireResponse.put("status", 502);
			wireResponse.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
			wireResponse.put("durationMs", System.currentTimeMillis() - start);
			this.store.add(wireResponse);
			response.sendError(502, "Spring AI Inspector could not reach " + upstream + ": " + ex.getMessage());
			return;
		}

		response.setStatus(upstreamResponse.statusCode());
		Map<String, Object> responseHeaders = new LinkedHashMap<>();
		upstreamResponse.headers().map().forEach((name, values) -> {
			if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
				return;
			}
			values.forEach(value -> response.addHeader(name, value));
			responseHeaders.put(name, String.join(", ", values));
		});

		// Stream through chunk by chunk (keeps SSE streaming responses live) and keep a copy.
		// The wire-response is published in any case, so a broken stream never leaves the
		// round-trip "waiting" in the UI.
		ByteArrayOutputStream copy = new ByteArrayOutputStream();
		try (InputStream in = upstreamResponse.body()) {
			OutputStream out = response.getOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				out.flush();
				copy.write(buffer, 0, read);
			}
		}
		catch (IOException ex) {
			wireResponse.put("error", "stream interrupted: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			throw ex;
		}
		finally {
			wireResponse.put("status", upstreamResponse.statusCode());
			wireResponse.put("durationMs", System.currentTimeMillis() - start);
			wireResponse.put("headers", responseHeaders);
			wireResponse.put("body", decode(copy.toByteArray(),
					upstreamResponse.headers().firstValue("content-encoding").orElse("")));
			this.store.add(wireResponse);
		}
	}

	private static String redact(String name, String value) {
		if (!SECRET_HEADERS.contains(name.toLowerCase()) || value == null) {
			return value;
		}
		return value.substring(0, Math.min(value.length(), 7)) + "…redacted";
	}

	private static String decode(byte[] bytes, String contentEncoding) {
		if (contentEncoding.toLowerCase().contains("gzip")) {
			try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch (IOException ex) {
				return "<gzip body could not be decoded: " + ex.getMessage() + ">";
			}
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

}
