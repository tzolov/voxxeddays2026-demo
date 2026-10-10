package org.springaicommunity.inspector.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * What of an HTTP request or response is recorded, and how. Secrets are redacted (headers,
 * query parameters); text bodies are capped; inline base64 payloads (images, audio,
 * documents) and binary bodies are replaced by a marker, their bytes kept in the
 * {@link BlobStore} when one is given, so the UI can show them; multipart requests (an
 * audio file for transcription) are recorded as their fields and file parts. Nothing here
 * changes what is forwarded; it only shapes the {@code wire-*} events.
 *
 * <p>The marker for media is {@code <base64 N chars[ TYPE][ blob:ID]>}, a JSON string where
 * the payload was, with the media type when known and the blob when kept.
 */
final class WireCapture {

	static final String REDACTED = "…redacted";

	private static final Set<String> SECRET_HEADERS = Set.of("x-api-key", "authorization", "proxy-authorization",
			"api-key", "x-goog-api-key", "openai-organization", "openai-project", "cookie", "set-cookie");

	private static final Set<String> SECRET_PARAMS = Set.of("key", "api_key", "api-key", "apikey", "token",
			"access_token", "auth", "signature", "sig");

	private static final Set<String> TEXT_TYPES = Set.of("json", "xml", "event-stream", "x-www-form-urlencoded",
			"javascript", "x-ndjson");

	/**
	 * A JSON string of 1,024+ base64 characters, optionally a {@code data:} URI: an image,
	 * an audio clip, a PDF. Natural text never runs that long without a space or punctuation
	 * outside the base64 alphabet.
	 */
	private static final Pattern BASE64 = Pattern.compile("\"(?:data:([\\w.+-]+/[\\w.+-]+);base64,)?([A-Za-z0-9+/_-]{1024,}=*)\"");

	/** Longest text field of a multipart request that is kept. */
	private static final int MAX_FIELD = 2_000;

	private final int maxBodyChars;

	private final @Nullable BlobStore blobs;

	WireCapture(int maxBodyChars) {
		this(maxBodyChars, null);
	}

	WireCapture(int maxBodyChars, @Nullable BlobStore blobs) {
		this.maxBodyChars = maxBodyChars;
		this.blobs = blobs;
	}

	static String redactHeader(String name, @Nullable String value) {
		return value == null || !SECRET_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? value : REDACTED;
	}

	/** The query string with secret parameters' values redacted, keeping the leading {@code ?}. */
	static String redactQuery(@Nullable String query) {
		if (query == null || query.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder("?");
		for (String pair : query.split("&", -1)) {
			int eq = pair.indexOf('=');
			String name = eq < 0 ? pair : pair.substring(0, eq);
			if (out.length() > 1) {
				out.append('&');
			}
			out.append(name);
			if (eq >= 0) {
				out.append('=').append(SECRET_PARAMS.contains(name.toLowerCase(Locale.ROOT)) ? REDACTED : pair.substring(eq + 1));
			}
		}
		return out.toString();
	}

	/**
	 * Records {@code bytes} on {@code event}: as {@code body} (text, media stripped, capped and
	 * then marked {@code truncated} with its full {@code size}); for a multipart request as
	 * {@code bodyKind=multipart} with its fields and file parts in {@code body} (JSON); for
	 * binary content as {@code bodyKind=binary} with the {@code contentType}, {@code size} and,
	 * when kept, {@code blobId}.
	 */
	void body(Map<String, Object> event, byte[] bytes, @Nullable String contentType, @Nullable String contentEncoding) {
		if (bytes.length == 0) {
			event.put("body", "");
			return;
		}
		byte[] decoded;
		try {
			decoded = decode(bytes, contentEncoding);
		}
		catch (IOException ex) {
			binary(event, contentType, bytes.length, null, "could not decode " + contentEncoding + ": " + ex.getMessage());
			return;
		}
		String boundary = multipartBoundary(contentType);
		if (boundary != null) {
			multipart(event, decoded, boundary);
			return;
		}
		if (!isText(contentType, decoded)) {
			binary(event, contentType, decoded.length, keep(decoded, mediaType(contentType, decoded)), null);
			return;
		}
		String text = new String(decoded, StandardCharsets.UTF_8);
		if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("event-stream")) {
			text = streamedAudio(text);
		}
		text = stripBase64(text);
		if (text.length() > this.maxBodyChars) {
			event.put("truncated", true);
			event.put("size", text.length());
			text = text.substring(0, this.maxBodyChars);
		}
		event.put("body", text);
	}

