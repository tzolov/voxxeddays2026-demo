import { adapterOf, normResponse, usageOf } from '../providers.js';
import { memoryHint, renderMemory } from './memory.js';
import { diffMessages, renderSpringMessage } from './messages.js';
import { RAG_CONTEXT_KEYS, renderIngest, renderRag } from './rag.js';
import { NOUL_HOT, renderWire } from './wire.js';
import { state } from '../state.js';
import { MAX_ORDER, esc, fmtMs, fmtNum, fmtOrder, highlightJson, isOpen, oneLine, parseJson, prettyMaybeJson } from '../util.js';

// ---------------------------------------------------------------- tool executions
export function renderTool(tool) {
	const key = 'tool:' + tool.id;
	const st = tool.start; const end = tool.end;
	const args = parseJson(st.arguments);
	let summary = `<span class="chev">▸</span><span class="num">⚙</span><span class="fn">${esc(st.name)}</span>
		${st.toolType ? `<span class="pill">${esc(st.toolType)}</span>` : ''}<span class="oneline">${esc(oneLine(st.arguments, 80))}</span><span class="arrow">→</span>`;
	if (!end) summary += '<span class="spinner"></span><span class="right-meta">running…</span>';
	else if (end.error) summary += `<span class="pill err">${esc(oneLine(end.error, 60))}</span><span class="right-meta">${fmtMs(end.durationMs)}</span>`;
	else summary += `<span class="oneline">${esc(oneLine(end.result, 80))}</span><span class="right-meta">${fmtMs(end.durationMs)}</span>`;
	const result = !end ? '<div class="notice info"><span class="spinner"></span> running…</div>'
		: end.error ? `<div class="notice err">${esc(end.error)}</div>` : `<pre class="json">${prettyMaybeJson(end.result)}</pre>`;
	const body = `<div class="pane on"><div class="cols">
		<div><div class="col-title">→ arguments${st.toolCallId ? ' · ' + esc(st.toolCallId) : ''}</div><pre class="json">${args === undefined ? esc(st.arguments) : highlightJson(args ?? {})}</pre>
			${st.description ? `<div class="s1-crit" style="margin-top:.4rem">${esc(st.description)}</div>` : ''}</div>
		<div><div class="col-title">← result</div>${result}</div></div></div>`;
	const remote = (tool.remoteCalls || []).map((rc) => `<div class="remote-head">↘ handled by <b>${esc(rc.run.app)}</b> · linked by timing</div>${renderCall(rc, false)}`).join('');
	if (remote) summary += `<span class="link-badge">↘ ${tool.remoteCalls.length} remote call${tool.remoteCalls.length === 1 ? '' : 's'}</span>`;
	return `<details class="wire tool-exec" data-key="${esc(key)}" ${isOpen(key, !!remote) ? 'open' : ''}><summary>${summary}</summary>${body}${remote ? `<div class="pane on" style="padding-top:0">${remote}</div>` : ''}</details>`;
}

export function renderItem(it, latest) {
	if (it.kind === 'wire') return renderWire(it.ref);
	if (it.kind === 'tool') return renderTool(it.ref);
	if (it.kind === 'ingest') return renderIngest(it.ref);
	return renderCall(it.ref, it === latest);
}

export const isJevWire = (it) => it.kind === 'wire' && adapterOf(it.ref)?.kind === 'systemone';

// Renders items, folding runs of 3+ consecutive Jev systemOne checks (e.g. per-document
// RAG filtering) into one group so they don't drown the model round-trips.
export function renderItems(items, latest) {
	let html = '';
	for (let i = 0; i < items.length;) {
		let j = i;
		while (j < items.length && isJevWire(items[j])) j++;
		if (j - i >= 3) {
			const group = items.slice(i, j).map((it) => it.ref);
			const key = 'jev:' + group[0].id;
			const done = group.filter((w) => w.resp).length;
			const hot = group.filter((w) => Object.values(normResponse(w)?.answers || {}).some((a) => a.type === 'noul' && a.noul >= NOUL_HOT)).length;
			const ms = group.map((w) => w.resp?.durationMs || 0);
			html += `<details class="wire" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary><span class="chev">▸</span>
				<span class="pill">typesafe</span><b>${group.length} Jev systemOne checks</b>
				${done < group.length ? `<span class="spinner"></span><span class="right-meta">${done}/${group.length}</span>` : ''}
				${done ? `<span class="pill" title="checks with at least one noul answer ≥ ${NOUL_HOT}">${hot} with P(true) ≥ ${NOUL_HOT}</span>` : ''}
				<span class="right-meta">#${group[0].num}–#${group[group.length - 1].num} · up to ${fmtMs(Math.max(...ms))}</span></summary>
				<div class="pane on">${group.map(renderWire).join('')}</div></details>`;
			i = j;
		}
		else {
			const end = Math.max(j, i + 1);
			for (let k = i; k < end; k++) html += renderItem(items[k], latest);
			i = end;
		}
	}
	return html;
}

