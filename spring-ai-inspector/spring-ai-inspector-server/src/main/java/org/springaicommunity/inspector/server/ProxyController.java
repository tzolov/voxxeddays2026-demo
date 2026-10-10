package org.springaicommunity.inspector.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recording reverse proxy (a built-in replacement for mitmweb). Demos call
 * {@code /r/<runId>/<provider>/v1/...}; the request is forwarded to the provider's
 * upstream and the response is streamed back unchanged. What is recorded of both, and how
 * it is linked to the calls it serves, is {@link WireRecorder}'s business: this class only
 * decides where to forward and moves the bytes.
 *
 * <p>The upstream is the base URL the application originally had for that provider (the
 * starter reports it in {@code run-start}, so custom gateways keep working), falling back
 * to {@code spring.ai.inspector.upstreams.<provider>}. A run's upstreams are fixed by its
 * first {@code run-start} (see {@link EventStore}), so a later event can't redirect its
 * calls elsewhere. When {@code spring.ai.inspector.token} is set, only runs registered with
 * the token are served: the fallback upstreams are then off, as the proxy route itself
 * carries no token.
 */
@RestController
public class ProxyController {

	private static final Set<String> SKIP_REQUEST_HEADERS = Set.of("host", "content-length", "connection",
			"accept-encoding", "transfer-encoding", "expect", "upgrade", "keep-alive", "te", "trailer",
			"http2-settings", WireRecorder.CALL_HEADER, WireRecorder.MODEL_CALL_HEADER);

	private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("content-length", "connection",
			"transfer-encoding", "keep-alive", ":status");

	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final EventStore store;

	private final WireRecorder recorder;

	private final Map<String, String> upstreams;

	private final boolean registeredRunsOnly;

	public ProxyController(EventStore store, WireRecorder recorder, InspectorProperties properties) {
		this.store = store;
		this.recorder = recorder;
		this.upstreams = properties.upstreams() == null ? Map.of() : properties.upstreams();
		this.registeredRunsOnly = properties.hasToken();
	}

	@RequestMapping("/r/{runId}/{provider}/**")
	public void proxy(@PathVariable String runId, @PathVariable String provider, HttpServletRequest request,
			HttpServletResponse response) throws IOException {

		String upstream = this.store.upstream(runId, provider);
		if (upstream == null && !this.registeredRunsOnly) {
			upstream = this.upstreams.get(provider);
		}
		if (upstream == null) {
			response.sendError(404, this.registeredRunsOnly
					? "Unknown run '" + runId + "': only runs registered with the inspector token are proxied"
					: "Unknown provider '" + provider + "', configure spring.ai.inspector.upstreams." + provider);
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
		Map<String, List<String>> forwardedHeaders = new LinkedHashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
				continue;
			}
			List<String> values = Collections.list(request.getHeaders(name));
			values.forEach(value -> upstreamRequest.header(name, value));
			forwardedHeaders.put(name, values);
		}
		String wireId = this.recorder.request(runId, provider, request, upstream, path, forwardedHeaders, body);

		long start = System.currentTimeMillis();
		HttpResponse<InputStream> upstreamResponse;
		try {
			upstreamResponse = this.httpClient.send(upstreamRequest.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (Exception ex) {
			this.recorder.failure(runId, wireId, ex, System.currentTimeMillis() - start);
			response.sendError(502, "Spring AI Inspector could not reach " + upstream + ": " + ex.getMessage());
			return;
		}

		response.setStatus(upstreamResponse.statusCode());
		Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
		upstreamResponse.headers().map().forEach((name, values) -> {
			if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
				return;
			}
			values.forEach(value -> response.addHeader(name, value));
			responseHeaders.put(name, values);
		});

		// Stream through chunk by chunk (keeps SSE streaming responses live) and keep a copy.
		// The wire-response is recorded in any case, so a broken stream never leaves the
		// round-trip "waiting" in the UI.
		ByteArrayOutputStream copy = new ByteArrayOutputStream();
		String error = null;
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
			error = "stream interrupted: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
			throw ex;
		}
		finally {
			this.recorder.response(runId, wireId, upstreamResponse.statusCode(), responseHeaders, copy.toByteArray(),
					System.currentTimeMillis() - start, error);
		}
	}

}
