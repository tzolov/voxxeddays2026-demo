package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springaicommunity.inspector.InspectorAdvisor.Phase;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.util.JsonHelper;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorAdvisorTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)));

	private final AtomicReference<String> callIdSeenByModel = new AtomicReference<>();

	private final ChatModel model = prompt -> {
		this.callIdSeenByModel.set(InspectorAdvisor.currentCallId());
		return new ChatResponse(List.of(new Generation(new AssistantMessage("hello"))));
	};

	private ChatClient chatClient() {
		return ChatClient.builder(this.model)
			.defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT), new InspectorAdvisor(this.client, Phase.MODEL))
			.build();
	}

	@Test
	void reportsTheCallAndTheModelPhaseAsATree() {
		String answer = chatClient().prompt("hi").call().content();

		assertThat(answer).isEqualTo("hello");
		assertThat(this.events).extracting(e -> e.get("type"))
			.containsExactly("client-request", "model-request", "model-response", "client-response");
		Map<String, Object> clientRequest = this.events.get(0);
		assertThat(this.events.get(1).get("parentId")).isEqualTo(clientRequest.get("callId"));
		assertThat(clientRequest.get("runId")).isEqualTo("run-1");
		assertThat(clientRequest.get("messages").toString()).contains("hi");
		assertThat(this.events.get(3).get("generations").toString()).contains("hello");
	}

	@Test
	void theCallIdIsVisibleToCodeRunningInsideTheCall() {
		chatClient().prompt("hi").call().content();

		assertThat(this.callIdSeenByModel.get()).isEqualTo(this.events.get(0).get("callId"));
		assertThat(InspectorAdvisor.currentCallId()).isNull();
	}

	@Test
	void aFailureWhileDescribingTheCallNeverBreaksTheCall() {
		Object hostile = new Object() {
			@Override
			public String toString() {
				throw new IllegalStateException("boom");
			}
		};

		String answer = chatClient().prompt("hi").advisors(a -> a.param("hostile", hostile)).call().content();

		assertThat(answer).isEqualTo("hello");
		// Events that would carry the hostile context value are dropped, nothing else.
		assertThat(this.events).noneMatch(e -> String.valueOf(e.get("context")).contains("hostile"));
	}

}
