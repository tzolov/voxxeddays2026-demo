package org.springaicommunity.inspector.server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireCaptureTest {

	private final WireCapture capture = new WireCapture(100);

	@Test
	void redactsSecretHeadersCompletelyAndLeavesOthers() {
		assertThat(WireCapture.redactHeader("x-api-key", "sk-ant-api03-secret")).isEqualTo(WireCapture.REDACTED);
		assertThat(WireCapture.redactHeader("Authorization", "Bearer sk-secret")).isEqualTo(WireCapture.REDACTED);
		assertThat(WireCapture.redactHeader("Set-Cookie", "session=abc")).isEqualTo(WireCapture.REDACTED);
		assertThat(WireCapture.redactHeader("Content-Type", "application/json")).isEqualTo("application/json");
	}

	@Test
	void redactsSecretQueryParameters() {
		assertThat(WireCapture.redactQuery("key=AIza-secret&alt=sse&access_token=t&flag"))
			.isEqualTo("?key=…redacted&alt=sse&access_token=…redacted&flag");
		assertThat(WireCapture.redactQuery(null)).isEmpty();
	}

	@Test
	void stripsInlineBase64ButKeepsShortStringsAndValidJson() {
		String image = "A".repeat(2000);
		String json = "{\"source\":{\"type\":\"base64\",\"data\":\"" + image + "\"},\"url\":\"data:image/png;base64," + image
				+ "\",\"text\":\"" + "B".repeat(500) + "\"}";
		String stripped = WireCapture.stripBase64(json);
		assertThat(stripped).contains("\"data\":\"<base64 2000 chars>\"")
			.contains("\"url\":\"<data:image/png;base64 base64 2000 chars>\"")
			.contains("B".repeat(500));
		assertThat(stripped.length()).isLessThan(json.length());
	}

	@Test
	void capsTextBodiesAndMarksTheCut() {
		Map<String, Object> event = new LinkedHashMap<>();
		this.capture.body(event, "x".repeat(250).getBytes(StandardCharsets.UTF_8), "application/json", null);
		assertThat(event).containsEntry("truncated", true).containsEntry("size", 250);
		assertThat(event.get("body").toString()).hasSize(100);
	}

	@Test
	void recordsBinaryBodiesBySizeOnly() {
		Map<String, Object> event = new LinkedHashMap<>();
		this.capture.body(event, new byte[] { 0, 1, 2, 3 }, "audio/mpeg", null);
		assertThat(event).containsEntry("bodyKind", "binary").containsEntry("contentType", "audio/mpeg")
			.containsEntry("size", 4).doesNotContainKey("body");
	}

	@Test
	void decodesGzipAndTreatsEventStreamsAsText() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
			gzip.write("data: {\"a\":1}\n\n".getBytes(StandardCharsets.UTF_8));
		}
		Map<String, Object> event = new LinkedHashMap<>();
		this.capture.body(event, bytes.toByteArray(), "text/event-stream", "gzip");
		assertThat(event).containsEntry("body", "data: {\"a\":1}\n\n");
	}

}
