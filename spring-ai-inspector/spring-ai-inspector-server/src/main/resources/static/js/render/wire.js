import { adapterOf, inputCount, normRequest, normResponse, providerLabel } from '../providers.js';
import { hash, renderFoldedMessage, renderSystemMessage } from './messages.js';
import { state } from '../state.js';
import { wireModelKey } from './tokens.js';
import { esc, fmtMs, fmtNum, highlightJson, isOpen, oneLine, prettyMaybeJson } from '../util.js';

// ---------------------------------------------------------------- wire rendering
export const stopClass = (stop) => /tool/.test(stop || '') ? 'stop-tool_use' : /end_turn|^stop$|^completed$/.test(stop || '') ? 'stop-end_turn' : '';

export function renderBlock(b) {
	switch (b.type) {
		case 'text':
			return `<div class="text">${esc(b.text)}</div>${b.note ? `<span class="tag muted">${esc(b.note)}</span>` : ''}`;
		case 'tool_use':
			return `<div class="block tool-use"><div class="block-label">tool call${b.id ? ' · ' + esc(b.id) : ''}</div><span class="fn">${esc(b.name)}</span><pre>${highlightJson(b.input ?? {})}</pre></div>`;
		case 'tool_result':
			return `<div class="block tool-result"><div class="block-label">tool result${b.name ? ' · <span class="fn">' + esc(b.name) + '</span>' : ''}${b.id ? ' · ' + esc(b.id) : ''}${b.isError ? ' · error' : ''}</div><pre>${prettyMaybeJson(b.content)}</pre></div>`;
		case 'thinking': {
			if (b.text) return `<div class="block"><div class="block-label">thinking</div><div class="text">${esc(b.text)}</div></div>`;
			// Reasoning not returned: the block only carries what the model needs to resume it.
			const [label, why] = b.redacted ? ['redacted (encrypted)', 'The reasoning was encrypted by the provider and is sent back as is.']
				: b.hidden ? ['hidden', 'The reasoning is not returned, only a reference to it, which is sent back so the model can continue from it.']
				: b.signed ? ['hidden (signature only)', 'The reasoning is not returned, only its signature, which is sent back so the model can continue from it.']
				: ['empty', 'The model returned an empty thinking block.'];
			return `<div class="block" title="${esc(why)}"><div class="block-label">thinking · ${label}</div></div>`;
		}
		case 'media':
			return `<div class="block"><div class="block-label">media</div>${esc(b.label)}</div>`;
		default:
			return `<div class="block"><div class="block-label">${esc(b.label)}</div><pre>${highlightJson(b.value)}</pre></div>`;
	}
}

export function renderWireMessage(role, blocks, extraCls = '', tag = '') {
	if (role === 'system') {
		const text = blocks.map((b) => b.text ?? b.label ?? '').join('\n');
		return renderSystemMessage(extraCls ? ' ' + extraCls : '', tag, text, blocks.map(renderBlock).join(''));
	}
	return `<div class="msg ${esc(role)} ${extraCls}"><div class="role">${esc(role)}${tag}</div>${blocks.map(renderBlock).join('')}</div>`;
}

// Objects such as a JSON schema are shortened here; the Request JSON tab has them in full.
export const paramPills = (params) => Object.entries(params).map(([k, v]) => {
	const text = typeof v === 'object' ? JSON.stringify(v) : String(v);
	return `<span class="pill" title="${esc(text)}">${esc(k)}: <b>${esc(oneLine(text, 60))}</b></span>`;
}).join('');

// A message's blocks as plain text, for a folded message's one-line preview and size.
function blocksText(blocks) {
	return blocks.map((b) => {
		switch (b.type) {
			case 'text': return b.text;
			case 'tool_use': return `${b.name}(${JSON.stringify(b.input ?? {})})`;
			case 'tool_result': return `${b.name ? b.name + ' ' : ''}→ ${typeof b.content === 'string' ? b.content : JSON.stringify(b.content)}`;
			case 'thinking': return b.text ? `thinking: ${b.text}` : '[thinking]';
			default: return `[${b.label}]`;
		}
	}).join(' · ');
}