	private static void binary(Map<String, Object> event, @Nullable String contentType, int size, @Nullable String blobId,
			@Nullable String note) {
		event.put("bodyKind", "binary");
		event.put("contentType", contentType == null ? "" : contentType);
		event.put("size", size);
		if (blobId != null) {
			event.put("blobId", blobId);
		}
		if (note != null) {
			event.put("note", note);
		}
	}

	/** By the content type; an unlabeled body is text unless its first bytes include a NUL. */
	static boolean isText(@Nullable String contentType, byte[] body) {
		if (contentType == null || contentType.isBlank()) {
			for (int i = 0; i < Math.min(body.length, 512); i++) {
				if (body[i] == 0) {
					return false;
				}
			}
			return true;
		}
		String type = contentType.toLowerCase(Locale.ROOT);
		return type.startsWith("text/") || TEXT_TYPES.stream().anyMatch(type::contains);
	}

	/**
	 * Replaces inline base64 payloads by a marker, keeping the JSON valid. The bytes are kept
	 * as a blob when a store is given and they are media: typed by the {@code data:} URI, or
	 * recognized by their first bytes. Anything else that long and base64 (an embedding vector
	 * in base64, a signature) is only cut, so it never fills the blob budget.
	 */
	String stripBase64(String text) {
		Matcher m = BASE64.matcher(text);
		if (!m.find()) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		do {
			String payload = m.group(2);
			// The type from the data: URI, else from the first bytes (16 base64 characters are 12 bytes,
			// enough for every magic number); only media is decoded whole, so an embedding vector or a
			// signature in base64 costs a prefix.
			String type = m.group(1) != null ? m.group(1) : sniff(decodeBase64(payload.substring(0, 16)));
			byte[] bytes = type == null || this.blobs == null ? null : decodeBase64(payload);
			String blobId = bytes == null ? null : keep(bytes, type);
			m.appendReplacement(out, Matcher.quoteReplacement("\"" + marker(payload.length(), type, blobId) + "\""));
		}
		while (m.find());
		m.appendTail(out);
		return out.toString();
	}

	/** The flat {@code "audio":{...}} object of a Chat Completions delta, and the base64 {@code data} in it. */
	private static final Pattern AUDIO_OBJECT = Pattern.compile("\"audio\"\\s*:\\s*\\{([^{}]*)\\}");

	private static final Pattern AUDIO_DATA = Pattern.compile("\"data\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");

