import { replay, stopReplay } from './replay.js';
import { adapterOf } from './providers.js';
import { ensureRun, state } from './state.js';
import { recordedTs } from './util.js';

/** The event format this UI reads (EVENTS.md); an event with a higher `v` is from a newer starter or server. */
export const EVENTS_VERSION = 1;

// ---------------------------------------------------------------- event handling
export function handle(ev) {
	if (ev.type === 'clear') {
		if (replay) stopReplay();
		state.runs.clear(); state.selected = null; state.open.clear(); state.tabs.clear();
		return;
	}
	const run = ensureRun(ev.runId || 'unattributed', ev.ts);
	run.events.push(ev);
	// Shown as best as this UI can: fields it doesn't know are ignored, the header says so.
	if ((ev.v || 0) > EVENTS_VERSION) run.newerFormat = Math.max(run.newerFormat || 0, ev.v);
	switch (ev.type) {
		case 'run-start':
			run.app = ev.app || run.app; run.model = ev.model; run.pid = ev.pid;
			run.imported = ev.imported; run.replayOf = ev.replayOf;
			run.routed = new Set(ev.routed || []); // providers whose calls are recorded on the wire
			// The starter announces the run again after the inspector was unreachable (e.g. restarted):
			// the run keeps its start and the view stays where it is.
			if (!ev.reannounce) { run.started = ev.ts; if (state.follow) state.selected = run.id; }
			break;
		case 'run-end':
			run.ended = ev.ts;
			break;
		case 'client-request': {
			const call = { id: ev.callId, req: ev, resp: null, modelCalls: [], items: [], wires: [], searches: [], memory: {}, seq: ev.seq,
				num: ++run.callCount, parent: run.calls.get(ev.parentId) || null, run,
				inferred: !!ev.parentInferred, linkedFrom: ev.linkedFrom || null };
			run.calls.set(call.id, call);
			(call.parent ? call.parent.items : run.items).push({ kind: 'call', ref: call });
			if (call.linkedFrom) linkRemoteCall(call);
			break;
		}
		case 'client-response': {
			const call = run.calls.get(ev.callId);
			if (call) call.resp = ev;
			break;
		}
		case 'model-request': {
			// The advisor right before the model saw this call; shown as a round-trip of its own
			// when no HTTP traffic was recorded for it (see render/models.js).
			const call = run.calls.get(ev.parentId);
			const mc = { id: ev.callId, req: ev, resp: null, call, run, num: (run.modelCallCount = (run.modelCallCount || 0) + 1) };
			run.modelCalls.set(mc.id, mc);
			if (call) { call.modelCalls.push(mc); call.items.push({ kind: 'model', ref: mc }); }
			break;
		}
		case 'model-response': {
			const mc = run.modelCalls.get(ev.callId);
			if (mc) mc.resp = ev;
			break;
		}
		case 'wire-request': {
			const wire = { id: ev.wireId, req: ev, resp: null, num: run.wireList.length + 1,
				prev: run.wireList[run.wireList.length - 1] || null };
			run.wires.set(wire.id, wire);
			run.wireList.push(wire);
			// The model call this HTTP round-trip served (stamped by the proxy): that call isn't shown again.
			if (ev.modelCallId) (run.wiredModelCalls ||= new Set()).add(ev.modelCallId);
			if (adapterOf(wire)?.kind === 'embedding') (run.httpEmbeddings ||= []).push(wire);
			const call = run.calls.get(ev.clientCallId);
			if (call) { call.items.push({ kind: 'wire', ref: wire }); call.wires.push(wire); }
			else run.items.push({ kind: 'wire', ref: wire });
			break;
		}
		case 'wire-response': {
			const wire = run.wires.get(ev.wireId);
			if (wire) wire.resp = ev;
			break;
		}
		case 'tool-start': {
			const tool = { id: ev.toolId, start: ev, end: null };
			run.tools.set(tool.id, tool);
			const call = run.calls.get(ev.clientCallId);
			(call ? call.items : run.items).push({ kind: 'tool', ref: tool });
			break;
		}
		case 'tool-end': {
			const tool = run.tools.get(ev.toolId);
			if (tool) tool.end = ev;
			break;
		}
		case 'mcp-message': {
			// The starter attributes each message to its MCP tool run (toolId); the rest
			// (initialize, tools/list, ...) belong to the connection itself.
			const tool = ev.toolId && run.tools.get(ev.toolId);
			if (tool) (tool.mcp ||= []).push(ev);
			else {
				run.mcp ||= new Map();
				if (!run.mcp.has(ev.connection)) run.mcp.set(ev.connection, []);
				run.mcp.get(ev.connection).push(ev);
			}
			break;
		}
		case 'embedding-call': {
			// An embedding call seen at the model bean. Remote models' calls are on the wire already;
			// one with no HTTP round-trip recorded meanwhile (a model running in the JVM, e.g. jinfer)
			// becomes a round-trip of its own, so it is shown, counted and drawn like the others.
			if (run.routed?.has(ev.provider) || onTheWire(run, ev)) break;
			const wire = inProcessEmbedding(run, ev, recordedTs(ev) - (ev.durationMs || 0));
			run.wires.set(wire.id, wire);
			run.wireList.push(wire);
			const call = run.calls.get(ev.clientCallId);
			(call ? call.items : run.items).push({ kind: 'wire', ref: wire });
			break;
		}
		case 'model-call': {
			// A model bean the starter observed (an image, speech, transcription or moderation model) that
			// made no HTTP round-trip the proxy saw: a model in the JVM, or an SDK with no base URL to
			// rewrite. Shown as a round-trip of its own, through the same adapter as its HTTP twin; a
			// routed provider's calls are already on the wire.
			if (run.routed?.has(ev.provider) || onTheWireKind(run, ev)) break;
			const wire = inProcessModelCall(run, ev);
			run.wires.set(wire.id, wire);
			run.wireList.push(wire);
			const call = run.calls.get(ev.clientCallId);
			(call ? call.items : run.items).push({ kind: 'wire', ref: wire });
			break;
		}
		case 'vector-start': {
			// A search or an add starting: shown (open) right away, completed by its end event, so
			// the embedding calls the store makes meanwhile are drawn inside it as they happen.
			const call = run.calls.get(ev.clientCallId);
			const op = { ...ev, pending: true, start: ev, ...(ev.op === 'search' ? { results: [] } : {}) };
			(run.vectorOps ||= new Map()).set(ev.opId, op);
			if (ev.op === 'search') (call ? call.searches : (run.searches ||= [])).push(op);
			else (call ? call.items : run.items).push({ kind: 'ingest', ref: op });
			break;
		}
		case 'vector-search': {
			const started = ev.opId && run.vectorOps?.get(ev.opId);
			if (started) { Object.assign(started, ev, { pending: false }); break; }
			const call = run.calls.get(ev.clientCallId);
			(call ? call.searches : (run.searches ||= [])).push(ev);
			break;
		}
		case 'vector-add':
		case 'vector-delete': {
			const started = ev.opId && run.vectorOps?.get(ev.opId);
			if (started) { Object.assign(started, ev, { pending: false }); break; }
			// Inside the ChatClient call that added them (e.g. a tool search indexing its tools), else the run's.
			const call = run.calls.get(ev.clientCallId);
			(call ? call.items : run.items).push({ kind: 'ingest', ref: ev });
			break;
		}
		case 'memory-snapshot': {
			const call = run.calls.get(ev.clientCallId);
			if (call) { call.memory[ev.phase] = ev.stores; call.memory[ev.phase + 'Seq'] = ev.seq; }
			break;
		}
	}
}

