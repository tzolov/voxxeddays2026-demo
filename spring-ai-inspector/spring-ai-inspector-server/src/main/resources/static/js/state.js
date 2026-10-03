// ---------------------------------------------------------------- state
export const state = {
	runs: new Map(),
	selected: null,
	follow: pref('follow', 'true') === 'true',
	open: new Map(),   // data-key -> boolean (user toggled <details>)
	tabs: new Map(),   // wire id -> tab name
};

export function pref(key, fallback) {
	try { return localStorage.getItem('inspector.' + key) ?? fallback; } catch { return fallback; }
}
export function savePref(key, value) {
	try { localStorage.setItem('inspector.' + key, value); } catch { /* ignore */ }
}

export function ensureRun(runId, ts) {
	let run = state.runs.get(runId);
	if (!run) {
		run = { id: runId, app: 'run ' + runId, model: null, started: ts, ended: null,
			items: [], calls: new Map(), modelCalls: new Map(), wires: new Map(), wireList: [], callCount: 0,
			tools: new Map(), events: [] };
		state.runs.set(runId, run);
		if (state.follow || !state.selected) state.selected = runId;
	}
	return run;
}
