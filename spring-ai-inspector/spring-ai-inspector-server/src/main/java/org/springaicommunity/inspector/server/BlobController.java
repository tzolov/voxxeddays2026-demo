package org.springaicommunity.inspector.server;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the media the recorder kept (see {@link BlobStore}) to the UI, and takes media the
 * starter uploads for models that don't go through the proxy (an image model in the JVM, an
 * SDK's speech model): the starter posts the bytes with their type and puts the returned id
 * in its event, where a proxied call would carry the recorder's.
 */
@RestController
public class BlobController {

	private final BlobStore blobs;

	public BlobController(BlobStore blobs) {
		this.blobs = blobs;
	}

	/** An upload; the type is the header's, or sniffed from the bytes when it says nothing. */
	@PostMapping(path = "/api/blobs", consumes = MediaType.ALL_VALUE)
	public ResponseEntity<Map<String, Object>> upload(@RequestHeader(name = "Content-Type", required = false) String contentType,
			@RequestBody byte[] bytes) {
		String type = WireCapture.mediaType(contentType, bytes);
		return stored(this.blobs.put(bytes, type), type, bytes.length);
	}

	/**
	 * An upload under the starter's own id, so its event can name the blob before the bytes
	 * arrive (the starter uploads in the background, after posting the event).
	 */
	@PutMapping(path = "/api/blobs/{id}", consumes = MediaType.ALL_VALUE)
	public ResponseEntity<Map<String, Object>> upload(@PathVariable String id,
			@RequestHeader(name = "Content-Type", required = false) String contentType, @RequestBody byte[] bytes) {
		if (!BlobStore.CLIENT_ID.matcher(id).matches()) {
			return ResponseEntity.badRequest().body(Map.of("error", "id must be 8 to 32 hex characters"));
		}
		String type = WireCapture.mediaType(contentType, bytes);
		return stored(this.blobs.put(id, bytes, type), type, bytes.length);
	}

	private static ResponseEntity<Map<String, Object>> stored(String id, String type, int size) {
		return id == null ? ResponseEntity.status(413).body(Map.of("error", "too large or empty"))
				: ResponseEntity.ok(Map.of("id", id, "size", size, "contentType", type));
	}

	@GetMapping("/api/blobs/{id}")
	public ResponseEntity<byte[]> blob(@PathVariable String id) {
		BlobStore.Blob blob = this.blobs.get(id);
		if (blob == null) {
			return ResponseEntity.notFound().build();
		}
		MediaType type;
		try {
			type = MediaType.parseMediaType(blob.contentType());
		}
		catch (RuntimeException ex) {
			type = MediaType.APPLICATION_OCTET_STREAM;
		}
		// Never rendered as a page: an SVG or HTML blob from a model is data, not markup to run.
		return ResponseEntity.ok()
			.contentType(type)
			.cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
			.header("Content-Security-Policy", "sandbox")
			.header("X-Content-Type-Options", "nosniff")
			.body(blob.bytes());
	}

}
