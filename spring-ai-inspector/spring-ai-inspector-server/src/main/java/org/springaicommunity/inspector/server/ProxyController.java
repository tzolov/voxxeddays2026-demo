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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recording reverse proxy (a built-in replacement for mitmweb). Demos call
 * {@code /r/<runId>/<provider>/v1/...}; the request is forwarded to the provider's
 * upstream, the response is streamed back unchanged, and both are published as
 * {@code wire-request} / {@code wire-response} events, shaped by {@link WireCapture}:
 * secrets redacted, inline base64 stripped, bodies capped, binary bodies by size only.
 *
 * <p>Each round-trip is linked to the ChatClient call and the model call it serves by the
 * {@code X-Inspector-Call} / {@code X-Inspector-Model-Call} headers the starter stamps on
 * the request (stripped here), falling back to the calls open for the run at that moment.
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

	/** Stamped by the starter on each model request: the ChatClient call and model call it serves. */
	static final String CALL_HEADER = "x-inspector-call";

	static final String MODEL_CALL_HEADER = "x-inspector-model-call";

	private static final Set<String> SKIP_REQUEST_HEADERS = Set.of("host", "content-length", "connection",
			"accept-encoding", "transfer-encoding", "expect", "upgrade", "keep-alive", "te", "trailer",
			"http2-settings", CALL_HEADER, MODEL_CALL_HEADER);

	private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("content-length", "connection",
			"transfer-encoding", "keep-alive", ":status");

	private final HttpClient httpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final EventStore store;

	private final Map<String, String> upstreams;

	private final boolean registeredRunsOnly;

	private final WireCapture capture;

	public ProxyController(EventStore store, InspectorProperties properties) {
		this.store = store;
		this.upstreams = properties.upstreams() == null ? Map.of() : properties.upstreams();
		this.registeredRunsOnly = properties.hasToken();
		this.capture = new WireCapture(properties.maxBodyChars());
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

		Map<String, Object> requestHeaders = new LinkedHashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
				continue;
			}
			for (String value : Collections.list(request.getHeaders(name))) {
				upstreamRequest.header(name, value);
			}
			requestHeaders.put(name, WireCapture.redactHeader(name, request.getHeader(name)));
		}

		String wireId = UUID.randomUUID().toString().substring(0, 8);
		String shownQuery = WireCapture.redactQuery(request.getQueryString());
		Map<String, Object> wireRequest = new LinkedHashMap<>();
		wireRequest.put("type", "wire-request");
		wireRequest.put("runId", runId);
		wireRequest.put("wireId", wireId);
		wireRequest.put("provider", provider);
		wireRequest.put("method", request.getMethod());
		wireRequest.put("url", upstream + path + shownQuery);
		wireRequest.put("path", path);
		wireRequest.put("headers", requestHeaders);
		this.capture.body(wireRequest, body, request.getContentType(), request.getHeader("content-encoding"));
		// The call this round-trip serves: from the starter's headers, else the call open for
		// the run right now (an app without the headers, e.g. an SDK streaming on its own threads).
		Map<String, Object> open = this.store.openCalls(runId);
		String call = request.getHeader(CALL_HEADER);
		String modelCall = request.getHeader(MODEL_CALL_HEADER);
		wireRequest.put("clientCallId", call != null ? call : open.get("clientCallId"));
		wireRequest.put("modelCallId", modelCall != null ? modelCall : open.get("modelCallId"));
		if (call != null || modelCall != null) {
			wireRequest.put("linkedBy", "header");
		}
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
			responseHeaders.put(name, WireCapture.redactHeader(name, String.join(", ", values)));
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
			this.capture.body(wireResponse, copy.toByteArray(),
					upstreamResponse.headers().firstValue("content-type").orElse(null),
					upstreamResponse.headers().firstValue("content-encoding").orElse(null));
			this.store.add(wireResponse);
		}
	}

}
