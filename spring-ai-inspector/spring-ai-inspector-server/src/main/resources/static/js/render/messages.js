import { esc, fmtNum, isOpen, prettyMaybeJson, renderText } from '../util.js';
import { renderBlock, renderMedia } from './wire.js';

export const hash = (s) => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return (h >>> 0).toString(36); };

// A message folded to a one-line preview when closed. Keys keep it open (or closed) across re-renders.
// A body given as a function is only rendered when open: opening it re-renders the page (see main.js).
export function renderFoldedMessage(role, cls, tags, text, body, key, open, size = `${fmtNum(text.length)} chars`) {
	const opened = isOpen(key, open);
	const content = typeof body === 'function' ? (opened ? body() : '') : body;
	return `<details class="msg ${role}${cls}" data-key="${esc(key)}"${typeof body === 'function' ? ' data-lazy' : ''} ${opened ? 'open' : ''}><summary class="role"><span class="chev">▸</span>${role}${tags}
		<span class="sys-preview">${esc(text.replace(/\s+/g, ' ').trim())}</span><span class="sys-size">${size}</span></summary>${content}</details>`;
}

// System prompts are long and repeat on every call: collapsed to a one-line preview by default.
// Keyed by content, so the same prompt stays open (or closed) across calls.
export function renderSystemMessage(cls, tags, text, body) {
	return renderFoldedMessage('system', cls, tags, text, body, 'sys:' + hash(text), false);
}

// The final answer: folded by default to a one-line preview. Keyed by call.
export function renderAnswerMessage(m, key) {
	// A thinking block Spring AI returned as a generation of its own (see InspectorAdvisor).
	if (m.thinking) return renderBlock({ type: 'thinking', text: m.text, signed: m.thinking === 'signed', redacted: m.thinking === 'redacted' });
	if (!m.text || (m.toolCalls || []).length || (m.media || []).length) return renderSpringMessage(m);
	return renderFoldedMessage(esc(m.role || 'assistant'), '', '', m.text, () => renderText(m.text), key, false);
}

// ---------------------------------------------------------------- generic (Spring AI) message rendering
export function renderSpringMessage(m, mark) {
	const role = m.role || 'user';
	const cls = mark ? ' ' + mark : '';
	const tags = `${mark === 'added' ? '<span class="tag added">+ added by advisors</span>' : ''}${mark === 'removed' ? '<span class="tag removed">− removed by advisors</span>' : ''}`;
	if (role === 'system' && m.text) return renderSystemMessage(cls, tags, m.text, renderText(m.text));
	let html = `<div class="msg ${esc(role)}${cls}"><div class="role">${esc(role)}${tags}</div>`;
	if (m.text) html += renderText(m.text);
	for (const tc of m.toolCalls || []) {
		html += `<div class="block tool-use"><div class="block-label">tool call</div><span class="fn">${esc(tc.name)}</span><pre>${prettyMaybeJson(tc.arguments)}</pre></div>`;
	}
	for (const tr of m.toolResponses || []) {
		html += `<div class="block tool-result"><div class="block-label">tool result · <span class="fn">${esc(tr.name)}</span></div><pre>${prettyMaybeJson(tr.data)}</pre></div>`;
	}
	// Media: mime types in older recordings; {type, size, blobId|url} since the starter uploads them for providers off the wire.
	if (m.media && m.media.length) html += `<div class="block media"><div class="block-label">media</div>${m.media.map((x) => typeof x === 'string' ? `<span class="tag muted">${esc(x)}</span>`
		: renderMedia(x.blobId || x.url ? { blobId: x.blobId, url: x.url, type: x.type, size: x.size } : null) || `<span class="tag muted">${esc(x.type || 'media')}${x.size != null ? ` · ${fmtNum(x.size)} bytes` : ''}${x.blobId ? '' : ' (on the wire)'}</span>`).join('')}</div>`;
	return html + '</div>';
}

export const sig = (m) => JSON.stringify([m.role, m.text || '', (m.toolCalls || []).map((t) => t.name + t.arguments), (m.toolResponses || []).map((t) => t.name + t.data)]);

// Messages that reach the model, marked against what the app sent.
export function diffMessages(appMsgs, modelMsgs) {
	const pool = new Map();
	for (const m of appMsgs) pool.set(sig(m), (pool.get(sig(m)) || 0) + 1);
	const out = modelMsgs.map((m) => {
		const s = sig(m);
		if (pool.get(s)) { pool.set(s, pool.get(s) - 1); return { m, mark: '' }; }
		return { m, mark: 'added' };
	});
	const removed = appMsgs.filter((m) => pool.get(sig(m)) > 0 && pool.set(sig(m), pool.get(sig(m)) - 1));
	return { out, removed };
}