// ---------------------------------------------------------------- ChatClient call rendering
export function step(num, title, hint, content) {
	return `<div class="step"><div class="step-num">${num}</div><div class="step-title">${title}${hint ? `<span class="hint">${hint}</span>` : ''}</div>${content}</div>`;
}

export function callTokens(call) {
	let input = 0, output = 0;
	for (const w of call.wires) { const u = usageOf(w); if (u) { input += u.input || 0; output += u.output || 0; } }
	if (!call.wires.length && call.resp?.usage) { input = call.resp.usage.input || 0; output = call.resp.usage.output || 0; }
	return { input, output };
}

export function renderCall(call, isLatest) {
	const key = 'call:' + call.id;
	const appMsgs = call.req.messages || [];
	const lastUser = [...appMsgs].reverse().find((m) => m.role === 'user');
	const tokens = callTokens(call);

	let meta = '';
	if (!call.resp) meta = '<span class="spinner"></span> running';
	else {
		meta = fmtMs(call.resp.durationMs);
		if (tokens.input || tokens.output) meta += ` · ${fmtNum(tokens.input)} in · ${fmtNum(tokens.output)} out`;
		if (call.wires.length) meta += ` · ${call.wires.length} round-trip${call.wires.length === 1 ? '' : 's'}`;
	}

	const summary = `<span class="chev">▸</span><span class="kind">${call.parent ? 'nested ChatClient' : 'ChatClient'}</span>${call.inferred ? '<span class="tag muted" title="parent inferred by timing: the call ran on another thread">inferred</span>' : ''}
		${call.linkedFrom ? (() => { const cr = state.runs.get(call.linkedFrom.runId); const t = cr?.tools.get(call.linkedFrom.toolId);
			return `<span class="link-badge" data-run="${esc(call.linkedFrom.runId)}" title="linked by timing">← from ${esc(cr ? cr.app.split(' · ')[0] : 'another run')}${t ? ' · ' + esc(t.start.name) : ''}</span>`; })() : ''}
		<span class="call-title">#${call.num} ${esc(oneLine(lastUser?.text || '(no user text)'))}</span>
		<span class="right-meta">${meta}</span>`;

	// 1. what the app wrote
	const appTools = call.req.options?.tools || [];
	let s1 = `<div class="msgs">${appMsgs.map((m) => renderSpringMessage(m)).join('')}</div>`;
	if (appTools.length) s1 += `<div class="chain" style="margin-top:.45rem">${appTools.map((t) => `<span class="chip" title="${esc(t.description)}">⚙ ${esc(t.name)}</span>`).join('')}</div>`;

	// 2. the advisor chain (the terminal "call" advisor is the model itself)
	const advisors = (call.req.advisors || []).filter((a) => !(a.name === 'call' && a.order === MAX_ORDER));
	const s2 = `<div class="chain"><span class="chip app">your app</span>${advisors.map((a) =>
		`<span class="arrow">→</span><span class="chip">${esc(a.name)}<span class="ord">${esc(fmtOrder(a.order))}</span></span>`).join('')}<span class="arrow">→</span><span class="chip model">model</span></div>`;

	// 3. what the advisors turned it into
	const first = call.modelCalls[0];
	let s3;
	let hint3 = '';
	if (first) {
		const { out, removed } = diffMessages(appMsgs, first.req.messages || []);
		const tools = diffTools(appTools, first.req.options?.tools || []);
		const added = out.filter((x) => x.mark === 'added').length + tools.added;
		const dropped = removed.length + tools.removed;
		hint3 = added || dropped ? `${added ? `+${added} added` : ''}${added && dropped ? ', ' : ''}${dropped ? `−${dropped} removed` : ''}` : 'unchanged';
		s3 = `<div class="msgs">${removed.map((m) => renderSpringMessage(m, 'removed')).join('')}${out.map((x) => renderSpringMessage(x.m, x.mark)).join('')}</div>`;
		s3 += tools.html;
		if (call.modelCalls.length > 1) s3 += `<div class="notice info" style="margin-top:.45rem">The advisor chain called the model ${call.modelCalls.length} times (e.g. a tool-calling loop or a retrying advisor).</div>`;
	}
	else if (call.resp) {
		s3 = '<div class="notice warn">The model was never called. An advisor answered on its own (e.g. a guardrail blocked the request).</div>';
	}
	else s3 = '<div class="notice info"><span class="spinner"></span> advisors running…</div>';

	// 4. wire round-trips, interleaved with nested ChatClient calls (sub-agents)
	let s4 = renderItems(call.items, null);
	if (!s4) s4 = first ? '<div class="notice info">No wire traffic captured for this call (provider not routed through the inspector).</div>'
		: '<div class="notice info">—</div>';

	// 5. answer
	let s5;
	if (!call.resp) s5 = '<div class="notice info"><span class="spinner"></span> waiting…</div>';
	else if (call.resp.error) s5 = `<div class="notice err">${esc(call.resp.error)}</div>`;
	else {
		s5 = `<div class="msgs answer">${(call.resp.generations || []).map((g) => renderSpringMessage(g)).join('')}</div>`;
		// Retrieved documents are shown in the RAG step, not as raw context.
		const ctx = Object.entries(call.resp.context || {}).filter(([k]) => !RAG_CONTEXT_KEYS.has(k));
		const ctxValue = (v) => typeof v === 'string' ? esc(v) : `<pre>${highlightJson(v)}</pre>`;
		if (ctx.length) s5 += `<details class="fold" data-key="ctx:${esc(call.id)}" ${isOpen('ctx:' + call.id, false) ? 'open' : ''}><summary>advisor context · ${ctx.map(([k]) => esc(k)).join(', ')}</summary>
			<div class="kv">${ctx.map(([k, v]) => `<div class="k">${esc(k)}</div><div class="v">${ctxValue(v)}</div>`).join('')}</div></details>`;
	}

	// Steps are numbered as they appear: RAG and Memory only when the call has them.
	const hasRag = (call.req.rag || []).length || call.searches.length;
	const hasMemory = call.memory.before || call.memory.after;
	const parts = [['Your app sent', '', s1], ['Advisor chain', '', s2],
		hasRag && ['Retrieval (RAG)', call.searches.length ? `${call.searches.length} vector search${call.searches.length === 1 ? '' : 'es'}` : '', renderRag(call)],
		['After the advisors', hint3, s3], ['On the wire', wireHint(call), s4],
		['Answer', call.resp?.generations?.[0]?.finishReason ? 'finish: ' + esc(call.resp.generations[0].finishReason) : '', s5],
		hasMemory && ['Memory after this call', memoryHint(call), renderMemory(call)]].filter(Boolean);
	const steps = parts.map(([title, hint, content], i) => step(i + 1, title, hint, content)).join('');

	return `<details class="call ${call.parent ? 'nested' : ''}" data-key="${esc(key)}" ${isOpen(key, isLatest || !call.resp) ? 'open' : ''}><summary>${summary}</summary><div class="steps">${steps}</div></details>`;
}