// A re-sent message folded to one line. Keyed by position and content, which stay the same in
// every later round-trip of the conversation, so an opened message stays open in the next ones.
function renderResentMessage(m, i) {
	if (m.role === 'system') return renderWireMessage(m.role, m.blocks);
	const text = blocksText(m.blocks);
	const size = `${fmtNum(text.length)} chars${m.blocks.length > 1 ? ` · ${m.blocks.length} blocks` : ''}`;
	return renderFoldedMessage(esc(m.role), '', '', oneLine(text, 200), () => m.blocks.map(renderBlock).join(''),
		`resent-msg:${i}:${hash(JSON.stringify(m.raw))}`, false, size);
}

// The messages re-sent from the previous round-trip, folded to one line: in a long conversation
// they are almost all of the request, and the new ones are what changed. Rendered only when open.
function renderResentGroup(messages, key) {
	const counts = {};
	for (const m of messages) counts[m.role] = (counts[m.role] || 0) + 1;
	const open = isOpen(key, false);
	return `<details class="resent" data-key="${esc(key)}" data-lazy ${open ? 'open' : ''}><summary><span class="chev">▸</span>
		<span class="tag muted">re-sent</span> ${messages.length} earlier message${messages.length === 1 ? '' : 's'}
		<span class="resent-roles">· ${Object.entries(counts).map(([r, n]) => `${n} ${esc(r)}`).join(' · ')}</span></summary>
		${open ? `<div class="msgs">${messages.map(renderResentMessage).join('')}</div>` : ''}</details>`;
}

export function renderNormRequest(req, prevReq, key) {
	let html = `<div class="params">${paramPills(req.params)}</div><div class="msgs">`;
	if (req.system) html += renderWireMessage('system', req.system);
	if (req.tools.length) {
		html += `<div class="msg tool"><div class="role">tools · ${req.tools.length}</div><div class="chain" style="margin-top:.3rem">${req.tools.map((t) => `<span class="chip">${esc(t.name)}</span>`).join('')}</div>
			<details class="fold"><summary>tool definitions</summary><pre class="json">${highlightJson(req.tools.map((t) => t.def))}</pre></details></div>`;
	}
	// Messages already sent in the previous round-trip are re-sent: the model is stateless.
	// Only the leading messages sent exactly as before count: once one differs (say, an advisor
	// summarized earlier turns), it and everything after it are new and shown in full.
	const prev = prevReq ? prevReq.messages : [];
	let same = 0;
	while (same < prev.length && same < req.messages.length
		&& JSON.stringify(prev[same].raw) === JSON.stringify(req.messages[same].raw)) same++;
	if (!same) return html + req.messages.map((m) => renderWireMessage(m.role, m.blocks)).join('') + '</div>';
	html += renderResentGroup(req.messages.slice(0, same), 'resent:' + key);
	html += req.messages.slice(same).map((m) => renderWireMessage(m.role, m.blocks, 'fresh', '<span class="tag fresh">new</span>')).join('');
	return html + '</div>';
}

export function renderNormResponse(wire) {
	if (!wire.resp) return '<div class="notice info"><span class="spinner"></span> waiting for the model…</div>';
	const r = normResponse(wire);
	if (!r) return `<pre class="json">${prettyMaybeJson(wire.resp.body)}</pre>`;
	if (r.error) return `<div class="notice err">${esc(r.error)}</div>`;
	const u = r.usage || {};
	const params = [
		r.stop && `<span class="pill ${stopClass(r.stop)}">stop: <b>${esc(r.stop)}</b></span>`,
		`<span class="pill">input: <b>${fmtNum(u.input)}</b></span>`,
		`<span class="pill">output: <b>${fmtNum(u.output)}</b></span>`,
		u.cacheRead ? `<span class="pill">cache read: <b>${fmtNum(u.cacheRead)}</b></span>` : '',
		u.cacheWrite ? `<span class="pill">cache write: <b>${fmtNum(u.cacheWrite)}</b></span>` : '',
	].filter(Boolean).join('');
	return `<div class="params">${params}</div><div class="msgs">${renderWireMessage('assistant', r.blocks)}</div>`;
}

