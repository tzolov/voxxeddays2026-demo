package com.example.demo;

import java.util.Random;

import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.advisor.JevSelfRefineAdvisor;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Model-as-a-judge with TypeSafe Jev doing the judging.
 *
 * The weather tool deliberately returns an absurd temperature part of the time. A judge
 * model asked for an overall rating tends to wave that through, because the answer reads
 * fluently and is on topic. The {@code is_plausible} noul does not: it is a single
 * question with a single job, so the advisor sees the defect, feeds it back and the model
 * tries again.
 */
@SpringBootApplication
public class ModelJudgeDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(ModelJudgeDemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(AnthropicChatModel mainChatModel, TypeSafeClient typeSafeClient) {
		return args -> { // @formatter:off

			ChatClient chatClient = ChatClient.builder(mainChatModel)

				.defaultTools(new MyTools())

				// BEFORE_TOOLS_ORDER places the advisor before the tool loop, so a rejected
				// answer is retried with a fresh call to the weather tool, and the tool calls
				// of each attempt are recorded for the judge as `tool_calls`.
				.defaultAdvisors(JevSelfRefineAdvisor.builder()
					.order(JevSelfRefineAdvisor.BEFORE_TOOLS_ORDER)
					.judge(createWeatherJudge(typeSafeClient))
					.maxRepeatAttempts(10)
					.build())

				.defaultAdvisors(MyLoggingAdvisor.builder()
					.order(Ordered.HIGHEST_PRECEDENCE + 150)
					.build())

				.build();

			var answer = chatClient
				.prompt("What is current weather in Paris?")
				.call()
				.content();

			System.out.println("\n -------------------------------- \n" + answer);

		}; // @formatter:on
	}

	static class MyTools {

		final int[] temperatures = { -125, 15, -255 };

		private final Random random = new Random();

		@Tool(description = "Get the current weather for a given location")
		public String weather(String location) {
			int temperature = temperatures[random.nextInt(temperatures.length)];
			System.out.println("[TOOL] weather tool response temperature: " + temperature);
			return "The current weather in " + location + " is sunny with a temperature of " + temperature + "°C.";
		}

	}

	/**
	 * Several narrow questions rather than one broad rubric, each thresholded on its own.
	 * An answer that is fluent and on topic but quotes an impossible temperature fails on
	 * {@code is_plausible} alone, instead of averaging the problem away into a middling
	 * score.
	 */
	static JevJudge createWeatherJudge(TypeSafeClient typeSafeClient) { // @formatter:off
		return JevJudge.builder(typeSafeClient)
			.score("helpfulness", Score.builder()
					.instructions("How well does `assistant_answer` address the question in `user_question`?")
					.level("Terrible: irrelevant to the question, or almost entirely missing")
					.level("Mostly unhelpful: misses key aspects of the question")
					.level("Mostly helpful: answers the question but could be improved")
					.level("Excellent: relevant, direct and addresses every concern raised")
					.build(), 2.0d)
			.noul("is_plausible", Noul.builder()
					.instructions("Are all the numeric values in `assistant_answer` physically plausible for their units?")
					.whenTrue("Every value is within a range that can actually occur")
					.whenFalse("At least one value is impossible, such as a temperature below absolute zero "
							+ "or far outside anything ever recorded on Earth")
					.build(), 0.7d)
			.noul("is_grounded", Noul.builder()
					.instructions(JsonContent.object(
						"question", "Does `assistant_answer` stay within what `user_question` asked, without asserting unrelated facts?",
						"note", "The assistant has a weather tool, so specific weather values for the place asked "
								+ "about are expected and are not themselves unsupported."))
					.whenTrue("Answers the question asked, adding no unrelated factual claims")
					.whenFalse("Introduces facts that appear nowhere in the question")
					.build(), 0.5d)
			// Whether the tool was called at all is a code check: exact, free and never sent to Jev.
			.check("used_weather_tool", input -> !input.toolCalls().isEmpty(),
					"answered without calling the weather tool")
			.build();
	} // @formatter:on

}
