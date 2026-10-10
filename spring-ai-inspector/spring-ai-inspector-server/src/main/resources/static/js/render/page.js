import { EVENTS_VERSION } from '../model.js';
import { usageOf } from '../providers.js';
import { renderItems } from './cards.js';
import { renderSequence } from './sequence.js';
import { renderMcpPanel } from './mcp.js';
import { advisorOnlyCalls, usageOfModelCall } from './models.js';
import { renderTokenPanel } from './tokens.js';
import { replay } from '../replay.js';
import { pref, state } from '../state.js';
import { esc, fmtMs, fmtNum, fmtTime } from '../util.js';

// ---------------------------------------------------------------- page rendering
/** Tokens and round-trips of a run: HTTP ones, and model calls without HTTP (see models.js). */
export function runTotals(run) {
	// Summed once per event: the sidebar asks for every run on every event, and a long run has many round-trips.
	if (run.totals && run.totals.at === run.events.length) return run.totals.value;
	let input = 0, output = 0;
	const noHttp = advisorOnlyCalls(run);
	for (const u of [...run.wireList.map(usageOf), ...noHttp.map(usageOfModelCall)]) {
		if (u) { input += u.input || 0; output += u.output || 0; }
	}
	run.totals = { at: run.events.length, value: { input, output, trips: run.wireList.length + noHttp.length } };
	return run.totals.value;
}

export function renderSidebar() {
	const runs = [...state.runs.values()].sort((a, b) => (b.started || 0) - (a.started || 0));
	document.getElementById('runs').innerHTML = runs.length ? runs.map((r) => {
		const t = runTotals(r);
		return `<button class="run-item ${r.id === state.selected ? 'sel' : ''}" data-run="${esc(r.id)}">
			<div class="name"><span class="status ${r.ended ? '' : 'running'}"></span>${esc(r.app)}</div>
			<div class="meta">${fmtTime(r.started)} · ${r.calls.size} call${r.calls.size === 1 ? '' : 's'} · ${t.trips} trip${t.trips === 1 ? '' : 's'}${t.input ? ` · ${fmtNum(t.input + t.output)} tok` : ''}</div>
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
		${run.newerFormat ? `<span class="stat warn" title="Recorded by a newer starter or inspector; what this UI doesn't know is not shown">event format v${esc(String(run.newerFormat))}, this UI reads v${EVENTS_VERSION}</span>` : ''}
		${run.model ? `<span class="stat">configured <b>${esc(run.model)}</b></span>` : ''}
		<span class="stat"><b>${run.calls.size}</b> ChatClient calls</span>
		<span class="stat"><b>${t.trips}</b> model round-trips</span>
		${run.tools.size ? `<span class="stat"><b>${run.tools.size}</b> tool runs</span>` : ''}
		<span class="stat"><b>${fmtNum(t.input)}</b> in · <b>${fmtNum(t.output)}</b> out tokens</span>
		${run.ended && run.started ? `<span class="stat">${fmtMs(run.ended - run.started)}</span>` : ''}
	</div></div>`;
	const body = (view === 'sequence' && run.items.length ? renderSequence(run, scaled) : renderItems(run.items, latest, { searches: run.searches || [] }))
		|| '<div class="notice info">Run started, no model calls yet.</div>';
	// The run bar: the view and replay controls beside the tokens panel, pinned together under the top bar.
	const tokens = renderTokenPanel(run);
	const pinned = pref('pinTokens', 'true') === 'true';
	const bar = actions || tokens ? `<div class="run-bar${pinned ? ' pinned' : ''}">${actions}${tokens}</div>` : '';
	main.innerHTML = head + bar + renderMcpPanel(run) + body;
}

/**
 * Where the sticky parts stop: the pinned run bar right under the top bar (and, while
 * replaying, the replay bar); the sequence lane heads under those and the pinned bar.
 */
export function updateStickyOffsets() {
	const replayBar = document.getElementById('replay');
	const header = document.querySelector('.topbar').offsetHeight; // e.g. taller with the Spring scheme's logo and line
	document.documentElement.style.setProperty('--header-h', header + 'px');
	const top = header + (replayBar.hidden ? 0 : replayBar.offsetHeight);
	document.documentElement.style.setProperty('--pin-top', top + 'px');
	const pinned = document.querySelector('.run-bar.pinned');
	document.documentElement.style.setProperty('--seq-top', top + (pinned ? pinned.offsetHeight : 0) + 'px');
}

// Recomputes the offsets whenever what they depend on changes size: the bars, and the pinned
// run bar (tokens folded, re-wrapped by the text size or the sidebar's width, ...).
let resizeObserver = null;
function watchStickyOffsets() {
	updateStickyOffsets();
	if (typeof ResizeObserver === 'undefined') return;
	resizeObserver ||= new ResizeObserver(updateStickyOffsets);
	resizeObserver.disconnect(); // the panel is a new element after each render
	for (const el of [document.querySelector('.topbar'), document.getElementById('replay'), document.querySelector('.run-bar.pinned')]) {
		if (el) resizeObserver.observe(el);
	}
}

/** Keeps the sticky lane heads aligned with the sequence diagram scrolled sideways under them. */
export function syncSequenceHead(wrap) {
	const head = wrap.parentElement?.querySelector('.seq-head');
	if (head) head.scrollLeft = wrap.scrollLeft;
}

export let renderPending = false;
let mainPending = false;

/**
 * Redraws on the next frame; calls within a frame are folded into one. With {@code main: false}
 * only the sidebar is redrawn (an event of a run that isn't shown changes its counts there,
 * nothing in the main view); a later call in the same frame with {@code main: true} upgrades it.
 */
export function render({ main = true } = {}) {
	mainPending ||= main;
	if (renderPending) return;
	renderPending = true;
	requestAnimationFrame(() => {
		renderPending = false;
		const withMain = mainPending;
		mainPending = false;
		renderSidebar();
		if (!withMain) return;
		const nearBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 80;
		// The sequence's sideways scroll is kept across live updates of the same run, not into another run.
		const before = document.querySelector('.seq-view');
		const scroll = before && { run: before.dataset.run, left: before.querySelector('.seq-wrap').scrollLeft };
		renderMain();
		const view = document.querySelector('.seq-view');
		if (view && scroll && view.dataset.run === scroll.run) view.querySelector('.seq-wrap').scrollLeft = scroll.left;
		if (view) syncSequenceHead(view.querySelector('.seq-wrap'));
		watchStickyOffsets();
		if (state.follow && nearBottom) window.scrollTo(0, document.body.scrollHeight);
	});
}
