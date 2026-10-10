package org.springaicommunity.inspector;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.Speech;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.ImageResponseMetadata;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.CategoryScores;
import org.springframework.ai.moderation.Generation;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import org.springframework.ai.util.JsonHelper;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class InspectorModelPostProcessorTest {

	private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

	/** Media the client uploaded (from its background thread): id, content type and size, in order. */
	private final List<String> uploads = new CopyOnWriteArrayList<>();

	private final InspectorClient client = new InspectorClient("run-1",
			body -> this.events.add(new JsonHelper().fromJsonToMap(body)),
			(id, bytes, type) -> this.uploads.add(id + " " + type + " " + bytes.length));

	@SuppressWarnings("unchecked")
	private <T> T wrap(T model) {
		return wrap(model, java.util.Set.of());
	}

	@SuppressWarnings("unchecked")
	private <T> T wrap(T model, java.util.Set<String> routed) {
		var provider = new StaticListableBeanFactory(Map.of("client", this.client)).getBeanProvider(InspectorClient.class);
		return (T) new InspectorModelPostProcessor(provider, routed).postProcessAfterInitialization(model, "model");
	}

	/** The one event, once the background thread has uploaded the media and posted it. */
	private Map<String, Object> event() {
		this.client.awaitBackground();
		assertThat(events()).hasSize(1);
		return events().get(0);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object o) {
		return (Map<String, Object>) o;
	}

	/** The events posted so far: the client posts from a background thread, so wait for it first. */
	private List<Map<String, Object>> events() {
		this.client.awaitBackground();
		return this.events;
	}

	@Test
	void reportsAnImageCallWithItsImagesUploadedAndUsage() {
		byte[] png = new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };
		ImageModel model = wrap(new FakeImageModel(new ImageResponse(List.of(
				new ImageGeneration(new Image(null, java.util.Base64.getEncoder().encodeToString(png))),
				new ImageGeneration(new Image("https://cdn.example/2.png", null))),
				new ImageResponseMetadata(1L, new DefaultUsage(12, 1056)))));

		model.call(new ImagePrompt("a lighthouse at dusk", ImageOptionsBuilder.builder().model("sd3").n(2).width(1024).height(1024).build()));

		Map<String, Object> event = event();
		assertThat(event).containsEntry("type", "model-call").containsEntry("kind", "image").containsEntry("modelType", "ImageModel")
			.containsEntry("provider", "fake").containsEntry("model", "sd3");
		Map<String, Object> request = map(event.get("request"));
		assertThat(request).containsEntry("prompt", "a lighthouse at dusk");
		assertThat(map(request.get("params"))).containsEntry("model", "sd3").containsEntry("n", 2).containsEntry("size", "1024x1024");
		Map<String, Object> response = map(event.get("response"));
		List<Map<String, Object>> images = (List<Map<String, Object>>) response.get("images");
		Map<String, Object> media = map(images.get(0).get("media"));
		assertThat(media).containsEntry("type", "image/png").containsEntry("size", 7); // typed from its bytes
		assertThat((String) media.get("blobId")).matches("[0-9a-f]{16}");
		assertThat(map(images.get(1).get("media"))).containsEntry("url", "https://cdn.example/2.png");
		assertThat(map(response.get("usage"))).containsEntry("input", 12).containsEntry("output", 1056);
		// Uploaded under that id, before the event was posted.
		assertThat(this.uploads).containsExactly(media.get("blobId") + " image/png 7");
	}

	@Test
	void aRoutedProvidersBeansAreLeftAlone() {
		// Its calls are on the wire already: no proxy, no uploads, no event.
		FakeImageModel bean = new FakeImageModel(new ImageResponse(List.of()));
		ImageModel model = wrap(bean, java.util.Set.of("fake", "openai"));

		assertThat(model).isSameAs(bean);
		model.call(new ImagePrompt("x"));
		this.client.awaitBackground();
		assertThat(events()).isEmpty();
		assertThat(this.uploads).isEmpty();
	}

	@Test
	void reportsSpeechWithTheAudioUploadedUnderItsFormatsType() {
		TextToSpeechModel model = wrap(new FakeSpeechModel());

		model.call(new TextToSpeechPrompt("Welcome", TextToSpeechOptions.builder().model("tts-1").voice("nova").format("mp3").build()));

		Map<String, Object> event = event();
		assertThat(event).containsEntry("kind", "speech").containsEntry("modelType", "TextToSpeechModel").containsEntry("model", "tts-1");
		assertThat(map(event.get("request"))).containsEntry("text", "Welcome");
		assertThat(map(map(event.get("request")).get("params"))).containsEntry("voice", "nova").containsEntry("response_format", "mp3");
		Map<String, Object> audio = map(map(event.get("response")).get("audio"));
		assertThat(audio).containsEntry("type", "audio/mpeg").containsEntry("size", 3);
		assertThat(this.uploads).containsExactly(audio.get("blobId") + " audio/mpeg 3");
	}

	@Test
	void theConvenienceMethodsAreReportedOnceThroughEitherKindOfProxy() {
		TextToSpeechModel cglib = wrap(new FakeSpeechModel()); // a subclass proxy: the nested prompt call goes through it again
		byte[] heard = cglib.call("Welcome");
		assertThat(heard).containsExactly(1, 2, 3);
		Map<String, Object> event = event();
		assertThat(map(event.get("request"))).containsEntry("text", "Welcome");
		assertThat(map(map(event.get("response")).get("audio"))).containsEntry("size", 3);
		this.events.clear();

		TextToSpeechModel jdk = wrap(new FinalSpeechModel()); // an interface proxy: the nested prompt call bypasses it
		assertThat(jdk.getClass().getSimpleName()).startsWith("$Proxy");
		jdk.call("Hi");
		assertThat(map(event().get("request"))).containsEntry("text", "Hi");
		this.events.clear();

		TranscriptionModel transcriber = wrap(new FakeTranscriptionModel());
		assertThat(transcriber.transcribe(new ByteArrayResource(new byte[] { 1 }))).isEqualTo("What is the weather?");
		assertThat(map(event().get("response"))).containsEntry("text", "What is the weather?");
		this.events.clear();

		String streamed = transcriber.streamTranscribe(new ByteArrayResource(new byte[] { 1 })).reduce("", String::concat).block();
		assertThat(streamed).isEqualTo("What is the weather?");
		assertThat(event()).containsEntry("streamed", true);
		assertThat(map(event().get("response"))).containsEntry("text", "What is the weather?");
	}

	@Test
	void theAudioFileIsReadForTheUploadOffTheApplicationThread() {
		TranscriptionModel model = wrap(new FakeTranscriptionModel());
		List<String> readers = new CopyOnWriteArrayList<>();
		ByteArrayResource audio = new ByteArrayResource("RIFF....WAVE....".getBytes(StandardCharsets.US_ASCII)) {
			@Override
			public java.io.InputStream getInputStream() throws java.io.IOException {
				readers.add(Thread.currentThread().getName());
				return super.getInputStream();
			}
		};

		model.call(new AudioTranscriptionPrompt(audio, () -> "whisper-1"));

		List<Map<String, Object>> files = (List<Map<String, Object>>) map(event().get("request")).get("files");
		assertThat(files.get(0)).containsKey("blobId");
		assertThat(readers).containsExactly(Thread.currentThread().getName(), "spring-ai-inspector-sender"); // the model, then the inspector
		assertThat(this.uploads).hasSize(1);
	}

	@Test
	void aStreamedSpeechCallIsReportedOnceWithTheChunksPutTogether() {
		TextToSpeechModel model = wrap(new FakeSpeechModel());

		List<byte[]> heard = model.stream(new TextToSpeechPrompt("Welcome", TextToSpeechOptions.builder().model("tts-1").format("mp3").build()))
			.map(r -> r.getResult().getOutput())
			.collectList()
			.block();

		assertThat(heard).hasSize(2); // the application got the stream as it was
		Map<String, Object> event = event();
		assertThat(map(map(event.get("response")).get("audio"))).containsEntry("size", 5);
		assertThat(event).containsEntry("kind", "speech").containsEntry("streamed", true).containsEntry("model", "tts-1");
		Map<String, Object> audio = map(map(event.get("response")).get("audio"));
		assertThat(audio).containsEntry("type", "audio/mpeg").containsEntry("size", 5);
		assertThat(this.uploads).containsExactly(audio.get("blobId") + " audio/mpeg 5");
	}

	@Test
	void aStreamSubscribedToTwiceIsTwoCallsNotOneWithDoubledAudio() {
		TextToSpeechModel model = wrap(new FakeSpeechModel());
		Flux<TextToSpeechResponse> stream = model.stream(new TextToSpeechPrompt("Welcome", TextToSpeechOptions.builder().model("tts-1").format("mp3").build()));

		stream.blockLast();
		stream.blockLast(); // e.g. a retry

		this.client.awaitBackground();
		assertThat(events()).hasSize(2)
			.allSatisfy(e -> assertThat(map(map(e.get("response")).get("audio"))).containsEntry("size", 5));
	}

	@Test
	void aStreamTheApplicationCancelsIsReportedWithWhatCameThrough() {
		TextToSpeechModel model = wrap(new FakeSpeechModel());

		byte[] first = model.stream(new TextToSpeechPrompt("Welcome", TextToSpeechOptions.builder().model("tts-1").format("mp3").build()))
			.map(r -> r.getResult().getOutput())
			.next() // the first chunk only: the rest is cancelled
			.block();

		assertThat(first).containsExactly(1, 2, 3);
		Map<String, Object> event = event();
		assertThat(event).containsEntry("streamed", true).containsEntry("cancelled", true).doesNotContainKey("error");
		assertThat(map(map(event.get("response")).get("audio"))).containsEntry("size", 3);

		// A one-item stream taken whole: the operator cancels right after the item, the source may also complete.
		this.events.clear();
		this.<TextToSpeechModel>wrap(new FinalSpeechModel()).stream(new TextToSpeechPrompt("Hi")).next().block();
		this.client.awaitBackground();
		assertThat(events()).hasSize(1); // one report, whichever signal came first
	}

	@Test
	void aStreamedTranscriptionIsReportedOnceWithItsTextJoined() {
		TranscriptionModel model = wrap(new FakeTranscriptionModel());

		String text = model.stream(new AudioTranscriptionPrompt(new ByteArrayResource(new byte[] { 1 }), () -> "whisper-1"))
			.map(r -> r.getResult().getOutput())
			.reduce("", String::concat)
			.block();

		assertThat(text).isEqualTo("What is the weather?");
		Map<String, Object> event = event();
		assertThat(event).containsEntry("kind", "transcription").containsEntry("streamed", true);
		assertThat(map(event.get("response"))).containsEntry("text", "What is the weather?");
	}

	@Test
	void reportsATranscriptionWithARereadableFileUploadedAndAOneShotStreamLeftAlone() {
		TranscriptionModel model = wrap(new FakeTranscriptionModel());
		byte[] wav = "RIFF....WAVE....".getBytes(StandardCharsets.US_ASCII);

		model.call(new AudioTranscriptionPrompt(new ByteArrayResource(wav) {
			@Override
			public String getFilename() {
				return "question.wav";
			}
		}, () -> "whisper-1"));
		model.call(new AudioTranscriptionPrompt(new InputStreamResource(new java.io.ByteArrayInputStream(wav)), () -> "whisper-1"));

		this.client.awaitBackground();
		assertThat(events()).hasSize(2);
		Map<String, Object> first = events().get(0);
		assertThat(first).containsEntry("kind", "transcription").containsEntry("model", "whisper-1");
		List<Map<String, Object>> files = (List<Map<String, Object>>) map(first.get("request")).get("files");
		assertThat(files.get(0)).containsEntry("filename", "question.wav").containsEntry("contentType", "audio/wav")
			.containsEntry("size", wav.length).containsKey("blobId");
		assertThat(map(first.get("response"))).containsEntry("text", "What is the weather?");
		// The stream was not consumed by the inspector: no upload, and the model still read it.
		List<Map<String, Object>> streamed = (List<Map<String, Object>>) map(events().get(1).get("request")).get("files");
		assertThat(streamed.get(0)).doesNotContainKey("blobId");
		assertThat(this.uploads).hasSize(1);
	}

	@Test
	void reportsModerationVerdictsAndScores() {
		ModerationModel model = wrap(new FakeModerationModel());

		model.call(new ModerationPrompt("I will hurt you", () -> "omni-moderation-latest"));

		Map<String, Object> event = event();
		assertThat(event).containsEntry("kind", "moderation").containsEntry("model", "omni-moderation-2024"); // the answering model wins
		List<Map<String, Object>> inputs = (List<Map<String, Object>>) map(event.get("request")).get("inputs");
		assertThat(inputs.get(0)).containsEntry("text", "I will hurt you");
		List<Map<String, Object>> results = (List<Map<String, Object>>) map(event.get("response")).get("results");
		assertThat(results.get(0)).containsEntry("flagged", true);
		assertThat((List<String>) results.get(0).get("flaggedCategories")).containsExactly("violence");
		assertThat(map(results.get(0).get("scores"))).containsEntry("violence", 0.9).containsEntry("harassment", 0.1);
	}

	@Test
	void aFailedCallIsReportedWithItsErrorAndStillThrows() {
		ModerationModel model = wrap(new FakeModerationModel() {
			@Override
			public ModerationResponse call(ModerationPrompt prompt) {
				throw new IllegalStateException("no key");
			}
		});

		assertThatIllegalStateException().isThrownBy(() -> model.call(new ModerationPrompt("x")));

		assertThat(event()).containsEntry("error", "IllegalStateException: no key").doesNotContainKey("response");
	}

	@Test
	void leavesOtherBeansAlone() {
		Object bean = new Object();
		var provider = new StaticListableBeanFactory(Map.of("client", this.client)).getBeanProvider(InspectorClient.class);
		assertThat(new InspectorModelPostProcessor(provider, java.util.Set.of()).postProcessAfterInitialization(bean, "x")).isSameAs(bean);
		assertThat(InspectorModelPostProcessor.provider(new FakeSpeechModel())).isEqualTo("fake");
	}

	public static class FakeImageModel implements ImageModel {

		private final ImageResponse response;

		FakeImageModel(ImageResponse response) {
			this.response = response;
		}

		@Override
		public ImageResponse call(ImagePrompt prompt) {
			return this.response;
		}

	}

	public static class FakeSpeechModel implements TextToSpeechModel {

		@Override
		public TextToSpeechResponse call(TextToSpeechPrompt prompt) {
			return new TextToSpeechResponse(List.of(new Speech(new byte[] { 1, 2, 3 })));
		}

		@Override
		public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) {
			return Flux.just(new TextToSpeechResponse(List.of(new Speech(new byte[] { 1, 2, 3 }))),
					new TextToSpeechResponse(List.of(new Speech(new byte[] { 4, 5 }))));
		}

	}

	/** Final: Spring's ProxyFactory can only give it an interface (JDK) proxy. */
	public static final class FinalSpeechModel implements TextToSpeechModel {

		@Override
		public TextToSpeechResponse call(TextToSpeechPrompt prompt) {
			return new TextToSpeechResponse(List.of(new Speech(new byte[] { 9 })));
		}

		@Override
		public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) {
			return Flux.just(call(prompt));
		}

	}

	public static class FakeTranscriptionModel implements TranscriptionModel {

		@Override
		public AudioTranscriptionResponse call(AudioTranscriptionPrompt prompt) {
			try {
				prompt.getInstructions().getInputStream().readAllBytes(); // the model reads the audio itself
			}
			catch (java.io.IOException ex) {
				throw new IllegalStateException(ex);
			}
			return new AudioTranscriptionResponse(new AudioTranscription("What is the weather?"));
		}

		@Override
		public Flux<AudioTranscriptionResponse> stream(AudioTranscriptionPrompt prompt) {
			return Flux.just(new AudioTranscriptionResponse(new AudioTranscription("What is ")),
					new AudioTranscriptionResponse(new AudioTranscription("the weather?")));
		}

	}

	public static class FakeModerationModel implements ModerationModel {

		@Override
		public ModerationResponse call(ModerationPrompt prompt) {
			return new ModerationResponse(new Generation(Moderation.builder().id("m").model("omni-moderation-2024")
				.results(List.of(ModerationResult.builder().flagged(true)
					.categories(Categories.builder().violence(true).harassment(false).build())
					.categoryScores(CategoryScores.builder().violence(0.9).harassment(0.1).build())
					.build()))
				.build()));
		}

	}

}
