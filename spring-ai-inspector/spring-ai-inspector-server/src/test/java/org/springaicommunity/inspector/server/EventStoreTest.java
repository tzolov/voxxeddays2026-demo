package org.springaicommunity.inspector.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventStoreTest {

	private final EventStore store = new EventStore();

	private Map<String, Object> add(String type, String runId, Object... keyValues) {
		Map<String, Object> event = new HashMap<>();
		event.put("type", type);
		event.put("runId", runId);
		for (int i = 0; i < keyValues.length; i += 2) {
			event.put((String) keyValues[i], keyValues[i + 1]);
		}
		this.store.add(event);
		return event;
	}

	@Test
	void tracksTheOpenCallsWireRequestsAreAttributedTo() {
		add("client-request", "r1", "callId", "c1");
		add("model-request", "r1", "callId", "m1", "parentId", "c1");

		assertThat(this.store.openCalls("r1")).containsEntry("clientCallId", "c1").containsEntry("modelCallId", "m1");

		add("model-response", "r1", "callId", "m1");
		add("client-response", "r1", "callId", "c1");

		assertThat(this.store.openCalls("r1")).containsEntry("clientCallId", null).containsEntry("modelCallId", null);
	}

	@Test
	void attributesSearchesFromOtherThreadsToTheOpenCall() {
		add("client-request", "r1", "callId", "c1");

		assertThat(add("vector-search", "r1", "searchId", "s1")).containsEntry("clientCallId", "c1");
		assertThat(add("vector-start", "r1", "opId", "o1", "op", "search")).containsEntry("clientCallId", "c1");
		// Adds and embedding calls carry their call from the thread that made them: without one (e.g.
		// ingesting in the background) they stay at run level, not under the open chat call.
		assertThat(add("vector-add", "r1", "count", 3)).doesNotContainKey("clientCallId");
		assertThat(add("vector-start", "r1", "opId", "o2", "op", "add")).doesNotContainKey("clientCallId");
		assertThat(add("embedding-call", "r1", "embeddingId", "e1")).doesNotContainKey("clientCallId");
	}

	@Test
	void aCallStartedWhileAToolRunsNestsUnderTheToolsCall() {
		// e.g. a background sub-agent started by the Task tool, on another thread
		add("client-request", "r1", "callId", "c1");
		add("tool-start", "r1", "toolId", "t1", "clientCallId", "c1");

		Map<String, Object> subAgent = add("client-request", "r1", "callId", "c2");

		assertThat(subAgent).containsEntry("parentId", "c1")
			.containsEntry("parentToolId", "t1")
			.containsEntry("parentInferred", true);
	}

	@Test
	void concurrentTopLevelCallsInAServerAreNotNested() {
		add("client-request", "r1", "callId", "c1"); // request A, no tool running

		Map<String, Object> requestB = add("client-request", "r1", "callId", "c2");

		assertThat(requestB.get("parentId")).isNull();
		assertThat(requestB).doesNotContainKey("linkedFrom");
	}

	@Test
	void aCallInAnotherRunIsLinkedToTheToolThatIsRunning() {
		add("client-request", "caller", "callId", "c1");
		add("tool-start", "caller", "toolId", "task", "clientCallId", "c1");

		Map<String, Object> remote = add("client-request", "remote", "callId", "x1");

		assertThat(remote.get("linkedFrom")).isEqualTo(Map.of("runId", "caller", "toolId", "task", "clientCallId", "c1"));
	}

	@Test
	void aToolWhoseEndWasNeverSeenDoesNotAttractLaterLinks() {
		add("client-request", "caller", "callId", "c1");
		add("tool-start", "caller", "toolId", "lost", "clientCallId", "c1"); // no tool-end
		add("client-response", "caller", "callId", "c1");

		Map<String, Object> unrelated = add("client-request", "other", "callId", "x1");

		assertThat(unrelated).doesNotContainKey("linkedFrom");
	}

	@Test
	void importedEventsKeepTheirOwnLinks() {
		add("client-request", "caller", "callId", "c1");
		add("tool-start", "caller", "toolId", "task", "clientCallId", "c1");

		this.store.importRun(List.of(Map.of("type", "client-request", "runId", "old", "callId", "z1")), "file.json");

		Map<String, Object> imported = this.store.events().get(this.store.events().size() - 1);
		assertThat(imported).doesNotContainKey("linkedFrom");
		assertThat((String) imported.get("runId")).startsWith("imp-");
	}

	@Test
	void remembersEachRunsOriginalUpstreams() {
		add("run-start", "r1", "upstreams", Map.of("anthropic", "https://gateway.example.com"));

		assertThat(this.store.upstream("r1", "anthropic")).isEqualTo("https://gateway.example.com");
		assertThat(this.store.upstream("r1", "openai")).isNull();
		assertThat(this.store.upstream("r2", "anthropic")).isNull();
	}

	@Test
	void aLaterRunStartCannotRedirectARunsTraffic() {
		add("run-start", "r1", "upstreams", Map.of("anthropic", "https://api.anthropic.com"));

		add("run-start", "r1", "upstreams", Map.of("anthropic", "https://attacker.example", "openai", "https://x.example"));

		assertThat(this.store.upstream("r1", "anthropic")).isEqualTo("https://api.anthropic.com");
		assertThat(this.store.upstream("r1", "openai")).isNull();
	}

	@Test
	void onlyHttpUpstreamsAreAccepted() {
		add("run-start", "r1", "upstreams", Map.of("anthropic", "file:///etc", "ollama", "http://localhost:11434"));

		assertThat(this.store.upstream("r1", "anthropic")).isNull();
		assertThat(this.store.upstream("r1", "ollama")).isEqualTo("http://localhost:11434");
	}

	@Test
	void importedRunsRegisterNothing() {
		this.store.importRun(List.of(
				Map.of("type", "run-start", "runId", "old", "upstreams", Map.of("anthropic", "https://attacker.example")),
				Map.of("type", "client-request", "runId", "old", "callId", "z1"),
				Map.of("type", "tool-start", "runId", "old", "toolId", "t1", "clientCallId", "z1")), "file.json");
		String imported = (String) this.store.events().get(0).get("runId");

		assertThat(this.store.upstream(imported, "anthropic")).isNull();
		assertThat(this.store.openCalls(imported)).containsEntry("clientCallId", null);
		// The imported run's open tool must not attract links from live runs either.
		assertThat(add("client-request", "live", "callId", "c1")).doesNotContainKey("linkedFrom");
	}

	@Test
	void aMalformedClientResponseIsStoredNotThrown() {
		add("client-request", "r1", "callId", "c1");
		add("tool-start", "r1", "toolId", "t1", "clientCallId", "c1");

		add("client-response", "r1"); // no callId

		assertThat(this.store.events()).hasSize(3);
	}

	@Test
	void dropsTheOldestEventsBeyondTheByteBudget() {
		EventStore small = new EventStore(new InspectorProperties(Map.of(), null, null, null, 512_000, 10_000L, 1L));
		for (int i = 0; i < 20; i++) {
			Map<String, Object> event = new HashMap<>();
			event.put("type", "wire-response");
			event.put("runId", "r1");
			event.put("n", i);
			event.put("body", "x".repeat(1000)); // ~2.5 KB each with overhead
			small.add(event);
		}

		List<Map<String, Object>> kept = small.events();
		assertThat(kept.size()).isBetween(1, 5);
		assertThat(kept.get(kept.size() - 1)).containsEntry("n", 19);
	}

}
