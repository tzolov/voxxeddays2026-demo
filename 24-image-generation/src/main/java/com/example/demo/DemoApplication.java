package com.example.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Image generation, three ways, so the inspector shows each:
 * <ol>
 * <li>a direct {@code ImageModel} call: the image comes back as base64, which the inspector
 * strips from the recorded body, keeps, and shows as a thumbnail, with the revised prompt and
 * the token usage;</li>
 * <li>a second direct call with other parameters (a lower quality), to compare side by side;</li>
 * <li>an image made by a tool during a {@code ChatClient} call: the image round-trip is
 * nested inside the tool, inside the chat call, through observation parentage.</li>
 * </ol>
 * The images are also written next to the run as {@code target/image-*.png} to compare with
 * what the inspector shows.
 */
@SpringBootApplication
public class DemoApplication {

	/**
	 * Named on every call: runtime {@code OpenAiImageOptions} default the model when none is
	 * set, overriding the properties. OpenAI's images API answers in base64 and no longer takes
	 * {@code response_format}, so none is sent.
	 */
	static final String MODEL = "gpt-image-1-mini";

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(ImageModel imageModel, ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			// 1. Base64 back: the inspector strips it from the recorded body and keeps the bytes for the preview.
			ImageResponse first = imageModel.call(new ImagePrompt("A lighthouse at dusk, oil painting, warm light",
					OpenAiImageOptions.builder().model(MODEL).n(1).width(1024).height(1024).build()));
			describe("dusk", first);

			// 2. Other parameters: a quick, low-quality variant to compare in the inspector.
			ImageResponse second = imageModel.call(new ImagePrompt("The same lighthouse at noon, watercolor",
					OpenAiImageOptions.builder().model(MODEL).n(1).width(1024).height(1024).quality("low").build()));
			describe("noon", second);

			// 3. From a tool: the image call appears inside the tool's card, inside the chat call.
			String answer = chatClientBuilder.build()
				.prompt()
				.tools(new ImageTools(imageModel))
				.user("Make an image of a small sailing boat in a storm, then tell me in one sentence what the image model actually painted.")
				.call()
				.content();
			System.out.println("\n" + answer);

		}; // @formatter:on
	}

	static void describe(String how, ImageResponse response) throws Exception {
		for (ImageGeneration generation : response.getResults()) {
			Image image = generation.getOutput();
			String revised = String.valueOf(generation.getMetadata());
			if (image.getB64Json() != null) {
				Path file = Files.createDirectories(Path.of("target")).resolve("image-" + how + ".png");
				Files.write(file, Base64.getDecoder().decode(image.getB64Json()));
				System.out.println(how + ": written to " + file.toAbsolutePath() + " · " + revised);
			}
			else {
				System.out.println(how + ": " + image.getUrl() + " · " + revised);
			}
		}
	}

	/** A tool that paints: what it does is shown inside its card. */
	record ImageTools(ImageModel imageModel) {

		@Tool(description = "Generates an image from a text prompt and returns the prompt the image model actually used")
		String generateImage(@ToolParam(description = "what to paint") String prompt) {
			ImageResponse response = this.imageModel.call(new ImagePrompt(prompt,
					OpenAiImageOptions.builder().model(MODEL).n(1).width(1024).height(1024).build()));
			ImageGeneration generation = response.getResult();
			try {
				describe("tool", response);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return "Image generated. Image model metadata: " + generation.getMetadata();
		}

	}

}
