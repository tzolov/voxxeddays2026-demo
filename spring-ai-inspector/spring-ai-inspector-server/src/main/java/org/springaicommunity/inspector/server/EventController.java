package org.springaicommunity.inspector.server;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class EventController {

	private final EventStore store;

	private final BlobStore blobs;

	public EventController(EventStore store, BlobStore blobs) {
		this.store = store;
		this.blobs = blobs;
	}

	/** Signature the starter looks for before routing an application's traffic here. */
	static final String SIGNATURE = "spring-ai-inspector";

	/**
	 * Probed by instrumented applications at startup. The body identifies this server, so
	 * an unrelated service that happens to answer 200 on the same port is never mistaken
	 * for the inspector.
	 */
	@GetMapping("/ping")
	public Map<String, Object> ping() {
		return Map.of("name", SIGNATURE, "api", 1, "events", EventStore.EVENTS_VERSION);
	}

	@PostMapping("/events")
	public void event(@RequestBody Map<String, Object> event) {
		this.store.add(event);
	}

	@GetMapping("/stream")
	public SseEmitter stream() {
		return this.store.subscribe();
	}

	/**
	 * Loads a run previously saved with the UI's Export button: the array of its events, in
	 * the format of EVENTS.md. A recording in a newer format than this server reads is refused
	 * with a 400 naming both versions.
	 */
	@PostMapping("/import")
	public Map<String, Object> importRun(@RequestBody List<Map<String, Object>> events,
			@RequestParam(defaultValue = "upload") String name) {
		return Map.of("runId", this.store.importRun(events, name), "events", events.size());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> rejected(IllegalArgumentException ex) {
		return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(ex.getMessage())));
	}

	@DeleteMapping("/events")
	public void clear() {
		this.store.clear();
		this.blobs.clear();
	}

}
