package org.springaicommunity.inspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springaicommunity.inspector.InspectorAdvisor.Phase;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.util.JsonHelper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

class InspectorAdvisorTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)));

	private final AtomicReference<String> callIdSeenByModel = new AtomicReference<>();

	/** Like the auto-configured ChatClient: observed (a registry needs a handler to be real), so the calls form a tree. */
	private final ObservationRegistry registry = ObservationRegistry.create();

	InspectorAdvisorTest() {
		this.registry.observationConfig().observationHandler(context -> true);
	}

	private final ChatModel model = prompt -> {
		this.callIdSeenByModel.set(InspectorCorrelation.currentCallId());
		return new ChatResponse(List.of(new Generation(new AssistantMessage("hello"))));
	};

	private ChatClient chatClient() {
		return observed(this.model)
			.defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT), new InspectorAdvisor(this.client, Phase.MODEL))
			.build();
	}

	private ChatClient.Builder observed(ChatModel model) {
		return ChatClient.builder(model, this.registry, null, null);
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
	void mediaIsUploadedOnceAndNamedInEveryViewOfTheCall() {
		List<String> uploads = new CopyOnWriteArrayList<>();
		InspectorClient uploading = new InspectorClient("run-1", body -> this.events.add(new JsonHelper().fromJsonToMap(body)),
				(id, bytes, type) -> uploads.add(id + " " + type + " " + bytes.length));
		byte[] drawn = png(1200);
		ChatModel painter = prompt -> new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("here you go")
			.media(List.of(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(drawn).build()))
			.build())));
		ChatClient chat = observed(painter)
			.defaultAdvisors(new InspectorAdvisor(uploading, Phase.CLIENT), new InspectorAdvisor(uploading, Phase.MODEL))
			.build();

		chat.prompt().user(u -> u.text("make it night").media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png(800)))).call().content();
		uploading.awaitBackground();

		Map<String, Object> sent = firstMedia(this.events.get(0), "messages");
		assertThat(sent).containsEntry("type", "image/png").containsEntry("size", 800);
		assertThat(sent.get("blobId").toString()).matches("[0-9a-f]{16}");
		assertThat(firstMedia(this.events.get(1), "messages")).isEqualTo(sent); // the MODEL view: the same blob
		Map<String, Object> got = firstMedia(this.events.get(2), "generations");
		assertThat(got).containsEntry("type", "image/png").containsEntry("size", 1200);
		assertThat(got.get("blobId")).isNotEqualTo(sent.get("blobId"));
		assertThat(firstMedia(this.events.get(3), "generations")).isEqualTo(got); // the CLIENT view: the same blob
		assertThat(uploads).containsExactlyInAnyOrder(sent.get("blobId") + " image/png 800", got.get("blobId") + " image/png 1200");
	}

	@Test
	void mediaOfARoutedProviderIsDescribedButLeftToTheWire() {
		List<String> uploads = new CopyOnWriteArrayList<>();
		InspectorClient uploading = new InspectorClient("run-1", body -> this.events.add(new JsonHelper().fromJsonToMap(body)),
				(id, bytes, type) -> uploads.add(id));
		// ChatOptions.builder() builds a DefaultChatOptions: the provider "default", routed here.
		ChatClient chat = observed(this.model)
			.defaultAdvisors(new InspectorAdvisor(uploading, Phase.CLIENT, null, java.util.Set.of("default")),
					new InspectorAdvisor(uploading, Phase.MODEL, null, java.util.Set.of("default")))
			.build();

		chat.prompt()
			.user(u -> u.text("what is this").media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png(800))))
			.options(ChatOptions.builder())
			.call()
			.content();
		uploading.awaitBackground();

		assertThat(firstMedia(this.events.get(0), "messages")).containsEntry("type", "image/png")
			.containsEntry("size", 800)
			.doesNotContainKey("blobId");
		assertThat(uploads).isEmpty();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> firstMedia(Map<String, Object> event, String list) {
		List<Map<String, Object>> messages = (List<Map<String, Object>>) event.get(list);
		return ((List<Map<String, Object>>) messages.get(0).get("media")).get(0);
	}

	private static byte[] png(int size) {
		byte[] bytes = new byte[size];
		bytes[0] = (byte) 0x89;
		bytes[1] = 'P';
		bytes[2] = 'N';
		bytes[3] = 'G';
		return bytes;
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
		observed(thinking)
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
		assertThat(InspectorCorrelation.currentCallId()).isNull();
	}

	@Test
	void aCallNestedDeeperThanAToolIsNotAttributedToIt() {
		// A tool of the outer call runs a sub-agent, whose own model calls one more ChatClient (not a tool).
		ChatClient innermost = chatClient();
		ChatClient subAgent = observed(prompt -> {
			innermost.prompt("rewrite").call().content();
			return new ChatResponse(List.of(new Generation(new AssistantMessage("sub"))));
		}).defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT)).build();
		observed(prompt -> {
			// As Spring AI's tool loop does: a tool observation, nested in the call, tagged by the handler.
			Observation tool = Observation.createNotStarted("tool", this.registry).start();
			tool.getContext().put(InspectorCorrelation.TOOL_ID, "task-1");
			try (Observation.Scope scope = tool.openScope()) {
				subAgent.prompt("sub-task").call().content();
			}
			finally {
				tool.stop();
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}).defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT)).build().prompt("hi").call().content();

		List<Map<String, Object>> requests = this.events.stream().filter(e -> "client-request".equals(e.get("type"))).toList();
		assertThat(requests).extracting(r -> r.get("parentToolId")).containsExactly(null, "task-1", null);
		assertThat(requests.get(1).get("parentId")).isEqualTo(requests.get(0).get("callId"));
		assertThat(requests.get(2).get("parentId")).isEqualTo(requests.get(1).get("callId"));
	}

	@Test
	void anUnobservedClientNestsUnderTheObservedCallAroundItWithoutTaggingIt() {
		// A sub-agent built with ChatClient.builder(chatModel), called twice from an observed tool:
		// both nest under the outer call and its tool, and neither nests under the other (the
		// outer observations are walked, never tagged).
		ChatClient unobserved = ChatClient.builder(this.model)
			.defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT), new InspectorAdvisor(this.client, Phase.MODEL))
			.build();
		observed(prompt -> {
			Observation tool = Observation.createNotStarted("tool", this.registry).start();
			tool.getContext().put(InspectorCorrelation.TOOL_ID, "task-1");
			try (Observation.Scope scope = tool.openScope()) {
				unobserved.prompt("first").call().content();
				unobserved.prompt("second").call().content();
			}
			finally {
				tool.stop();
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}).defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT)).build().prompt("hi").call().content();

		List<Map<String, Object>> requests = this.events.stream().filter(e -> "client-request".equals(e.get("type"))).toList();
		assertThat(requests).hasSize(3);
		assertThat(requests.get(1)).containsEntry("parentId", requests.get(0).get("callId")).containsEntry("parentToolId", "task-1");
		assertThat(requests.get(2)).containsEntry("parentId", requests.get(0).get("callId")).containsEntry("parentToolId", "task-1");
	}

	@Test
	void withoutObservationsCallsAreReportedButNotNested() {
		// ChatClient.builder(chatModel) uses ObservationRegistry.NOOP: no tree, no attribution.
		ChatClient inner = ChatClient.builder(this.model)
			.defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT), new InspectorAdvisor(this.client, Phase.MODEL))
			.build();
		ChatClient.builder((ChatModel) prompt -> {
			inner.prompt("nested").call().content();
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
		}).defaultAdvisors(new InspectorAdvisor(this.client, Phase.CLIENT)).build().prompt("hi").call().content();

		List<Map<String, Object>> requests = this.events.stream().filter(e -> "client-request".equals(e.get("type"))).toList();
		assertThat(requests).hasSize(2);
		assertThat(requests.get(1).get("parentId")).isNull();
		assertThat(this.callIdSeenByModel.get()).isNull();
		// The MODEL phase still finds its call through the advisor context.
		Map<String, Object> modelRequest = this.events.stream().filter(e -> "model-request".equals(e.get("type"))).findFirst().orElseThrow();
		assertThat(modelRequest.get("parentId")).isEqualTo(requests.get(1).get("callId"));
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