export function renderHeaders(h) {
	if (!h) return '';
	return `<table class="headers">${Object.entries(h).map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join('')}</table>`;
}

export function previousConversation(wire) {
	// The closest earlier round-trip to the same provider and protocol, for the re-sent / new markers.
	const adapter = adapterOf(wire);
	if (!adapter) return null;
	for (let w = wire.prev; w; w = w.prev) {
		if (w.req.provider === wire.req.provider && adapterOf(w) === adapter) return normRequest(w);
	}
	return null;
}

// noul answers are probabilities that the statement is true. Whether true is good (is_plausible)
// or bad (is_injection), and the pass threshold, stay in the app: they are not on the wire,
// so noul answers are shown neutrally, never as pass or fail: only which way Jev leans.
export const NOUL_HOT = 0.5;
export const noulLeaning = (p) => p > 0.6 ? 'true' : p < 0.4 ? 'false' : 'uncertain';

export function probRow(label, p, picked, neutral = false, lead = '') {
	const pct = Math.max(0, Math.min(1, Number(p) || 0)) * 100;
	return `<div class="prob ${picked ? 'picked' : ''}"><span class="lbl" title="${esc(label)}">${lead}${esc(label)}</span>
		<div class="bar ${neutral ? 'neutral' : ''}"><span style="width:${pct.toFixed(1)}%"></span></div><span class="val">${(Number(p) || 0).toFixed(2)}</span></div>`;
}

export const criterionText = (c) => typeof c === 'string' ? c : JSON.stringify(c);

export function renderSystemOneAnswer(a) {
	if (!a) return '<span class="spinner"></span>';
	switch (a.type) {
		case 'noul':
			return probRow('P(true)', a.noul, false, true, `<b>${noulLeaning(a.noul)}</b> · `);
		case 'score': {
			const levels = Object.keys(a.probabilities || a.legend || {});
			const top = String(Math.round(a.score));
			return `<div class="big">${esc(a.score)} <span style="font-weight:500">· ${esc(criterionText(a.legend?.[top] ?? ''))}</span></div>
				${levels.map((k) => probRow(`${k} · ${criterionText(a.legend?.[k] ?? '')}`, a.probabilities?.[k], k === top)).join('')}
				${a.confidence != null ? `<span class="pill">confidence <b>${Number(a.confidence).toFixed(2)}</b></span>` : ''}`;
		}
		case 'choice':
			return `<div class="big">${esc(a.choice)}</div>
				${Object.entries(a.probabilities || {}).map(([k, p]) => probRow(k, p, k === a.choice)).join('')}
				${a.confidence != null ? `<span class="pill">confidence <b>${Number(a.confidence).toFixed(2)}</b></span>` : ''}`;
		default:
			return `<pre>${highlightJson(a)}</pre>`;
	}
}

export function renderSystemOneCriteria(q) {
	const c = q.criteria;
	if (!c) return '';
	if (Array.isArray(c)) return `<div class="s1-crit">${c.map((x, i) => `${i}: ${esc(criterionText(x))}`).join('<br>')}</div>`;
	return `<div class="s1-crit">${Object.entries(c).map(([k, v]) => `<b>${esc(k)}</b>: ${esc(criterionText(v))}`).join('<br>')}</div>`;
}

