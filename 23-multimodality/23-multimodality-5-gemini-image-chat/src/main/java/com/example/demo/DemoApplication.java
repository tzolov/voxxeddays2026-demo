package com.example.demo;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Multimodal output from a chat model: Gemini's image models answer a chat message with an
 * image, which Spring AI returns as {@link Media} on the {@link AssistantMessage}. Two turns:
 * <ol>
 * <li>draw a picture from a text prompt;</li>
 * <li>send that picture back as media of the next user message and ask for a change.</li>
 * </ol>
 * The Google GenAI SDK does not go through the inspector's proxy, so there is no wire view of
 * these calls; the inspector shows them from the ChatClient advisors: the image in the answer
 * of the first call, the same image as input and the edited one as output of the second,
 * uploaded by the starter for the previews. Both images are written to {@code target/}
 * (JPEG, Gemini's default). Needs Spring AI 2.1.0-SNAPSHOT, see the pom.
 */
@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off
			ChatClient chatClient = chatClientBuilder.build();
			Path out = Files.createDirectories(Path.of("target"));

			// 1. Draw: the answer is a picture (and maybe a sentence about it).
			AssistantMessage drawn = chatClient.prompt()
				.user("Draw a flat, friendly illustration of a conference stage: a speaker at a lectern, a big screen "
						+ "showing a steaming coffee cup, a few people in the audience. Daylight colors. No text in the picture.")
				.call()
				.chatResponse()
				.getResult()
				.getOutput();
			Media picture = image(drawn);
			Path first = out.resolve("stage." + extension(picture));
			Files.write(first, picture.getDataAsByteArray());
			System.out.println("\n1. drew " + describe(picture) + " -> " + first + text(drawn));

			// 2. Edit: the picture goes back as media of the next message, with the change wanted.
			AssistantMessage edited = chatClient.prompt()
				.user(u -> u.text("Same picture, but at night: dark blue background, the screen glowing, a moon in the window. "
						+ "Keep everything else as it is.")
					.media(picture))
				.call()
				.chatResponse()
				.getResult()
				.getOutput();
			Media night = image(edited);
			Path second = out.resolve("stage-night." + extension(night));
			Files.write(second, night.getDataAsByteArray());
			System.out.println("\n2. edited " + describe(night) + " -> " + second + text(edited));
		}; // @formatter:on
	}

	/**
	 * The first image of an answer; fails with the text and the finish reason when there is
	 * none (the model answered with words, or the answer was blocked). An empty answer with
	 * finish reason STOP and a thousand-odd output tokens means the image was dropped on the
	 * way: Spring AI 2.1.0-M1 does that, see the version override in the pom.
	 */
	private static Media image(AssistantMessage answer) {
		return answer.getMedia()
			.stream()
			.filter(m -> m.getMimeType().getType().equals("image"))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("no image in the answer (finish reason "
					+ answer.getMetadata().get("finishReason") + "): " + answer.getText()));
	}

	/** The file extension for the image's type: Gemini answers with JPEG by default. */
	private static String extension(Media media) {
		return media.getMimeType().getSubtype().equals("jpeg") ? "jpg" : media.getMimeType().getSubtype();
	}

	private static String describe(Media media) {
		return media.getMimeType() + ", " + media.getDataAsByteArray().length / 1024 + " KB";
	}

	private static String text(AssistantMessage answer) {
		return answer.getText() == null || answer.getText().isBlank() ? "" : "\n   " + answer.getText().strip();
	}

}
