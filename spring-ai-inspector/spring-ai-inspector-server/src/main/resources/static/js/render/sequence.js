import { adapterOf, inputCount, normRequest, normResponse, providerLabel, usageOf } from '../providers.js';
import { fmtCompact, wireModelKey } from './tokens.js';
import { embeddingTotals, foldKey, foldKind, isJevWire } from './cards.js';
import { mcpName, mcpSummary } from './mcp.js';
import { searchSummary } from './rag.js';
import { isAdvisorOnly, modelCallOutcome, modelOf, providerOf, usageOfModelCall } from './models.js';
import { memoryStoreKey } from './memory.js';
import { isEmbeddingWire, opKey, toolAt as toolRunning, toolOfCall, vectorOps } from './vectorops.js';
import { NOUL_HOT, systemOneHighlights } from './wire.js';
import { esc, fmtMs, oneLine, recordedTs } from '../util.js';

// ---------------------------------------------------------------- sequence view
// Flattens a run (plus remote calls linked to its tools) into lanes and messages ordered
// by the server's global sequence number, then draws them as an SVG sequence diagram.
export const LANE_RANK = { app: 0, adv: 1, model: 2, jev: 3, tool: 4, vector: 5 };

export function buildSequence(run) {
	const lanes = new Map(); // key -> {key, label, sub, group, kind}
	const groups = new Map(); // runId -> label
	const msgs = []; // {seq, ts, from, to, label, kind, ret, path, note}
	const acts = []; // activation bars {lane, from: seq, to: seq}: calls, tool runs, model round-trips, searches
	const lane = (r, kind, id, label, sub) => {
		const key = `${r.id}|${kind}|${id}`;
		if (!lanes.has(key)) lanes.set(key, { key, label, sub, group: r.id, kind });
		if (!groups.has(r.id)) groups.set(r.id, r === run ? null : r.app);
		return key;
	};
	// MCP tools get a lane per MCP connection (named like their card's badge); the application's own tools share one.
	const toolLane = (r, t) => {
		const mcp = t.start.mcp; const name = mcp && mcpName(mcp);
		return mcp ? lane(r, 'tool', 'mcp:' + name, name, `MCP · ${mcp.server || 'server'}`) : lane(r, 'tool', '', 'Tools', '');
	};
	const app = lane(run, 'app', '', run.app.split(' · ')[1] || 'App', run.app.split(' · ')[0]);
	// Token totals per model/systemOne lane, shown under the lane head.
	const addTokens = (laneKey, w, u = usageOf(w)) => {
		const l = lanes.get(laneKey);
		if (!u || !l) return;
		l.tokens = { input: (l.tokens?.input || 0) + (u.input || 0), output: (l.tokens?.output || 0) + (u.output || 0) };
	};

	// One lane per model, keyed like the tokens panel. systemOne lanes say who served them
	// (TypeSafe, a local Ollama, ...).
	function wireLane(r, w) {
		const model = wireModelKey(w).slice(w.req.provider.length + 1);
		return isJevWire({ kind: 'wire', ref: w }) ? lane(r, 'jev', model, model, providerLabel(w)) : lane(r, 'model', model, model, w.req.provider);
	}

	function wireReturn(w) {
		if (!w.resp) return '…';
		if (w.inProcess && w.resp.error) return `⚠ ${oneLine(w.resp.error, 40)}`;
		if (w.resp.error || w.resp.status >= 400) return `HTTP ${w.resp.status}`;
		const r = normResponse(w);
		if (r?.answers) return systemOneHighlights(r).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() || 'answers';
		if (r?.vectors != null) return `${r.vectors} vector${r.vectors === 1 ? '' : 's'} · ${fmtMs(w.resp.durationMs)}`;
		if (r?.images) return `${r.images.length} image${r.images.length === 1 ? '' : 's'} · ${fmtMs(w.resp.durationMs)}`;
		if (r?.audio) return `audio · ${fmtMs(w.resp.durationMs)}`;
		if (r?.results) return `${r.results.some((x) => x.flagged) ? 'flagged' : 'ok'} · ${fmtMs(w.resp.durationMs)}`;
		if (r?.text != null && !r.blocks?.length) return `${oneLine(r.text, 30)} · ${fmtMs(w.resp.durationMs)}`;
		const tools = (r?.blocks || []).filter((b) => b.type === 'tool_use').map((b) => b.name);
		return `${tools.length ? 'tool_use ' + tools.join(', ') : (r?.stop || 'response')} · ${fmtMs(w.resp.durationMs)}`;
	}

	function walkItems(r, call, items, adv, path, nest = 0) {
		const tools = items.filter((i) => i.kind === 'tool').map((i) => i.ref);
		// A round-trip with no recorded response ends with its call (e.g. the call failed), or runs on while the call does.
		const openEnd = call?.resp ? call.resp.seq : Infinity;
		// Who made a step: the tool running at that moment (from its lane), else the advisors.
		const toolAt = (seq) => toolRunning(tools, seq, openEnd);
		const fromAt = (seq) => {
			const t = toolAt(seq);
			return t ? toolLane(r, t) : adv;
		};
		// Vector store adds and searches: the embedding calls made meanwhile are the store's own,
		// drawn from its lane, inside the operation. Reported when they start (open until they end),
		// or, in older recordings, only once done, with their duration.
		const vectorLane = (ev) => lane(r, 'vector', '', 'Vector store', ev.store);
		const { ops, opOf } = vectorOps(items, call ? call.searches : (r.searches || []));
		const sourceOf = (w) => {
			const op = opOf(w);
			return op ? vectorLane(op.ev) : fromAt(w.req.seq);
		};
		// Where an arrow's card is in the Cards view, to open it: inside the card of the tool that
		// made it, and of the vector store operation, as the cards nest them (see renderItems).
		const inTool = (seq) => { const t = toolAt(seq); return t ? ['tool:' + t.id] : []; };
		const opPath = (op) => [...path, ...inTool(op.seq), opKey(op.ev)];
		const pathOf = (w) => { const op = opOf(w); return op ? opPath(op) : [...path, ...inTool(w.req.seq)]; };
		for (let i = 0; i < items.length;) {
			let j = i;
			// Fold runs of systemOne checks or embedding calls to the same lane into one exchange.
			const key = foldKey(items[i]); const fold = foldKind(items[i]);
			while (key && j < items.length && foldKey(items[j]) === key) j++;
			if (j - i >= 3) {
				const group = items.slice(i, j).map((it) => it.ref);
				const to = wireLane(r, group[0]); const kind = fold === 'jev' ? 'jev' : 'model'; const gpath = [...pathOf(group[0]), fold + ':' + group[0].id];
				// Calls may answer out of order: the group is done at its latest response.
				const done = group.every((w) => w.resp) ? group.reduce((a, w) => (w.resp.seq > a.resp.seq ? w : a)) : null;
				group.forEach((w) => addTokens(to, w));
				const totals = fold === 'embed' ? embeddingTotals(group) : null;
				const src = sourceOf(group[0]);
				msgs.push({ seq: group[0].req.seq, ts: group[0].req.ts, from: src, to, kind, path: gpath,
					label: totals ? `${group.length} embedding calls · ${totals.inputs} inputs` : `${group.length} systemOne checks` });
				if (done) msgs.push({ seq: done.resp.seq, ts: done.resp.ts, from: to, to: src, ret: true, kind, path: gpath,
					label: totals ? `${totals.vectors} vectors · ${fmtMs(totals.ms ?? 0)}`
						: `${group.filter((w) => Object.values(normResponse(w)?.answers || {}).some((a) => a.type === 'noul' && a.noul >= NOUL_HOT)).length} with P(true) ≥ ${NOUL_HOT}` });
				acts.push({ lane: to, from: group[0].req.seq, to: done ? done.resp.seq : openEnd, nest: 0 });
				i = j;
				continue;
			}
			const it = items[i++];
			if (it.kind === 'wire') {
				const w = it.ref; const to = wireLane(r, w); const req = normRequest(w);
				const special = adapterOf(w)?.kind;
				addTokens(to, w);
				const kind = to.includes('|jev|') ? 'jev' : 'model';
				const label = kind === 'jev' ? `systemOne · ${Object.keys(req?.questions || {}).length} questions`
					: isEmbeddingWire(it) ? `#${w.num} · embed ${inputCount(req) ?? '?'} input${inputCount(req) === 1 ? '' : 's'}${w.inProcess ? ' · no HTTP' : ''}`
					: special && special !== 'systemone' ? `#${w.num} · ${special}`
					: `#${w.num} · ${req?.messages?.length ?? '?'} msgs`;
				const src = sourceOf(w);
				const wpath = [...pathOf(w), 'wire:' + w.id];
				msgs.push({ seq: w.req.seq, ts: w.req.ts, from: src, to, label, kind, path: wpath });
				if (w.resp) msgs.push({ seq: w.resp.seq, ts: w.resp.ts, from: to, to: src, ret: true, kind, label: wireReturn(w), path: wpath });
				acts.push({ lane: to, from: w.req.seq, to: w.resp ? w.resp.seq : openEnd, nest: 0 });
			}
			else if (it.kind === 'model' && isAdvisorOnly(it.ref)) {
				// A model call without HTTP (e.g. a model running in the JVM): as the advisor saw it.
				const mc = it.ref; const model = modelOf(mc);
				const to = lane(r, 'model', model, model, providerOf(mc));
				addTokens(to, null, usageOfModelCall(mc));
				const p = [...path, ...inTool(mc.req.seq), 'model:' + mc.id];
				msgs.push({ seq: mc.req.seq, ts: mc.req.ts, from: adv, to, kind: 'model', path: p,
					label: `#${mc.num} · ${(mc.req.messages || []).length} msgs · no HTTP` });
				if (mc.resp) {
					const { toolCalls, finish } = modelCallOutcome(mc);
					const calls = toolCalls.map((t) => t.name);
					msgs.push({ seq: mc.resp.seq, ts: mc.resp.ts, from: to, to: adv, ret: true, kind: 'model', path: p,
						label: `${mc.resp.error ? '⚠ error' : calls.length ? 'tool_use ' + calls.join(', ') : finish || 'response'} · ${fmtMs(mc.resp.durationMs)}` });
				}
				acts.push({ lane: to, from: mc.req.seq, to: mc.resp ? mc.resp.seq : openEnd, nest: 0 });
			}
			else if (it.kind === 'tool') {
				const t = it.ref; const to = toolLane(r, t);
				msgs.push({ seq: t.start.seq, ts: t.start.ts, from: adv, to, kind: 'tool', label: `${t.start.name}(${oneLine(t.start.arguments, 40)})`, path: [...path, 'tool:' + t.id] });
				for (const rc of t.remoteCalls || []) walkCall(rc.run, rc, to, [...path, 'tool:' + t.id], 0);
				// What the MCP server sends while the tool runs (logs, progress, sampling requests): notes on its lane.
				// MCP messages are posted in the background, so their seq can come late (even after the tool's end):
				// each note goes right after the last of the tool's start and its nested calls' steps recorded before it.
				const anchors = [t.start, ...items.filter((i) => i.kind === 'call' && toolOfCall(tools, i.ref, openEnd) === t)
					.flatMap((i) => [i.ref.req, i.ref.resp].filter(Boolean))].sort((a, b) => recordedTs(a) - recordedTs(b) || a.seq - b.seq);
				(t.mcp || []).filter((x) => x.direction === 'in' && x.kind !== 'response').forEach((m, i) => {
					const anchor = anchors.filter((a) => recordedTs(a) <= recordedTs(m)).pop() ?? t.start;
					msgs.push({ seq: anchor.seq + 0.001 * (i + 1), ts: m.ts, from: to, to, kind: 'tool', path: [...path, 'tool:' + t.id],
						note: oneLine(m.method === 'notifications/message' ? mcpSummary(m) : m.method.replace(/^notifications\//, ''), 36) });
				});
				if (t.end) {
					msgs.push({ seq: t.end.seq, ts: t.end.ts, from: to, to: adv, ret: true, kind: 'tool', path: [...path, 'tool:' + t.id],
						label: `${t.end.error ? '⚠ ' + oneLine(t.end.error, 40) : oneLine(t.end.result, 40)} · ${fmtMs(t.end.durationMs)}` });
					acts.push({ lane: to, from: t.start.seq, to: t.end.seq, nest: 0 });
				}
			}
			else if (it.kind === 'call') {
				const nested = it.ref;
				// A nested call made by a tool is a sub-agent called by that tool (its card is inside the
				// tool's, as for remote agents); otherwise an advisor made it (e.g. RAG query rewriting): a self-call.
				const byTool = toolOfCall(tools, nested, openEnd);
				// A call made while the MCP server's sampling request is open serves that request
				// (by time: MCP messages are posted in the background, so their seq may come later).
				const at = recordedTs(nested.req);
				const open = (byTool?.mcp || []).filter((m) => m.method === 'sampling/createMessage' && m.kind === 'request' && m.direction === 'in'
					&& recordedTs(m) <= at);
				const sampling = open.some((req) => !byTool.mcp.some((x) => x.kind === 'response' && x.direction === 'out' && x.id === req.id
					&& recordedTs(x) < at));
				if (byTool) walkCall(r, nested, toolLane(r, byTool), [...path, 'tool:' + byTool.id], 0, byTool.start.name, null, sampling ? mcpName(byTool.start.mcp ?? {}) : null);
				else walkCall(r, nested, adv, path, nest + 1, null, adv);
			}
		}
		// Each add or search: a request before the embedding calls it made, a return when it reported.
		for (const op of ops) {
			const { ev, seq: at } = op;
			const to = vectorLane(ev);
			const from = fromAt(at);
			const opp = opPath(op);
			const search = ev.query !== undefined;
			msgs.push({ seq: at, ts: ev.start?.ts ?? ev.ts, from, to, kind: 'vector', path: opp,
				label: search ? `🔎 ${oneLine(ev.query, 40)}` : ev.op === 'delete' || ev.type === 'vector-delete' ? `delete ${ev.filter ? 'by filter' : `${ev.count} ids`}` : `ingest ${ev.count} chunks` });
			if (!ev.pending) {
				msgs.push({ seq: ev.seq, ts: ev.ts, from: to, to: from, ret: true, kind: 'vector', path: opp,
					label: ev.error ? `⚠ ${oneLine(ev.error, 40)}`
						: search ? `${searchSummary(ev)} · ${fmtMs(ev.durationMs)}`
						: `${ev.count} stored · ${fmtMs(ev.durationMs)}` });
			}
			acts.push({ lane: to, from: at, to: ev.pending ? openEnd : ev.seq, nest: 0 });
		}
	}

	// nest: depth of self-nesting on the same lane (e.g. RAG rewrite inside the RAG advisor);
	// viaTool: an in-process sub-agent started by that tool gets its own lane.
	// samplingFor: the call answers a sampling request of that MCP connection's server.
	function walkCall(r, call, from, parentPath, nest = 0, viaTool = null, sameLane = null, samplingFor = null) {
		const adv = sameLane || (samplingFor != null && r === run
			? lane(r, 'adv', 'sampling:' + samplingFor, 'MCP sampling', `for ${samplingFor}`)
			: viaTool && r === run
			? lane(r, 'adv', 'sub:' + viaTool, 'Sub-agent', `via ${viaTool}`)
			: lane(r, 'adv', '', r === run ? 'Advisors' : r.app.split(' · ')[0], r === run ? 'ChatClient' : 'remote agent'));
		const path = [...parentPath, 'call:' + call.id];
		const user = [...(call.req.messages || [])].reverse().find((m) => m.role === 'user');
		const remote = r !== run;
		msgs.push({ seq: call.req.seq, ts: call.req.ts, from, to: adv, kind: remote ? 'remote' : 'call', path,
			label: `${remote ? 'A2A · ' : ''}#${call.num} “${oneLine(user?.text || '', 44)}”` });
		walkItems(r, call, call.items, adv, path, nest);
		const fresh = call.memory.after ? countWritten(call) : 0;
		// The note opens the "written by this call" folds of the memory step.
		const grew = (s) => s.kind !== 'files' && s.items.length > ((call.memory.before || []).find((b) => b.kind === s.kind && b.id === s.id)?.items.length || 0);
		const memPath = [...path, ...(call.memory.after || []).filter(grew).map((s) => memoryStoreKey(call.id, s) + ':new')];
		if (fresh) msgs.push({ seq: call.memory.afterSeq, ts: call.resp?.ts, from: adv, to: adv, note: `memory +${fresh}`, kind: 'call', path: memPath });
		if (call.resp) {
			const answer = call.resp.error ? '⚠ ' + call.resp.error : (call.resp.generations || []).filter((g) => !g.thinking).map((g) => g.text).join(' ');
			msgs.push({ seq: call.resp.seq, ts: call.resp.ts, from: adv, to: from, ret: true, kind: remote ? 'remote' : 'call', path, label: oneLine(answer, 48) });
			acts.push({ lane: adv, from: call.req.seq, to: call.resp.seq, nest });
		}
		else acts.push({ lane: adv, from: call.req.seq, to: Infinity, nest });
	}

	for (const it of run.items) {
		if (it.kind === 'call') walkCall(run, it.ref, app, []);
	}
	walkItems(run, null, run.items.filter((i) => i.kind !== 'call'), app, []);

	msgs.sort((a, b) => a.seq - b.seq);
	// Lanes: this run's group first, then remote groups; inside a group by kind.
	const groupOrder = [...groups.keys()];
	const laneList = [...lanes.values()].sort((a, b) => groupOrder.indexOf(a.group) - groupOrder.indexOf(b.group)
		|| LANE_RANK[a.kind] - LANE_RANK[b.kind]);
	return { lanes: laneList, groups, msgs, acts };
}

export function countWritten(call) {
	let n = 0;
	for (const store of call.memory.after || []) {
		const before = (call.memory.before || []).find((b) => b.kind === store.kind && b.id === store.id);
		if (store.kind === 'files') {
			const prev = new Map((before?.items || []).map((f) => [f.name, f.content]));
			n += store.items.filter((f) => prev.get(f.name) !== f.content).length;
		}
		else n += Math.max(0, store.items.length - (before?.items.length || 0));
	}
	return n;
}

export function renderSequence(run, scaled) {
	const { lanes, groups, msgs, acts } = buildSequence(run);
	if (!msgs.length) return '';
	const withTokens = lanes.some((l) => l.tokens);
	const LANE_W = 190; const LEFT = 20; const HEAD = withTokens ? 72 : 58; const ROW = 30;
	const x = new Map(lanes.map((l, i) => [l.key, LEFT + i * LANE_W + LANE_W / 2]));
	// Row positions: uniform, or with extra space proportional to elapsed time (capped).
	const ys = []; let y = HEAD + 24;
	msgs.forEach((m, i) => {
		if (i > 0) y += ROW + (scaled ? Math.min(180, Math.max(0, (m.ts - msgs[i - 1].ts) / 25)) : 0);
		ys.push(y);
	});
	const ySeq = (seq) => { // y of the first message at or after seq
		const i = msgs.findIndex((m) => m.seq >= seq);
		return i < 0 ? ys[ys.length - 1] + ROW / 2 : ys[i];
	};
	const height = ys[ys.length - 1] + ROW + 10;
	const width = LEFT * 2 + lanes.length * LANE_W;
	// The lane heads are drawn apart, in a header that stays in view while the diagram scrolls
	// (see .seq-head); both share one coordinate system, the diagram starting below the heads.
	let svg = ''; let head = '';
	// remote groups
	for (const [gid, label] of groups) {
		if (!label) continue;
		const gl = lanes.filter((l) => l.group === gid);
		const x0 = x.get(gl[0].key) - LANE_W / 2 + 6; const x1 = x.get(gl[gl.length - 1].key) + LANE_W / 2 - 6;
		const group = `<rect class="group" x="${x0}" y="4" width="${x1 - x0}" height="${height - 8}" rx="10"/>`;
		head += `${group}<text class="group-label" x="${x0 + 8}" y="16">${esc(label)} · linked by timing</text>`;
		svg += group;
	}
	// lane heads and lifelines
	for (const l of lanes) {
		const cx = x.get(l.key);
		svg += `<line class="lifeline" x1="${cx}" y1="${HEAD}" x2="${cx}" y2="${height}"/>`;
		head += `<g class="lane-head"><rect x="${cx - LANE_W / 2 + 12}" y="20" width="${LANE_W - 24}" height="36" rx="6"/>
			<text x="${cx}" y="${l.sub ? 35 : 42}" text-anchor="middle">${esc(oneLine(l.label, 24))}</text>
			${l.sub ? `<text class="sub" x="${cx}" y="49" text-anchor="middle">${esc(oneLine(l.sub, 28))}</text>` : ''}
			${l.tokens ? `<text class="sub" x="${cx}" y="68" text-anchor="middle">${fmtCompact(l.tokens.input)} in · ${fmtCompact(l.tokens.output)} out</text>` : ''}</g>`;
	}
	// activation bars
	for (const a of acts) {
		const y0 = ySeq(a.from) - 4; const y1 = a.to === Infinity ? height - 6 : ySeq(a.to) + 4;
		svg += `<rect class="act" x="${x.get(a.lane) - 5 + (a.nest || 0) * 6}" y="${y0}" width="10" height="${Math.max(8, y1 - y0)}" rx="2"/>`;
	}
	// messages
	msgs.forEach((m, i) => {
		const yy = ys[i]; const x1 = x.get(m.from); const x2 = x.get(m.to);
		const goto = esc(JSON.stringify(m.path));
		if (m.note) {
			svg += `<g class="msg note" data-goto="${goto}"><rect x="${x1 + 10}" y="${yy - 11}" width="${m.note.length * 7 + 14}" height="20" rx="4"/><text x="${x1 + 17}" y="${yy + 3}">${esc(m.note)}</text></g>`;
			return;
		}
		const cls = `msg ${m.ret ? 'ret' : ''}`;
		const k = `k-${m.kind}`;
		if (x1 === x2) { // self-call
			svg += `<g class="${cls}" data-goto="${goto}"><path class="${k}" d="M${x1 + 5},${yy - 6} h28 v12 h-28" marker-end="url(#ah-${m.kind})"/>
				<text x="${x1 + 40}" y="${yy + 4}">${esc(oneLine(m.label, 46))}</text></g>`;
			return;
		}
		const dir = x2 > x1 ? 1 : -1;
		const maxChars = Math.max(12, Math.floor((Math.abs(x2 - x1) - 16) / 6.4));
		svg += `<g class="${cls}" data-goto="${goto}"><line class="${k}" x1="${x1 + dir * 5}" y1="${yy}" x2="${x2 - dir * 7}" y2="${yy}" marker-end="url(#ah-${m.kind})"/>
			<text x="${(x1 + x2) / 2}" y="${yy - 5}" text-anchor="middle">${esc(oneLine(m.label, maxChars))}</text></g>`;
	});
	const colors = { call: '--text', model: '--assistant', tool: '--tool', jev: '--system', vector: '--user', remote: '--accent' };
	const defs = `<defs>${Object.entries(colors).map(([kd, c]) => `<marker id="ah-${kd}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" style="fill:var(${c});stroke:none"/></marker>`).join('')}</defs>`;
	return `<div class="seq-view" data-run="${esc(run.id)}"><div class="seq-head"><svg width="${width}" height="${HEAD}" viewBox="0 0 ${width} ${HEAD}">${head}</svg></div>
		<div class="seq-wrap"><svg width="${width}" height="${height - HEAD}" viewBox="0 ${HEAD} ${width} ${height - HEAD}">${defs}${svg}</svg></div></div>
		<div class="s1-crit" style="margin-top:.4rem">Click an arrow to open it in the Cards view. Dashed arrows are returns.</div>`;
}