/**
 * Whether an embedding call was recorded on the wire already: an HTTP embedding round-trip of
 * the same provider and ChatClient call, made while it ran. Only recent round-trips are looked at.
 */
function onTheWire(run, ev) {
	const end = recordedTs(ev); const start = end - (ev.durationMs || 0);
	const http = run.httpEmbeddings || [];
	for (let i = http.length - 1; i >= 0; i--) {
		const w = http[i]; const at = recordedTs(w.req);
		if (at < start) break;
		if (at <= end && w.req.provider === ev.provider && (w.req.clientCallId ?? null) === (ev.clientCallId ?? null)) return true;
	}
	return false;
}

/** The proxy path of a model kind, so the synthetic round-trip picks the same adapter as an HTTP one. */
const PATH_OF_KIND = { image: '/v1/images/generations', speech: '/v1/audio/speech', transcription: '/v1/audio/transcriptions', moderation: '/v1/moderations' };

/** Whether an HTTP round-trip of the same kind, provider and call was recorded while the model call ran. */
function onTheWireKind(run, ev) {
	const end = recordedTs(ev); const start = end - (ev.durationMs || 0);
	for (let i = run.wireList.length - 1; i >= 0; i--) {
		const w = run.wireList[i]; const at = recordedTs(w.req);
		if (at < start) break;
		if (at <= end && !w.inProcess && w.req.provider === ev.provider && adapterOf(w)?.kind === ev.kind
			&& (w.req.clientCallId ?? null) === (ev.clientCallId ?? null)) return true;
	}
	return false;
}

