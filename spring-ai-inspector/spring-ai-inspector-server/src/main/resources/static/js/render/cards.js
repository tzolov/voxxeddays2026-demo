import { adapterOf, inputCount, normRequest, normResponse, providerLabel, usageOf } from '../providers.js';
import { mcpName, renderMcpMessages } from './mcp.js';
import { isAdvisorOnly, renderModelCall, usageOfModelCall } from './models.js';
import { memoryHint, renderMemory } from './memory.js';
import { diffMessages, renderAnswerMessage, renderSpringMessage } from './messages.js';
import { RAG_CONTEXT_KEYS, pageOf, renderRag, scoreText, searchStatus } from './rag.js';
import { wireModelKey } from './tokens.js';
import { isEmbeddingWire, opKey, toolAt, toolOfCall, vectorOps } from './vectorops.js';
import { NOUL_HOT, renderWire } from './wire.js';
import { state } from '../state.js';
import { MAX_ORDER, esc, fmtMs, fmtNum, fmtOrder, highlightJson, isOpen, oneLine, parseJson, prettyMaybeJson, recordedTs } from '../util.js';

// ---------------------------------------------------------------- tool executions
// Where an MCP tool comes from: connection, server and the tool's own name on that server.
const mcpTitle = (mcp) => [mcp.connection && `MCP connection: ${mcp.connection}`,
	mcp.server && `server: ${mcp.server}${mcp.serverVersion ? ' ' + mcp.serverVersion : ''}`, mcp.tool && `tool: ${mcp.tool}`].filter(Boolean).join('\n');

/**
 * {@code nested}: what the tool did meanwhile, e.g. a vector store search or a sub-agent's
 * ChatClient call (see renderItems); {@code running}: what of it is still running, shown on the
 * (collapsed) card's summary line; {@code subAgents}: how many of the nested entries are sub-agent
 * calls. Sub-agents in another app (remote agents, linked by the server) nest the same way.
 */
export function renderTool(tool, nested = '', running = '', subAgents = 0) {
	const key = 'tool:' + tool.id;
	const st = tool.start; const end = tool.end;
	const args = parseJson(st.arguments);
	let summary = `<span class="chev">▸</span><span class="num">⚙</span><span class="fn">${esc(st.name)}</span>
		${st.mcp ? `<span class="pill mcp" title="${esc(mcpTitle(st.mcp))}">MCP · ${esc(mcpName(st.mcp))}</span>`
		: st.toolType ? `<span class="pill">${esc(st.toolType)}</span>` : ''}<span class="oneline">${esc(oneLine(st.arguments, 80))}</span><span class="arrow">→</span>`;
	summary += running;
	if (!end) summary += '<span class="spinner"></span><span class="right-meta">running…</span>';
	else if (end.error) summary += `<span class="pill err">${esc(oneLine(end.error, 60))}</span><span class="right-meta">${fmtMs(end.durationMs)}</span>`;
	else summary += `<span class="oneline">${esc(oneLine(end.result, 80))}</span><span class="right-meta">${fmtMs(end.durationMs)}</span>`;
	const result = !end ? '<div class="notice info"><span class="spinner"></span> running…</div>'
		: end.error ? `<div class="notice err">${esc(end.error)}</div>` : `<pre class="json">${prettyMaybeJson(end.result)}</pre>`;
	const body = `<div class="pane on"><div class="cols">
		<div><div class="col-title">→ arguments${st.toolCallId ? ' · ' + esc(st.toolCallId) : ''}</div><pre class="json">${args === undefined ? esc(st.arguments) : highlightJson(args ?? {})}</pre>
			${st.description ? `<div class="s1-crit" style="margin-top:.4rem">${esc(st.description)}</div>` : ''}</div>
		<div><div class="col-title">← result</div>${result}</div></div></div>`;
	// MCP messages exchanged while the tool ran: the call, the server's logs, sampling requests, ...
	const mcp = tool.mcp?.length ? `<div class="pane on" style="padding-top:0"><div class="col-title">MCP messages · ${esc(st.mcp?.connection ?? '')}</div>${renderMcpMessages(tool.mcp)}</div>` : '';
	if (mcp) summary += `<span class="pill mcp" title="MCP messages while the tool ran">${tool.mcp.length} MCP msgs</span>`;
	const remote = (tool.remoteCalls || []).map((rc) => `<div class="remote-head">↘ handled by <b>${esc(rc.run.app)}</b> · linked by timing</div>${renderCall(rc, false)}`).join('');
	const remotes = tool.remoteCalls?.length || 0;
	const agents = [subAgents && `${subAgents} sub-agent${subAgents === 1 ? '' : 's'}`, remotes && `${remotes} remote agent${remotes === 1 ? '' : 's'}`].filter(Boolean);
	if (agents.length) summary += `<span class="link-badge" title="ChatClient calls this tool made, shown inside it">↘ ${agents.join(' · ')}</span>`;
	const inner = nested ? `<div class="pane on" style="padding-top:0">${nested}</div>` : '';
	// A tool that ran sub-agents opens by default: their calls are what it did.
	return `<details class="wire tool-exec" data-key="${esc(key)}" ${isOpen(key, agents.length > 0) ? 'open' : ''}><summary>${summary}</summary>${body}${inner}${mcp}${remote ? `<div class="pane on" style="padding-top:0">${remote}</div>` : ''}</details>`;
}

