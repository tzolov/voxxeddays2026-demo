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
