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

/**
 * Valid JSON text re-indented by two spaces without parsing its values, so it shows what was sent:
 * big numbers keep their digits, and key order and duplicate keys stay.
 */
export function indentJson(text) {
	let out = '', depth = 0;
	const nl = () => '\n' + '  '.repeat(depth);
	for (let i = 0; i < text.length; i++) {
		const c = text[i];
		if (c === '"') { // a string, copied as is up to its closing quote
			let j = i + 1;
			while (j < text.length && text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
			out += text.slice(i, j + 1);
			i = j;
		} else if (c === '{' || c === '[') {
			let j = i + 1;
			while (/\s/.test(text[j] ?? '')) j++;
			if (text[j] === (c === '{' ? '}' : ']')) { out += c + text[j]; i = j; } // empty: {} or []
			else { depth++; out += c + nl(); }
		} else if (c === '}' || c === ']') { depth--; out += nl() + c; }
		else if (c === ',') out += ',' + nl();
		else if (c === ':') out += ': ';
		else if (!/\s/.test(c)) out += c;
	}
	return out;
}

export function prettyMaybeJson(text) {
	if (typeof text !== 'string') return text == null ? '' : highlightJson(text);
	const t = text.trim();
	return t === '' || parseJson(t) === undefined ? esc(text) : highlightJson(indentJson(t));
}

/**
 * A text that is a JSON object or array, alone or in a ```json fence (as models often answer):
 * the JSON re-indented, and whether it was fenced; else undefined.
 */
export function jsonOfText(text) {
	const raw = String(text ?? '').trim();
	const t = raw.replace(/^```(?:json)?[ \t]*\n([\s\S]*?)\n?```$/i, '$1').trim();
	if (!/^[[{]/.test(t) || parseJson(t) === undefined) return undefined;
	return { json: indentJson(t), fenced: t !== raw };
}

/**
 * A message's text: pretty-printed when it is JSON (e.g. a structured answer), as is otherwise.
 * The JSON keeps the text class, so the message styles (height, struck through when removed) apply.
 */
export function renderText(text) {
	const j = jsonOfText(text);
	if (!j) return `<div class="text">${esc(text)}</div>`;
	return `${j.fenced ? '<span class="tag muted">in a ```json fence</span>' : ''}<pre class="json text text-json">${highlightJson(j.json)}</pre>`;
}

export function isOpen(key, fallback) { return state.open.has(key) ? state.open.get(key) : fallback; }
