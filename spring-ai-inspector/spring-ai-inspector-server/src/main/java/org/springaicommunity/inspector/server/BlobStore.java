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

	/** Ids the starter mints itself: hex, 8 to 32 characters. */
	static final java.util.regex.Pattern CLIENT_ID = java.util.regex.Pattern.compile("[a-f0-9]{8,32}");

	/** Keeps the bytes and returns their id, or null when they don't fit the limits. */
	public synchronized @Nullable String put(byte[] bytes, String contentType) {
		return put(UUID.randomUUID().toString().replace("-", "").substring(0, 16), bytes, contentType);
	}

	/** Keeps the bytes under a given id (the starter's), replacing an earlier item with that id. */
	public synchronized @Nullable String put(String id, byte[] bytes, String contentType) {
		if (bytes.length == 0 || bytes.length > this.maxBlobSize || bytes.length > this.maxTotalBytes) {
			return null;
		}
		Blob before = this.blobs.remove(id);
		if (before != null) {
			this.totalBytes -= before.bytes().length;
		}
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
