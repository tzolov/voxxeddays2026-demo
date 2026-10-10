package org.springaicommunity.inspector.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;

/**
 * What of an HTTP request or response is recorded, and how. Secrets are redacted (headers,
 * query parameters), inline base64 payloads (images, audio, documents) are replaced by a
 * marker, text bodies are capped and binary bodies are recorded by size only. Nothing
 * here changes what is forwarded; it only shapes the {@code wire-*} events.
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
	private static final Pattern BASE64 = Pattern.compile("\"(data:[\\w.+-]+/[\\w.+-]+;base64,)?([A-Za-z0-9+/_-]{1024,}=*)\"");

	private final int maxBodyChars;

	WireCapture(int maxBodyChars) {
		this.maxBodyChars = maxBodyChars;
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
	 * Records {@code bytes} on {@code event}: as {@code body} (text, base64 stripped, capped
	 * and then marked {@code truncated} with its full {@code size}), or for binary content as
	 * {@code bodyKind=binary} with the {@code contentType} and {@code size} only.
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
			binary(event, contentType, bytes.length, "could not decode " + contentEncoding + ": " + ex.getMessage());
			return;
		}
		if (!isText(contentType, decoded)) {
			binary(event, contentType, decoded.length, null);
			return;
		}
		String text = stripBase64(new String(decoded, StandardCharsets.UTF_8));
		if (text.length() > this.maxBodyChars) {
			event.put("truncated", true);
			event.put("size", text.length());
			text = text.substring(0, this.maxBodyChars);
		}
		event.put("body", text);
	}

	private static void binary(Map<String, Object> event, @Nullable String contentType, int size, @Nullable String note) {
		event.put("bodyKind", "binary");
		event.put("contentType", contentType == null ? "" : contentType);
		event.put("size", size);
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

	/** Replaces inline base64 payloads by {@code "<base64 N chars>"}, keeping the JSON valid. */
	static String stripBase64(String text) {
		Matcher m = BASE64.matcher(text);
		if (!m.find()) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		do {
			String prefix = m.group(1) == null ? "" : m.group(1).substring(0, m.group(1).length() - 1) + " ";
			m.appendReplacement(out, Matcher.quoteReplacement("\"<" + prefix + "base64 " + m.group(2).length() + " chars>\""));
		}
		while (m.find());
		m.appendTail(out);
		return out.toString();
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
