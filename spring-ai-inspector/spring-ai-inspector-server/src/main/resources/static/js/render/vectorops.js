import { adapterOf } from '../providers.js';
import { recordedTs } from '../util.js';

// ---------------------------------------------------------------- what ran inside what
// The tool running when something happened, and the vector store adds and searches of a call
// (or of the run) with the embedding round-trips the store made meanwhile: one set of rules,
// so the cards and the sequence view nest them the same way.

export const isEmbeddingWire = (it) => it.kind === 'wire' && adapterOf(it.ref)?.kind === 'embedding';

/** The card key of a vector store add or search, which the sequence view's arrows open. */
export const opKey = (ev) => 'vop:' + (ev.opId ?? ev.searchId ?? `${ev.store}:${ev.seq}`);

/**
 * The tool running at {@code seq}, or undefined. By timing: with tools running in parallel, the
 * first one open. A tool whose end was never recorded (e.g. a dropped event) runs until its
 * call ended ({@code callEnd}), not on forever.
 */
export function toolAt(tools, seq, callEnd = Infinity) {
	return tools.find((t) => t.start.seq < seq && seq < (t.end ? t.end.seq : callEnd));
}

/**
 * The vector store operations among `items` (adds) and `searches`, each with the embedding
 * round-trips made while it ran, and the seq it starts at. An operation reported when it
 * started is open until it ended; one of an older recording, reported only once done, spans
 * its duration before that.
 */
export function vectorOps(items, searches = []) {
	const ops = [...items.filter((i) => i.kind === 'ingest').map((i) => i.ref), ...searches].map((ev) => ({
		ev, search: ev.query !== undefined, wires: [],
		start: ev.start ? recordedTs(ev.start) : recordedTs(ev) - (ev.durationMs || 0),
		end: ev.pending ? Infinity : recordedTs(ev) }));
	const byWire = new Map();
	for (const it of items) {
		if (!isEmbeddingWire(it)) continue;
		const at = recordedTs(it.ref.req);
		const op = ops.find((o) => at >= o.start && at <= o.end);
		if (op) {
			op.wires.push(it.ref);
			byWire.set(it.ref, op);
		}
	}
	for (const op of ops) {
		const first = Math.min(...op.wires.map((w) => w.req.seq));
		op.seq = op.ev.start ? op.ev.start.seq : (Number.isFinite(first) ? first : op.ev.seq) - 0.5;
	}
	return { ops, opOf: (wire) => byWire.get(wire) };
}