export function renderItem(it, latest) {
	if (it.kind === 'wire') return renderWire(it.ref);
	if (it.kind === 'model') return isAdvisorOnly(it.ref) ? renderModelCall(it.ref) : ''; // else its HTTP round-trip shows it
	return renderCall(it.ref, it === latest);
}

export const isJevWire = (it) => it.kind === 'wire' && adapterOf(it.ref)?.kind === 'systemone';

/** Round-trips that fold into one group when 3+ run back to back: systemOne checks, embedding calls. */
export const foldKind = (it) => (isJevWire(it) ? 'jev' : isEmbeddingWire(it) ? 'embed' : null);

/**
 * What folds together: round-trips of one fold kind to one model, the same in the cards and
 * the sequence view (one lane), so an arrow of the sequence opens its group in the cards.
 */
export function foldKey(it) {
	const kind = foldKind(it);
	return kind && kind + '|' + wireModelKey(it.ref);
}

/** The shell of a folded group: its round-trips, with the group's own pills and timing. */
function renderFoldGroup(group, prefix, title, pills, timing) {
	const key = prefix + ':' + group[0].id;
	const done = group.filter((w) => w.resp).length;
	return `<details class="wire" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary><span class="chev">▸</span>
		<span class="pill">${esc(providerLabel(group[0]))}</span><b>${group.length} ${title}</b>${pills(done)}
		${done < group.length ? `<span class="spinner"></span><span class="right-meta">${done}/${group.length}</span>` : ''}
		<span class="right-meta">#${group[0].num}–#${group[group.length - 1].num} · ${timing}</span></summary>
		<div class="pane on">${group.map(renderWire).join('')}</div></details>`;
}

function renderJevGroup(group) {
	const hot = group.filter((w) => Object.values(normResponse(w)?.answers || {}).some((a) => a.type === 'noul' && a.noul >= NOUL_HOT)).length;
	return renderFoldGroup(group, 'jev', 'systemOne checks',
		(done) => (done ? `<span class="pill" title="checks with at least one noul answer ≥ ${NOUL_HOT}">${hot} with P(true) ≥ ${NOUL_HOT}</span>` : ''),
		`up to ${fmtMs(Math.max(...group.map((w) => w.resp?.durationMs || 0)))}`);
}

/**
 * Totals of a run of embedding calls (e.g. ingesting documents for RAG). {@code ms} is the
 * time it took, first request to last response, so parallel calls aren't counted twice.
 */