	/**
	 * Audio a chat model streams back (Chat Completions with {@code modalities: ["text",
	 * "audio"]}) comes as small base64 deltas, each under the size that {@link #stripBase64}
	 * looks at. Put together they are the answer's audio: raw 16-bit PCM at 24 kHz, which is
	 * what OpenAI streams, given a WAV header so a browser can play it (an already typed format
	 * is kept as it is). The whole is kept as one blob, the first delta's {@code data} carries
	 * the marker, the others are emptied. Anything odd (a delta that doesn't decode) leaves the
	 * stream as it was.
	 */
	String streamedAudio(String text) {
		if (!text.contains("\"audio\"") || !text.contains("\"data\"")) {
			return text;
		}
		java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
		List<int[]> spans = new ArrayList<>(); // start and end of each data value in text
		int chars = 0;
		Matcher object = AUDIO_OBJECT.matcher(text);
		while (object.find()) {
			Matcher data = AUDIO_DATA.matcher(object.group(1));
			if (!data.find()) {
				continue;
			}
			byte[] chunk = decodeBase64(data.group(1));
			if (chunk == null) {
				return text;
			}
			pcm.writeBytes(chunk);
			chars += data.group(1).length();
			spans.add(new int[] { object.start(1) + data.start(1), object.start(1) + data.end(1) });
		}
		if (spans.isEmpty()) {
			return text;
		}
		byte[] bytes = pcm.toByteArray();
		String type = sniff(bytes);
		if (type == null) {
			bytes = wav(bytes, 24_000, 1, 16);
			type = "audio/wav";
		}
		String blobId = keep(bytes, type);
		StringBuilder out = new StringBuilder(text.length());
		int at = 0;
		for (int i = 0; i < spans.size(); i++) {
			out.append(text, at, spans.get(i)[0]);
			if (i == 0) {
				out.append(marker(chars, type, blobId));
			}
			at = spans.get(i)[1];
		}
		out.append(text, at, text.length());
		return out.toString();
	}

