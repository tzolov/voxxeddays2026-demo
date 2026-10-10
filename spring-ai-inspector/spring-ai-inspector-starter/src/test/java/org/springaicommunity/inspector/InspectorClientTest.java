package org.springaicommunity.inspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class InspectorClientTest {

	@Test
	void anEventThatFailsToBuildIsDropped() {
		List<String> sent = new ArrayList<>();
		InspectorClient client = new InspectorClient("run-1", sent::add);

		assertThatNoException().isThrownBy(() -> client.send("x", () -> {
			throw new IllegalStateException("boom");
		}));
		client.send("y", () -> Map.of("k", "v"));

		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).contains("\"type\":\"y\"").contains("\"runId\":\"run-1\"").contains("\"k\":\"v\"");
	}

	@Test
	void everyEventCarriesTheFormatVersionAndTheEnvelope() {
		List<String> sent = new ArrayList<>();
		new InspectorClient("run-1", sent::add).send("x", () -> Map.of("k", "v"));

		Map<String, Object> event = new org.springframework.ai.util.JsonHelper().fromJsonToMap(sent.get(0));
		assertThat(event).containsEntry("v", InspectorClient.EVENTS_VERSION)
			.containsEntry("type", "x")
			.containsEntry("runId", "run-1")
			.containsEntry("k", "v")
			.containsKey("ts");
		assertThat(InspectorClient.EVENTS_VERSION).isEqualTo(1); // bump EVENTS.md and the server/UI constants with it
	}

	@Test
	void anEventQueuesBehindAnUploadButNotBehindOtherBackgroundWork() throws Exception {
		List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
		java.util.concurrent.CountDownLatch uploadMayFinish = new java.util.concurrent.CountDownLatch(1);
		InspectorClient client = new InspectorClient("run-1", body -> order.add(body.contains("\"type\":\"a\"") ? "a"
				: body.contains("\"type\":\"b\"") ? "b" : "c"), (id, bytes, type) -> {
					try {
						uploadMayFinish.await();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					order.add("upload");
				});

		client.sendAsync("a", Map::of); // async work pending: a synchronous event still goes out at once
		client.send("b", Map::of);
		client.awaitBackground();
		assertThat(order).containsExactly("a", "b").as("'b' was posted inline, 'a' from the background thread");
		order.clear();

		InspectorClient.Media media = client.sendBlob(new byte[] { 1, 2, 3 }, "image/png");
		client.send("c", () -> Map.of("blobId", media.id())); // names the blob: waits for it
		assertThat(order).isEmpty();
		uploadMayFinish.countDown();
		client.awaitBackground();
		assertThat(order).containsExactly("upload", "c");
	}

	@Test
	void afterShutdownNothingIsThrownAtTheCaller() {
		List<String> sent = new ArrayList<>();
		InspectorClient client = new InspectorClient("run-1", sent::add, (id, bytes, type) -> {
		});
		client.shutdown();

		assertThatNoException().isThrownBy(() -> {
			assertThat(client.sendBlob(new byte[] { 1 }, "image/png")).isNull();
			client.sendAsync("x", Map::of);
			client.send("y", Map::of);
		});
		assertThat(sent).hasSize(1); // the synchronous event still went out on the caller's thread
	}

	@Test
	void theRunIsAnnouncedAgainAfterTheInspectorWasUnreachable() throws Exception {
		List<String> sent = new ArrayList<>();
		AtomicInteger posts = new AtomicInteger();
		InspectorClient client = new InspectorClient("run-1", body -> {
			if (posts.incrementAndGet() == 2) {
				throw new IllegalStateException("connection refused"); // the inspector goes down after the announcement
			}
			sent.add(body);
		});

		client.announce("run-start", () -> Map.of("app", "demo"));
		client.send("client-request", () -> Map.of("callId", "c1")); // fails: the inspector is down
		assertThat(sent).hasSize(1);

		// Still paused: nothing is posted, nothing is re-announced.
		client.send("client-response", () -> Map.of("callId", "c1"));
		assertThat(sent).hasSize(1);

		// Back after the pause: the run is introduced again before the next event.
		java.lang.reflect.Field pausedUntil = InspectorClient.class.getDeclaredField("pausedUntil");
		pausedUntil.setAccessible(true);
		pausedUntil.set(client, 0L);
		client.send("client-request", () -> Map.of("callId", "c2"));

		assertThat(sent).hasSize(3);
		assertThat(sent.get(1)).contains("\"type\":\"run-start\"").contains("\"app\":\"demo\"").contains("\"reannounce\":true");
		assertThat(sent.get(2)).contains("\"callId\":\"c2\"");
		// Once is enough: the next event goes alone.
		client.send("client-response", () -> Map.of("callId", "c2"));
		assertThat(sent).hasSize(4);
	}

	@Test
	void anUnreachableInspectorPausesPublishingInsteadOfFailingTheCaller() {
		InspectorClient client = new InspectorClient("http://localhost:1", "run-1");
		AtomicInteger built = new AtomicInteger();

		assertThatNoException().isThrownBy(() -> client.send("x", () -> {
			built.incrementAndGet();
			return Map.of();
		}));
		client.send("x", () -> {
			built.incrementAndGet();
			return Map.of();
		});

		// The second event isn't even built while publishing is paused.
		assertThat(built).hasValue(1);
	}

}
