package com.example.demo;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

/**
 * Text to speech and back, so the inspector shows audio in both directions:
 * <ol>
 * <li>a direct {@code TextToSpeechModel} call: a binary response, kept by the inspector
 * and played from the card; also written to {@code target/speech.mp3};</li>
 * <li>a direct {@code TranscriptionModel} call on that file: a multipart request whose
 * audio part is kept and playable, and the transcript back;</li>
 * <li>both again from tools during a {@code ChatClient} call, nested inside the tools.</li>
 * </ol>
 */
@SpringBootApplication
public class DemoApplication {

	private static final String TEXT = "Welcome to Voxxed Days. This sentence was spoken by a model and will be transcribed by another.";

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public CommandLineRunner cli(TextToSpeechModel speech, TranscriptionModel transcription, ChatClient.Builder chatClientBuilder) {
		return args -> { // @formatter:off

			// 1. Text to speech: the response body is audio, not JSON.
			byte[] audio = speech.call(new TextToSpeechPrompt(TEXT,
					OpenAiAudioSpeechOptions.builder().voice("alloy").responseFormat("mp3").build())).getResult().getOutput();
			Path file = Files.createDirectories(Path.of("target")).resolve("speech.mp3");
			Files.write(file, audio);
			System.out.println("speech: " + audio.length + " bytes written to " + file.toAbsolutePath());

			// 2. And back: a multipart upload of that file. A FileSystemResource can be read again, so the
			// inspector keeps the audio for the card; the model reads it itself as usual.
			String transcript = transcription.call(new AudioTranscriptionPrompt(new FileSystemResource(file))).getResult().getOutput();
			System.out.println("transcript: " + transcript);

			// 3. From tools inside a chat call: both round-trips nest under their tools.
			String answer = chatClientBuilder.build()
				.prompt()
				.tools(new AudioTools(speech, transcription))
				.user("Say 'the quick brown fox jumps over the lazy dog' out loud, then transcribe what you said and tell me whether it came back unchanged.")
				.call()
				.content();
			System.out.println("\n" + answer);

		}; // @formatter:on
	}

	/** Tools that speak and listen: what they do is shown inside their cards. */
	record AudioTools(TextToSpeechModel speech, TranscriptionModel transcription) {

		/** The last synthesized audio, handed from one tool to the next. */
		private static final java.util.concurrent.atomic.AtomicReference<byte[]> LAST = new java.util.concurrent.atomic.AtomicReference<>();

		@Tool(description = "Speaks a text out loud and returns the length of the audio in bytes")
		String speak(@ToolParam(description = "the text to say") String text) {
			byte[] audio = this.speech.call(new TextToSpeechPrompt(text,
					OpenAiAudioSpeechOptions.builder().voice("nova").responseFormat("mp3").build())).getResult().getOutput();
			LAST.set(audio);
			return audio.length + " bytes of mp3";
		}

		@Tool(description = "Transcribes the audio spoken last and returns the text")
		String transcribeLast() {
			byte[] audio = LAST.get();
			if (audio == null) {
				return "nothing was spoken yet";
			}
			return this.transcription.call(new AudioTranscriptionPrompt(new ByteArrayResource(audio) {
				@Override
				public String getFilename() {
					return "spoken.mp3"; // the API needs a name with an extension to know the format
				}
			})).getResult().getOutput();
		}

	}

}