/** A round-trip for a model call made in the JVM, in the adapter's normalized shape (see providers.js). */
function inProcessModelCall(run, ev) {
	const replayed = ev.recordedTs != null;
	const start = recordedTs(ev) - (ev.durationMs || 0);
	const wire = { id: 'mc:' + ev.modelCallId, inProcess: true, inProcessLabel: `${ev.modelType || 'Model'} call · no HTTP`,
		num: run.wireList.length + 1, prev: run.wireList[run.wireList.length - 1] || null,
		req: { provider: ev.provider, method: '', path: PATH_OF_KIND[ev.kind] || '/' + ev.kind, url: '', headers: {}, seq: ev.seq - 0.5,
			ts: ev.ts - (ev.durationMs || 0), ...(replayed ? { recordedTs: start } : {}), clientCallId: ev.clientCallId,
			body: JSON.stringify(ev.request ?? {}) },
		resp: { seq: ev.seq, ts: ev.ts, ...(replayed ? { recordedTs: ev.recordedTs } : {}), status: ev.error ? 'error' : 200, error: ev.error,
			durationMs: ev.durationMs, headers: {}, body: JSON.stringify(ev.response ?? {}) } };
	wire._nreq = { params: {}, system: null, tools: [], messages: [], ...(ev.request || {}) };
	wire._nresp = ev.error ? { error: ev.error } : { blocks: [], usage: null, ...(ev.response || {}) };
	return wire;
}

/** A round-trip for an embedding call made in the JVM, normalized like an HTTP one (see providers.js). */
function inProcessEmbedding(run, ev, start) {
	const replayed = ev.recordedTs != null;
	const wire = { id: 'emb:' + ev.embeddingId, inProcess: true, num: run.wireList.length + 1, prev: run.wireList[run.wireList.length - 1] || null,
		req: { provider: ev.provider, method: '', path: '/embeddings', url: '', headers: {}, seq: ev.seq - 0.5,
			ts: ev.ts - (ev.durationMs || 0), ...(replayed ? { recordedTs: start } : {}),
			body: JSON.stringify({ model: ev.model, inputs: ev.inputs, sample: ev.sample }) },
		resp: { seq: ev.seq, ts: ev.ts, ...(replayed ? { recordedTs: ev.recordedTs } : {}), status: ev.error ? 'error' : 200, error: ev.error,
			durationMs: ev.durationMs, headers: {}, body: JSON.stringify({ model: ev.model, vectors: ev.vectors, dimensions: ev.dimensions, usage: ev.usage, error: ev.error }) } };
	wire._nreq = { params: { model: ev.model ?? undefined }, inputs: ev.sample || [], total: ev.inputs, system: null, tools: [], messages: [] };
	wire._nresp = ev.error ? { error: ev.error }
		: { model: ev.model, vectors: ev.vectors, dimensions: ev.dimensions, blocks: [], usage: ev.usage ? { input: ev.usage.input, output: 0 } : null };
	return wire;
}

// A call in another JVM, linked by the server (by timing) to the tool call that was open
// in this run when it started, e.g. an A2A remote agent serving 18's Task tool.
export function linkRemoteCall(call) {
	const caller = state.runs.get(call.linkedFrom.runId);
	const tool = caller && caller.tools.get(call.linkedFrom.toolId);
	if (tool) (tool.remoteCalls ||= []).push(call);
}
