package org.springaicommunity.inspector.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyControllerTest {

	@LocalServerPort
	int port;

	@Autowired
	EventStore store;

	private HttpServer upstream;

	private final AtomicReference<String> receivedPath = new AtomicReference<>();

	private final AtomicReference<String> receivedKey = new AtomicReference<>();

	private final AtomicReference<String> receivedQuery = new AtomicReference<>();

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void startUpstream() throws IOException {
		this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.upstream.createContext("/", exchange -> {
			this.receivedPath.set(exchange.getRequestURI().getPath());
			this.receivedQuery.set(exchange.getRequestURI().getQuery());
			this.receivedKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
			byte[] body = "{\"stop_reason\":\"end_turn\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.getResponseHeaders().add("Set-Cookie", "session=abc");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.upstream.createContext("/gateway/v1/audio/speech", exchange -> {
			byte[] body = new byte[] { 0x49, 0x44, 0x33, 0, 1, 2, 3 }; // "ID3": an MP3
			exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.upstream.start();
	}

	@AfterEach
	void stopUpstream() {
		this.upstream.stop(0);
		this.store.clear();
	}

	private String upstreamUrl() {
		return "http://127.0.0.1:" + this.upstream.getAddress().getPort() + "/gateway";
	}

	private HttpResponse<String> post(String path) throws Exception {
		return post(path, "{\"model\":\"claude\"}");
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("x-api-key", "sk-ant-api03-secret-value")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private List<Map<String, Object>> events(String type) {
		return this.store.events().stream().filter(e -> type.equals(e.get("type"))).toList();
	}

	@Test
	void forwardsToTheRunsOriginalUpstreamAndRecordsBothSides() throws Exception {
		this.store.add(new java.util.HashMap<>(Map.of("type", "run-start", "runId", "run1", "upstreams",
				Map.of("anthropic", upstreamUrl()))));

		HttpResponse<String> response = post("/r/run1/anthropic/v1/messages");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("end_turn");
		// Forwarded unchanged: the gateway's path prefix kept, the real key passed on.
		assertThat(this.receivedPath.get()).isEqualTo("/gateway/v1/messages");
		assertThat(this.receivedKey.get()).isEqualTo("sk-ant-api03-secret-value");
		// Recorded with the key redacted, entirely: not even a prefix of it.
		Map<String, Object> wireRequest = events("wire-request").get(0);
		assertThat(wireRequest.get("body")).isEqualTo("{\"model\":\"claude\"}");
		assertThat(wireRequest.get("headers").toString()).contains("redacted").doesNotContain("secret-value")
			.doesNotContain("sk-ant");
		Map<String, Object> wireResponse = events("wire-response").get(0);
		assertThat(wireResponse).containsEntry("status", 200).containsEntry("wireId", wireRequest.get("wireId"));
		assertThat(wireResponse.get("body").toString()).contains("end_turn");
		assertThat(wireResponse.get("headers").toString()).doesNotContain("session=abc");
	}

	@Test
	void linksTheRoundTripByTheStartersHeadersAndStripsThem() throws Exception {
		this.store.add(new java.util.HashMap<>(Map.of("type", "run-start", "runId", "run5", "upstreams",
				Map.of("anthropic", upstreamUrl()))));
		this.store.add(new java.util.HashMap<>(Map.of("type", "client-request", "runId", "run5", "callId", "open-call")));
		AtomicReference<String> forwardedHeader = new AtomicReference<>();
		this.upstream.createContext("/gateway/v1/linked", exchange -> {
			forwardedHeader.set(exchange.getRequestHeaders().getFirst("X-Inspector-Call"));
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});

		this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/r/run5/anthropic/v1/linked"))
			.header("X-Inspector-Call", "c-77").header("X-Inspector-Model-Call", "m-78")
			.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
		this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/r/run5/anthropic/v1/linked"))
			.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

		assertThat(forwardedHeader.get()).isNull();
		List<Map<String, Object>> requests = events("wire-request");
		assertThat(requests.get(0)).containsEntry("clientCallId", "c-77").containsEntry("modelCallId", "m-78").containsEntry("linkedBy", "header");
		// Without the headers: the call open for the run, as before.
		assertThat(requests.get(1)).containsEntry("clientCallId", "open-call").doesNotContainKey("linkedBy");
	}

	@Test
	void forwardsAMultipartUploadIntactAndRecordsItsFieldsAndFile() throws Exception {
		// e.g. a transcription: the body must reach the provider as sent, not parsed into parts by the servlet stack.
		this.store.add(new java.util.HashMap<>(Map.of("type", "run-start", "runId", "run6", "upstreams",
				Map.of("openai", upstreamUrl()))));
		AtomicReference<byte[]> received = new AtomicReference<>();
		this.upstream.createContext("/gateway/v1/audio/transcriptions", exchange -> {
			received.set(exchange.getRequestBody().readAllBytes());
			byte[] body = "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		String boundary = "----inspector";
		byte[] audio = { 'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E', 9, 9 };
		byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n--" + boundary
				+ "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"q.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
		byte[] body = new byte[head.length + audio.length + tail.length];
		System.arraycopy(head, 0, body, 0, head.length);
		System.arraycopy(audio, 0, body, head.length, audio.length);
		System.arraycopy(tail, 0, body, head.length + audio.length, tail.length);

		HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/r/run6/openai/v1/audio/transcriptions"))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(received.get()).isEqualTo(body);
		Map<String, Object> wireRequest = events("wire-request").get(0);
		assertThat(wireRequest).containsEntry("bodyKind", "multipart");
		assertThat(wireRequest.get("body").toString()).contains("\"model\":\"whisper-1\"").contains("\"filename\":\"q.wav\"");
	}

	@Test
	void theStarterCanUploadMediaForModelsThatDontGoThroughTheProxy() throws Exception {
		byte[] png = { (byte) 0x89, 'P', 'N', 'G', 1 };
		// Under its own id, typed from the bytes when it says octet-stream.
		HttpResponse<String> put = this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/blobs/0123456789abcdef"))
			.header("Content-Type", "application/octet-stream")
			.PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(put.statusCode()).isEqualTo(200);
		assertThat(put.body()).contains("\"contentType\":\"image/png\"");
		HttpResponse<byte[]> blob = this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/blobs/0123456789abcdef")).build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(blob.headers().firstValue("content-type")).hasValue("image/png");
		assertThat(blob.body()).hasSize(5);
		// An id that isn't the starter's shape is refused; a plain POST gets a server id.
		assertThat(this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/blobs/not-hex!"))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
		HttpResponse<String> post = this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/blobs"))
			.header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(post.body()).matches(".*\"id\":\"[0-9a-f]{16}\".*");
	}

	@Test
	void redactsSecretQueryParametersButForwardsThem() throws Exception {
		this.store.add(new java.util.HashMap<>(Map.of("type", "run-start", "runId", "run3", "upstreams",
				Map.of("google", upstreamUrl()))));

		assertThat(post("/r/run3/google/v1/models/gemini:generateContent?key=AIza-secret&alt=sse").statusCode()).isEqualTo(200);

		assertThat(this.receivedQuery.get()).isEqualTo("key=AIza-secret&alt=sse");
		assertThat(events("wire-request").get(0).get("url").toString()).contains("key=…redacted&alt=sse").doesNotContain("AIza");
	}

	@Test
	void recordsBinaryResponsesBySizeAndStripsInlineBase64FromRequests() throws Exception {
		this.store.add(new java.util.HashMap<>(Map.of("type", "run-start", "runId", "run4", "upstreams",
				Map.of("openai", upstreamUrl()))));
		String image = "Q".repeat(5000);

		HttpResponse<String> response = post("/r/run4/openai/v1/audio/speech", "{\"input\":\"hi\",\"ref\":\"" + image + "\"}");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).startsWith("ID3"); // the client got the audio itself, unchanged
		Map<String, Object> wireRequest = events("wire-request").get(0);
		// Stripped to a marker; not kept, since nothing recognizable is sniffed in it.
		assertThat(wireRequest.get("body").toString()).contains("<base64 5000 chars>").doesNotContain(image);
		Map<String, Object> wireResponse = events("wire-response").get(0);
		assertThat(wireResponse).containsEntry("bodyKind", "binary").containsEntry("contentType", "audio/mpeg")
			.containsEntry("size", 7).doesNotContainKey("body");
		// The bytes are kept, and served to the UI as what they are, never as a page.
		HttpResponse<byte[]> blob = this.http.send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + this.port + "/api/blobs/" + wireResponse.get("blobId"))).build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(blob.statusCode()).isEqualTo(200);
		assertThat(blob.headers().firstValue("content-type")).hasValue("audio/mpeg");
		assertThat(blob.headers().firstValue("content-security-policy")).hasValue("sandbox");
		assertThat(blob.body()).startsWith("ID3".getBytes(StandardCharsets.UTF_8));
		assertThat(this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/blobs/nope")).build(),
				HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
	}

	@Test
	void anUnknownProviderIsRejected() throws Exception {
		assertThat(post("/r/run1/nope/v1/messages").statusCode()).isEqualTo(404);
	}

	@Test
	void anUnreachableUpstreamStillClosesTheRoundTrip() throws Exception {
		this.store.add(new java.util.HashMap<>(Map.of("type", "run-start", "runId", "run2", "upstreams",
				Map.of("anthropic", "http://127.0.0.1:1"))));

		assertThat(post("/r/run2/anthropic/v1/messages").statusCode()).isEqualTo(502);
		assertThat(events("wire-response")).singleElement().satisfies(e -> {
			assertThat(e).containsEntry("status", 502);
			assertThat(e.get("error")).isNotNull();
		});
	}

	@Test
	void pingIdentifiesTheInspector() throws Exception {
		HttpResponse<String> ping = this.http.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/ping")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(ping.body()).contains("\"name\":\"spring-ai-inspector\"");
	}

}
