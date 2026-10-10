package org.springaicommunity.inspector;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.util.context.ContextView;

/**
 * How the inspector's events find each other: by Micrometer observation parentage, not by
 * thread. Spring AI observes every ChatClient call, every advisor in its chain, every model
 * call and every tool execution, each nested in the one that caused it; the tool loop sets
 * the tool observation's parent explicitly, and the stream advisors carry theirs in the
 * Reactor context. The inspector tags those observations with its own ids:
 * <ul>
 * <li>{@link #CALL_ID} on the CLIENT advisor's observation of a ChatClient call;</li>
 * <li>{@link #MODEL_CALL_ID} on the MODEL advisor's observation of a model round-trip;</li>
 * <li>{@link #TOOL_ID} on a tool execution's observation.</li>
 * </ul>
 * Anything that runs inside them (a tool, a vector search, an embedding, the model's HTTP
 * request, a sub-agent's ChatClient call) looks up the nearest tag by walking the parents
 * of the current observation. A streamed call, whose tools run on Reactor threads, is
 * attributed like a blocking one, and two concurrent calls on one thread pool never mix.
 *
 * <p>Tags are written once, when the observation starts, before anything runs inside it;
 * readers come later, on this thread or after a hand-off to another. The context's map is
 * not synchronized, so no tag may be added once children are running.
 *
 * <p>Nothing is found when there is no observation: a {@code ChatClient} built with
 * {@code ChatClient.builder(chatModel)} uses {@code ObservationRegistry.NOOP}, so its
 * calls (which the inspector's advisors don't see either) and what runs inside them stay
 * unattributed, and the server falls back to attribution by timing.
 */
final class InspectorCorrelation {

	static final String CALL_ID = "inspector.callId";

	static final String MODEL_CALL_ID = "inspector.modelCallId";

	static final String TOOL_ID = "inspector.toolId";

	/** Headers stamped on the model's HTTP requests, which the inspector's proxy reads. */
	static final String CALL_HEADER = "X-Inspector-Call";

	static final String MODEL_CALL_HEADER = "X-Inspector-Model-Call";

	/** Parent chains are short; a cycle (never seen) must not hang the application. */
	private static final int MAX_DEPTH = 64;

	/** The call a new ChatClient call is nested in, and the tool of that call that made it. */
	record Parent(String callId, String toolId) {
	}

	private InspectorCorrelation() {
	}

	/**
	 * The observation current on this thread, or null. A ChatClient without a registry
	 * still opens scopes, of no-op observations with no parent (so that tracing doesn't
	 * attach to whatever is outside); those are skipped, back to the enclosing real one, so
	 * such a client called from an observed tool still nests under it.
	 */
	static Observation current() {
		try {
			ObservationRegistry registry = ObservationThreadLocalAccessor.getInstance().getObservationRegistry();
			int depth = 0;
			for (Observation.Scope scope = registry.getCurrentObservationScope(); scope != null
					&& depth++ < MAX_DEPTH; scope = scope.getPreviousObservationScope()) {
				Observation observation = scope.getCurrentObservation();
				if (observation != null && !isNoop(observation)) {
					return observation;
				}
			}
			return null;
		}
		catch (RuntimeException | LinkageError ex) {
			return null;
		}
	}

	/** The observation a reactive chain carries, else the thread's current one, or null. */
	static Observation fromContext(ContextView context) {
		try {
			Object observation = context.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
			return observation instanceof Observation o && !isNoop(o) ? o : current();
		}
		catch (RuntimeException | LinkageError ex) {
			return current();
		}
	}

	/** Micrometer's no-op observations, including the scope-handling one, which has no name. */
	static boolean isNoop(Observation observation) {
		return observation.isNoop() || observation.getContextView().getName() == null;
	}

	static void tag(Observation observation, String key, String value) {
		if (observation != null && !isNoop(observation)) {
			observation.getContext().put(key, value);
		}
	}

	/** The value of {@code key} on {@code from} or its nearest ancestor, or null. */
	static String find(ObservationView from, String key) {
		int depth = 0;
		for (ObservationView o = from; o != null && depth++ < MAX_DEPTH; o = o.getContextView().getParentObservation()) {
			Object value = o.getContextView().get(key);
			if (value instanceof String s) {
				return s;
			}
		}
		return null;
	}

	/**
	 * For a ChatClient call starting under {@code from}: the nearest enclosing call, and the
	 * tool between them that made it, if any (a tool of an outer call does not count).
	 */
	static Parent parentOf(ObservationView from) {
		String tool = null;
		int depth = 0;
		for (ObservationView o = from; o != null && depth++ < MAX_DEPTH; o = o.getContextView().getParentObservation()) {
			Object call = o.getContextView().get(CALL_ID);
			if (call instanceof String id) {
				return new Parent(id, tool);
			}
			Object toolId = o.getContextView().get(TOOL_ID);
			if (tool == null && toolId instanceof String id) {
				tool = id;
			}
		}
		return new Parent(null, null);
	}

	/** The innermost ChatClient call running on this thread, if any. */
	static String currentCallId() {
		return find(current(), CALL_ID);
	}

	/** The correlation headers for an HTTP request made under {@code from}: none when unknown. */
	static Map<String, String> headers(ObservationView from) {
		Map<String, String> headers = new LinkedHashMap<>();
		String call = find(from, CALL_ID);
		if (call != null) {
			headers.put(CALL_HEADER, call);
		}
		String modelCall = find(from, MODEL_CALL_ID);
		if (modelCall != null) {
			headers.put(MODEL_CALL_HEADER, modelCall);
		}
		return headers;
	}

}
