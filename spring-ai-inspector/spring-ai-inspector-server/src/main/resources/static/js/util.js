import { state } from './state.js';

// ---------------------------------------------------------------- helpers
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const fmtTime = (ts) => ts ? new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '';
export const fmtMs = (ms) => ms == null ? '' : ms < 1000 ? ms + ' ms' : (ms / 1000).toFixed(1) + ' s';
export const fmtNum = (n) => n == null ? '–' : Number(n).toLocaleString();
export const MAX_ORDER = 2147483647;
// Spring's Ordered constants are huge; show them relative to HIGHEST/LOWEST precedence.
export const fmtOrder = (o) => o == null ? '' : o < -2e9 ? `HIGHEST+${o + MAX_ORDER + 1}` : o > 2e9 ? `LOWEST−${MAX_ORDER - o}` : String(o);
export const oneLine =(s, n = 90) => { s = String(s ?? '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n) + '…' : s; };

/** When an event happened: a replay feeds events at replay time, keeping the recorded time here. */
export const recordedTs = (ev) => ev.recordedTs ?? ev.ts;

export function parseJson(text) {
	if (text == null || text === '') return null;
	try { return JSON.parse(text); } catch { return undefined; }
}

export function highlightJson(value) {
	const json = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
	return esc(json).replace(/(&quot;(?:\\.|[^&\\]|&(?!quot;))*?&quot;)(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
		(m, str, colon, lit) => {
			if (str) return colon ? `<span class="j-key">${str}</span>${colon}` : `<span class="j-str">${str}</span>`;
			if (lit) return `<span class="j-lit">${m}</span>`;
			return `<span class="j-num">${m}</span>`;
		});
}

export function prettyMaybeJson(text) {
	const parsed = parseJson(text);
	return parsed === undefined || parsed === null ? esc(text) : highlightJson(parsed);
}

export function isOpen(key, fallback) { return state.open.has(key) ? state.open.get(key) : fallback; }
