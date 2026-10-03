import { esc, prettyMaybeJson } from '../util.js';

// ---------------------------------------------------------------- generic (Spring AI) message rendering
export function renderSpringMessage(m, mark) {
	const role = m.role || 'user';
	const cls = mark ? ' ' + mark : '';
	let html = `<div class="msg ${esc(role)}${cls}"><div class="role">${esc(role)}${mark === 'added' ? '<span class="tag added">+ added by advisors</span>' : ''}${mark === 'removed' ? '<span class="tag removed">− removed by advisors</span>' : ''}</div>`;
	if (m.text) html += `<div class="text">${esc(m.text)}</div>`;
	for (const tc of m.toolCalls || []) {
		html += `<div class="block tool-use"><div class="block-label">tool call</div><span class="fn">${esc(tc.name)}</span><pre>${prettyMaybeJson(tc.arguments)}</pre></div>`;
	}
	for (const tr of m.toolResponses || []) {
		html += `<div class="block tool-result"><div class="block-label">tool result · <span class="fn">${esc(tr.name)}</span></div><pre>${prettyMaybeJson(tr.data)}</pre></div>`;
	}
	if (m.media && m.media.length) html += `<div class="block"><div class="block-label">media</div>${m.media.map(esc).join(', ')}</div>`;
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
