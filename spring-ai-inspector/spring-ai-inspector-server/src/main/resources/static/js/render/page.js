import { usageOf } from '../providers.js';
import { renderItems } from './cards.js';
import { renderSequence } from './sequence.js';
import { renderTokenPanel } from './tokens.js';
import { replay } from '../replay.js';
import { pref, state } from '../state.js';
import { esc, fmtMs, fmtNum, fmtTime } from '../util.js';

// ---------------------------------------------------------------- page rendering
export function runTotals(run) {
	let input = 0, output = 0;
	for (const w of run.wireList) { const u = usageOf(w); if (u) { input += u.input || 0; output += u.output || 0; } }
	return { input, output };
}

export function renderSidebar() {
	const runs = [...state.runs.values()].sort((a, b) => (b.started || 0) - (a.started || 0));
	document.getElementById('runs').innerHTML = runs.length ? runs.map((r) => {
		const t = runTotals(r);
		return `<button class="run-item ${r.id === state.selected ? 'sel' : ''}" data-run="${esc(r.id)}">
			<div class="name"><span class="status ${r.ended ? '' : 'running'}"></span>${esc(r.app)}</div>
			<div class="meta">${fmtTime(r.started)} · ${r.calls.size} call${r.calls.size === 1 ? '' : 's'} · ${r.wireList.length} trip${r.wireList.length === 1 ? '' : 's'}${t.input ? ` · ${fmtNum(t.input + t.output)} tok` : ''}</div>
		</button>`;
	}).join('') : '<div class="meta" style="padding:.25rem;color:var(--muted)">No runs yet.</div>';
}

export function renderMain() {
	const main = document.getElementById('main');
	const run = state.runs.get(state.selected);
	if (!run) {
		main.innerHTML = `<div class="empty"><h2>Waiting for a demo…</h2>
			<p>Run any demo module. Demos that use <code>common</code> detect the inspector automatically
			(<code>spring.ai.inspector.url</code>), route their model traffic through it and report their ChatClient calls and tool runs.</p>
			<p>Or <b>Import</b> a run saved earlier with <b>Export</b> and <b>▶ Replay</b> it — no network needed.</p></div>`;
		return;
	}
	const t = runTotals(run);
	const topCalls = run.items.filter((i) => i.kind === 'call');
	const latest = topCalls[topCalls.length - 1];
	const replaying = replay && replay.runId === run.id;
	const view = pref('view', 'cards');
	const scaled = pref('seqScale', 'ordered') === 'scaled';
	const viewToggle = `<div class="seg"><button class="btn ${view === 'cards' ? 'on' : ''}" data-action="view-cards">Cards</button>
		<button class="btn ${view === 'sequence' ? 'on' : ''}" data-action="view-sequence">Sequence</button></div>
		${view === 'sequence' ? `<div class="seg"><button class="btn ${scaled ? '' : 'on'}" data-action="seq-ordered" title="One row per event">ordered</button>
		<button class="btn ${scaled ? 'on' : ''}" data-action="seq-scaled" title="Gaps proportional to elapsed time">to scale</button></div>` : ''}`;
	const actions = run.events.length && !replaying ? `<div class="run-actions">${viewToggle}
		<button class="btn" data-action="export" title="Save this run as JSON">Export</button>
		<select class="btn" id="replay-speed" title="Replay pace">${['step', '1', '2', '4'].map((v) =>
			`<option value="${v}" ${pref('replaySpeed', 'step') === v ? 'selected' : ''}>${v === 'step' ? 'step by step' : v + '× speed'}</option>`).join('')}</select>
		<button class="btn" data-action="replay" title="Re-play this run as a new run, without calling any model">▶ Replay</button></div>` : '';
	const head = `<div class="run-head"><h1>${esc(run.app)}</h1><div class="stats">
		${run.replayOf ? '<span class="stat">replay</span>' : run.imported ? `<span class="stat" title="${esc(run.imported)}">imported</span>` : ''}
		<span class="stat">${run.ended ? 'finished' : '<span class="spinner"></span> running'}</span>
		${run.model ? `<span class="stat">configured <b>${esc(run.model)}</b></span>` : ''}
		<span class="stat"><b>${run.calls.size}</b> ChatClient calls</span>
		<span class="stat"><b>${run.wireList.length}</b> model round-trips</span>
		${run.tools.size ? `<span class="stat"><b>${run.tools.size}</b> tool runs</span>` : ''}
		<span class="stat"><b>${fmtNum(t.input)}</b> in · <b>${fmtNum(t.output)}</b> out tokens</span>
		${run.ended && run.started ? `<span class="stat">${fmtMs(run.ended - run.started)}</span>` : ''}
	</div>${actions}</div>`;
	const body = (view === 'sequence' && run.items.length ? renderSequence(run, scaled) : renderItems(run.items, latest))
		|| '<div class="notice info">Run started, no model calls yet.</div>';
	main.innerHTML = head + renderTokenPanel(run) + body;
}

export let renderPending = false;
export function render() {
	if (renderPending) return;
	renderPending = true;
	requestAnimationFrame(() => {
		renderPending = false;
		const nearBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 80;
		renderSidebar();
		renderMain();
		if (state.follow && nearBottom) window.scrollTo(0, document.body.scrollHeight);
	});
}
