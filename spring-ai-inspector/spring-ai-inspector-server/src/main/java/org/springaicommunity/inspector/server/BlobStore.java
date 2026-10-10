package org.springaicommunity.inspector.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Media kept out of the events: images, audio and documents that travel inline as base64
 * or as binary bodies. The events carry a marker with the blob's id; the UI fetches the
 * bytes from {@code /api/blobs/{id}} to show the image or play the audio. In memory, under
 * a byte budget (the oldest go first), cleared with the events. Not part of exports: a
 * replayed or imported run shows the markers without previews.
 */
@Component
public class BlobStore {

	record Blob(String id, String contentType, byte[] bytes) {
	}

	private final Map<String, Blob> blobs = new LinkedHashMap<>();

	private final long maxTotalBytes;

	private final int maxBlobSize;

	private long totalBytes;

	@Autowired
	public BlobStore(InspectorProperties properties) {
		this(properties.maxBlobBytes(), properties.maxBlobSize());
	}

	BlobStore(long maxTotalBytes, int maxBlobSize) {
		this.maxTotalBytes = maxTotalBytes;
		this.maxBlobSize = maxBlobSize;
	}

	/** Keeps the bytes and returns their id, or null when they don't fit the limits. */
	public synchronized @Nullable String put(byte[] bytes, String contentType) {
		if (bytes.length == 0 || bytes.length > this.maxBlobSize || bytes.length > this.maxTotalBytes) {
			return null;
		}
		String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		this.blobs.put(id, new Blob(id, contentType, bytes));
		this.totalBytes += bytes.length;
		var oldest = this.blobs.entrySet().iterator();
		while (this.totalBytes > this.maxTotalBytes && oldest.hasNext()) {
			this.totalBytes -= oldest.next().getValue().bytes().length;
			oldest.remove();
		}
		return id;
	}

	public synchronized @Nullable Blob get(String id) {
		return this.blobs.get(id);
	}

	public synchronized void clear() {
		this.blobs.clear();
		this.totalBytes = 0;
	}

	synchronized int size() {
		return this.blobs.size();
	}

}
