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
		client.awaitBackground();

		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).contains("\"type\":\"y\"").contains("\"runId\":\"run-1\"").contains("\"k\":\"v\"");
	}

	@Test
	void everyEventCarriesTheFormatVersionAndTheEnvelope() {
		List<String> sent = new ArrayList<>();
		InspectorClient client = new InspectorClient("run-1", sent::add);
		client.send("x", () -> Map.of("k", "v"));
		client.awaitBackground();

		Map<String, Object> event = new org.springframework.ai.util.JsonHelper().fromJsonToMap(sent.get(0));
		assertThat(event).containsEntry("v", InspectorClient.EVENTS_VERSION)
			.containsEntry("type", "x")
			.containsEntry("runId", "run-1")
			.containsEntry("k", "v")
			.containsKey("ts");
		assertThat(InspectorClient.EVENTS_VERSION).isEqualTo(1); // bump EVENTS.md and the server/UI constants with it
	}

	@Test
	void eventsAndUploadsLeaveInOrderFromTheBackgroundThreadWithoutBlockingTheCaller() throws Exception {
		List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
		java.util.concurrent.CountDownLatch inspectorAnswers = new java.util.concurrent.CountDownLatch(1);
		InspectorClient client = new InspectorClient("run-1", body -> order.add(body.contains("\"type\":\"a\"") ? "a" : "b"),
				(id, bytes, type) -> {
					try {
						inspectorAnswers.await(); // a slow inspector
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					order.add("upload");
				});

		InspectorClient.Media media = client.sendBlob(new byte[] { 1, 2, 3 }, "image/png");
		client.send("a", () -> Map.of("blobId", media.id())); // names the blob: must follow it
		client.send("b", Map::of);
		assertThat(order).isEmpty(); // the caller went on while the inspector hadn't answered

		inspectorAnswers.countDown();
		client.awaitBackground();
		assertThat(order).containsExactly("upload", "a", "b");
	}

	@Test
	void aBlobSentWhileAnEventIsBuiltInTheBackgroundGoesOutBeforeThatEvent() {
		List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
		InspectorClient client = new InspectorClient("run-1", body -> order.add("event"), (id, bytes, type) -> order.add("upload"));

		client.sendLater("model-call", () -> Map.of("blobId", client.sendBlob(new byte[] { 1, 2 }, "image/png").id()));
		client.awaitBackground();

		assertThat(order).containsExactly("upload", "event");
	}

	@Test
	void afterShutdownNothingIsThrownAtTheCaller() {
		List<String> sent = new ArrayList<>();
		InspectorClient client = new InspectorClient("run-1", sent::add, (id, bytes, type) -> {
		});
		client.shutdown();

		assertThatNoException().isThrownBy(() -> {
			client.sendBlob(new byte[] { 1 }, "image/png");
			client.send("x", Map::of);
			client.sendLater("y", Map::of);
		});
		assertThat(sent).isEmpty(); // the application is stopping: dropped, not thrown
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
		client.awaitBackground();
		assertThat(sent).hasSize(1);

		// Still paused: nothing is posted, nothing is re-announced.
		client.send("client-response", () -> Map.of("callId", "c1"));
		client.awaitBackground();
		assertThat(sent).hasSize(1);

		// Back after the pause: the run is introduced again before the next event.
		java.lang.reflect.Field pausedUntil = InspectorClient.class.getDeclaredField("pausedUntil");
		pausedUntil.setAccessible(true);
		pausedUntil.set(client, 0L);
		client.send("client-request", () -> Map.of("callId", "c2"));
		client.awaitBackground();

		assertThat(sent).hasSize(3);
		assertThat(sent.get(1)).contains("\"type\":\"run-start\"").contains("\"app\":\"demo\"").contains("\"reannounce\":true");
		assertThat(sent.get(2)).contains("\"callId\":\"c2\"");
		// Once is enough: the next event goes alone.
		client.send("client-response", () -> Map.of("callId", "c2"));
		client.awaitBackground();
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
		client.awaitBackground(); // the failed post pauses publishing
		client.send("x", () -> {
			built.incrementAndGet();
			return Map.of();
		});

		// The second event isn't even built while publishing is paused.
		assertThat(built).hasValue(1);
	}

}
