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

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void startUpstream() throws IOException {
		this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.upstream.createContext("/", exchange -> {
			this.receivedPath.set(exchange.getRequestURI().getPath());
			this.receivedKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
			byte[] body = "{\"stop_reason\":\"end_turn\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
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
		return this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("x-api-key", "sk-ant-api03-secret-value")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"claude\"}"))
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
		// Recorded with the key redacted.
		Map<String, Object> wireRequest = events("wire-request").get(0);
		assertThat(wireRequest.get("body")).isEqualTo("{\"model\":\"claude\"}");
		assertThat(wireRequest.get("headers").toString()).contains("redacted").doesNotContain("secret-value");
		Map<String, Object> wireResponse = events("wire-response").get(0);
		assertThat(wireResponse).containsEntry("status", 200).containsEntry("wireId", wireRequest.get("wireId"));
		assertThat(wireResponse.get("body").toString()).contains("end_turn");
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
