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
	void secretLookingContextKeysAreRedacted() {
		chatClient().prompt("hi")
			.advisors(a -> a.param("tenant", "acme").param("apiKey", "sk-live-1").param("access_token", "t0k").param("tokens", 12))
			.call()
			.content();

		Map<String, Object> context = (Map<String, Object>) this.events.get(0).get("context");
		assertThat(context).containsEntry("tenant", "acme")
			.containsEntry("apiKey", "…redacted")
			.containsEntry("access_token", "…redacted")
			.containsEntry("tokens", "12"); // a count, not a credential
	}

	@Test
	void marksThinkingGenerations() {
		ChatModel thinking = prompt -> new ChatResponse(List.of(
				new Generation(AssistantMessage.builder().content("").properties(Map.of("signature", "sig")).build()),
				new Generation(new AssistantMessage("hello"))));
		ChatClient.builder(thinking)
			.defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT))
			.build()
			.prompt("hi")
			.call()
			.content();

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> generations = (List<Map<String, Object>>) this.events.get(1).get("generations");
		assertThat(generations).extracting(g -> g.get("thinking")).containsExactly("signed", null);
	}

	@Test
	void theCallIdIsVisibleToCodeRunningInsideTheCall() {
		chatClient().prompt("hi").call().content();

		assertThat(this.callIdSeenByModel.get()).isEqualTo(this.events.get(0).get("callId"));
		assertThat(InspectorAdvisor.currentCallId()).isNull();
	}

	@Test
	void aCallNestedDeeperThanAToolIsNotAttributedToIt() {
		// A tool of the outer call runs a sub-agent, whose own model calls one more ChatClient (not a tool).
		ChatClient innermost = chatClient();
		ChatClient subAgent = ChatClient.builder((ChatModel) prompt -> {
			innermost.prompt("rewrite").call().content();
			return new ChatResponse(List.of(new Generation(new AssistantMessage("sub"))));
		}).defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT)).build();
		ChatClient.builder((ChatModel) prompt -> {
			var open = InspectorAdvisor.toolStarted("task-1");
			try {
				subAgent.prompt("sub-task").call().content();
			}
			finally {
				open.removeIf(t -> t.toolId().equals("task-1"));
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}).defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT)).build().prompt("hi").call().content();

		List<Map<String, Object>> requests = this.events.stream().filter(e -> "client-request".equals(e.get("type"))).toList();
		assertThat(requests).extracting(r -> r.get("parentToolId")).containsExactly(null, "task-1", null);
		assertThat(requests.get(2).get("parentId")).isEqualTo(requests.get(1).get("callId"));
	}

	@Test
	void aToolWhoseEndWasNeverObservedIsForgottenWhenItsCallEnds() {
		ChatClient.builder((ChatModel) prompt -> {
			InspectorAdvisor.toolStarted("lost"); // e.g. an Error thrown before the observation stopped
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}).defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT)).build().prompt("hi").call().content();

		assertThat(InspectorAdvisor.parentToolId((String) this.events.get(0).get("callId"))).isNull();
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
