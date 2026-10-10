package org.springaicommunity.inspector.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** The live stream through the real server: history first, then live events, each once, in order. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventStreamTest {

	@LocalServerPort
	int port;

	@Autowired
	EventStore store;

	@AfterEach
	void clear() {
		this.store.clear();
	}

	private void add(String id) {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "run-start");
		event.put("runId", id);
		this.store.add(event);
	}

	@Test
	void aNewTabGetsTheHistoryThenTheLiveEventsWithoutDuplicates() throws Exception {
		for (int i = 0; i < 300; i++) {
			add("history-" + i);
		}
		HttpResponse<Stream<String>> stream = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/api/stream")).build(),
				HttpResponse.BodyHandlers.ofLines());
		// Live events while (and after) the history is being replayed.
		for (int i = 0; i < 20; i++) {
			add("live-" + i);
		}

		List<String> runIds = new ArrayList<>();
		try (Stream<String> lines = stream.body()) { // closing the stream closes the connection
			for (String line : (Iterable<String>) lines::iterator) {
				if (line.startsWith("data:")) {
					runIds.add(line.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1"));
				}
				if (runIds.size() == 320) {
					break;
				}
			}
		}

		assertThat(runIds).hasSize(320).doesNotHaveDuplicates();
		assertThat(runIds.subList(0, 300)).first().isEqualTo("history-0");
		assertThat(runIds.get(319)).isEqualTo("live-19");
	}

}