	/** Raw PCM samples in a WAV container: the 44-byte header, then the samples as they are. */
	static byte[] wav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
		int byteRate = sampleRate * channels * bitsPerSample / 8;
		java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
			.put("WAVE".getBytes(StandardCharsets.US_ASCII)).put("fmt ".getBytes(StandardCharsets.US_ASCII))
			.putInt(16).putShort((short) 1).putShort((short) channels).putInt(sampleRate).putInt(byteRate)
			.putShort((short) (channels * bitsPerSample / 8)).putShort((short) bitsPerSample)
			.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
		byte[] out = new byte[44 + pcm.length];
		System.arraycopy(header.array(), 0, out, 0, 44);
		System.arraycopy(pcm, 0, out, 44, pcm.length);
		return out;
	}

	static String marker(int chars, @Nullable String type, @Nullable String blobId) {
		return "<base64 " + chars + " chars" + (type == null ? "" : " " + type) + (blobId == null ? "" : " blob:" + blobId) + ">";
	}

	private @Nullable String keep(byte[] bytes, String contentType) {
		return this.blobs == null ? null : this.blobs.put(bytes, contentType);
	}

	private static byte @Nullable [] decodeBase64(String payload) {
		try {
			return Base64.getDecoder().decode(payload);
		}
		catch (IllegalArgumentException ex) {
			try {
				return Base64.getUrlDecoder().decode(payload);
			}
			catch (IllegalArgumentException again) {
				return null;
			}
		}
	}

	/** The media type of a binary body: the header's, else sniffed from the first bytes. */
	static String mediaType(@Nullable String contentType, byte[] bytes) {
		if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase(Locale.ROOT).contains("octet-stream")) {
			return contentType;
		}
		String sniffed = sniff(bytes);
		return sniffed != null ? sniffed : "application/octet-stream";
	}

	/** The media type by magic bytes, for the formats models send and receive, or null. */
	static @Nullable String sniff(byte @Nullable [] b) {
		if (b == null) {
			return null;
		}
		if (starts(b, 0x89, 'P', 'N', 'G')) {
			return "image/png";
		}
		if (starts(b, 0xFF, 0xD8, 0xFF)) {
			return "image/jpeg";
		}
		if (starts(b, 'G', 'I', 'F', '8')) {
			return "image/gif";
		}
		if (starts(b, 'R', 'I', 'F', 'F') && b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
			return "image/webp";
		}
		if (starts(b, 'R', 'I', 'F', 'F') && b.length > 11 && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') {
			return "audio/wav";
		}
		if (starts(b, '%', 'P', 'D', 'F')) {
			return "application/pdf";
		}
		if (starts(b, 'I', 'D', '3') || starts(b, 0xFF, 0xFB) || starts(b, 0xFF, 0xF3) || starts(b, 0xFF, 0xF2)) {
			return "audio/mpeg";
		}
		if (starts(b, 'O', 'g', 'g', 'S')) {
			return "audio/ogg";
		}
		if (starts(b, 'f', 'L', 'a', 'C')) {
			return "audio/flac";
		}
		if (b.length > 11 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
			return "audio/mp4";
		}
		return null;
	}

	private static boolean starts(byte[] b, int... magic) {
		if (b.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if ((b[i] & 0xFF) != magic[i]) {
				return false;
			}
		}
		return true;
	}

	/** The boundary of a multipart content type, or null. */
	static @Nullable String multipartBoundary(@Nullable String contentType) {
		if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
			return null;
		}
		for (String param : contentType.split(";")) {
			String p = param.trim();
			if (p.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
				String boundary = p.substring("boundary=".length()).trim();
				if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) {
					boundary = boundary.substring(1, boundary.length() - 1);
				}
				return boundary.isEmpty() ? null : boundary;
			}
		}
		return null;
	}

	/**
	 * A multipart request (e.g. an audio file for transcription) as its text fields and file
	 * parts; a file part's bytes are kept as a blob. {@code body} holds them as JSON, so the UI
	 * reads the request like any other.
	 */
	private void multipart(Map<String, Object> event, byte[] bytes, String boundary) {
		// Scanned in place: no copy of the body, one of each file part (the blob).
		Map<String, Object> fields = new LinkedHashMap<>();
		List<Map<String, Object>> files = new ArrayList<>();
		byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
		byte[] next = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
		byte[] headersEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
		int at = indexOf(bytes, delimiter, 0);
		while (at >= 0) {
			int start = at + delimiter.length;
			if (start + 1 < bytes.length && bytes[start] == '-' && bytes[start + 1] == '-') {
				break; // the closing delimiter
			}
			int partEnd = indexOf(bytes, next, start);
			if (partEnd < 0) {
				break;
			}
			int blank = indexOf(bytes, headersEnd, start);
			if (blank >= 0 && blank < partEnd) {
				String headers = new String(bytes, start, blank - start, StandardCharsets.ISO_8859_1);
				int contentStart = blank + headersEnd.length;
				String name = headerParam(headers, "name");
				String filename = headerParam(headers, "filename");
				if (name != null && filename == null) {
					String value = new String(bytes, contentStart, partEnd - contentStart, StandardCharsets.UTF_8);
					fields.put(name, value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD) + "…");
				}
				else if (name != null) {
					byte[] file = java.util.Arrays.copyOfRange(bytes, contentStart, partEnd);
					String type = mediaType(headerValue(headers, "content-type"), file);
					Map<String, Object> f = new LinkedHashMap<>();
					f.put("name", name);
					f.put("filename", filename);
					f.put("contentType", type);
					f.put("size", file.length);
					String blobId = keep(file, type);
					if (blobId != null) {
						f.put("blobId", blobId);
					}
					files.add(f);
				}
			}
			at = partEnd + 2;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("fields", fields);
		body.put("files", files);
		event.put("bodyKind", "multipart");
		event.put("body", toJson(body));
	}

	/** The first index of {@code needle} in {@code hay} at or after {@code from}, or -1. */
	static int indexOf(byte[] hay, byte[] needle, int from) {
		outer: for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static @Nullable String headerParam(String headers, String param) {
		Matcher m = Pattern.compile("(?i)[;\\s]" + param + "=\"([^\"]*)\"").matcher(headers);
		return m.find() ? m.group(1) : null;
	}

	private static @Nullable String headerValue(String headers, String header) {
		for (String line : headers.split("\r\n")) {
			int colon = line.indexOf(':');
			if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(header)) {
				return line.substring(colon + 1).trim();
			}
		}
		return null;
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	static String toJson(Object value) {
		return JSON.writeValueAsString(value);
	}

	private static byte[] decode(byte[] bytes, @Nullable String contentEncoding) throws IOException {
		if (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
			try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
				return in.readAllBytes();
			}
		}
		return bytes;
	}

}