// The state can be long (e.g. every tool a tool search chooses from): folded to its first
// lines by default, like system prompts.
const STATE_PREVIEW_LINES = 6;
const STATE_FOLD_CHARS = 1_500; // folded also when a few lines hold long strings
const STATE_PREVIEW_LINE_CHARS = 160;

function renderSystemOneState(wire, st) {
	if (st && typeof st === 'object' && !Array.isArray(st) && Object.keys(st).length === 1 && typeof st.text === 'string') {
		return `<div class="msg user"><div class="role">state · text</div><div class="text">${esc(st.text)}</div></div>`;
	}
	const json = JSON.stringify(st ?? null, null, 2);
	const lines = json.split('\n');
	if (lines.length <= STATE_PREVIEW_LINES + 2 && json.length <= STATE_FOLD_CHARS) return `<pre class="json">${highlightJson(json)}</pre>`;
	const preview = lines.slice(0, STATE_PREVIEW_LINES).map((l) => (l.length > STATE_PREVIEW_LINE_CHARS ? l.slice(0, STATE_PREVIEW_LINE_CHARS) + '…' : l));
	const key = 's1-state:' + wire.id;
	return `<details class="s1-state" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary>
		<span class="s1-state-label"><span class="chev">▸</span>state · ${fmtNum(lines.length)} lines · ${fmtNum(json.length)} chars</span>
		<pre class="json s1-state-preview">${highlightJson(preview.join('\n'))}${lines.length > STATE_PREVIEW_LINES ? '\n  …' : ''}</pre></summary>
		<pre class="json">${highlightJson(json)}</pre></details>`;
}

export function renderSystemOne(wire, nreq, nresp) {
	const stateHtml = renderSystemOneState(wire, nreq.state);
	let answersNote = '';
	if (wire.resp && nresp && nresp.error) answersNote = `<div class="notice err">${esc(nresp.error)}</div>`;
	const params = [nreq.params.model && `<span class="pill">model: <b>${esc(nreq.params.model)}</b></span>`,
		nresp?.model && `<span class="pill">answered by: <b>${esc(nresp.model)}</b></span>`,
		nresp?.usage?.input != null && `<span class="pill">input: <b>${fmtNum(nresp.usage.input)}</b></span>`,
		nresp?.usage?.output != null && `<span class="pill">output: <b>${fmtNum(nresp.usage.output)}</b></span>`].filter(Boolean).join('');
	const rows = Object.entries(nreq.questions).map(([name, q]) => `<tr>
		<td class="q"><span class="fn">${esc(name)}</span><br><span class="tag muted">${esc(q.type)}</span></td>
		<td>${esc(criterionText(q.instructions ?? ''))}${renderSystemOneCriteria(q)}</td>
		<td class="a">${wire.resp ? (nresp && nresp.answers ? renderSystemOneAnswer(nresp.answers[name]) : '–') : '<span class="spinner"></span>'}</td></tr>`).join('');
	return `<div class="params">${params}</div>${stateHtml}${answersNote}
		<table class="s1" style="margin-top:.6rem"><thead><tr><th>question</th><th>asked</th><th>answer</th></tr></thead><tbody>${rows}</tbody></table>`;
}

// ---------------------------------------------------------------- embeddings
const NO_HTTP_EMBEDDING = 'Seen at the embedding model bean: no HTTP traffic was recorded for this call (a model running in the JVM).';
const MAX_EMBEDDING_INPUTS = 20;

/** e.g. "1 vector × 1,536" */
export const embeddingResult = (nresp) => `${fmtNum(nresp.vectors)} vector${nresp.vectors === 1 ? '' : 's'}${nresp.dimensions ? ' × ' + fmtNum(nresp.dimensions) : ''}`;

