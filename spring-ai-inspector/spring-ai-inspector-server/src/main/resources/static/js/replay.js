import { handle } from './model.js';
import { render } from './render/page.js';
import { state } from './state.js';
import { esc } from './util.js';

// ---------------------------------------------------------------- replay
// Feeds a run's recorded events into a new client-side run, either with the original
// timing (sped up) or one step per key press. Nothing is sent to any model.
export let replay = null;
// Events that change nothing visible are fed without waiting for a step.
export const SILENT = new Set(['run-start', 'model-request', 'model-response', 'run-end']);

export function startReplay(run, speed) {
	stopReplay();
	const runId = 'replay-' + Math.random().toString(36).slice(2, 7);
	const events = run.events.map((e) => ({ ...e, runId }));
	const runStart = events.find((e) => e.type === 'run-start');
	if (runStart) Object.assign(runStart, { app: run.app.replace(/ · replay$/, '') + ' · replay', replayOf: run.id });
	else events.unshift({ type: 'run-start', runId, app: run.app + ' · replay', replayOf: run.id, ts: events[0]?.ts });
	replay = { runId, events, i: 0, speed, paused: speed === 'step', timer: null };
	state.selected = runId;
	window.scrollTo(0, 0);
	feedSilent();
	if (speed !== 'step') scheduleNext();
	renderReplayBar();
}

export function feedOne() {
	const ev = replay.events[replay.i++];
	handle({ ...ev, ts: Date.now() });
	return ev;
}

export function feedSilent() {
	while (replay && replay.i < replay.events.length && SILENT.has(replay.events[replay.i].type)) feedOne();
	render();
}

export function stepReplay() {
	if (!replay || replay.i >= replay.events.length) return;
	feedOne();
	feedSilent();
	renderReplayBar();
}

export function scheduleNext() {
	clearTimeout(replay.timer);
	if (replay.paused || replay.i >= replay.events.length) { renderReplayBar(); return; }
	const prev = replay.events[replay.i - 1];
	const next = replay.events[replay.i];
	// Original pacing divided by the speed, with long gaps capped so the room doesn't wait.
	const gap = prev ? Math.min(Math.max(0, next.ts - prev.ts) / Number(replay.speed), 4000) : 0;
	replay.timer = setTimeout(() => { if (replay) { stepReplay(); scheduleNext(); } }, gap);
}

export function togglePause() {
	if (!replay || replay.speed === 'step') return;
	replay.paused = !replay.paused;
	scheduleNext();
}

export function stopReplay() {
	if (replay) clearTimeout(replay.timer);
	replay = null;
	renderReplayBar();
}

export function renderReplayBar() {
	const bar = document.getElementById('replay');
	if (!replay) { bar.hidden = true; bar.innerHTML = ''; render(); return; }
	const done = replay.i >= replay.events.length;
	const pct = (100 * replay.i / replay.events.length).toFixed(1);
	const controls = replay.speed === 'step'
		? `<button class="btn" data-action="step" ${done ? 'disabled' : ''}>Next ▶</button><span>press <kbd>→</kbd> or <kbd>n</kbd></span>`
		: `<button class="btn" data-action="pause">${replay.paused ? '▶ Resume' : '❚❚ Pause'}</button><span>${esc(replay.speed)}× · <kbd>p</kbd> pause</span>`;
	bar.hidden = false;
	bar.innerHTML = `<b>Replay</b>${done ? '<span>finished</span>' : controls}
		<div class="progress"><span style="width:${pct}%"></span></div><span>${replay.i}/${replay.events.length}</span>
		<button class="btn" data-action="stop">${done ? 'Close' : '■ Stop'}</button>`;
	render();
}
