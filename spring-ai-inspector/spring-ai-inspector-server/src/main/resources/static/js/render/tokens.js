// ---------------------------------------------------------------- tokens by model
// Totals per model for a run, including the remote agents its tools called (A2A), so the
// numbers cover the whole conversation. Usage is normalized by the provider adapters:
// input is all prompt tokens, cached ones included.
import { normRequest, normResponse, providerLabel, usageOf } from '../providers.js';
import { advisorOnlyCalls, isAdvisorOnly, modelCallKey, providerOf, usageOfModelCall } from './models.js';
import { pref, state } from '../state.js';
import { esc, fmtNum, isOpen } from '../util.js';

/** The model a round-trip went to, as `provider|model` (also the highlight key). */
export function wireModelKey(wire) {
	const model = normRequest(wire)?.params?.model || normResponse(wire)?.model || wire.req.provider;
	return `${wire.req.provider}|${model}`;
}

// Round-trips of a call and everything under it (nested calls, remote calls of its tools):
// HTTP ones (kind 'wire') or model calls without HTTP (kind 'model').
function collectCallWires(call, out, kind = 'wire') {
	for (const it of call.items) {
		if (it.kind === kind) out.push(it.ref);
		else if (it.kind === 'call') collectCallWires(it.ref, out, kind);
		else if (it.kind === 'tool') for (const rc of it.ref.remoteCalls || []) collectCallWires(rc, out, kind);
	}
	return out;
}

export function tokensByModel(run) {
	const rows = new Map();
	const seen = new Set();
	// One round-trip: an HTTP one, or a model call without HTTP (seen by the advisor, see models.js).
	const addUsage = (id, key, provider, label, u, remoteApp) => {
		if (seen.has(id)) return;
		seen.add(id);
		// Every model that made round-trips gets a row, also one reporting no token usage
		// (e.g. an embedding model in the JVM returning bare vectors).
		const rowKey = (remoteApp ? remoteApp + '|' : '') + key;
		const model = key.slice(provider.length + 1);
		const row = rows.get(rowKey) || { key, provider, label, model, remote: remoteApp, input: 0, output: 0, cacheRead: 0,
			cacheWrite: 0, reasoning: 0, calls: 0, reported: 0 };
		row.calls += 1;
		if (u) {
			row.input += u.input || 0;
			row.output += u.output || 0;
			row.cacheRead += u.cacheRead || 0;
			row.cacheWrite += u.cacheWrite || 0;
			row.reasoning += u.reasoning || 0;
			row.reported += 1;
		}
		rows.set(rowKey, row);
	};
	const add = (wire, remoteApp) => addUsage(wire.id, wireModelKey(wire), wire.req.provider, providerLabel(wire), usageOf(wire), remoteApp);
	for (const wire of run.wireList) add(wire, null);
	for (const mc of advisorOnlyCalls(run)) addUsage('model:' + mc.id, modelCallKey(mc), providerOf(mc), providerOf(mc), usageOfModelCall(mc), null);
	for (const tool of run.tools.values()) {
		for (const rc of tool.remoteCalls || []) {
			const app = rc.run.app.split(' · ')[0];
			for (const wire of collectCallWires(rc, [])) add(wire, app);
			for (const mc of collectCallWires(rc, [], 'model').filter(isAdvisorOnly)) {
				addUsage('model:' + mc.id, modelCallKey(mc), providerOf(mc), providerOf(mc), usageOfModelCall(mc), app);
			}
		}
	}
	const list = [...rows.values()].sort((a, b) => (b.input + b.output) - (a.input + a.output));
	const total = list.reduce((t, r) => ({ input: t.input + r.input, output: t.output + r.output, calls: t.calls + r.calls }),
		{ input: 0, output: 0, calls: 0 });
	return { rows: list, total, hasRemote: list.some((r) => r.remote) };
}

/** Compact counts for tight spots (sequence lanes): 1,234 → 1.2k. */
export function fmtCompact(n) {
	if (n == null) return '–';
	return n < 1000 ? String(n) : n < 1e6 ? (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k' : (n / 1e6).toFixed(1) + 'M';
}

export function renderTokenPanel(run) {
	const { rows, total, hasRemote } = tokensByModel(run);
	if (!rows.length) return '';
	const key = 'tokens:' + run.id;
	const max = Math.max(...rows.map((r) => r.input + r.output));
	const pct = (part, whole) => whole ? (100 * part / whole).toFixed(1) : 0;
	const rowHtml = rows.map((r) => {
		const sum = r.input + r.output;
		const extras = r.reported ? [r.reasoning && `${fmtNum(r.reasoning)} reasoning`, r.cacheRead && `${fmtNum(r.cacheRead)} cached`,
			r.cacheWrite && `${fmtNum(r.cacheWrite)} cache write`].filter(Boolean).join(' · ') : 'no token usage reported';
		const on = state.highlight === r.key;
		return `<button class="tok-row ${on ? 'on' : ''}" data-action="hl-model" data-model="${esc(r.key)}" title="Highlight these round-trips">
			<span class="tok-name"><span class="fn">${esc(r.model)}</span><span class="pill">${esc(r.label)}</span>${r.remote ? `<span class="link-badge" title="remote agent">remote · ${esc(r.remote)}</span>` : ''}</span>
			<span class="tok-bar-wrap"><span class="tok-bar" style="width:${pct(sum, max)}%">
				<span class="t-in" style="flex:${r.input || 0}"><span class="t-cache" style="width:${pct(r.cacheRead, r.input)}%"></span></span>
				<span class="t-out" style="flex:${r.output || 0}"></span></span></span>
			<span class="tok-num">${r.reported ? fmtNum(r.input) : '–'}</span><span class="tok-num">${r.reported ? fmtNum(r.output) : '–'}</span>
			<span class="tok-extra">${esc(extras)}</span><span class="tok-num">${r.calls}</span></button>`;
	}).join('');
	// Pinned, the panel stays in view while the cards or the sequence scroll under it.
	const pinned = pref('pinTokens', 'true') === 'true';
	return `<details class="fold tokens${pinned ? ' pinned' : ''}" data-key="${esc(key)}" ${isOpen(key, true) ? 'open' : ''}>
		<summary>Tokens by model · <b>${fmtNum(total.input)}</b> in · <b>${fmtNum(total.output)}</b> out${hasRemote ? ' · incl. remote agents' : ''}
			<button class="pin" data-action="pin-tokens" aria-pressed="${pinned}" title="${pinned ? 'Unpin: scroll with the page' : 'Pin: keep in view while scrolling'}">📌</button></summary>
		<div class="tok-grid">
			<div class="tok-row tok-head-row"><span>model</span>
				<span><span class="t-key t-in"></span>input <span class="t-key t-cache"></span>cached <span class="t-key t-out"></span>output</span>
				<span class="tok-num">in</span><span class="tok-num">out</span><span></span><span class="tok-num">trips</span></div>
			${rowHtml}
		</div></details>`;
}
