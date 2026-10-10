package org.springaicommunity.inspector.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The recorded events, oldest first: a ring buffer under a count and a byte budget, each
 * event numbered on arrival ({@code seq}). Storage only: what the events mean is
 * {@link Correlator}'s business, who sees them {@link SseBroadcaster}'s. Not thread-safe;
 * {@link EventStore} serializes the calls.
 */
final class EventLog {

	private static final int MAX_EVENTS = 20_000;

	/** Bytes a stored event costs beyond its text fields: the map, the small values. */
	private static final int EVENT_OVERHEAD = 512;

	private record Stored(Map<String, Object> event, int size) {
	}

	private final Deque<Stored> events = new ArrayDeque<>();

	private final long maxTotalBytes;

	private long totalBytes;

	private long seq;

	EventLog(long maxTotalBytes) {
		this.maxTotalBytes = maxTotalBytes;
	}

	/** Appends the event, numbering it, and drops the oldest ones beyond the budget. */
	void append(Map<String, Object> event) {
		event.put("seq", ++this.seq);
		int size = sizeOf(event);
		this.events.addLast(new Stored(event, size));
		this.totalBytes += size;
		while (this.events.size() > MAX_EVENTS || (this.totalBytes > this.maxTotalBytes && this.events.size() > 1)) {
			this.totalBytes -= this.events.pollFirst().size();
		}
	}

	/** Roughly what an event costs in memory: its text fields (bodies, results) plus overhead. */
	private static int sizeOf(Map<String, Object> event) {
		long size = EVENT_OVERHEAD;
		for (Object value : event.values()) {
			if (value instanceof String s) {
				size += 2L * s.length();
			}
		}
		return (int) Math.min(size, Integer.MAX_VALUE);
	}

	/** A copy of the events, oldest first. */
	List<Map<String, Object>> events() {
		List<Map<String, Object>> copy = new ArrayList<>(this.events.size());
		for (Stored stored : this.events) {
			copy.add(stored.event());
		}
		return copy;
	}

	/** Forgets the events; the numbering goes on, so a browser that saw the old ones skips nothing new. */
	void clear() {
		this.events.clear();
		this.totalBytes = 0;
	}

}
