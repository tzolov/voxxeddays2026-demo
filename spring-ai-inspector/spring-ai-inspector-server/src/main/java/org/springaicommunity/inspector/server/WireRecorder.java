package org.springaicommunity.inspector.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns what the proxy forwards into {@code wire-request} / {@code wire-response} events:
 * redaction and body capture ({@link WireCapture}), and the link to the ChatClient call and
 * model call the round-trip serves, from the starter's {@code X-Inspector-Call} /
 * {@code X-Inspector-Model-Call} headers, else from the calls open for the run at that
 * moment. {@link ProxyController} only moves bytes; this is what gets recorded.
 */
@Component
public class WireRecorder {

	/** Stamped by the starter on each model request: the ChatClient call and model call it serves. */
	static final String CALL_HEADER = "x-inspector-call";

	static final String MODEL_CALL_HEADER = "x-inspector-model-call";

	private final EventStore store;

	private final WireCapture capture;

	@Autowired
	public WireRecorder(EventStore store, InspectorProperties properties, BlobStore blobs) {
		this(store, new WireCapture(properties.maxBodyChars(), blobs));
	}

	WireRecorder(EventStore store, WireCapture capture) {
		this.store = store;
		this.capture = capture;
	}

	/**
	 * Records a request about to be forwarded and returns the round-trip's id.
	 * @param headers the headers as forwarded (name to values), before redaction
	 */
	public String request(String runId, String provider, HttpServletRequest request, String upstream, String path,
			Map<String, List<String>> headers, byte[] body) {
		String wireId = UUID.randomUUID().toString().substring(0, 8);
		Map<String, Object> shownHeaders = new LinkedHashMap<>();
		headers.forEach((name, values) -> shownHeaders.put(name, WireCapture.redactHeader(name, String.join(", ", values))));

		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "wire-request");
		event.put("runId", runId);
		event.put("wireId", wireId);
		event.put("provider", provider);
		event.put("method", request.getMethod());
		event.put("url", upstream + path + WireCapture.redactQuery(request.getQueryString()));
		event.put("path", path);
		event.put("headers", shownHeaders);
		this.capture.body(event, body, request.getContentType(), request.getHeader("content-encoding"));
		// The call this round-trip serves: from the starter's headers, else the call open for
		// the run right now (an app without the headers, e.g. an SDK streaming on its own threads).
		Map<String, Object> open = this.store.openCalls(runId);
		String call = request.getHeader(CALL_HEADER);
		String modelCall = request.getHeader(MODEL_CALL_HEADER);
		event.put("clientCallId", call != null ? call : open.get("clientCallId"));
		event.put("modelCallId", modelCall != null ? modelCall : open.get("modelCallId"));
		if (call != null || modelCall != null) {
			event.put("linkedBy", "header");
		}
		this.store.add(event);
		return wireId;
	}

	/** Records the upstream's response, streamed back meanwhile; {@code error} when the stream broke. */
	public void response(String runId, String wireId, int status, Map<String, List<String>> headers, byte[] body,
			long durationMs, @Nullable String error) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "wire-response");
		event.put("runId", runId);
		event.put("wireId", wireId);
		event.put("status", status);
		event.put("durationMs", durationMs);
		if (error != null) {
			event.put("error", error);
		}
		Map<String, Object> shownHeaders = new LinkedHashMap<>();
		headers.forEach((name, values) -> shownHeaders.put(name, WireCapture.redactHeader(name, String.join(", ", values))));
		event.put("headers", shownHeaders);
		this.capture.body(event, body, first(headers, "content-type"), first(headers, "content-encoding"));
		this.store.add(event);
	}

	/** Records that the upstream could not be reached: the round-trip is closed as a 502. */
	public void failure(String runId, String wireId, Exception ex, long durationMs) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "wire-response");
		event.put("runId", runId);
		event.put("wireId", wireId);
		event.put("status", 502);
		event.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
		event.put("durationMs", durationMs);
		this.store.add(event);
	}

	private static @Nullable String first(Map<String, List<String>> headers, String name) {
		for (Map.Entry<String, List<String>> header : headers.entrySet()) {
			if (header.getKey().equalsIgnoreCase(name) && !header.getValue().isEmpty()) {
				return header.getValue().get(0);
			}
		}
		return null;
	}

}
