package org.springaicommunity.inspector.server;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Loads a run exported from the UI: its events, and the media they point at. The UI appends
 * the blobs the inspector still held when the run was exported as {@code blob} events
 * ({@code id, contentType, data} in base64, see EVENTS.md); they go back into the
 * {@link BlobStore} under the same ids, so the markers in the events find their previews
 * again, and are not kept as events. An item that doesn't decode or doesn't fit is skipped:
 * the run is still shown, with that marker only.
 */
@Component
public class RunImporter {

	private final EventStore store;

	private final BlobStore blobs;

	public RunImporter(EventStore store, BlobStore blobs) {
		this.store = store;
		this.blobs = blobs;
	}

	/**
	 * @return the new run id
	 * @throws IllegalArgumentException for a recording in a newer format than this server reads
	 */
	public String importRun(List<Map<String, Object>> events, String source) {
		EventStore.requireReadable(events); // refuses a newer format before anything is kept
		// The media first: a browser draws an event as it arrives and fetches its previews right away.
		for (Map<String, Object> event : events) {
			if ("blob".equals(event.get("type")) && event.get("id") instanceof String id
					&& BlobStore.CLIENT_ID.matcher(id).matches() && event.get("data") instanceof String data) {
				try {
					byte[] bytes = Base64.getDecoder().decode(data);
					String type = event.get("contentType") instanceof String t && !t.isBlank() ? t : WireCapture.mediaType(null, bytes);
					this.blobs.put(id, bytes, type);
				}
				catch (IllegalArgumentException ex) {
					// not base64: the marker stays without a preview
				}
			}
		}
		return this.store.importRun(events, source);
	}

}
