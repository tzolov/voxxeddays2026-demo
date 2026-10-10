package org.springaicommunity.inspector.server;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live fan-out of the events to the browsers over SSE.
 *
 * <p>Events are sent from a single dispatcher thread, so a slow or stalled browser tab never
 * delays the applications posting events. A new tab gets the history on a thread of its own
 * and live events are queued for it meanwhile, so a tab catching up never delays the others.
 * (A tab that stalls once live does hold the dispatcher for its send, as before.)
 */
final class SseBroadcaster {

	private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

	/** Sends to browsers, in event order, off the recording lock. */
	private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "inspector-sse-dispatcher");
		thread.setDaemon(true);
		return thread;
	});

	/** Replays the history to each new browser on a thread of its own, so other tabs stay live. */
	private final ExecutorService catchUp = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r, "inspector-sse-catch-up");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * A browser connection. Until its history is replayed, live events are kept in
	 * {@code backlog} (never waiting for the replay's sends) and go out right after it;
	 * {@code lastSeq} drops what both had.
	 */
	private static final class Subscriber {

		final SseEmitter emitter;

		long lastSeq = -1;

		/** Guarded by {@code backlog}: once true, events go straight to the emitter. */
		boolean live;

		final Deque<Map<String, Object>> backlog = new ArrayDeque<>();

		Subscriber(SseEmitter emitter) {
			this.emitter = emitter;
		}

		/** A live event: kept while the history is going out, sent otherwise. */
		void deliver(Map<String, Object> event) throws IOException {
			synchronized (this.backlog) {
				if (!this.live) {
					this.backlog.addLast(event);
					return;
				}
			}
			send(event);
		}

		/**
		 * The history is through: what arrived meanwhile goes out in batches (the backlog is
		 * only locked to take a batch, never while sending), then the events go straight through.
		 */
		void goLive() throws IOException {
			while (true) {
				List<Map<String, Object>> batch;
				synchronized (this.backlog) {
					if (this.backlog.isEmpty()) {
						this.live = true;
						return;
					}
					batch = new ArrayList<>(this.backlog);
					this.backlog.clear();
				}
				for (Map<String, Object> event : batch) {
					send(event);
				}
			}
		}

		/** Sends are sequential by construction (the history, the batches, then live events). */
		synchronized void send(Map<String, Object> event) throws IOException {
			Object seq = event.get("seq");
			if (seq instanceof Long s) {
				if (s <= this.lastSeq) {
					return; // already sent as part of the replayed history
				}
				this.lastSeq = s;
			}
			this.emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
		}

	}

	/**
	 * Registers a new browser right away (so no live event is missed), replays the history
	 * to it on a thread of its own, then lets the live events through.
	 * @param history the events recorded so far, read when the replay starts
	 */
	SseEmitter subscribe(Supplier<List<Map<String, Object>>> history) {
		SseEmitter emitter = new SseEmitter(0L);
		Subscriber subscriber = new Subscriber(emitter);
		emitter.onCompletion(() -> this.subscribers.remove(subscriber));
		emitter.onTimeout(() -> this.subscribers.remove(subscriber));
		emitter.onError(ex -> this.subscribers.remove(subscriber));
		this.subscribers.add(subscriber);
		this.catchUp.execute(() -> {
			try {
				for (Map<String, Object> event : history.get()) {
					subscriber.send(event);
				}
				subscriber.goLive();
			}
			catch (IOException | RuntimeException ex) {
				this.subscribers.remove(subscriber);
				emitter.completeWithError(ex);
			}
		});
		return emitter;
	}

	/** Hands the event to the dispatcher thread, which sends it to every browser in order. */
	void broadcast(Map<String, Object> event) {
		this.dispatcher.execute(() -> {
			for (Subscriber subscriber : this.subscribers) {
				try {
					subscriber.deliver(event);
				}
				catch (Exception ex) {
					this.subscribers.remove(subscriber);
				}
			}
		});
	}

	void shutdown() {
		this.dispatcher.shutdownNow();
		this.catchUp.shutdownNow();
	}

}
