package org.springaicommunity.inspector;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;
import org.springframework.ai.util.JsonHelper;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Wraps the non-chat model beans ({@link ImageModel}, {@link TextToSpeechModel},
 * {@link TranscriptionModel}, {@link ModerationModel}) so the Spring AI Inspector sees their
 * calls even when no HTTP goes through its proxy: a model running in the JVM, or a provider
 * reached through an SDK with no base URL to rewrite (Google GenAI, Bedrock). Each call is
 * reported once done as a {@code model-call} event, in the same normalized shape the
 * inspector's wire adapters produce for the HTTP twin of the call (see the server's
 * {@code providers.js}), so the UI shows both the same way. The media of a call (an image
 * made, speech synthesized, audio transcribed) is uploaded to the inspector from the
 * background thread, which keeps it for previews, and referenced by id. Beans of a routed
 * provider are left alone: their calls are on the wire already. (The inspector also drops a
 * bean's call when an HTTP round-trip of the same kind was recorded for it meanwhile.)
 *
 * <p>The event is posted from the background thread too, after the media, so it never
 * names a blob the inspector doesn't have yet. Only the blocking {@code call} is observed;
 * streamed speech and transcription pass through unobserved.
 */
public class InspectorModelPostProcessor implements BeanPostProcessor {

	/** Media larger than this is described but not uploaded. */

	private final ObjectProvider<InspectorClient> client;

	/** Providers whose calls go through the inspector's proxy: their beans are not wrapped. */
	private final Set<String> routed;

	private final JsonHelper json = new JsonHelper();

	private volatile InspectorClient resolved;

	public InspectorModelPostProcessor(ObjectProvider<InspectorClient> client, Set<String> routed) {
		this.client = client;
		this.routed = routed;
	}

	private InspectorClient client() {
		InspectorClient c = this.resolved;
		if (c == null) {
			c = this.client.getIfAvailable();
			this.resolved = c;
		}
		return c;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		String kind = kind(bean);
		if (kind == null) {
			return bean;
		}
		String provider = provider(bean);
		if (this.routed.contains(provider)) {
			return bean;
		}
		ProxyFactory factory = new ProxyFactory(bean);
		factory.setProxyTargetClass(!Modifier.isFinal(bean.getClass().getModifiers()));
		factory.addAdvice((MethodInterceptor) invocation -> {
			Object[] args = invocation.getArguments();
			if (!"call".equals(invocation.getMethod().getName()) || args.length != 1 || !isPrompt(args[0])) {
				return invocation.proceed();
			}
			String clientCallId = InspectorCorrelation.currentCallId();
			long start = System.currentTimeMillis();
			try {
				Object result = invocation.proceed();
				report(kind, provider, args[0], result, null, System.currentTimeMillis() - start, clientCallId);
				return result;
			}
			catch (Throwable ex) {
				report(kind, provider, args[0], null, ex.getClass().getSimpleName() + ": " + ex.getMessage(),
						System.currentTimeMillis() - start, clientCallId);
				throw ex;
			}
		});
		return factory.getProxy();
	}

	/** The kind of model, or null for a bean this class doesn't observe. */
	static String kind(Object bean) {
		if (bean instanceof ImageModel) {
			return "image";
		}
		if (bean instanceof TextToSpeechModel) {
			return "speech";
		}
		if (bean instanceof TranscriptionModel) {
			return "transcription";
		}
		if (bean instanceof ModerationModel) {
			return "moderation";
		}
		return null;
	}

	private static boolean isPrompt(Object arg) {
		return arg instanceof ImagePrompt || arg instanceof TextToSpeechPrompt || arg instanceof AudioTranscriptionPrompt
				|| arg instanceof ModerationPrompt;
	}

	private static String modelType(String kind) {
		return switch (kind) {
			case "image" -> "ImageModel";
			case "speech" -> "TextToSpeechModel";
			case "transcription" -> "TranscriptionModel";
			default -> "ModerationModel";
		};
	}

	/** e.g. OpenAiAudioSpeechModel -> openai, StabilityAiImageModel -> stabilityai. */
	static String provider(Object model) {
		Class<?> type = model.getClass();
		while ((type.isAnonymousClass() || type.getName().contains("$$")) && type.getSuperclass() != null) {
			type = type.getSuperclass();
		}
		String name = type.getSimpleName()
			.replaceAll("(Image|AudioSpeech|TextToSpeech|Speech|AudioTranscription|Transcription|Moderation)?Model$", "")
			.toLowerCase(Locale.ROOT);
		return name.isEmpty() ? "model" : name;
	}

	private void report(String kind, String provider, Object prompt, Object result, String error, long durationMs,
			String clientCallId) {
		InspectorClient c = client();
		if (c == null) {
			return;
		}
		// Built here, so the media is handed to the background thread first; posted from there.
		c.send("model-call", () -> {
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("modelCallId", UUID.randomUUID().toString().substring(0, 8));
			event.put("clientCallId", clientCallId);
			event.put("kind", kind);
			event.put("modelType", modelType(kind));
			event.put("provider", provider);
			event.put("thread", Thread.currentThread().getName());
			Map<String, Object> request = switch (kind) {
				case "image" -> imageRequest(c, (ImagePrompt) prompt);
				case "speech" -> speechRequest((TextToSpeechPrompt) prompt);
				case "transcription" -> transcriptionRequest(c, (AudioTranscriptionPrompt) prompt);
				default -> moderationRequest((ModerationPrompt) prompt);
			};
			event.put("request", request);
			Object params = request.get("params");
			event.put("model", params instanceof Map<?, ?> p ? p.get("model") : null);
			if (error != null) {
				event.put("error", error);
			}
			else if (result != null) {
				Map<String, Object> response = switch (kind) {
					case "image" -> imageResponse(c, (ImageResponse) result);
					case "speech" -> speechResponse(c, (TextToSpeechPrompt) prompt, (TextToSpeechResponse) result);
					case "transcription" -> transcriptionResponse((AudioTranscriptionResponse) result);
					default -> moderationResponse((ModerationResponse) result);
				};
				event.put("response", response);
				if (response.get("model") instanceof String answeredBy && !answeredBy.isBlank()) {
					event.put("model", answeredBy);
				}
			}
			event.put("durationMs", durationMs);
			return event;
		});
	}

	// ---------------------------------------------------------------- image

	private Map<String, Object> imageRequest(InspectorClient c, ImagePrompt prompt) {
		Map<String, Object> params = new LinkedHashMap<>();
		ImageOptions options = prompt.getOptions();
		if (options != null) {
			put(params, "model", options.getModel());
			put(params, "n", options.getN());
			if (options.getWidth() != null && options.getHeight() != null) {
				params.put("size", options.getWidth() + "x" + options.getHeight());
			}
			put(params, "response_format", options.getResponseFormat());
			put(params, "style", options.getStyle());
		}
		List<String> texts = new ArrayList<>();
		List<Map<String, Object>> files = new ArrayList<>();
		for (ImageMessage message : prompt.getInstructions()) {
			if (message.getText() != null && !message.getText().isBlank()) {
				texts.add(message.getText());
			}
			// Images given to edit or vary: described, uploaded when the data is at hand.
			for (var media : message.getMedia()) {
				Map<String, Object> file = new LinkedHashMap<>();
				file.put("name", "image");
				file.put("filename", media.getName());
				String type = media.getMimeType() == null ? "application/octet-stream" : media.getMimeType().toString();
				file.put("contentType", type);
				byte[] bytes = media.getData() instanceof byte[] b ? b : null;
				if (bytes != null) {
					file.put("size", bytes.length);
					InspectorClient.Media kept = c.sendBlob(bytes, type);
					if (kept != null) {
						file.put("contentType", kept.contentType());
						file.put("blobId", kept.id());
					}
				}
				files.add(file);
			}
		}
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("params", params);
		request.put("prompt", String.join("\n", texts));
		request.put("files", files);
		return request;
	}

	private Map<String, Object> imageResponse(InspectorClient c, ImageResponse response) {
		List<Map<String, Object>> images = new ArrayList<>();
		for (ImageGeneration generation : response.getResults()) {
			Map<String, Object> image = new LinkedHashMap<>();
			Image output = generation.getOutput();
			Map<String, Object> media = new LinkedHashMap<>();
			String data = output.getB64Json();
			if (data != null && !data.isBlank()) {
				byte[] bytes = InspectorMedia.decodeBase64(data);
				media.put("type", bytes == null ? "" : InspectorMedia.type(null, bytes));
				media.put("chars", data.length());
				if (bytes != null) {
					media.put("size", bytes.length);
					InspectorClient.Media kept = c.sendBlob(bytes, null); // typed from its bytes
					if (kept != null) {
						media.put("blobId", kept.id());
					}
				}
			}
			else if (output.getUrl() != null) {
				media.put("url", output.getUrl());
				media.put("type", "");
			}
			image.put("media", media.isEmpty() ? null : media);
			Object revised = InspectorReflection.callIfPresent(generation.getMetadata(), "getRevisedPrompt");
			if (revised instanceof String s && !s.isBlank()) {
				image.put("revisedPrompt", s);
			}
			images.add(image);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("images", images);
		out.put("blocks", List.of());
		out.put("usage", usage(response.getMetadata() == null ? null : response.getMetadata().getUsage()));
		return out;
	}

	// ---------------------------------------------------------------- speech

	private static Map<String, Object> speechRequest(TextToSpeechPrompt prompt) {
		Map<String, Object> params = new LinkedHashMap<>();
		var options = prompt.getOptions();
		if (options != null) {
			put(params, "model", options.getModel());
			put(params, "voice", options.getVoice());
			put(params, "response_format", options.getFormat());
			put(params, "speed", options.getSpeed());
		}
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("params", params);
		request.put("text", prompt.getInstructions() == null ? "" : prompt.getInstructions().getText());
		return request;
	}

	private static Map<String, Object> speechResponse(InspectorClient c, TextToSpeechPrompt prompt, TextToSpeechResponse response) {
		byte[] bytes = response.getResult() == null ? null : response.getResult().getOutput();
		Map<String, Object> audio = new LinkedHashMap<>();
		String format = prompt.getOptions() == null ? null : prompt.getOptions().getFormat();
		String type = bytes == null ? "" : InspectorMedia.type(InspectorMedia.audioType(format), bytes);
		audio.put("type", type);
		audio.put("size", bytes == null ? 0 : bytes.length);
		if (bytes != null) {
			InspectorClient.Media kept = c.sendBlob(bytes, type);
			if (kept != null) {
				audio.put("blobId", kept.id());
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("audio", audio);
		out.put("blocks", List.of());
		out.put("usage", null);
		return out;
	}

	// ---------------------------------------------------------------- transcription

	private static Map<String, Object> transcriptionRequest(InspectorClient c, AudioTranscriptionPrompt prompt) {
		Map<String, Object> params = new LinkedHashMap<>();
		if (prompt.getOptions() != null) {
			put(params, "model", prompt.getOptions().getModel());
		}
		Map<String, Object> file = new LinkedHashMap<>();
		Resource audio = prompt.getInstructions();
		file.put("name", "file");
		file.put("filename", audio == null ? null : audio.getFilename());
		String byName = InspectorMedia.audioType(extension(audio == null ? null : audio.getFilename()));
		byte[] bytes = rereadable(audio);
		file.put("contentType", bytes == null ? (byName == null ? "application/octet-stream" : byName) : InspectorMedia.type(byName, bytes));
		if (bytes != null) {
			file.put("size", bytes.length);
			InspectorClient.Media kept = c.sendBlob(bytes, byName);
			if (kept != null) {
				file.put("contentType", kept.contentType());
				file.put("blobId", kept.id());
			}
		}
		else if (audio != null) {
			try {
				file.put("size", audio.contentLength());
			}
			catch (Exception ex) {
				// unknown size
			}
		}
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("params", params);
		request.put("files", List.of(file));
		return request;
	}

	/**
	 * The bytes of an audio resource that can be read again without side effects: a file, a
	 * byte array, a class path resource. Anything else (a stream that can be read once, a URL
	 * that would be downloaded again) is left to the model.
	 */
	private static byte[] rereadable(Resource resource) {
		if (resource == null) {
			return null;
		}
		try {
			if (!(resource instanceof ByteArrayResource || resource instanceof ClassPathResource || resource.isFile())) {
				return null;
			}
			try (InputStream in = resource.getInputStream()) {
				byte[] bytes = in.readNBytes(InspectorMedia.MAX_BYTES + 1);
				return bytes.length > InspectorMedia.MAX_BYTES ? null : bytes;
			}
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static String extension(String filename) {
		int dot = filename == null ? -1 : filename.lastIndexOf('.');
		return dot < 0 ? null : filename.substring(dot + 1);
	}

	private static Map<String, Object> transcriptionResponse(AudioTranscriptionResponse response) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("text", response.getResult() == null ? "" : response.getResult().getOutput());
		out.put("blocks", List.of());
		out.put("usage", null);
		return out;
	}

	// ---------------------------------------------------------------- moderation

	private static Map<String, Object> moderationRequest(ModerationPrompt prompt) {
		Map<String, Object> params = new LinkedHashMap<>();
		if (prompt.getOptions() != null) {
			put(params, "model", prompt.getOptions().getModel());
		}
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("type", "text");
		input.put("text", prompt.getInstructions() == null ? "" : prompt.getInstructions().getText());
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("params", params);
		request.put("inputs", List.of(input));
		return request;
	}

	private Map<String, Object> moderationResponse(ModerationResponse response) {
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> results = new ArrayList<>();
		var generation = response.getResult();
		var moderation = generation == null ? null : generation.getOutput();
		if (moderation != null) {
			out.put("model", moderation.getModel());
			for (ModerationResult result : moderation.getResults()) {
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("flagged", result.isFlagged());
				// The category flags and scores as maps, whatever categories this provider has.
				Map<String, Object> flags = result.getCategories() == null ? Map.of() : this.json.convertToMap(result.getCategories());
				List<String> flagged = new ArrayList<>();
				flags.forEach((k, v) -> {
					if (Boolean.TRUE.equals(v)) {
						flagged.add(k);
					}
				});
				r.put("flaggedCategories", flagged);
				r.put("scores", result.getCategoryScores() == null ? Map.of() : this.json.convertToMap(result.getCategoryScores()));
				results.add(r);
			}
		}
		out.put("results", results);
		out.put("blocks", List.of());
		out.put("usage", null);
		return out;
	}

	// ---------------------------------------------------------------- helpers

	private static Map<String, Object> usage(Usage usage) {
		if (usage == null || (usage.getPromptTokens() == null && usage.getCompletionTokens() == null)) {
			return null;
		}
		Map<String, Object> u = new LinkedHashMap<>();
		u.put("input", usage.getPromptTokens());
		u.put("output", usage.getCompletionTokens());
		return u;
	}

	private static void put(Map<String, Object> params, String key, Object value) {
		if (value != null) {
			params.put(key, value);
		}
	}

}
