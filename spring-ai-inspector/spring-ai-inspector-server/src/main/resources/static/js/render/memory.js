import { renderSpringMessage, sig } from './messages.js';
import { esc, fmtNum, isOpen, oneLine } from '../util.js';

// ---------------------------------------------------------------- memory
export const MEMORY_TITLES = { 'chat-memory': 'Chat memory', session: 'Session events', files: 'Memory files' };

// Fold keys are per call: opening a store's folds on one call leaves the other calls alone.
export const memoryStoreKey = (callId, store) => `mem:${callId}:${store.kind}:${store.id}`;

export function renderMemoryStore(store, before, callId) {
	const head = `<div class="mem-head"><b>${esc(MEMORY_TITLES[store.kind] || store.kind)}</b>
		<span class="chip">${esc(store.source)}</span>${store.memory ? `<span class="pill">${esc(store.memory)}</span>` : ''}
		<span class="pill" title="${esc(store.id)}">${store.kind === 'files' ? 'dir' : 'id'}: <b>${esc(oneLine(store.id, 40))}</b></span>`;
	const key = memoryStoreKey(callId, store);
	if (store.kind === 'files') {
		const prev = new Map((before?.items || []).map((f) => [f.name, f]));
		const rows = store.items.map((f) => {
			const p = prev.get(f.name);
			const tag = !p ? '<span class="tag added">new</span>' : (p.content !== f.content ? '<span class="tag fresh">changed</span>' : '');
			const fk = key + ':' + f.name;
			return `<details class="fold" data-key="${esc(fk)}" ${isOpen(fk, !!tag) ? 'open' : ''}><summary><span class="file-row">${esc(f.name)} ${tag}<span class="right-meta">${fmtNum(f.size)} B</span></span></summary>
				<pre class="json">${esc(f.content)}</pre></details>`;
		}).join('');
		const changed = store.items.filter((f) => !prev.has(f.name) || prev.get(f.name).content !== f.content).length;
		return `<div class="mem-store">${head}<span class="pill ${changed ? 'cool' : ''}">${changed ? `${changed} written by this call` : 'unchanged'}</span></div>${rows}</div>`;
	}
	// Messages / events: which ones did this call write? Compare against the "before" snapshot.
	const pool = new Map();
	for (const m of before?.items || []) pool.set(sig(m), (pool.get(sig(m)) || 0) + 1);
	const marked = store.items.map((m) => {
		const sg = sig(m);
		if (pool.get(sg)) { pool.set(sg, pool.get(sg) - 1); return { m, fresh: false }; }
		return { m, fresh: true };
	});
	const added = marked.filter((x) => x.fresh);
	const archived = store.items.filter((m) => m.archived).length;
	const render = (x) => {
		let html = renderSpringMessage(x.m);
		const tags = [x.fresh && '<span class="tag fresh">written by this call</span>', x.m.archived && '<span class="tag muted">archived</span>',
			x.m.synthetic && '<span class="tag added">summary</span>'].filter(Boolean).join('');
		html = html.replace('<div class="role">', `<div class="role">`).replace(/(<div class="role">[^<]*)/, `$1${tags}`);
		return x.fresh || x.m.archived ? html.replace('class="msg ', `class="msg ${x.fresh ? 'fresh ' : ''}${x.m.archived ? 'archived ' : ''}`) : html;
	};
	const old = marked.filter((x) => !x.fresh);
	const oldKey = key + ':old';
	const newKey = key + ':new';
	return `<div class="mem-store">${head}<span class="pill">${store.items.length} ${store.kind === 'session' ? 'events' : 'messages'}</span>
		<span class="pill ${added.length ? 'cool' : ''}">${added.length ? `+${added.length} new` : 'nothing written'}</span>
		${archived ? `<span class="pill">${archived} archived</span>` : ''}</div>
		${old.length ? `<details class="fold" data-key="${esc(oldKey)}" ${isOpen(oldKey, false) ? 'open' : ''}><summary>${old.length} already in memory before this call</summary><div class="msgs">${old.map(render).join('')}</div></details>` : ''}
		${added.length ? `<details class="fold" data-key="${esc(newKey)}" ${isOpen(newKey, false) ? 'open' : ''}><summary>${added.length} written by this call</summary><div class="msgs">${added.map(render).join('')}</div></details>` : ''}</div>`;
}

export function renderMemory(call) {
	const after = call.memory.after; const before = call.memory.before || [];
	if (!after) return '<div class="notice info"><span class="spinner"></span> waiting for the call to finish…</div>';
	const match = (s) => before.find((b) => b.kind === s.kind && b.id === s.id && b.source === s.source);
	return after.map((s) => renderMemoryStore(s, match(s), call.id)).join('');
}

export function memoryHint(call) {
	const after = call.memory.after;
	if (!after) return '';
	return after.map((s) => `${s.items.length} ${s.kind === 'files' ? 'files' : 'items'}`).join(' · ');
}
