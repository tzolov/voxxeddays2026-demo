package org.springaicommunity.inspector.server;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogTest {

	private static Map<String, Object> event(String body) {
		Map<String, Object> event = new HashMap<>();
		event.put("body", body);
		return event;
	}

	@Test
	void numbersEventsAndKeepsNumberingAfterAClear() {
		EventLog log = new EventLog(1_000_000);
		Map<String, Object> first = event("a");
		log.append(first);
		log.clear();
		Map<String, Object> second = event("b");
		log.append(second);

		// A browser drops what it already got by seq: a restart of the numbering would make it drop new events.
		assertThat(first).containsEntry("seq", 1L);
		assertThat(second).containsEntry("seq", 2L);
		assertThat(log.events()).containsExactly(second);
	}

	@Test
	void dropsTheOldestBeyondTheByteBudgetButAlwaysKeepsTheLatest() {
		EventLog log = new EventLog(2_000); // each event: 512 overhead + 2 bytes a char
		log.append(event("x".repeat(400))); // 1312
		log.append(event("y".repeat(400))); // 1312: over budget together, the first goes
		assertThat(log.events()).extracting(e -> e.get("body")).containsExactly("y".repeat(400));

		Map<String, Object> huge = event("z".repeat(5_000)); // alone over the budget: still kept
		log.append(huge);
		assertThat(log.events()).containsExactly(huge);
	}

}