function renderEmbedding(wire, nreq, nresp) {
	const params = [nreq.params.model && `<span class="pill">model: <b>${esc(nreq.params.model)}</b></span>`,
		nreq.params.dimensions && `<span class="pill">dimensions: <b>${esc(nreq.params.dimensions)}</b></span>`,
		nresp && !nresp.error && `<span class="pill">${embeddingResult(nresp)}</span>`,
		nresp?.usage?.input != null && `<span class="pill">input: <b>${fmtNum(nresp.usage.input)}</b></span>`].filter(Boolean).join('');
	const inputs = nreq.inputs.slice(0, MAX_EMBEDDING_INPUTS).map((text, i) => `<div class="msg user"><div class="role">input ${i + 1}</div>
		<div class="text">${esc(typeof text === 'string' ? oneLine(text, 600) : JSON.stringify(text))}</div></div>`).join('');
	const hidden = inputCount(nreq) - Math.min(nreq.inputs.length, MAX_EMBEDDING_INPUTS);
	const more = hidden > 0 ? `<div class="notice info">… and ${hidden} more input${hidden === 1 ? '' : 's'}${wire.inProcess ? '' : ' (see Request JSON)'}</div>` : '';
	const err = nresp?.error ? `<div class="notice err">${esc(nresp.error)}</div>` : '';
	return `<div class="params">${params}</div>${err}<div class="msgs" style="margin-top:.5rem">${inputs}${more}</div>`;
}

// Compact answer highlights for the round-trip summary line.
export function systemOneHighlights(nresp) {
	if (!nresp || !nresp.answers) return '';
	const entries = Object.entries(nresp.answers);
	let html = '';
	for (const [n, a] of entries) {
		if (a.type === 'noul') html += `<span class="pill">${esc(n)}: <b>${noulLeaning(a.noul)}</b> ${a.noul.toFixed(2)}</span>`;
		if (a.type === 'score') html += `<span class="pill">${esc(n)}: <b>${esc(a.score)}</b></span>`;
		if (a.type === 'choice') html += `<span class="pill">${esc(n)}: <b>${esc(a.choice)}</b></span>`;
	}
	return html;
}