export function embeddingTotals(group) {
	const answered = group.filter((w) => w.resp);
	return { inputs: group.reduce((n, w) => n + (inputCount(normRequest(w)) ?? 0), 0),
		vectors: group.reduce((n, w) => n + (normResponse(w)?.vectors ?? 0), 0),
		tokens: group.reduce((n, w) => n + (usageOf(w)?.input ?? 0), 0),
		ms: answered.length ? Math.max(...answered.map((w) => recordedTs(w.resp))) - Math.min(...group.map((w) => recordedTs(w.req))) : null };
}

function renderEmbeddingGroup(group) {
	const model = normRequest(group[0])?.params.model;
	const t = embeddingTotals(group);
	return renderFoldGroup(group, 'embed', 'embedding calls',
		() => `${model ? `<span class="pill">${esc(model)}</span>` : ''}<span class="pill">${fmtNum(t.inputs)} inputs</span>${t.tokens ? `<span class="right-meta">${fmtNum(t.tokens)} in</span>` : ''}`,
		t.ms != null ? fmtMs(t.ms) : '…');
}

// Renders items, folding runs of 3+ consecutive systemOne checks (e.g. per-document RAG
// filtering) or embedding calls (e.g. ingesting documents) into one group each, so they
// don't drown the model round-trips.
//
// What a tool did while it ran is shown inside its card: the round-trips it made (e.g. a
// systemOne check, an embedding), model calls without HTTP, and vector store adds and searches,
// these with the embedding round-trips they made inside them (see vectorops.js). Like the
// sequence view, by timing: with tools running in parallel, the first one open gets them.
// ctx: the call's searches, its id (so a search's hits can open the Retrieval step) and the order key (ord)
// it ended at (a tool whose end was never recorded runs until then).
export function renderItems(all, latest, ctx = {}) {
	const { ops, opOf } = vectorOps(all, ctx.searches || []);
	const tools = all.filter((i) => i.kind === 'tool').map((i) => i.ref);
	const seqOf = (it) => (it.kind === 'vop' ? it.ref.ord : it.kind === 'tool' ? it.ref.start.ord : it.ref.req?.ord ?? it.ref.ord ?? 0);
	const inTool = new Map(tools.map((t) => [t, []])); const top = [];
	const place = (it) => {
		const tool = it.kind === 'call' ? toolOfCall(tools, it.ref, ctx.callEnd) // a sub-agent, inside its tool
			: (it.kind === 'wire' || it.kind === 'model' || it.kind === 'vop') && toolAt(tools, seqOf(it), ctx.callEnd);
		(tool ? inTool.get(tool) : top).push(it);
	};
	ops.forEach((op) => place({ kind: 'vop', ref: op }));
	all.filter((i) => i.kind !== 'ingest' && !(i.kind === 'wire' && opOf(i.ref))).forEach(place);
	const inOrder = (list) => list.map((it, i) => [it, i]).sort(([a, i], [b, j]) => seqOf(a) - seqOf(b) || i - j).map(([it]) => it);
	const render = (it) => (it.kind === 'vop' ? renderVectorOp(it.ref, ctx)
		: it.kind === 'tool' ? renderTool(it.ref, renderInOrder(inOrder(inTool.get(it.ref) || []), latest, render),
			runningInside(inTool.get(it.ref) || []), (inTool.get(it.ref) || []).filter((e) => e.kind === 'call').length)
		: renderItem(it, latest));
	return renderInOrder(inOrder(top), latest, render);
}

/** What of a tool's own work is still running, e.g. "⤓ ingesting…", for its collapsed card's summary line. */
function runningInside(entries) {
	return entries.filter((e) => e.kind === 'vop' && e.ref.ev.pending)
		.map((e) => `<span class="pill">${e.ref.search ? '🔎 searching…' : e.ref.ev.op === 'delete' ? '✕ deleting…' : '⤓ ingesting…'}</span>`).join('');
}

