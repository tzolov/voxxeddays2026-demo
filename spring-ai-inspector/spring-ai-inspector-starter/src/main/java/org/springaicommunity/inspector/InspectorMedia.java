package org.springaicommunity.inspector;

import java.util.Locale;

/** The media type of bytes a model produced or consumed, when the code around doesn't say. */
final class InspectorMedia {

	private InspectorMedia() {
	}

	/** Media larger than this is described but not uploaded (the inspector keeps items up to its own limit). */
	static final int MAX_BYTES = 16 * 1024 * 1024;

	/** The bytes of a base64 payload, standard or URL-safe alphabet; null when it is neither. */
	static byte[] decodeBase64(String data) {
		try {
			return java.util.Base64.getDecoder().decode(data);
		}
		catch (IllegalArgumentException ex) {
			try {
				return java.util.Base64.getUrlDecoder().decode(data);
			}
			catch (IllegalArgumentException again) {
				return null;
			}
		}
	}

	/** The given type unless it says nothing, else sniffed from the first bytes, else octet-stream. */
	static String type(String contentType, byte[] bytes) {
		if (contentType != null && !contentType.isBlank() && !contentType.toLowerCase(Locale.ROOT).contains("octet-stream")) {
			return contentType;
		}
		String sniffed = sniff(bytes);
		return sniffed != null ? sniffed : "application/octet-stream";
	}

	/** The media type by magic bytes, for the formats models send and receive, or null. */
	static String sniff(byte[] b) {
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

	/** The media type of a speech or audio format name, or null when unknown. */
	static String audioType(String format) {
		if (format == null) {
			return null;
		}
		return switch (format.toLowerCase(Locale.ROOT)) {
			case "mp3", "mpeg", "mpga" -> "audio/mpeg";
			case "opus", "ogg", "oga" -> "audio/ogg";
			case "aac", "m4a", "mp4" -> "audio/mp4";
			case "flac" -> "audio/flac";
			case "wav", "wave" -> "audio/wav";
			case "webm" -> "audio/webm";
			case "pcm" -> "audio/pcm";
			default -> null;
		};
	}

}
