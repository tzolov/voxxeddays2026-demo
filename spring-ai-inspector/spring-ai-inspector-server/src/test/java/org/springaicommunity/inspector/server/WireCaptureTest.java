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
		String stripped = this.capture.stripBase64(json); // no blob store: markers only
		assertThat(stripped).contains("\"data\":\"<base64 2000 chars>\"")
			.contains("\"url\":\"<base64 2000 chars image/png>\"")
			.contains("B".repeat(500));
		assertThat(stripped.length()).isLessThan(json.length());
	}

	@Test
	void keepsInlineMediaAsBlobsUnderTheTypeSniffedFromItsBytes() {
		BlobStore blobs = new BlobStore(1_000_000, 100_000);
		WireCapture keeping = new WireCapture(100_000, blobs);
		byte[] png = new byte[1500];
		png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
		String payload = java.util.Base64.getEncoder().encodeToString(png);
		Map<String, Object> event = new LinkedHashMap<>();

		keeping.body(event, ("{\"b64_json\":\"" + payload + "\"}").getBytes(StandardCharsets.UTF_8), "application/json", null);

		String body = event.get("body").toString();
		assertThat(body).matches("\\{\"b64_json\":\"<base64 " + payload.length() + " chars image/png blob:[0-9a-f]{16}>\"\\}");
		String id = body.replaceAll(".*blob:([0-9a-f]+)>.*", "$1");
		assertThat(blobs.get(id).contentType()).isEqualTo("image/png");
		assertThat(blobs.get(id).bytes()).isEqualTo(png);
	}

	@Test
	void base64ThatIsNotMediaIsCutButNotKept() {
		BlobStore blobs = new BlobStore(1_000_000, 100_000);
		String vector = java.util.Base64.getEncoder().encodeToString(new byte[6144]); // e.g. an embedding in base64
		Map<String, Object> event = new LinkedHashMap<>();

		new WireCapture(100_000, blobs).body(event, ("{\"embedding\":\"" + vector + "\"}").getBytes(StandardCharsets.UTF_8), "application/json", null);

		assertThat(event.get("body").toString()).isEqualTo("{\"embedding\":\"<base64 " + vector.length() + " chars>\"}");
		assertThat(blobs.size()).isZero();
	}

	@Test
	void keepsABinaryBodyAsABlob() {
		BlobStore blobs = new BlobStore(1_000_000, 100_000);
		Map<String, Object> event = new LinkedHashMap<>();
		new WireCapture(100, blobs).body(event, new byte[] { 'I', 'D', '3', 4, 0, 0 }, "application/octet-stream", null);

		assertThat(event).containsEntry("bodyKind", "binary").containsEntry("size", 6);
		String id = (String) event.get("blobId");
		assertThat(blobs.get(id).contentType()).isEqualTo("audio/mpeg"); // octet-stream: sniffed
	}

	@Test
	void recordsAMultipartRequestAsItsFieldsAndFileParts() {
		BlobStore blobs = new BlobStore(1_000_000, 100_000);
		String boundary = "----WebKitFormBoundary7MA4YWxk";
		byte[] audio = { 'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E', 0, 7 };
		byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n"
				+ "--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nen\r\n"
				+ "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"q.wav\"\r\nContent-Type: application/octet-stream\r\n\r\n")
			.getBytes(StandardCharsets.ISO_8859_1);
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
		byte[] all = new byte[body.length + audio.length + tail.length];
		System.arraycopy(body, 0, all, 0, body.length);
		System.arraycopy(audio, 0, all, body.length, audio.length);
		System.arraycopy(tail, 0, all, body.length + audio.length, tail.length);
		Map<String, Object> event = new LinkedHashMap<>();

		new WireCapture(100_000, blobs).body(event, all, "multipart/form-data; boundary=" + boundary, null);

		assertThat(event).containsEntry("bodyKind", "multipart");
		String json = event.get("body").toString();
		assertThat(json).contains("\"fields\":{\"model\":\"whisper-1\",\"language\":\"en\"}")
			.contains("\"name\":\"file\",\"filename\":\"q.wav\",\"contentType\":\"audio/wav\",\"size\":14,\"blobId\":\"");
		String id = json.replaceAll(".*\"blobId\":\"([0-9a-f]+)\".*", "$1");
		assertThat(blobs.get(id).bytes()).isEqualTo(audio);
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
	void anUnlabeledBodyIsTextUnlessItLooksBinary() {
		Map<String, Object> text = new LinkedHashMap<>();
		this.capture.body(text, "{\"a\":1}".getBytes(StandardCharsets.UTF_8), null, null);
		assertThat(text).containsEntry("body", "{\"a\":1}");

		Map<String, Object> bytes = new LinkedHashMap<>();
		this.capture.body(bytes, new byte[] { 'P', 'K', 3, 4, 0, 0 }, null, null);
		assertThat(bytes).containsEntry("bodyKind", "binary").containsEntry("size", 6);
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