/** Renders entries in order, folding runs of 3+ systemOne checks or embedding calls to one model. */
function renderInOrder(items, latest, render) {
	let html = '';
	for (let i = 0; i < items.length;) {
		const key = foldKey(items[i]);
		let j = i;
		while (key && j < items.length && foldKey(items[j]) === key) j++;
		if (j - i >= 3) {
			const group = items.slice(i, j).map((it) => it.ref);
			html += foldKind(items[i]) === 'jev' ? renderJevGroup(group) : renderEmbeddingGroup(group);
			i = j;
		}
		else {
			const end = Math.max(j, i + 1);
			for (let k = i; k < end; k++) html += render(items[k]);
			i = end;
		}
	}
	return html;
}

/** A vector store add or search, with the embedding round-trips it made and, for a search, its hits. */
function renderVectorOp(op, ctx) {
	const ev = op.ev;
	const key = opKey(ev);
	const deleting = ev.op === 'delete' || ev.type === 'vector-delete';
	const status = op.search ? searchStatus(ev)
		: ev.pending ? `<span class="spinner"></span><span class="right-meta">${deleting ? 'deleting…' : 'ingesting…'}</span>`
		: ev.error ? `<span class="pill err" title="${esc(ev.error)}">${esc(oneLine(ev.error, 60))}</span>`
		: deleting ? '<span class="pill">removed</span>' : `<span class="pill">${fmtNum(ev.count)} stored</span>`;
	const what = op.search ? `<span class="fn">“${esc(oneLine(ev.query, 80))}”</span>`
		: deleting ? `<span class="fn">delete ${ev.filter ? 'by filter ' + esc(oneLine(ev.filter, 60)) : `${fmtNum(ev.count)} id${ev.count === 1 ? '' : 's'}`}</span>`
		: `<span class="fn">add ${fmtNum(ev.count)} chunk${ev.count === 1 ? '' : 's'}</span>`;
	const summary = `<span class="chev">▸</span><span class="num">${op.search ? '🔎' : deleting ? '✕' : '⤓'}</span><span class="pill">${esc(ev.store)}</span>
		${what}
		<span class="arrow">→</span>${status}${ev.pending ? '' : `<span class="right-meta">${fmtMs(ev.durationMs)}</span>`}`;
	const nested = op.wires.length ? renderItems(op.wires.map((w) => ({ kind: 'wire', ref: w })), null) : '';
	// The hits, one line: each opens the search in the Retrieval step, where the documents are.
	const goto = ctx.callId && esc(JSON.stringify([`call:${ctx.callId}`, `search:${ev.opId ?? ev.searchId}`]));
	const hits = op.search && ev.results.length ? `<div class="hits-line"><span class="col-title">hits</span>${ev.results.map((r) => {
		const page = pageOf(r);
		return `<span class="chip"${goto ? ` data-goto="${goto}"` : ''} title="${esc(oneLine(r.text, 300))}">${scoreText(r.score)}${page != null ? ' · page ' + esc(page) : ''}</span>`;
	}).join('')}</div>` : '';
	const sources = !op.search ? [...new Set((ev.sample || []).map((d) => d.metadata?.file_name || d.metadata?.source).filter(Boolean))] : [];
	const from = sources.length ? `<div class="hits-line"><span class="col-title">from</span><span class="fn">${sources.map(esc).join(', ')}</span></div>` : '';
	const error = ev.error ? `<div class="notice err">${esc(ev.error)}</div>` : '';
	// Whether embeddings were recorded, whatever the search returned (a delete makes none).
	const empty = !nested && !ev.pending && !ev.error && !deleting
		? '<div class="notice info">No embedding round-trips recorded during it (e.g. embeddings reused, computed elsewhere or not routed).</div>' : '';
	return `<details class="wire vector-op" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary>${summary}</summary>
		<div class="pane on">${error}${nested}${hits}${from}${empty}</div></details>`;
}

// ---------------------------------------------------------------- ChatClient call rendering
export function step(num, title, hint, content) {
	return `<div class="step"><div class="step-num">${num}</div><div class="step-title">${title}${hint ? `<span class="hint">${hint}</span>` : ''}</div>${content}</div>`;
}

