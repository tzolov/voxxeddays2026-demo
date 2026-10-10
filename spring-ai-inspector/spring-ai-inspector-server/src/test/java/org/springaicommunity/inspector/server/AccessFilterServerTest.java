package org.springaicommunity.inspector.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Host check through the real server: a raw socket, because the JDK HttpClient refuses
 * to send a Host header of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessFilterServerTest {

	@LocalServerPort
	int port;

	private String statusLine(String host) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", this.port)) {
			OutputStream out = socket.getOutputStream();
			out.write(("GET /api/ping HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
		}
	}

	@Test
	void tomcatHandsTheHostHeaderToTheFilter() throws Exception {
		assertThat(statusLine("localhost:" + this.port)).contains("200");
		assertThat(statusLine("127.0.0.1:" + this.port)).contains("200");
		assertThat(statusLine("[::1]:" + this.port)).contains("200");
		assertThat(statusLine("evil.example")).contains("403");
		assertThat(statusLine("evil.example:" + this.port)).contains("403");
	}

}
