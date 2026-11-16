package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorMemoryReaderTest {

	@Test
	@SuppressWarnings("unchecked")
	void readsTheSessionLogAndMarksWhatACompactionMovedOutOfTheActiveView() {
		SessionService sessionService = DefaultSessionService.builder()
			.sessionRepository(InMemorySessionRepository.builder().build())
			.build();
		sessionService.create(CreateSessionRequest.builder().id("s-1").build());
		sessionService.appendMessage("s-1", new UserMessage("first question"));
		sessionService.appendMessage("s-1", new AssistantMessage("first answer"));
		sessionService.appendMessage("s-1", new UserMessage("second question"));
		sessionService.appendMessage("s-1", new AssistantMessage("second answer"));
		// Keep only the last turn in the active view: appends a boundary, the log keeps all
		sessionService.compact("s-1", new TurnCountTrigger(1), WindowCompactionStrategy.builder().lastTurns(1).build());

		List<Map<String, Object>> stores = new InspectorMemoryReader(List.of()).snapshot(
				List.of(SessionMemoryAdvisor.builder(sessionService).build()),
				Map.of(InspectorMemoryReader.CONVERSATION_ID, "s-1"));

		assertThat(stores).singleElement().satisfies(store -> {
			assertThat(store).containsEntry("kind", "session").containsEntry("id", "s-1");
			var items = (List<Map<String, Object>>) store.get("items");
			// the compaction boundary carries no message and is not listed
			assertThat(items).extracting(item -> item.get("text"))
				.containsExactly("first question", "first answer", "second question", "second answer");
			assertThat(items).extracting(item -> item.get("archived")).containsExactly(true, true, false, false);
		});
	}

}