/** A call's own round-trips: HTTP ones, in-process ones (e.g. embeddings in the JVM), and model calls without HTTP. */
export function callTrips(call) {
	const wires = call.items.filter((i) => i.kind === 'wire').map((i) => i.ref);
	return { wires, noHttp: call.modelCalls.filter(isAdvisorOnly), count: wires.length + call.modelCalls.filter(isAdvisorOnly).length };
}

export function callTokens(call) {
	let input = 0, output = 0;
	const { wires, noHttp, count } = callTrips(call);
	for (const u of [...wires.map(usageOf), ...noHttp.map(usageOfModelCall)]) {
		if (u) { input += u.input || 0; output += u.output || 0; }
	}
	if (!count && call.resp?.usage) { input = call.resp.usage.input || 0; output = call.resp.usage.output || 0; }
	return { input, output };
}

/**
 * Call cards, cached: a card is built again only when its call changed (`rev`, bumped by model.js
 * for every event of the call and of the calls nested in it), when it stops being the latest, or
 * when the user changed a fold, a tab or the highlight (`state.uiVersion`). A long run redraws
 * one card per event instead of all of them.
 */
const cards = new WeakMap(); // call -> { key, html }

export function renderCall(call, isLatest) {
	const key = `${call.rev || 0}|${isLatest ? 1 : 0}|${state.uiVersion}`;
	const cached = cards.get(call);
	if (cached && cached.key === key) return cached.html;
	const html = renderCallFresh(call, isLatest);
	cards.set(call, { key, html });
	return html;
}

function renderCallFresh(call, isLatest) {
	const key = 'call:' + call.id;
	const appMsgs = call.req.messages || [];
	const lastUser = [...appMsgs].reverse().find((m) => m.role === 'user');
	const tokens = callTokens(call);

	let meta = '';
	if (!call.resp) meta = '<span class="spinner"></span> running';
	else {
		meta = fmtMs(call.resp.durationMs);
		if (tokens.input || tokens.output) meta += ` · ${fmtNum(tokens.input)} in · ${fmtNum(tokens.output)} out`;
		const trips = callTrips(call).count;
		if (trips) meta += ` · ${trips} round-trip${trips === 1 ? '' : 's'}`;
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

	// 4. wire round-trips and tool runs, with what each tool did inside it (sub-agents, searches, ...)
	let s4 = renderItems(call.items, null, { searches: call.searches, callId: call.id, callEnd: call.resp?.ord });
	if (!s4) s4 = first ? '<div class="notice info">No wire traffic captured for this call (provider not routed through the inspector).</div>'
		: '<div class="notice info">—</div>';

	// 5. answer
	let s5;
	if (!call.resp) s5 = '<div class="notice info"><span class="spinner"></span> waiting…</div>';
	else if (call.resp.error) s5 = `<div class="notice err">${esc(call.resp.error)}</div>`;
	else {
		s5 = `<div class="msgs answer">${(call.resp.generations || []).map((g, i) => renderAnswerMessage(g, `ans:${esc(call.id)}:${i}`)).join('')}</div>`;
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
	const searches = call.searches.length; const adds = call.items.filter((i) => i.kind === 'ingest').length;
	if (searches) parts.push(`${searches} vector search${searches === 1 ? '' : 'es'}`);
	if (adds) parts.push(`${adds} vector store add${adds === 1 ? '' : 's'}`);
	if (call.wires.length) parts.push(`${call.wires.length} HTTP round-trip${call.wires.length === 1 ? '' : 's'}`);
	const noHttp = call.modelCalls.filter(isAdvisorOnly).length;
	if (noHttp) parts.push(`${noHttp} model call${noHttp === 1 ? '' : 's'} without HTTP`);
	const embeds = call.items.filter((i) => i.kind === 'wire' && i.ref.inProcess).length;
	if (embeds) parts.push(`${embeds} embedding call${embeds === 1 ? '' : 's'} without HTTP`);
	if (tools) parts.push(`${tools} tool run${tools === 1 ? '' : 's'}`);
	return parts.join(' · ');
}