// The tools the model receives, against the ones the app passed: unchanged tools are
// listed too, so a tool that simply passes through the advisors doesn't look dropped.
export function diffTools(appTools, modelTools) {
	const appNames = new Set(appTools.map((t) => t.name));
	const modelNames = new Set(modelTools.map((t) => t.name));
	const chip = (t, mark) => `<span class="chip ${mark}" title="${esc(t.description)}">${mark === 'added' ? '+ ' : mark === 'removed' ? '− ' : ''}⚙ ${esc(t.name)}</span>`;
	const chips = [...modelTools.map((t) => chip(t, appNames.has(t.name) ? '' : 'added')),
		...appTools.filter((t) => !modelNames.has(t.name)).map((t) => chip(t, 'removed'))];
	return {
		added: modelTools.filter((t) => !appNames.has(t.name)).length,
		removed: appTools.filter((t) => !modelNames.has(t.name)).length,
		html: chips.length ? `<div class="chain" style="margin-top:.45rem">${chips.join('')}</div>` : '',
	};
}

export function wireHint(call) {
	const tools = call.items.filter((i) => i.kind === 'tool').length;
	const parts = [];
	if (call.wires.length) parts.push(`${call.wires.length} HTTP round-trip${call.wires.length === 1 ? '' : 's'}`);
	if (tools) parts.push(`${tools} tool run${tools === 1 ? '' : 's'}`);
	return parts.join(' · ');
}
