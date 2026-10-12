package com.example.demo;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Multimodal input to a chat model, as the inspector sees it:
 * <ol>
 * <li>an image: a bar chart of ticket sales per day ({@code ticket-sales-per-day.png} on the
 * class path), with a question only the picture can answer. The inspector shows the chart in
 * "Your app sent" and strips the base64 from the recorded wire request, keeping it for the
 * thumbnail;</li>
 * <li>a PDF (Claude only): the hurricane page from demo 05, 3 MB of base64 on the wire,
 * which the inspector replaces by a marker and keeps as a document.</li>
 * </ol>
 */
@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(ChatClient.Builder chatClientBuilder, Environment env) {
		return args -> { // @formatter:off
			ChatClient chatClient = chatClientBuilder.build();

			// 1. An image: only the picture holds the numbers.
			String answer = chatClient.prompt()
				.user(u -> u.text("This chart shows ticket sales per day of a conference. Which day sold the most, and roughly how many? Answer in one sentence.")
					.media(MimeTypeUtils.IMAGE_PNG, new ClassPathResource("ticket-sales-per-day.png")))
				.call()
				.content();
			System.out.println("\nchart: " + answer);

			// 2. A PDF, with Claude (Ollama chat takes images only).
			Path pdf = Path.of("05-rag/src/main/resources/wikipedia-hurricane-milton-page.pdf");
			if (env.matchesProfiles("ollama")) {
				System.out.println("\npdf: skipped with the ollama profile");
			}
			else if (!Files.exists(pdf)) {
				System.out.println("\npdf: run from the repository root to find " + pdf);
			}
			else {
				String summary = chatClient.prompt()
					.user(u -> u.text("In two sentences: where and when did this hurricane make landfall?")
						.media(MimeType.valueOf("application/pdf"), new FileSystemResource(pdf)))
					.call()
					.content();
				System.out.println("\npdf: " + summary);
			}
		}; // @formatter:on
	}

}
