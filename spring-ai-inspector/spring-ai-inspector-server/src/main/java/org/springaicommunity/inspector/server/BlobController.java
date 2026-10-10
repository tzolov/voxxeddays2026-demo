package org.springaicommunity.inspector.server;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves the media the recorder kept (see {@link BlobStore}) to the UI. */
@RestController
public class BlobController {

	private final BlobStore blobs;

	public BlobController(BlobStore blobs) {
		this.blobs = blobs;
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
