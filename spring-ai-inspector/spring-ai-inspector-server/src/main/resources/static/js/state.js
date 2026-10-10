// ---------------------------------------------------------------- state
export const state = {
	runs: new Map(),
	selected: null,
	follow: pref('follow', 'true') === 'true',
	open: new Map(),   // data-key -> boolean (user toggled <details>)
	tabs: new Map(),   // wire id -> tab name
	highlight: null,   // 'provider|model' whose round-trips are outlined (tokens panel)
};

export function pref(key, fallback) {
	try { return localStorage.getItem('inspector.' + key) ?? fallback; } catch { return fallback; }
}
export function savePref(key, value) {
	try { localStorage.setItem('inspector.' + key, value); } catch { /* ignore */ }
}

// The inspector's access token (spring.ai.inspector.token), when it has one: given once as
// #token=<value> in the address bar, kept per browser. Sent as a header on fetches and, since an
// EventSource can't carry headers, as a query parameter on the stream.
export const token = (() => {
	try {
		const given = typeof location === 'undefined' ? null : new URLSearchParams(location.hash.slice(1)).get('token');
		if (given) savePref('token', given);
	} catch { /* no storage: the token lives for this page only */ }
	return pref('token', '');
})();
export const apiHeaders = (headers = {}) => (token ? { ...headers, 'X-Inspector-Token': token } : headers);
export const apiUrl = (path) => (token ? `${path}${path.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}` : path);

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
