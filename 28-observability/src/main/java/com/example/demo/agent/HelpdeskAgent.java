package com.example.demo.agent;

import java.util.Map;
import java.util.stream.Collectors;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientBuilderConfigurer;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The helpdesk, once per provider. Both clients share the system prompt, the tools and
 * the FAQ retrieval, so the "by model" panels compare like with like.
 */
@Component
public class HelpdeskAgent {

	public static final String ANTHROPIC = "anthropic";

	public static final String OPENAI = "openai";

	private static final String SYSTEM = """
			You are the helpdesk assistant of Voxxed Days Amsterdam 2026.
			Use the tools for anything about sessions, rooms, times and shuttles, and the provided
			context for practical questions about the venue. If neither answers the question, say so
			and point to the registration desk. Answer in two or three short sentences.
			""";

	public record Answer(String provider, String question, String answer) {
	}

	private final Map<String, ChatClient> clients;

	private final Chaos chaos;

	public HelpdeskAgent(AnthropicChatModel anthropic, OpenAiChatModel openai, VectorStore faq, ConferenceTools tools,
			Chaos chaos, ObjectProvider<ObservationRegistry> observationRegistry,
			ChatClientBuilderConfigurer configurer, ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisor) {
		this.chaos = chaos;
		this.clients = Map.of( //
				ANTHROPIC, build(anthropic, faq, tools, observationRegistry, configurer, toolCallingAdvisor), //
				OPENAI, build(openai, faq, tools, observationRegistry, configurer, toolCallingAdvisor));
	}

	/**
	 * Two clients need two builders, so they are built by hand, with the same pieces the
	 * auto-configured builder gets: the application's {@link ObservationRegistry} (or the
	 * ChatClient and advisor observations, and the tool runs, would be no-ops), its tool
	 * calling advisor (the configured {@code ToolCallingManager}), and its configurer,
	 * which applies the registered customizers, among them the inspector's advisors when
	 * it runs. The auto-configured prototype {@code ChatClient.Builder} stays registered
	 * but is not injectable with two chat models; nothing here asks for it.
	 */
	private ChatClient build(ChatModel model, VectorStore vectorStore, ConferenceTools tools,
			ObjectProvider<ObservationRegistry> observationRegistry, ChatClientBuilderConfigurer configurer,
			ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisor) {

		ChatClient.Builder builder = ChatClient.builder(model,
				observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP), null, null,
				toolCallingAdvisor.getIfAvailable());

		configurer.configure(builder);

		return builder.defaultSystem(SYSTEM)
			.defaultTools(tools)
			.defaultAdvisors(new ContentLogAdvisor(this.chaos), new ChaosLatencyAdvisor(this.chaos),
					// Ahead of the tool-calling advisor, or the FAQ would be retrieved again on
					// every round of the tool loop.
					QuestionAnswerAdvisor.builder(vectorStore)
						.order(Ordered.HIGHEST_PRECEDENCE + 200)
						.searchRequest(SearchRequest.builder().topK(3).similarityThreshold(0.3).build())
						.build())
			.build();
	}

	public Answer ask(String question, String provider) {
		if (!this.clients.containsKey(provider)) {
			throw new IllegalArgumentException("unknown provider '" + provider + "', expected one of " + this.clients.keySet());
		}
		String p = provider;
		ChatClient.ChatClientRequestSpec request = this.clients.get(p).prompt().user(question);
		String badModel = this.chaos.badModelFor(p);
		if (badModel != null) {
			request = request.options(ChatOptions.builder().model(badModel));
		}
		return new Answer(p, question, text(request.call().chatResponse()));
	}

	/**
	 * All the text the model returned, not just the first generation: an Anthropic answer
	 * that starts with an empty text block would otherwise read as empty.
	 */
	static String text(ChatResponse response) {
		if (response == null) {
			return "";
		}
		return response.getResults()
			.stream()
			.map(g -> g.getOutput().getText())
			.filter(StringUtils::hasText)
			.collect(Collectors.joining("\n"));
	}

}