export function renderWire(wire) {
	const key = 'wire:' + wire.id;
	const nreq = normRequest(wire);
	const nresp = normResponse(wire);
	const u = nresp && nresp.usage;

	// An in-process call (e.g. an embedding model running in the JVM) has no HTTP request to show.
	const where = wire.inProcess ? `<span class="path" title="${esc(NO_HTTP_EMBEDDING)}">EmbeddingModel call · no HTTP</span>`
		: `<span class="path">${esc(wire.req.method)} ${esc(wire.req.path)}</span>`;
	let summary = `<span class="chev">▸</span><span class="num">#${wire.num}</span>
		<span class="pill">${esc(providerLabel(wire))}</span>${where}`;
	const systemOne = adapterOf(wire)?.kind === 'systemone';
	const embedding = adapterOf(wire)?.kind === 'embedding';
	if (nreq && embedding) {
		if (nreq.params.model) summary += `<span class="pill">${esc(nreq.params.model)}</span>`;
		summary += `<span class="pill">embed ${inputCount(nreq)} input${inputCount(nreq) === 1 ? '' : 's'}</span>`;
	}
	else if (nreq && systemOne) {
		if (nreq.params.model) summary += `<span class="pill">${esc(nreq.params.model)}</span>`;
		const n = Object.keys(nreq.questions).length;
		summary += `<span class="pill">${n} question${n === 1 ? '' : 's'}</span>`;
	}
	else if (nreq) {
		if (nreq.params.model) summary += `<span class="pill">${esc(nreq.params.model)}</span>`;
		summary += `<span class="pill">${nreq.messages.length} msg${nreq.messages.length === 1 ? '' : 's'}</span>`;
		if (nreq.tools.length) summary += `<span class="pill">${nreq.tools.length} tools</span>`;
		if (nreq.params.stream) summary += `<span class="pill">stream</span>`;
	}
	summary += '<span class="arrow">→</span>';
	if (!wire.resp) summary += '<span class="spinner"></span><span class="right-meta">waiting for the model…</span>';
	else if (wire.resp.error || wire.resp.status >= 400) summary += `<span class="pill err">HTTP ${esc(wire.resp.status)}</span>`;
	else {
		const toolUses = nresp && nresp.blocks ? nresp.blocks.filter((b) => b.type === 'tool_use') : [];
		if (systemOne) summary += systemOneHighlights(nresp);
		else if (embedding && nresp && !nresp.error) summary += `<span class="pill stop-end_turn">${embeddingResult(nresp)}</span>`;
		else if (toolUses.length) summary += toolUses.map((t) => `<span class="pill stop-tool_use">⚙ ${esc(t.name)}</span>`).join('');
		else if (nresp && nresp.stop) summary += `<span class="pill ${stopClass(nresp.stop)}">${esc(nresp.stop)}</span>`;
		if (u && (u.input != null || u.output != null)) summary += `<span class="right-meta">${fmtNum(u.input)} in · ${fmtNum(u.output)} out</span>`;
		summary += `<span class="right-meta">${fmtMs(wire.resp.durationMs)}</span>`;
	}

	const tab = state.tabs.get(wire.id) || (nreq ? 'conv' : 'req');
	const tabs = [nreq && ['conv', systemOne ? 'Questions & answers' : embedding ? 'Embedding' : 'Conversation'], ['req', wire.inProcess ? 'Request' : 'Request JSON'],
		['resp', 'Response'], !wire.inProcess && ['hdr', 'Headers']].filter(Boolean);
	let body = `<div class="tabs">${tabs.map(([id, label]) => `<button class="tab ${tab === id ? 'on' : ''}" data-wire="${esc(wire.id)}" data-tab="${id}">${label}</button>`).join('')}</div>`;
	if (nreq && systemOne) {
		body += `<div class="pane ${tab === 'conv' ? 'on' : ''}" data-pane="conv">${renderSystemOne(wire, nreq, nresp)}</div>`;
	}
	else if (nreq && embedding) {
		body += `<div class="pane ${tab === 'conv' ? 'on' : ''}" data-pane="conv">${renderEmbedding(wire, nreq, nresp)}</div>`;
	}
	else if (nreq) {
		body += `<div class="pane ${tab === 'conv' ? 'on' : ''}" data-pane="conv"><div class="cols">
			<div><div class="col-title">→ request to ${esc(nreq.params.model || wire.req.provider)}</div>${renderNormRequest(nreq, previousConversation(wire), wire.id)}</div>
			<div><div class="col-title">← response</div>${renderNormResponse(wire)}</div></div></div>`;
	}
	body += `<div class="pane ${tab === 'req' ? 'on' : ''}" data-pane="req"><div class="col-title">${esc(wire.req.url)}</div><pre class="json">${prettyMaybeJson(wire.req.body)}</pre></div>`;
	const respBody = wire.resp ? (wire.resp.error ? esc(wire.resp.error) : prettyMaybeJson(wire.resp.body)) : '<span class="spinner"></span> waiting…';
	body += `<div class="pane ${tab === 'resp' ? 'on' : ''}" data-pane="resp"><div class="col-title">${wire.inProcess ? 'in-process' : `HTTP ${esc(wire.resp?.status ?? '…')}`}</div><pre class="json">${respBody}</pre></div>`;
	if (!wire.inProcess) body += `<div class="pane ${tab === 'hdr' ? 'on' : ''}" data-pane="hdr"><div class="cols">
		<div><div class="col-title">request headers</div>${renderHeaders(wire.req.headers)}</div>
		<div><div class="col-title">response headers</div>${renderHeaders(wire.resp?.headers)}</div></div></div>`;

	const hl = state.highlight && state.highlight === wireModelKey(wire) ? ' hl' : '';
	return `<details class="wire${hl}" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary>${summary}</summary>${body}</details>`;
}
