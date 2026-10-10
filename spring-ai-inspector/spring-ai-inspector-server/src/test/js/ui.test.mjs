// Node tests for the inspector UI modules (no browser, no build step):
//   node --test spring-ai-inspector/spring-ai-inspector-server/src/test/js
// Recorded runs from the demos drive the real model and render code, so a missing import
// or a broken view fails here instead of on stage.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { state } from '../../main/resources/static/js/state.js';
import { handle } from '../../main/resources/static/js/model.js';
import { ADAPTERS, adapterOf, anthropicBlock, blobMarker, mediaOf, normRequest, normResponse, usageOf } from '../../main/resources/static/js/providers.js';
import { diffTools, renderCall, renderItems, renderTool } from '../../main/resources/static/js/render/cards.js';
import { renderMcpPanel } from '../../main/resources/static/js/render/mcp.js';
import { runTotals } from '../../main/resources/static/js/render/page.js';
import { renderRag } from '../../main/resources/static/js/render/rag.js';
import { renderMemory } from '../../main/resources/static/js/render/memory.js';
import { renderAnswerMessage, renderSpringMessage } from '../../main/resources/static/js/render/messages.js';
import { indentJson, prettyMaybeJson } from '../../main/resources/static/js/util.js';
import { buildSequence, renderSequence } from '../../main/resources/static/js/render/sequence.js';
import { noulLeaning, renderBlock, renderMedia, renderNormRequest, renderRawBody, renderWire } from '../../main/resources/static/js/render/wire.js';
import { renderTokenPanel, tokensByModel } from '../../main/resources/static/js/render/tokens.js';

const fixture = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url)));

function load(name) {
	for (const event of fixture(name)) handle(event);
	return [...state.runs.values()];
}

const runOf = (runs, app) => runs.find((r) => r.app.startsWith(app));

/** The card with this key in rendered HTML, with everything nested inside it. */
function cardOf(html, key) {
	const at = html.indexOf(`data-key="${key}"`);
	if (at < 0) return null;
	const re = /<details\b|<\/details>/g;
	re.lastIndex = html.lastIndexOf('<details', at);
	let depth = 0;
	for (let m; (m = re.exec(html));) {
		depth += m[0] === '</details>' ? -1 : 1;
		if (!depth) return html.slice(html.lastIndexOf('<details', at), re.lastIndex);
	}
	return null;
}
const topCalls = (run) => run.items.filter((i) => i.kind === 'call').map((i) => i.ref);

beforeEach(() => {
	state.runs.clear();
	state.open.clear();
	state.selected = null;
});

// ---------------------------------------------------------------- recorded runs

test('chat memory: advisors inject history, memory shows what the call wrote', () => {
	const [run] = load('chat-memory');
	const [first, second] = topCalls(run);

	assert.equal(topCalls(run).length, 2);
	assert.match(renderCall(second, true), /\+2 added/);
	const memory = renderMemory(second);
	assert.match(memory, /\+2 new/);
	assert.match(memory, /2 already in memory before this call/);
	// Both the earlier and the newly written messages fold, closed by default.
	assert.match(memory, /<details class="fold" data-key="mem:[^"]+:new" ><summary>2 written by this call<\/summary>/);
	// Fold keys are per call, and the sequence's memory note opens this call's fold.
	const newKey = memory.match(/data-key="(mem:[^"]+:new)"/)[1];
	assert.ok(newKey.startsWith(`mem:${second.id}:`));
	assert.ok(!renderMemory(first).includes(newKey));
	const note = buildSequence(run).msgs.find((m) => m.note && m.path.includes('call:' + second.id));
	assert.equal(note.path[note.path.length - 1], newKey);
	assert.ok(renderCall(first, false).includes('ChatClient'));
});

test('modular RAG: searches, funnel, documents in the prompt, folded systemOne checks', () => {
	const [run] = load('modular-rag');
	const [call] = topCalls(run);

	assert.equal(call.searches.length, 4);
	const rag = renderRag(call);
	assert.match(rag, /RewriteQueryTransformer/);
	assert.match(rag, /in the prompt/);
	assert.match(rag, /jev rerank/);
	assert.match(rag, /dropped by joining \/ post-processing/);
	assert.match(renderItems(call.items, null), /\d+ systemOne checks/);

	const { lanes } = buildSequence(run);
	// systemOne lanes are named by model and who served it (no upstream reported: TypeSafe).
	const s1 = lanes.find((l) => l.kind === 'jev');
	assert.deepEqual([s1.label, s1.sub], ['jev-latest', 'typesafe · system-one']);
	assert.ok(lanes.some((l) => l.label === 'Vector store'));
	assert.match(renderSequence(run, false), /<svg/);
});

test('tools: tool runs sit between the round-trips that requested and consumed them', () => {
	const [run] = load('tools');
	const [call] = topCalls(run);

	assert.deepEqual(call.items.map((i) => i.kind).filter((k) => k !== 'model'), ['wire', 'tool', 'tool', 'wire']);
	// The advisor's model calls are recorded on the wire here, so they aren't shown again.
	assert.doesNotMatch(renderCall(call, true), /no HTTP/);
	assert.equal(runTotals(run).trips, 2);
	assert.ok(call.wires.every((w) => usageOf(w).input > 0));
	assert.match(renderCall(call, true), /getTemperature/);

	// Model round-trips get activation bars on the model lane, like tool runs and calls.
	const { lanes, acts } = buildSequence(run);
	const modelLanes = lanes.filter((l) => l.kind === 'model').map((l) => l.key);
	assert.equal(acts.filter((a) => modelLanes.includes(a.lane)).length, call.wires.length);
});

test('a round-trip whose response was never recorded ends its bar with the call', () => {
	let dropped = false;
	fixture('tools').filter((e) => e.type !== 'wire-response' || dropped || !(dropped = true)).forEach(handle);
	const run = [...state.runs.values()][0];
	const [call] = topCalls(run);
	const { lanes, acts } = buildSequence(run);
	const model = lanes.find((l) => l.kind === 'model').key;

	assert.equal(acts.find((a) => a.lane === model && a.from === call.wires[0].req.seq).to, call.resp.seq);
});

test('MCP tools show their connection and get a lane per MCP connection', () => {
	let first = true;
	const mcp = { connection: 'poet-server', server: 'mcp-server-voxxeddays-2026', serverVersion: '0.0.1', tool: 'getTemperature' };
	// The first call comes from an MCP server, the second is a local tool of the same name.
	fixture('tools').map((e) => e.type === 'tool-start' && first && !(first = false) ? { ...e, mcp } : e).forEach(handle);
	const run = [...state.runs.values()][0];
	const html = renderCall(topCalls(run)[0], true);

	assert.match(html, /<span class="pill mcp" title="MCP connection: poet-server\nserver: mcp-server-voxxeddays-2026 0\.0\.1\ntool: getTemperature">MCP · poet-server<\/span>/);
	const tools = buildSequence(run).lanes.filter((l) => l.kind === 'tool').map((l) => [l.label, l.sub]);
	assert.deepEqual(tools, [['poet-server', 'MCP · mcp-server-voxxeddays-2026'], ['Tools', '']]);
});

// A run where an MCP tool's server logs, asks the client to sample (answered by a nested ChatClient
// call), logs again and returns; MCP messages are posted in the background, so some arrive late.
function mcpToolRunEvents() {
	const conn = 'poet-server';
	const events = fixture('tools');
	const runId = events[0].runId; const callId = events[1].callId; const toolId = events[6].toolId;
	const base = events[6].ts - 700; // timestamps on the fixture's timeline: seq 7 is the tool's start
	const msg = (seq, direction, kind, method, id, body, extra = {}) => ({ type: 'mcp-message', runId, seq, ts: base + Math.round(seq * 100),
		connection: conn, direction, kind, method, id, payload: { jsonrpc: '2.0', id, ...body }, ...extra });
	const at = (seq, e) => ({ ...e, runId, seq, ts: base + Math.round(seq * 100) });
	const handshake = [
		// Older recordings carry the payload as a JSON string.
		{ ...msg(2.1, 'out', 'request', 'initialize', 0, {}), payload: JSON.stringify({ method: 'initialize', params: { protocolVersion: '2025-06-18' } }) },
		msg(2.2, 'in', 'response', 'initialize', 0, { result: { protocolVersion: '2025-06-18', serverInfo: { name: 'mcp-server-voxxeddays-2026', version: '0.0.1' } } }),
		msg(2.3, 'out', 'request', 'tools/list', 1, {}),
		msg(2.4, 'in', 'response', 'tools/list', 1, { result: { tools: [{ name: 'hello' }, { name: 'getTemperature' }] } }),
	].map((e) => ({ ...e, ts: events[1].ts }));
	const duringTool = [
		msg(7.1, 'out', 'request', 'tools/call', 2, { params: { name: 'getTemperature', arguments: {} } }, { toolId }),
		at(7.4, { type: 'client-request', callId: 'sampling-call', parentId: callId, parentInferred: true, messages: [{ role: 'user', text: 'Write a poem' }] }),
		at(7.5, { type: 'client-response', callId: 'sampling-call', generations: [{ role: 'assistant', text: 'A poem' }] }),
		msg(7.2, 'in', 'notification', 'notifications/message', undefined, { params: { level: 'info', data: 'Start sampling' } }, { toolId }),
		msg(7.3, 'in', 'request', 'sampling/createMessage', 0, { params: { messages: [{ role: 'user', content: { type: 'text', text: 'Write a poem' } }] } }, { toolId }),
		msg(7.6, 'out', 'response', 'sampling/createMessage', 0, { result: { content: { type: 'text', text: 'A poem' } } }, { toolId }),
	];
	// Arriving after the tool's end: a log recorded after the sampling call, and the tools/call result.
	const late = [msg(8.4, 'in', 'notification', 'notifications/message', undefined, { params: { level: 'info', data: 'Done' } }, { toolId, ts: base + 760 }),
		msg(8.5, 'in', 'response', 'tools/call', 2, { result: { content: [{ type: 'text', text: '12°C' }] } }, { toolId, ts: base + 790 })];
	let tagged = false;
	const tag = (e) => (e.type === 'tool-start' && !tagged && (tagged = true) ? { ...e, mcp: { connection: conn, server: 'mcp-server-voxxeddays-2026', tool: 'getTemperature' } } : e);
	return { conn, toolId, events: [...events.slice(0, 2), ...handshake, ...events.slice(2, 7).map(tag), ...duringTool, events[7], ...late, ...events.slice(8)] };
}

test('MCP messages: the handshake belongs to the connection, the rest to the MCP tool run the starter attributed', () => {
	const { conn, toolId, events } = mcpToolRunEvents();
	events.forEach(handle);
	const run = [...state.runs.values()][0];
	const tool = run.tools.get(toolId);

	assert.deepEqual(tool.mcp.map((m) => `${m.kind} ${m.method}`), ['request tools/call', 'notification notifications/message',
		'request sampling/createMessage', 'response sampling/createMessage', 'notification notifications/message', 'response tools/call']);
	assert.deepEqual(run.mcp.get(conn).map((m) => m.method), ['initialize', 'initialize', 'tools/list', 'tools/list']);

	const panel = renderMcpPanel(run);
	assert.match(panel, /mcp-server-voxxeddays-2026 0\.0\.1/);
	assert.match(panel, /protocol 2025-06-18/);
	assert.match(panel, /2 tools/);
	const card = renderTool(tool);
	assert.match(card, /6 MCP msgs/);
	assert.match(card, /info: Start sampling/);
	assert.match(card, /<b>tools\/call ✓<\/b><span class="muted">#2<\/span>\s*<span class="mcp-sum">12°C<\/span>/);

	// Notes are placed by when they were recorded: before and after the sampling call, inside the tool run.
	const { lanes, msgs } = buildSequence(run);
	const sampling = run.calls.get('sampling-call');
	const before = msgs.find((m) => m.note === 'info: Start sampling'); const after = msgs.find((m) => m.note === 'info: Done');
	assert.ok(before.seq > tool.start.seq && before.seq < sampling.req.seq, 'the first log comes before the sampling call');
	assert.ok(after.seq > sampling.resp.seq && after.seq < tool.end.seq, 'the late log comes after it, still inside the tool run');
	assert.ok(lanes.some((l) => l.label === 'MCP sampling' && l.sub === 'for poet-server'));
	assert.ok(!lanes.some((l) => l.label === 'Sub-agent'));
});

test('a replayed MCP run keeps its sampling lane and its MCP response times', () => {
	// Replay feeds every event at replay time, in recorded order, keeping the recorded time apart.
	mcpToolRunEvents().events.forEach((e, i) => handle({ ...e, ts: 9_000_000_000_000 + i * 1000, recordedTs: e.ts }));
	const run = [...state.runs.values()][0];

	assert.ok(buildSequence(run).lanes.some((l) => l.label === 'MCP sampling'));
	assert.match(renderTool([...run.tools.values()][0]), /sampling\/createMessage ✓<\/b><span class="muted">#0<\/span>\s*<span class="mcp-sum">A poem<\/span>\s*<span class="right-meta">30 ms<\/span>/);
});

test('an MCP tool without a connection name is still drawn on an MCP lane, named like its badge', () => {
	let first = true;
	fixture('tools').map((e) => e.type === 'tool-start' && first && !(first = false) ? { ...e, mcp: { server: 'weather-mcp' } } : e).forEach(handle);
	const run = [...state.runs.values()][0];

	assert.match(renderCall(topCalls(run)[0], true), />MCP · weather-mcp<\/span>/);
	assert.ok(buildSequence(run).lanes.some((l) => l.kind === 'tool' && l.label === 'weather-mcp' && l.sub === 'MCP · weather-mcp'));
});

test('a model making no HTTP calls (e.g. in the JVM) is shown from the advisor: cards, tokens, sequence', () => {
	// The same run without its HTTP round-trips, as a jinfer model would leave it.
	fixture('tools').filter((e) => !e.type.startsWith('wire-'))
		.map((e) => (e.type === 'model-request' ? { ...e, options: { ...e.options, type: 'JinferChatOptions', model: 'LFM2.5-8B' } } : e))
		.forEach(handle);
	const run = [...state.runs.values()][0];
	const [call] = topCalls(run);

	const html = renderCall(call, true);
	assert.equal((html.match(/ChatModel call · no HTTP/g) || []).length, 2);
	assert.match(html, /<span class="pill" title="Seen by the advisor[^"]*">jinfer<\/span>/);
	assert.match(html, /<span class="pill">claude-sonnet-4-6<\/span>/); // the model that answered
	assert.match(html, /⚙ getTemperature/);
	assert.match(html, /2 model calls without HTTP · 2 tool runs/);
	assert.match(html, /1,565 in · 400 out · 2 round-trips/); // the call's own numbers include them too
	assert.deepEqual(runTotals(run), { input: 1565, output: 400, trips: 2 });
	// An unrelated HTTP round-trip during a model call (e.g. a systemOne check) doesn't hide it: the proxy
	// stamps each round-trip with the model call it served, and this one served none.
	const mc = [...run.modelCalls.values()][0];
	handle({ type: 'wire-request', runId: run.id, seq: mc.req.seq + 0.1, ts: mc.req.ts + 1, wireId: 'jev', clientCallId: call.id,
		provider: 'typesafe', method: 'POST', path: '/v1/systemone', url: 'u', headers: {}, body: '{}' });
	assert.equal(runTotals(run).trips, 3);
	assert.equal((renderCall(call, true).match(/ChatModel call · no HTTP/g) || []).length, 2);
	// Every model with round-trips has a row, also the systemOne one reporting no usage (yet).
	assert.deepEqual(tokensByModel(run).rows.map((r) => [r.provider, r.model, r.input, r.calls, r.reported]),
		[['jinfer', 'claude-sonnet-4-6', 1565, 2, 2], ['typesafe', 'typesafe', 0, 1, 0]]);
	const { lanes, msgs } = buildSequence(run);
	assert.ok(lanes.some((l) => l.kind === 'model' && l.label === 'claude-sonnet-4-6' && l.sub === 'jinfer'));
	assert.ok(msgs.some((m) => m.label === '#1 · 1 msgs · no HTTP'));
	assert.ok(msgs.some((m) => /^tool_use getTemperature, getTemperature · /.test(m.label)));
});

test("a routed provider's model calls are never shown as without HTTP, even when one went unstamped", () => {
	// Overlapping model calls can leave one without the proxy's stamp; its provider is routed, so it's on the wire.
	fixture('tools').filter((e) => !e.type.startsWith('wire-'))
		.map((e) => (e.type === 'run-start' ? { ...e, routed: ['anthropic'] } : e)).forEach(handle);
	const run = [...state.runs.values()][0];

	assert.doesNotMatch(renderCall(topCalls(run)[0], true), /no HTTP/);
	assert.equal(runTotals(run).trips, 0);
});

test('a provider routed by a name of its own is recognized by its API path', () => {
	assert.equal(adapterOf(wire('groq', '/openai/v1/chat/completions', { messages: [] }, '{}')), ADAPTERS.openai);
	assert.equal(adapterOf(wire('groq', '/openai/v1/embeddings', { input: 'x' }, '{}')).kind, 'embedding');
	assert.equal(adapterOf(wire('anthropic', '/v1/models', {}, '{}')), null); // a known provider's other endpoints stay raw
});

test('vector store adds and searches enclose the embedding calls they make, from whoever made them', () => {
	// As 10-1 runs it: the first call indexes its tools (one embedding call per tool), then a tool search embeds its query.
	let seq = 0; const ev = (e) => handle({ runId: 'ts', seq: ++seq, ...e });
	const embed = (id, ts, input) => {
		ev({ type: 'wire-request', ts, wireId: id, clientCallId: 'c1', provider: 'openai', method: 'POST', path: '/v1/embeddings', url: 'u',
			headers: {}, body: JSON.stringify({ model: 'text-embedding-ada-002', input: [input] }) });
		ev({ type: 'wire-response', ts: ts + 5, wireId: id, status: 200, durationMs: 5, headers: {}, body: JSON.stringify({ data: [{ embedding: [0, 1] }] }) });
	};
	ev({ type: 'run-start', ts: 1000, app: '10-1 · App' });
	ev({ type: 'client-request', ts: 1000, callId: 'c1', messages: [{ role: 'user', text: 'Plan my day' }] });
	['probe', 'weather', 'clothing', 'currentTime'].forEach((t, i) => embed('e' + i, 1010 + 10 * i, t));
	ev({ type: 'vector-add', ts: 1050, clientCallId: 'c1', store: 'SimpleVectorStore', count: 3, durationMs: 45 });
	ev({ type: 'tool-start', ts: 1100, toolId: 't1', clientCallId: 'c1', name: 'toolSearchTool', arguments: '{}' });
	embed('q', 1110, 'tool to get the time');
	ev({ type: 'vector-search', ts: 1130, clientCallId: 'c1', store: 'SimpleVectorStore', query: 'tool to get the time', results: [{ score: 0.8 }], durationMs: 30 });
	ev({ type: 'tool-end', ts: 1140, toolId: 't1', result: '["currentTime"]', durationMs: 40 });
	ev({ type: 'client-response', ts: 1200, callId: 'c1', generations: [{ role: 'assistant', text: 'ok' }] });
	const run = state.runs.get('ts');
	const call = topCalls(run)[0];

	assert.deepEqual(call.items.filter((i) => i.kind === 'ingest').length, 1, 'the add belongs to the call that made it');
	// In the cards: the add (with its embedding calls) before the tool, the search inside the tool's card.
	const cards = renderItems(call.items, null, { searches: call.searches, callId: call.id });
	const [add, tool, search] = ['add 3 chunks', 'data-key="tool:t1"', '“tool to get the time”'].map((x) => cards.indexOf(x));
	assert.ok(add >= 0 && add < tool && tool < search, 'add, then the tool with its search inside');
	assertArrowsOpenTheirCards(run);
	const toolCard = renderTool(call.items.find((i) => i.kind === 'tool').ref, 'NESTED', '<span class="pill">🔎 searching…</span>');
	assert.match(toolCard, /<div class="pane on" style="padding-top:0">NESTED<\/div>/);
	assert.match(toolCard, /<summary>[\s\S]*🔎 searching…[\s\S]*<\/summary>/, 'what runs inside, on the collapsed card');
	const { lanes, msgs } = buildSequence(run);
	const name = (key) => lanes.find((l) => l.key === key).label;
	const arrows = msgs.map((m) => `${name(m.from)} → ${name(m.to)}: ${m.label}`);
	assert.deepEqual(arrows.slice(1, 6), [
		'Advisors → Vector store: ingest 3 chunks',
		'Vector store → text-embedding-ada-002: 4 embedding calls · 4 inputs',
		'text-embedding-ada-002 → Vector store: 4 vectors · 35 ms',
		'Vector store → Advisors: 3 stored · 45 ms',
		'Advisors → Tools: toolSearchTool({})']);
	assert.deepEqual(arrows.slice(6, 10), [
		'Tools → Vector store: 🔎 tool to get the time',
		'Vector store → text-embedding-ada-002: #5 · embed 1 input',
		'text-embedding-ada-002 → Vector store: 1 vector · 5 ms',
		'Vector store → Tools: 1 hit · best 0.800 · 30 ms']);
});

test('a vector store add reported when it starts is drawn live: the diagram only grows', () => {
	let seq = 0; const ev = (e) => handle({ runId: 'live', seq: ++seq, ...e });
	const embed = (id, ts) => {
		ev({ type: 'wire-request', ts, wireId: id, provider: 'openai', method: 'POST', path: '/v1/embeddings', url: 'u',
			headers: {}, body: JSON.stringify({ model: 'text-embedding-ada-002', input: ['chunk'] }) });
		ev({ type: 'wire-response', ts: ts + 5, wireId: id, status: 200, durationMs: 5, headers: {}, body: JSON.stringify({ data: [{ embedding: [0] }] }) });
	};
	const arrows = () => {
		const { lanes, msgs } = buildSequence(state.runs.get('live'));
		const name = (key) => lanes.find((l) => l.key === key).label;
		return msgs.map((m) => `${name(m.from)} → ${name(m.to)}: ${m.label}`);
	};
	ev({ type: 'run-start', ts: 1000, app: '05-rag · DemoApplication' });
	ev({ type: 'vector-start', ts: 1000, opId: 'a1', op: 'add', store: 'SimpleVectorStore', count: 52 });
	[0, 1, 2, 3].forEach((i) => embed('e' + i, 1010 + 10 * i));

	const live = arrows();
	assert.deepEqual(live, ['DemoApplication → Vector store: ingest 52 chunks',
		'Vector store → text-embedding-ada-002: 4 embedding calls · 4 inputs', 'text-embedding-ada-002 → Vector store: 4 vectors · 35 ms']);
	// In the cards: one add card, still running, with the embedding calls inside it.
	const liveCards = renderItems(state.runs.get('live').items, null);
	assert.match(liveCards, /^<details class="wire vector-op" data-key="vop:a1" >[\s\S]*add 52 chunks[\s\S]*ingesting…[\s\S]*<b>4 embedding calls<\/b>/);

	ev({ type: 'vector-add', ts: 1060, opId: 'a1', store: 'SimpleVectorStore', count: 52, durationMs: 60 });
	assert.deepEqual(arrows(), [...live, 'Vector store → DemoApplication: 52 stored · 60 ms']);
	assert.match(renderItems(state.runs.get('live').items, null), /add 52 chunks<\/span>\s*<span class="arrow">→<\/span><span class="pill">52 stored<\/span><span class="right-meta">60 ms<\/span>/);
});

test('an embedding model running in the JVM is shown from its embedding calls, and not twice for a remote one', () => {
	let seq = 0; const ev = (e) => handle({ runId: 'jinfer', seq: ++seq, ...e });
	const embed = (id, ts, n) => ev({ type: 'embedding-call', ts, embeddingId: id, clientCallId: 'c1', provider: 'jinfer', model: 'Qwen3-Embedding-0.6B',
		inputs: n, sample: ['toolDescription: weather'], vectors: n, dimensions: 1024, durationMs: 50 });
	ev({ type: 'run-start', ts: 1000, app: 'jinfer-tool-search-demo · App', routed: ['groq', 'openai'] });
	ev({ type: 'client-request', ts: 1000, callId: 'c1', messages: [{ role: 'user', text: 'Plan my day' }] });
	ev({ type: 'vector-start', ts: 1001, opId: 'a1', op: 'add', clientCallId: 'c1', store: 'SimpleVectorStore', count: 26 });
	[0, 1, 2].forEach((i) => embed('e' + i, 1100 + 100 * i, 1));
	ev({ type: 'vector-add', ts: 1400, opId: 'a1', clientCallId: 'c1', store: 'SimpleVectorStore', count: 26, durationMs: 399 });
	const run = state.runs.get('jinfer');

	const { lanes, msgs } = buildSequence(run);
	const name = (key) => lanes.find((l) => l.key === key).label;
	assert.deepEqual(msgs.slice(1).map((m) => `${name(m.from)} → ${name(m.to)}: ${m.label}`), [
		'Advisors → Vector store: ingest 26 chunks',
		'Vector store → Qwen3-Embedding-0.6B: 3 embedding calls · 3 inputs',
		'Qwen3-Embedding-0.6B → Vector store: 3 vectors · 250 ms',
		'Vector store → Advisors: 26 stored · 399 ms']);
	assert.ok(lanes.some((l) => l.label === 'Qwen3-Embedding-0.6B' && l.sub === 'jinfer'));
	const html = renderCall(topCalls(run)[0], true);
	assert.match(html, /EmbeddingModel call · no HTTP/);
	assert.match(html, /3 embedding calls without HTTP/);
	assert.equal(runTotals(run).trips, 3);

	// An in-JVM embedding overlapping another provider's HTTP one is still shown.
	ev({ type: 'wire-request', ts: 1500, wireId: 'other', clientCallId: 'c1', provider: 'openai', method: 'POST', path: '/v1/embeddings', url: 'u',
		headers: {}, body: JSON.stringify({ model: 'text-embedding-3-small', input: ['y'] }) });
	ev({ type: 'embedding-call', ts: 1520, embeddingId: 'tf', clientCallId: 'c1', provider: 'transformers', inputs: 1, vectors: 1, durationMs: 40 });
	assert.equal(runTotals(run).trips, 5);
	// The embedding model reports no token usage: it still has its row, with its trips and no numbers.
	const embedRow = tokensByModel(run).rows.find((r) => r.model === 'Qwen3-Embedding-0.6B');
	assert.deepEqual([embedRow.calls, embedRow.reported], [3, 0]);
	assert.match(renderTokenPanel(run), /Qwen3-Embedding-0\.6B[\s\S]*?<span class="tok-num">–<\/span><span class="tok-num">–<\/span>\s*<span class="tok-extra">no token usage reported<\/span><span class="tok-num">3<\/span>/);
	// A routed provider's embedding calls are on the wire (here under the route's name, groq): not added again.
	ev({ type: 'embedding-call', ts: 1600, embeddingId: 'routed', clientCallId: 'c1', provider: 'openai', inputs: 1, vectors: 1, durationMs: 40 });
	assert.equal(runTotals(run).trips, 5);
	// A failed in-process embedding says so in the sequence.
	ev({ type: 'embedding-call', ts: 1700, embeddingId: 'fail', clientCallId: 'c1', provider: 'jinfer', inputs: 1, durationMs: 5,
		error: 'IllegalStateException: model not loaded' });
	assert.ok(buildSequence(run).msgs.some((m) => m.label === '⚠ IllegalStateException: model not loaded'));
	assert.equal(runTotals(run).trips, 6);
	// A remote embedding model's call is on the wire already: its embedding-call event adds nothing.
	ev({ type: 'wire-request', ts: 2000, wireId: 'w1', clientCallId: 'c1', provider: 'openai', method: 'POST', path: '/v1/embeddings', url: 'u',
		headers: {}, body: JSON.stringify({ model: 'text-embedding-3-small', input: ['x'] }) });
	ev({ type: 'wire-response', ts: 2040, wireId: 'w1', status: 200, durationMs: 40, headers: {}, body: JSON.stringify({ data: [{ embedding: [0] }] }) });
	ev({ type: 'embedding-call', ts: 2045, embeddingId: 'dup', clientCallId: 'c1', provider: 'openai', inputs: 1, vectors: 1, durationMs: 50 });
	assert.equal(runTotals(run).trips, 7);
});

test("On the wire shows vector store searches where they ran, with their embedding round-trips inside", () => {
	// As 05-rag runs it: the question is embedded for the search, then the model answers.
	let seq = 0; const ev = (e) => handle({ runId: 'rag', seq: ++seq, ...e });
	ev({ type: 'run-start', ts: 1000, app: '05-rag · DemoApplication' });
	ev({ type: 'client-request', ts: 1000, callId: 'c1', messages: [{ role: 'user', text: 'Was Florida hit by Milton?' }] });
	ev({ type: 'vector-start', ts: 1001, opId: 's1', op: 'search', clientCallId: 'c1', store: 'SimpleVectorStore', query: 'Was Florida hit by Milton?' });
	ev({ type: 'wire-request', ts: 1010, wireId: 'q', clientCallId: 'c1', provider: 'openai', method: 'POST', path: '/v1/embeddings', url: 'u',
		headers: {}, body: JSON.stringify({ model: 'text-embedding-ada-002', input: ['Was Florida hit by Milton?'] }) });
	ev({ type: 'wire-response', ts: 1180, wireId: 'q', status: 200, durationMs: 170, headers: {}, body: JSON.stringify({ data: [{ embedding: [0] }] }) });
	ev({ type: 'vector-search', ts: 1182, opId: 's1', searchId: 'x', clientCallId: 'c1', store: 'SimpleVectorStore', query: 'Was Florida hit by Milton?',
		results: [{ score: 0.869, text: 'Six million Floridians…', metadata: { page_number: 5 } }, { score: 0.861, text: 'drop of 84 mb', metadata: { page_number: 3 } }], durationMs: 181 });
	ev({ type: 'wire-request', ts: 1200, wireId: 'chat', clientCallId: 'c1', provider: 'openai', method: 'POST', path: '/v1/chat/completions', url: 'u',
		headers: {}, body: JSON.stringify({ model: 'gpt-5-mini', messages: [{ role: 'user', content: 'q' }] }) });
	ev({ type: 'client-response', ts: 4200, callId: 'c1', generations: [{ role: 'assistant', text: 'Yes.' }] });
	const call = topCalls(state.runs.get('rag'))[0];

	const html = renderCall(call, true);
	const wire = html.slice(html.indexOf('On the wire'));
	assert.match(wire, /1 vector search · 2 HTTP round-trips/);
	// The search first, holding its embedding round-trip, then the chat round-trip: the order they ran in.
	assert.match(wire, /data-key="vop:s1"[\s\S]*“Was Florida hit by Milton\?”[\s\S]*2 hits[\s\S]*best 0\.869[\s\S]*181 ms[\s\S]*data-key="wire:q"[\s\S]*<\/details><div class="hits-line">[\s\S]*<\/details><details class="wire" data-key="wire:chat"/);
	// Each hit opens the search in the Retrieval step.
	assert.match(wire, /<span class="chip" data-goto="\[&quot;call:c1&quot;,&quot;search:s1&quot;\]" title="Six million Floridians…">0\.869 · page 5<\/span>/);
	assert.equal((wire.match(/data-key="wire:q"/g) || []).length, 1, 'shown once, inside the search');
});

// Every arrow of the sequence opens its card: each key of its path is a card of the Cards view.
function assertArrowsOpenTheirCards(run) {
	const cards = topCalls(run).map((c) => renderCall(c, true)).join('');
	for (const m of buildSequence(run).msgs) {
		for (const key of m.path) assert.ok(cards.includes(`data-key="${key}"`), `${m.label}: no card ${key}`);
	}
}

test("a tool's own round-trips are shown inside its card, e.g. the systemOne check of a Jev tool search", () => {
	// As 10-1 runs with tool-index-type=jev: the tool search asks Jev which tool fits.
	let seq = 0; const ev = (e) => handle({ runId: 'jev', seq: ++seq, ...e });
	const wire = (id, ts, provider, path, body, resp) => {
		ev({ type: 'wire-request', ts, wireId: id, clientCallId: 'c1', provider, method: 'POST', path, url: 'u', headers: {}, body: JSON.stringify(body) });
		ev({ type: 'wire-response', ts: ts + 10, wireId: id, status: 200, durationMs: 10, headers: {}, body: JSON.stringify(resp) });
	};
	ev({ type: 'run-start', ts: 1000, app: '10-1 · App' });
	ev({ type: 'client-request', ts: 1000, callId: 'c1', messages: [{ role: 'user', text: 'Plan my day' }] });
	wire('chat1', 1010, 'openai', '/v1/chat/completions', { model: 'gpt-5-mini', messages: [] }, { choices: [{ message: { content: '' }, finish_reason: 'tool_calls' }] });
	ev({ type: 'tool-start', ts: 1100, toolId: 't1', clientCallId: 'c1', name: 'toolSearchTool', arguments: '{}' });
	wire('jev', 1110, 'typesafe', '/v1/systemone', { model: 'jev-latest', state: {}, questions: { best_tool: { type: 'choice' } } },
		{ answers: { best_tool: { type: 'choice', choice: 'clothing' } } });
	ev({ type: 'tool-end', ts: 1200, toolId: 't1', result: '["clothing"]', durationMs: 100 });
	wire('chat2', 1300, 'openai', '/v1/chat/completions', { model: 'gpt-5-mini', messages: [] }, { choices: [{ message: { content: 'ok' }, finish_reason: 'stop' }] });
	const call = topCalls(state.runs.get('jev'))[0];

	const cards = renderItems(call.items, null, { searches: call.searches, callId: call.id });
	const at = (x) => cards.indexOf(x);
	assert.ok(at('data-key="wire:chat1"') < at('data-key="tool:t1"') && at('data-key="tool:t1"') < at('data-key="wire:jev"')
		&& at('data-key="wire:jev"') < at('data-key="wire:chat2"'), 'chat, then the tool with its check inside, then chat');
	const tool = cards.slice(at('data-key="tool:t1"'), at('data-key="wire:chat2"'));
	assert.match(tool, /<div class="pane on" style="padding-top:0"><details class="wire" data-key="wire:jev"/);
	assert.equal((cards.match(/data-key="wire:jev"/g) || []).length, 1, 'shown once, inside the tool');
	// Clicking the systemOne arrow opens the tool's card, where the check is.
	const arrow = buildSequence(state.runs.get('jev')).msgs.find((m) => m.label === 'systemOne · 1 questions');
	assert.deepEqual(arrow.path, ['call:c1', 'tool:t1', 'wire:jev']);
	assertArrowsOpenTheirCards(state.runs.get('jev'));
});

test('a tool whose end was never recorded holds only what ran before its call ended', () => {
	let seq = 0; const ev = (e) => handle({ runId: 'lost', seq: ++seq, ...e });
	ev({ type: 'run-start', ts: 1000, app: 'App' });
	ev({ type: 'client-request', ts: 1000, callId: 'c1', messages: [] });
	ev({ type: 'tool-start', ts: 1100, toolId: 't1', clientCallId: 'c1', name: 'lostTool', arguments: '{}' }); // its end is lost
	ev({ type: 'wire-request', ts: 1200, wireId: 'inside', clientCallId: 'c1', provider: 'openai', method: 'POST', path: '/v1/chat/completions', url: 'u', headers: {}, body: '{}' });
	ev({ type: 'client-response', ts: 1300, callId: 'c1', generations: [] });
	ev({ type: 'client-request', ts: 1400, callId: 'c2', messages: [] });
	const call = topCalls(state.runs.get('lost'))[0];
	// A later round-trip of the same call, recorded after the call ended (e.g. delivered late), stays outside.
	handle({ type: 'wire-request', runId: 'lost', seq: ++seq, ts: 1500, wireId: 'after', clientCallId: 'c1', provider: 'openai', method: 'POST',
		path: '/v1/chat/completions', url: 'u', headers: {}, body: '{}' });

	const cards = renderItems(call.items, null, { searches: call.searches, callId: call.id, callEnd: call.resp.seq });
	const tool = cards.indexOf('data-key="tool:t1"');
	assert.ok(tool < cards.indexOf('data-key="wire:inside"'), 'inside the tool');
	assert.match(cards, /<\/details><details class="wire" data-key="wire:after"/, 'after the tool, at top level');
});

test('a vector store card shows a failure in full, and no "nothing recorded" note while running or failed', () => {
	let seq = 0; const ev = (e) => handle({ runId: 'ops', seq: ++seq, ...e });
	ev({ type: 'run-start', ts: 1000, app: 'App' });
	ev({ type: 'vector-start', ts: 1000, opId: 'a1', op: 'add', store: 'SimpleVectorStore', count: 2 });
	const running = renderItems(state.runs.get('ops').items, null);
	assert.match(running, /ingesting…/);
	assert.doesNotMatch(running, /No embedding round-trips recorded/);
	const error = 'HttpClientErrorException: 400 Bad Request: ' + 'x'.repeat(200);
	ev({ type: 'vector-add', ts: 1050, opId: 'a1', store: 'SimpleVectorStore', count: 2, durationMs: 50, error });
	const failed = renderItems(state.runs.get('ops').items, null);
	assert.ok(failed.includes(`<div class="notice err">${error}</div>`), 'the whole error');
	assert.doesNotMatch(failed, /No embedding round-trips recorded/);
	// A search whose hits carry no scores claims no best score.
	ev({ type: 'vector-start', ts: 1100, opId: 's1', op: 'search', store: 'SimpleVectorStore', query: 'q' });
	ev({ type: 'vector-search', ts: 1110, opId: 's1', store: 'SimpleVectorStore', query: 'q', results: [{ text: 'a' }], durationMs: 10 });
	const run = state.runs.get('ops');
	const search = renderItems(run.items, null, { searches: run.searches });
	assert.match(search, /1 hit<\/span>/);
	assert.doesNotMatch(search, /best/);
	// Searches outside any ChatClient call are drawn in the sequence too, also without a best score.
	assert.ok(buildSequence(run).msgs.some((m) => m.label === '🔎 q'));
	assert.ok(buildSequence(run).msgs.some((m) => m.label === '1 hit · 10 ms'));
	// Whether embeddings were recorded is said whatever the search returned: here it has a hit but none.
	assert.match(search, /data-key="vop:s1"[\s\S]*No embedding round-trips recorded/);
});

test('a collapsed tool card says what is still running inside it', () => {
	let seq = 0; const ev = (e) => handle({ runId: 'busy', seq: ++seq, ...e });
	ev({ type: 'run-start', ts: 1000, app: 'App' });
	ev({ type: 'client-request', ts: 1000, callId: 'c1', messages: [] });
	ev({ type: 'tool-start', ts: 1100, toolId: 't1', clientCallId: 'c1', name: 'toolSearchTool', arguments: '{}' });
	ev({ type: 'vector-start', ts: 1110, opId: 's1', op: 'search', clientCallId: 'c1', store: 'SimpleVectorStore', query: 'q' });
	const call = topCalls(state.runs.get('busy'))[0];
	const summary = (html) => html.slice(html.indexOf('data-key="tool:t1"'), html.indexOf('</summary>', html.indexOf('data-key="tool:t1"')));

	assert.match(summary(renderItems(call.items, null, { searches: call.searches, callId: call.id })), /🔎 searching…/);
	ev({ type: 'vector-search', ts: 1120, opId: 's1', clientCallId: 'c1', store: 'SimpleVectorStore', query: 'q', results: [], durationMs: 10 });
	assert.doesNotMatch(summary(renderItems(call.items, null, { searches: call.searches, callId: call.id })), /searching…/);
});

test('tools that pass through the advisors stay visible after them', () => {
	const [run] = load('tools');
	const html = renderCall(topCalls(run)[0], true);
	const afterAdvisors = html.slice(html.indexOf('After the advisors'), html.indexOf('On the wire'));

	assert.match(afterAdvisors, /unchanged/);
	assert.match(afterAdvisors, /⚙ getTemperature/);
});

test('tool diff marks tools added and removed by advisors', () => {
	const diff = diffTools([{ name: 'search' }, { name: 'weather' }], [{ name: 'weather' }, { name: 'toolSearch' }]);

	assert.equal(diff.added, 1);
	assert.equal(diff.removed, 1);
	assert.match(diff.html, /chip added[^>]*>\+ ⚙ toolSearch/);
	assert.match(diff.html, /chip removed[^>]*>− ⚙ search/);
	assert.match(diff.html, /chip "[^>]*>⚙ weather/);
});

test('A2A: the remote agent call is linked under the caller\'s Task tool', () => {
	const runs = load('a2a');
	const caller = runOf(runs, 'subagent-a2a-demo');
	const [task] = [...caller.tools.values()].filter((t) => t.start.name === 'Task');

	assert.equal(task.remoteCalls.length, 1);
	assert.ok(task.remoteCalls[0].run.app.startsWith('airbnb-agent'));
	const { groups, lanes } = buildSequence(caller);
	assert.equal([...groups.values()].filter(Boolean).length, 1, 'one remote lane group');
	assert.ok(lanes.some((l) => l.label === 'airbnb-agent'));
	const card = cardOf(renderCall(topCalls(caller)[0], true), 'tool:' + task.id);
	assert.match(card, /↘ 1 remote agent</);
	assert.ok(card.includes(`data-key="call:${task.remoteCalls[0].id}"`), 'the remote call is inside the Task card');
});

test('systemOne served by a local Ollama: the lane shows the model and ollama', () => {
	const events = fixture('modular-rag').map((e) => e.provider === 'typesafe' && e.type === 'wire-request'
		? { ...e, url: 'http://localhost:11434/v1/systemone', body: e.body.replace(/"jev-latest"/g, '"nimble"') } : e);
	events.forEach(handle);
	const s1 = buildSequence([...state.runs.values()][0]).lanes.find((l) => l.kind === 'jev');

	assert.deepEqual([s1.label, s1.sub], ['nimble', 'ollama · system-one']);
});

test('systemOne checks fold only while they go to the same model', () => {
	let n = 0;
	fixture('modular-rag').map((e) => e.provider === 'typesafe' && e.type === 'wire-request' && n++ % 2
		? { ...e, body: e.body.replace(/"jev-latest"/g, '"nimble"') } : e).forEach(handle);
	const { lanes, msgs } = buildSequence([...state.runs.values()][0]);

	assert.deepEqual(lanes.filter((l) => l.kind === 'jev').map((l) => l.label).sort(), ['jev-latest', 'nimble']);
	assert.ok(!msgs.some((m) => /systemOne checks/.test(m.label)), 'alternating models never fold');
});

test('sub-agents: a call made by a tool gets its own lane', () => {
	const [run] = load('subagent');

	assert.ok(buildSequence(run).lanes.some((l) => l.label === 'Sub-agent' && l.sub === 'via Task'));
});

test('sub-agents: a call made by a tool is shown inside the tool, in the cards and the sequence', () => {
	const [run] = load('subagent');
	const [agent] = topCalls(run);
	const task = [...run.tools.values()].find((t) => t.start.name === 'Task');
	const sub = [...run.calls.values()].find((c) => c.parent === agent);

	const html = renderCall(agent, true);
	const card = cardOf(html, 'tool:' + task.id);
	assert.ok(card.includes(`data-key="call:${sub.id}"`), 'the sub-agent call is inside the Task card');
	assert.equal(html.split(`data-key="call:${sub.id}"`).length, 2, 'and nowhere else');
	assert.match(card, /↘ 1 sub-agent</);
	assert.match(card, /^<details[^>]* open>/, 'a tool that ran a sub-agent opens by default');
	// Its arrow opens the Task card first, as for a remote agent.
	const arrow = buildSequence(run).msgs.find((m) => m.path?.at(-1) === 'call:' + sub.id && !m.ret);
	assert.deepEqual(arrow.path.slice(-2), ['tool:' + task.id, 'call:' + sub.id]);
});

test('sub-agents: the tool recorded as the parent wins over timing, e.g. two Task tools running in parallel', () => {
	let seq = 0;
	const at = (e) => handle({ runId: 'par', seq: ++seq, ts: 1000 + seq, ...e });
	at({ type: 'run-start', app: 'parallel · App' });
	at({ type: 'client-request', callId: 'main', messages: [{ role: 'user', text: 'plan a trip' }] });
	at({ type: 'tool-start', toolId: 'weather', clientCallId: 'main', name: 'Task', arguments: '{}' });
	at({ type: 'tool-start', toolId: 'airbnb', clientCallId: 'main', name: 'Task', arguments: '{}' });
	at({ type: 'client-request', callId: 'sub', parentId: 'main', parentToolId: 'airbnb', messages: [{ role: 'user', text: 'find a flat' }] });
	at({ type: 'client-response', callId: 'sub', durationMs: 1, generations: [{ role: 'assistant', text: 'a flat' }] });
	at({ type: 'tool-end', toolId: 'airbnb', durationMs: 3, result: 'a flat' });
	at({ type: 'tool-end', toolId: 'weather', durationMs: 5, result: 'sunny' });
	at({ type: 'client-response', callId: 'main', durationMs: 9, generations: [{ role: 'assistant', text: 'done' }] });
	const run = state.runs.get('par');

	const html = renderCall(run.calls.get('main'), true);
	assert.ok(cardOf(html, 'tool:airbnb').includes('data-key="call:sub"'));
	assert.ok(!cardOf(html, 'tool:weather').includes('data-key="call:sub"'), 'not the first tool running at the time');
	const arrow = buildSequence(run).msgs.find((m) => m.path?.at(-1) === 'call:sub' && !m.ret);
	assert.ok(arrow.path.includes('tool:airbnb'));
});

// ---------------------------------------------------------------- provider adapters

const wire = (provider, path, req, resp, contentType = 'application/json') => ({
	id: 'w1', num: 1, prev: null,
	req: { provider, path, method: 'POST', url: 'u', body: JSON.stringify(req), headers: {} },
	resp: { status: 200, durationMs: 5, body: resp, headers: { 'content-type': contentType } },
});

test('anthropic: reassembles a streamed response, including tool input', () => {
	const sse = [
		{ type: 'message_start', message: { model: 'claude', usage: { input_tokens: 12 } } },
		{ type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } },
		{ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Let me ' } },
		{ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'check.' } },
		{ type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: 't1', name: 'weather' } },
		{ type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: '{"city":' } },
		{ type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: '"Lisbon"}' } },
		{ type: 'content_block_stop', index: 1 },
		{ type: 'message_delta', delta: { stop_reason: 'tool_use' }, usage: { output_tokens: 7 } },
	].map((e) => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n`).join('\n');
	const w = wire('anthropic', '/v1/messages', { model: 'claude', stream: true, messages: [] }, sse, 'text/event-stream');

	const r = normResponse(w);
	assert.equal(r.stop, 'tool_use');
	assert.deepEqual(r.usage, { input: 12, output: 7, cacheRead: undefined, cacheWrite: undefined });
	assert.equal(r.blocks[0].text, 'Let me check.');
	assert.deepEqual(r.blocks[1], { type: 'tool_use', id: 't1', name: 'weather', input: { city: 'Lisbon' } });
});

test('openai: merges streamed tool-call fragments and reads DeepSeek reasoning', () => {
	const chunks = [
		{ choices: [{ delta: { reasoning_content: 'Need the weather.' } }] },
		{ choices: [{ delta: { tool_calls: [{ index: 0, id: 'c1', function: { name: 'weather', arguments: '{"ci' } }] } }] },
		{ choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: 'ty":"Oslo"}' } }] } }] },
		{ choices: [{ delta: {}, finish_reason: 'tool_calls' }], usage: { prompt_tokens: 30, completion_tokens: 9, prompt_cache_hit_tokens: 16 } },
	].map((c) => `data: ${JSON.stringify(c)}\n`).join('\n') + '\ndata: [DONE]\n';
	const w = wire('deepseek', '/chat/completions', { model: 'deepseek', stream: true, messages: [] }, chunks, 'text/event-stream');

	const r = normResponse(w);
	assert.equal(r.stop, 'tool_calls');
	assert.equal(r.usage.cacheRead, 16);
	assert.equal(r.blocks[0].type, 'thinking');
	assert.deepEqual(r.blocks[1], { type: 'tool_use', id: 'c1', name: 'weather', input: { city: 'Oslo' } });
	assert.equal(ADAPTERS.deepseek, ADAPTERS.openai);
});

test('openai responses: items become a conversation, with the instructions, tools and function calls', () => {
	const req = {
		model: 'gpt-6-luna', instructions: 'Use toolSearchTool.', store: false,
		tools: [{ type: 'function', name: 'toolSearchTool', parameters: { type: 'object' } }],
		input: [
			{ type: 'message', role: 'user', content: [{ type: 'input_text', text: 'What to wear?' }] },
			{ type: 'reasoning', id: 'rs_1', summary: [] },
			{ type: 'function_call', call_id: 'call_1', name: 'toolSearchTool', arguments: '{"query":"weather"}' },
			{ type: 'function_call_output', call_id: 'call_1', output: '["weather"]' },
		],
	};
	const resp = { status: 'completed', output: [
		{ type: 'reasoning', id: 'rs_2', summary: [{ type: 'summary_text', text: 'Need the weather.' }] },
		{ type: 'function_call', call_id: 'call_2', name: 'weather', arguments: '{"location":"Amsterdam"}' }],
	usage: { input_tokens: 120, input_tokens_details: { cached_tokens: 64 }, output_tokens: 30, output_tokens_details: { reasoning_tokens: 20 } } };
	const w = wire('openai', '/v1/responses', req, JSON.stringify(resp));

	const n = normRequest(w);
	assert.equal(n.params.model, 'gpt-6-luna');
	assert.equal(n.system[0].text, 'Use toolSearchTool.');
	assert.deepEqual(n.tools.map((t) => t.name), ['toolSearchTool']);
	// The reasoning and the function call it led to are one assistant turn; the result is named after its call.
	assert.deepEqual(n.messages.map((m) => m.role), ['user', 'assistant', 'tool']);
	assert.deepEqual(n.messages[1].blocks.map((b) => b.type), ['thinking', 'tool_use']);
	assert.equal(n.messages[1].blocks[0].hidden, true);
	assert.deepEqual(n.messages[2].blocks[0], { type: 'tool_result', id: 'call_1', name: 'toolSearchTool', content: '["weather"]' });

	const r = normResponse(w);
	assert.equal(r.stop, 'tool_calls');
	assert.deepEqual(r.usage, { input: 120, output: 30, cacheRead: 64, reasoning: 20 });
	assert.equal(r.blocks[0].text, 'Need the weather.');
	assert.deepEqual(r.blocks[1], { type: 'tool_use', id: 'call_2', name: 'weather', input: { location: 'Amsterdam' } });

	const html = renderWire(w);
	assert.match(html, /<span class="pill">gpt-6-luna<\/span><span class="pill">3 msgs<\/span><span class="pill">1 tools<\/span>/);
	assert.match(html, /⚙ weather/);
	assert.match(html, />Conversation<\/button>/);
	// A plain string input is one user message; a routed provider of another name is recognized by the path.
	assert.equal(normRequest(wire('azure', '/openai/v1/responses', { input: 'hi' }, '{}')).messages[0].blocks[0].text, 'hi');
	// A stored item referenced by id has no content in the request: it is not shown as a turn.
	const ref = normRequest(wire('openai', '/v1/responses', { input: [{ type: 'item_reference', id: 'msg_1' }, { role: 'user', content: 'again' }] }, '{}'));
	assert.deepEqual(ref.messages.map((m) => m.role), ['user']);
});

test('openai responses: a streamed response is read from its last lifecycle event', () => {
	const events = [
		{ type: 'response.created', response: { status: 'in_progress', output: [] } },
		{ type: 'response.output_text.delta', delta: 'Wear a ' },
		{ type: 'response.completed', response: { status: 'completed',
			output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'Wear a T-shirt.' }] }],
			usage: { input_tokens: 10, output_tokens: 5 } } },
	].map((e) => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n`).join('\n');
	const r = normResponse(wire('openai', '/v1/responses', { model: 'gpt', stream: true, input: [] }, events, 'text/event-stream'));
	assert.equal(r.stop, 'completed');
	assert.equal(r.blocks[0].text, 'Wear a T-shirt.');
	assert.equal(r.usage.output, 5);

	const failed = normResponse(wire('openai', '/v1/responses', { input: [] },
		JSON.stringify({ error: { type: 'invalid_request_error', message: 'Unknown model' } })));
	assert.equal(failed.error, 'invalid_request_error: Unknown model');
});

test('ollama: joins newline-delimited stream chunks', () => {
	const ndjson = [
		{ message: { role: 'assistant', content: 'Hel' }, done: false },
		{ message: { role: 'assistant', content: 'lo' }, done: true, done_reason: 'stop', prompt_eval_count: 4, eval_count: 2 },
	].map((l) => JSON.stringify(l)).join('\n');
	const r = normResponse(wire('ollama', '/api/chat', { model: 'llama3.1', messages: [{ role: 'user', content: 'hi' }] }, ndjson));

	assert.equal(r.blocks[0].text, 'Hello');
	assert.deepEqual(r.usage, { input: 4, output: 2 });
});

test('embeddings: model, inputs, vectors and prompt tokens, for OpenAI and Ollama', () => {
	const openai = wire('openai', '/v1/embeddings', { model: 'text-embedding-3-small', input: ['a chunk', 'another'] },
		JSON.stringify({ model: 'text-embedding-3-small', data: [{ embedding: [0.1, 0.2, 0.3] }, { embedding: [0.4, 0.5, 0.6] }], usage: { prompt_tokens: 7, total_tokens: 7 } }));
	assert.deepEqual(normRequest(openai).inputs, ['a chunk', 'another']);
	assert.deepEqual({ ...normResponse(openai) }, { model: 'text-embedding-3-small', vectors: 2, dimensions: 3, blocks: [], usage: { input: 7, output: 0 } });
	const html = renderWire(openai);
	assert.match(html, /<span class="pill">text-embedding-3-small<\/span><span class="pill">embed 2 inputs<\/span>/);
	assert.match(html, /2 vectors × 3/);
	assert.match(html, />Embedding<\/button>/);
	assert.match(html, /input 2<\/div>\s*<div class="text">another/);

	const ollama = wire('ollama', '/api/embed', { model: 'nomic-embed-text', input: 'one text' },
		JSON.stringify({ model: 'nomic-embed-text', embeddings: [[1, 2]], prompt_eval_count: 3 }));
	assert.equal(normResponse(ollama).vectors, 1);
	assert.equal(usageOf(ollama).input, 3);
	// Pre-tokenized text is one input; Mistral reports errors without an `error` field.
	assert.equal(normRequest(wire('openai', '/v1/embeddings', { input: [9906, 1917, 0] }, '{}')).inputs.length, 1);
	assert.equal(normResponse(wire('mistralai', '/v1/embeddings', { input: 'x' },
		JSON.stringify({ object: 'error', message: 'Unauthorized', type: 'invalid_request_error' }))).error, 'invalid_request_error: Unauthorized');
	// A chat round-trip of the same provider is unaffected.
	assert.equal(adapterOf(wire('openai', '/v1/chat/completions', { messages: [] }, '{}')), ADAPTERS.openai);
});

test('runs of embedding calls fold into one group, in cards and in the sequence', () => {
	handle({ type: 'run-start', runId: 'rag', seq: 1, ts: 1000, app: '05-1-modular-rag · DemoApplication' });
	for (let i = 0; i < 5; i++) {
		handle({ type: 'wire-request', runId: 'rag', seq: 2 + 2 * i, ts: 1000 + 10 * i, wireId: 'e' + i, provider: 'openai', method: 'POST',
			path: '/v1/embeddings', url: 'u', headers: {}, body: JSON.stringify({ model: 'text-embedding-3-small', input: ['chunk ' + i] }) });
		handle({ type: 'wire-response', runId: 'rag', seq: 3 + 2 * i, ts: 1005 + 10 * i, wireId: 'e' + i, status: 200, durationMs: 100, headers: {},
			body: JSON.stringify({ data: [{ embedding: [0, 1] }], usage: { prompt_tokens: 4 } }) });
	}
	const run = state.runs.get('rag');

	const cards = renderItems(run.items, null);
	assert.match(cards, /<b>5 embedding calls<\/b>\s*<span class="pill">text-embedding-3-small<\/span><span class="pill">5 inputs<\/span>/);
	assert.match(cards, /20 in/);
	// The time it took, first request to last response (parallel calls aren't counted twice).
	assert.match(cards, /#1–#5 · 45 ms/);
	const { lanes, msgs } = buildSequence(run);
	assert.deepEqual(msgs.map((m) => m.label), ['5 embedding calls · 5 inputs', '5 vectors · 45 ms']);
	assert.ok(lanes.some((l) => l.label === 'text-embedding-3-small' && l.sub === 'openai'));

	// Runs only fold per model, the same in both views, so a sequence arrow opens its card group.
	handle({ type: 'wire-request', runId: 'rag', seq: 20, ts: 2000, wireId: 'other', provider: 'openai', method: 'POST', path: '/v1/embeddings',
		url: 'u', headers: {}, body: JSON.stringify({ model: 'text-embedding-3-large', input: ['x'] }) });
	assert.match(renderItems(run.items, null), /<b>5 embedding calls<\/b>[\s\S]*data-key="wire:other"/);
	assert.deepEqual(buildSequence(run).msgs.map((m) => m.path.at(-1)), ['embed:e0', 'embed:e0', 'wire:other']);

	// The lane heads are drawn in their own (sticky) header, above the diagram.
	const seq = renderSequence(run, false);
	assert.match(seq, /<div class="seq-view" data-run="rag">/);
	const [head, body] = [seq.match(/<div class="seq-head">([\s\S]*?)<\/div>/)[1], seq.match(/<div class="seq-wrap">([\s\S]*?)<\/div>/)[1]];
	assert.match(head, /lane-head[\s\S]*text-embedding-3-small/);
	assert.doesNotMatch(body, /lane-head/);
	assert.match(body, /5 embedding calls/);
});

test('typesafe: systemOne requests and answers', () => {
	const w = wire('typesafe', '/v1/systemone',
		{ model: 'jev-latest', state: { text: 'hi' }, questions: { jailbreak: { type: 'noul', instructions: 'x' } } },
		JSON.stringify({ model: 'jev-1', answers: { jailbreak: { type: 'noul', noul: 0.99 } }, usage: { input_tokens: 5, output_tokens: 1 } }));

	assert.deepEqual(Object.keys(normRequest(w).questions), ['jailbreak']);
	// Whether true is good or bad is not on the wire: noul answers are shown, not judged.
	const html = renderWire(w);
	assert.match(html, /<span class="pill">jailbreak: <b>true<\/b> 0\.99<\/span>/);
	assert.match(html, /<b>true<\/b> · P\(true\)/);
	assert.doesNotMatch(html, /hot|⚠/);
	// Labeled by protocol, since Jev is also served by e.g. a local Ollama.
	assert.match(html, /<span class="pill">typesafe · system-one<\/span>/);
});

// ---------------------------------------------------------------- escaping

test('system prompts fold to a one-line preview and remember being opened', () => {
	const m = { role: 'system', text: 'You are an interactive CLI tool.\n\nIMPORTANT: be careful.' };
	const folded = renderSpringMessage(m);
	assert.match(folded, /^<details class="msg system" data-key="sys:\w+" >/);
	assert.match(folded, /sys-preview">You are an interactive CLI tool\. IMPORTANT: be careful\.</);
	state.open.set(folded.match(/data-key="([^"]+)"/)[1], true);
	assert.match(renderSpringMessage(m, 'added'), /^<details class="msg system added" data-key="sys:\w+" open>/);
	assert.doesNotMatch(renderSpringMessage({ role: 'user', text: 'hi' }), /<details/);
});

test('answers fold to a one-line preview and remember being opened', () => {
	const m = { role: 'assistant', text: 'Shops open now:\nFoo, Bar.' };
	assert.match(renderAnswerMessage(m, 'ans:c1:0'), /^<details class="msg assistant" data-key="ans:c1:0" data-lazy >/);
	assert.match(renderAnswerMessage(m, 'ans:c1:0'), /sys-preview">Shops open now: Foo, Bar\.</);
	state.open.set('ans:c1:0', true);
	assert.match(renderAnswerMessage(m, 'ans:c1:0'), /data-key="ans:c1:0" data-lazy open>/);
	// Thinking returned as a generation of its own is shown as thinking, not as an empty answer.
	const thinking = renderAnswerMessage({ role: 'assistant', text: '', thinking: 'signed' }, 'k');
	assert.match(thinking, /thinking · hidden \(signature only\)/);
	assert.doesNotMatch(thinking, /msg assistant/);
	assert.doesNotMatch(renderAnswerMessage({ role: 'assistant', toolCalls: [{ name: 'weather', arguments: '{}' }] }, 'k'), /<details/);
});

test('JSON answers are pretty-printed as sent, other text is left as is', () => {
	const answer = (text) => { state.open.set('ans', true); return renderAnswerMessage({ role: 'assistant', text }, 'ans'); };
	const json = answer('{ "evaluation": "mostly helpful", "rating": 3 }');
	assert.match(json, /<pre class="json text text-json">\{\n  <span class="j-key">&quot;evaluation&quot;<\/span>: <span class="j-str">&quot;mostly helpful&quot;<\/span>,\n  <span class="j-key">&quot;rating&quot;<\/span>: <span class="j-num">3<\/span>\n\}<\/pre>/);
	// The one-line preview stays the text as answered.
	assert.match(json, /sys-preview">\{ &quot;evaluation&quot;: &quot;mostly helpful&quot;, &quot;rating&quot;: 3 \}</);
	// Closed, the answer's body is not rendered at all.
	state.open.set('ans', false);
	assert.doesNotMatch(renderAnswerMessage({ role: 'assistant', text: '{"a":1}' }, 'ans'), /text-json/);

	// Re-indented, not re-serialized: digits, key order and duplicate keys are what was sent.
	assert.equal(indentJson('{"id":12345678901234567890,"b":1,"2":[],"b":{"s":"a, {b}: \\"c\\""}}'),
		'{\n  "id": 12345678901234567890,\n  "b": 1,\n  "2": [],\n  "b": {\n    "s": "a, {b}: \\"c\\""\n  }\n}');
	assert.match(prettyMaybeJson('{"id":12345678901234567890}'), /12345678901234567890/);
	assert.equal(prettyMaybeJson('not json'), 'not json');

	// A fence is pretty-printed too, but shown.
	const fenced = answer('```json\n[1, 2]\n```');
	assert.match(fenced, /<span class="tag muted">in a ```json fence<\/span><pre class="json text text-json">\[\n  <span class="j-num">1<\/span>,/);
	assert.doesNotMatch(answer('[1, 2]'), /json fence/);
	for (const text of ['{ not json }', '42', 'Shops: {Foo, Bar}']) {
		assert.doesNotMatch(answer(text), /text-json/);
		assert.match(answer(text), /<div class="text">/);
	}
	// Also in the conversation on the wire, and for system prompts; a removed message keeps its mark.
	assert.match(renderBlock({ type: 'text', text: '{"a":true}' }), /<pre class="json text text-json">\{\n  <span class="j-key">&quot;a&quot;<\/span>: <span class="j-lit">true<\/span>/);
	assert.match(renderSpringMessage({ role: 'system', text: '{"a":1}' }), /text-json/);
	assert.match(renderSpringMessage({ role: 'user', text: '{"a":1}' }, 'removed'), /<div class="msg user removed">.*<pre class="json text text-json">/s);
});

test('re-sent messages fold to one line, new ones stay open', () => {
	const msg = (role, blocks) => ({ role, raw: { role, blocks }, blocks });
	const user = msg('user', [{ type: 'text', text: 'Help me buy\nclothes.' }]);
	const call = msg('assistant', [{ type: 'thinking', redacted: true }, { type: 'tool_use', id: 'c1', name: 'toolSearchTool', input: { query: 'shops' } }]);
	const result = msg('tool', [{ type: 'tool_result', id: 'c1', name: 'toolSearchTool', content: '[]' }]);
	const next = msg('assistant', [{ type: 'tool_use', id: 'c2', name: 'weather', input: {} }]);
	const req = (messages) => ({ params: {}, tools: [], messages });

	const render = () => renderNormRequest(req([user, call, result, next]), req([user, call, result]), 'w1');
	const closed = render();
	assert.match(closed, /<details class="resent" data-key="resent:w1" data-lazy >/);
	assert.match(closed, /3 earlier messages\s*<span class="resent-roles">· 1 user · 1 assistant · 1 tool</);
	// Closed, the fold renders none of its messages.
	assert.doesNotMatch(closed, /Help me buy|toolSearchTool/);
	// The new message is outside the fold, in full.
	assert.match(closed, /<\/details><div class="msg assistant fresh"><div class="role">assistant<span class="tag fresh">new<\/span>/);

	state.open.set('resent:w1', true);
	const html = render();
	assert.match(html, /data-key="resent:w1" data-lazy open>/);
	assert.match(html, /data-key="resent-msg:0:\w+" data-lazy ><summary class="role">.*?sys-preview">Help me buy clothes\.<\/span><span class="sys-size">20 chars</s);
	assert.match(html, /sys-preview">\[thinking\] · toolSearchTool\(\{&quot;query&quot;:&quot;shops&quot;\}\)<\/span><span class="sys-size">\d+ chars · 2 blocks</);
	assert.match(html, /sys-preview">toolSearchTool → \[\]</);
	// Closed messages are not rendered; an opened one is, and stays open in the next round-trip.
	assert.doesNotMatch(html, /tool call · c1/);
	const key = html.match(/data-key="(resent-msg:1:\w+)"/)[1];
	state.open.set(key, true);
	assert.match(render(), /tool call · c1/);
	state.open.set('resent:w2', true);
	assert.match(renderNormRequest(req([user, call, result, next, result]), req([user, call, result, next]), 'w2'), new RegExp(`data-key="${key}" data-lazy open>`));

	// Long previews are cut to one short line.
	const big = msg('tool', [{ type: 'tool_result', id: 'c1', name: 'search', content: 'x'.repeat(50000) }]);
	state.open.set('resent:w3', true);
	const preview = renderNormRequest(req([user, big, next]), req([user, big]), 'w3').match(/sys-preview">(search → x+…)<\/span><span class="sys-size">50,009 chars</);
	assert.ok(preview && preview[1].length < 210);

	// Only an unchanged run of leading messages is re-sent: a changed earlier message is new, in full.
	const summary = msg('assistant', [{ type: 'text', text: 'Summary of earlier turns.' }]);
	const changed = renderNormRequest(req([user, summary, next]), req([user, call, result]), 'w4');
	assert.match(changed, /1 earlier message\s*<span class="resent-roles">· 1 user</);
	assert.match(changed, /<div class="msg assistant fresh"><div class="role">assistant<span class="tag fresh">new<\/span><\/div><div class="text">Summary of earlier turns\.</);
	// A first request has nothing re-sent.
	assert.doesNotMatch(renderNormRequest(req([user]), null, 'w0'), /resent|tag fresh/);
});

test('a long systemOne state is folded to its first lines', () => {
	const available_tools = Array.from({ length: 30 }, (_, i) => ({ name: 'tool' + i, does: 'does thing ' + i }));
	const w = wire('typesafe', '/v1/systemone', { model: 'jev-latest', state: { user_request: 'shops open now', available_tools },
		questions: { best_tool: { type: 'choice', instructions: 'x' } } }, JSON.stringify({ answers: {} }));

	const html = renderWire(w);
	assert.match(html, /<details class="s1-state" data-key="s1-state:w1" >/);
	assert.match(html, /state · 125 lines · 2,055 chars/);
	const preview = html.match(/<pre class="json s1-state-preview">([\s\S]*?)<\/pre>/)[1];
	assert.match(preview, /user_request/);
	assert.match(preview, /does thing 0/); // the first entry
	assert.doesNotMatch(preview, /tool1/);
	assert.match(html, /tool29/); // the full state is there when opened
	// A few lines holding a long string are folded too, with long preview lines cut.
	const long = renderWire(wire('typesafe', '/v1/systemone', { state: { user_request: 'x'.repeat(3000) }, questions: {} }, '{}'));
	assert.match(long, /<details class="s1-state"/);
	assert.match(long.match(/<pre class="json s1-state-preview">([\s\S]*?)<\/pre>/)[1], /x{100,}…/);
	assert.doesNotMatch(long.match(/<pre class="json s1-state-preview">([\s\S]*?)<\/pre>/)[1], /x{200}/);
	// A short state stays as it is.
	assert.doesNotMatch(renderWire(wire('typesafe', '/v1/systemone', { state: { a: 1 }, questions: {} }, '{}')), /s1-state/);
});

test('noul answers say which way Jev leans, with a band for close calls', () => {
	assert.deepEqual([0.07, 0.39, 0.4, 0.51, 0.6, 0.61, 0.97].map(noulLeaning),
		['false', 'false', 'uncertain', 'uncertain', 'uncertain', 'true', 'true']);
});

test('anthropic: a thinking block without its text says why it is empty', () => {
	assert.deepEqual(anthropicBlock({ type: 'thinking', thinking: '', signature: 'sig' }), { type: 'thinking', text: '', signed: true });
	assert.match(renderBlock(anthropicBlock({ type: 'thinking', thinking: '', signature: 'sig' })), /thinking · hidden \(signature only\)/);
	assert.match(renderBlock(anthropicBlock({ type: 'redacted_thinking', data: 'x' })), /thinking · redacted \(encrypted\)/);
	const shown = renderBlock(anthropicBlock({ type: 'thinking', thinking: 'Check the units.', signature: 'sig' }));
	assert.match(shown, /Check the units\./);
	assert.doesNotMatch(shown, /hidden/);
});

test('event data is escaped wherever it is rendered', () => {
	const evil = '"><img src=x onerror=alert(1)>';
	for (const event of [
		{ type: 'run-start', runId: 'r1', app: evil, ts: 1, seq: 1 },
		{ type: 'client-request', runId: 'r1', callId: evil, ts: 2, seq: 2, messages: [{ role: 'user', text: evil }], advisors: [{ name: evil, order: 0 }] },
		{ type: 'tool-start', runId: 'r1', toolId: evil, clientCallId: evil, name: evil, arguments: evil, ts: 3, seq: 3 },
		{ type: 'tool-end', runId: 'r1', toolId: evil, result: evil, durationMs: 1, ts: 4, seq: 4 },
		{ type: 'client-response', runId: 'r1', callId: evil, durationMs: 2, ts: 5, seq: 5, generations: [{ role: 'assistant', text: evil }] },
	]) handle(event);
	const run = state.runs.get('r1');

	for (const html of [renderItems(run.items, null), renderSequence(run, true)]) {
		assert.ok(!html.includes('<img'), 'no raw markup from event data');
	}
});

// ---------------------------------------------------------------- tokens by model

test('tokens by model: the remote A2A agent is included and tagged', () => {
	const runs = load('a2a');
	const caller = runOf(runs, 'subagent-a2a-demo');
	const { rows, total, hasRemote } = tokensByModel(caller);

	assert.ok(hasRemote);
	const remote = rows.filter((r) => r.remote);
	assert.ok(remote.length >= 1 && remote.every((r) => r.remote === 'airbnb-agent' && r.provider === 'openai'));
	assert.ok(rows.some((r) => !r.remote && r.provider === 'anthropic'));
	assert.equal(total.input, rows.reduce((t, r) => t + r.input, 0));
	assert.match(renderTokenPanel(caller), /remote · airbnb-agent/);
	assert.match(renderTokenPanel(caller), /incl\. remote agents/);
});

test('tokens by model: Jev and the chat model are counted separately, every round-trip once', () => {
	const [run] = load('modular-rag');
	const { rows, total } = tokensByModel(run);

	assert.deepEqual(rows.map((r) => r.provider).sort(), ['anthropic', 'typesafe']);
	assert.equal(total.calls, run.wireList.length);
});

test('usage is normalized: input counts all prompt tokens, cache and reasoning are subsets', () => {
	const anthropic = normResponse(wire('anthropic', '/v1/messages', { model: 'claude', messages: [] },
		JSON.stringify({ content: [], stop_reason: 'end_turn',
			usage: { input_tokens: 10, cache_read_input_tokens: 100, cache_creation_input_tokens: 5, output_tokens: 3 } })));
	assert.equal(anthropic.usage.input, 115);
	assert.equal(anthropic.usage.cacheRead, 100);

	const openai = normResponse(wire('openai', '/v1/chat/completions', { model: 'gpt', messages: [] },
		JSON.stringify({ choices: [{ message: { content: 'hi' }, finish_reason: 'stop' }],
			usage: { prompt_tokens: 120, completion_tokens: 40, prompt_tokens_details: { cached_tokens: 100 },
				completion_tokens_details: { reasoning_tokens: 32 } } })));
	assert.deepEqual([openai.usage.input, openai.usage.cacheRead, openai.usage.reasoning], [120, 100, 32]);
});

test('raw bodies: a binary body is described, a cut body says how much is missing', () => {
	assert.match(renderRawBody({ bodyKind: 'binary', contentType: 'audio/mpeg', size: 70521 }), /binary body · audio\/mpeg · 70,521 bytes/);
	assert.match(renderRawBody({ bodyKind: 'binary', contentType: '<b>x</b>', size: 1 }), /&lt;b&gt;x&lt;\/b&gt;/);
	const cut = renderRawBody({ body: '{"a":"' + 'x'.repeat(20), truncated: true, size: 512000 });
	assert.match(cut, /cut at 26 of 512,000 characters/);
	assert.match(cut, /xxxx/);
	assert.doesNotMatch(renderRawBody({ body: '{"a":1}' }), /cut at|binary body/);
});

test('a re-announced run keeps its start time and does not steal the selection', () => {
	state.runs.clear(); state.follow = true;
	handle({ type: 'run-start', runId: 'a', app: 'first', ts: 1000 });
	handle({ type: 'run-start', runId: 'b', app: 'second', ts: 2000 });
	handle({ type: 'run-start', runId: 'a', app: 'first', ts: 3000, reannounce: true });
	assert.equal(state.runs.get('a').started, 1000);
	assert.equal(state.selected, 'b');
});

test('a vector store delete is shown as an operation of its own, with its ids or filter', () => {
	state.runs.clear();
	handle({ type: 'run-start', runId: 'd', app: 'deletes', ts: 1 });
	handle({ type: 'client-request', runId: 'd', callId: 'c1', seq: 1, ts: 2, messages: [{ role: 'user', text: 'clear' }] });
	handle({ type: 'vector-start', runId: 'd', opId: 'o1', op: 'delete', clientCallId: 'c1', store: 'SimpleVectorStore', count: 3, seq: 2, ts: 3 });
	let html = renderCall(state.runs.get('d').calls.get('c1'), true);
	assert.match(html, /delete 3 ids/);
	assert.match(html, /deleting…/);
	handle({ type: 'vector-delete', runId: 'd', opId: 'o1', clientCallId: 'c1', store: 'SimpleVectorStore', count: 3, durationMs: 4, seq: 3, ts: 7 });
	html = renderCall(state.runs.get('d').calls.get('c1'), true);
	assert.match(html, /removed/);
	assert.doesNotMatch(html, /No embedding round-trips/);
	handle({ type: 'vector-start', runId: 'd', opId: 'o2', op: 'delete', clientCallId: 'c1', store: 'SimpleVectorStore', filter: "source == 'old'", seq: 4, ts: 8 });
	assert.match(renderCall(state.runs.get('d').calls.get('c1'), true), /delete by filter source == &#39;old&#39;/);
	assert.match(renderSequence(state.runs.get('d'), false), /delete by filter/);
});

// ---------------------------------------------------------------- images, speech, transcription, moderation
/** A run with one round-trip of the given shape, returning the wire. */
function wireOf(provider, path, reqExtra, respExtra) {
	state.runs.clear();
	const runId = 'w-' + Math.random().toString(36).slice(2, 7);
	handle({ type: 'run-start', runId, app: 'media', ts: 1 });
	handle({ type: 'wire-request', runId, wireId: 'w1', provider, method: 'POST', path, url: 'https://api.example' + path, headers: {}, seq: 1, ts: 2, ...reqExtra });
	if (respExtra) handle({ type: 'wire-response', runId, wireId: 'w1', status: 200, durationMs: 800, headers: { 'content-type': 'application/json' }, seq: 2, ts: 900, ...respExtra });
	return state.runs.get(runId).wires.get('w1');
}

test('media markers: kept blobs, provider URLs and stripped payloads are told apart and rendered safely', () => {
	assert.deepEqual(blobMarker('<base64 2000 chars image/png blob:0123456789abcdef>'), { chars: 2000, type: 'image/png', blobId: '0123456789abcdef' });
	assert.deepEqual(blobMarker('<base64 2000 chars>'), { chars: 2000, type: '', blobId: null });
	assert.equal(blobMarker('plain text'), null);
	assert.deepEqual(mediaOf('https://cdn.example/a.png'), { url: 'https://cdn.example/a.png', type: '' });
	const img = renderMedia({ blobId: 'abc', type: 'image/png', chars: 2000 });
	assert.match(img, /<img class="media" src="api\/blobs\/abc"/);
	assert.match(renderMedia({ blobId: 'abc', type: 'audio/mpeg', size: 70521 }), /<audio class="media" controls[^>]*src="api\/blobs\/abc"/);
	assert.match(renderMedia({ chars: 2000, type: 'image/png' }), /not kept/);
	assert.match(renderMedia({ url: 'https://x.example/<script>' }), /&lt;script&gt;/);
	assert.doesNotMatch(renderMedia({ url: 'https://x.example/<script>' }), /<script>/);
	// A chat message with an inline image shows it.
	const block = anthropicBlock({ type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: '<base64 9000 chars image/jpeg blob:feedfeedfeedfeed>' } });
	assert.equal(block.media.blobId, 'feedfeedfeedfeed');
	assert.match(renderBlock(block), /api\/blobs\/feedfeedfeedfeed/);
});

test('image generation: prompt and parameters in, images out, with usage where the model reports it', () => {
	const w = wireOf('openai', '/v1/images/generations', { body: JSON.stringify({ model: 'gpt-image-1', prompt: 'a lighthouse at dusk', n: 2, size: '1024x1024' }) },
		{ body: JSON.stringify({ created: 1, data: [{ b64_json: '<base64 400000 chars image/png blob:1111111111111111>', revised_prompt: 'A lighthouse at dusk, oil painting' }, { url: 'https://cdn.example/img2.png' }], usage: { input_tokens: 12, output_tokens: 1056 } }) });
	assert.equal(adapterOf(w).kind, 'image');
	assert.equal(normRequest(w).prompt, 'a lighthouse at dusk');
	assert.equal(normResponse(w).images.length, 2);
	assert.deepEqual(usageOf(w), { input: 12, output: 1056 });
	const html = renderWire(w);
	assert.match(html, /gpt-image-1/);
	assert.match(html, /2 images/);
	assert.match(html, /api\/blobs\/1111111111111111/);
	assert.match(html, /cdn\.example\/img2\.png/);
	assert.match(html, /revised prompt/);
	assert.match(html, /data-tab="conv">Image</);
	assert.match(renderSequence(state.runs.get(w.req.runId), false), /image/);
});

test('speech: the text and voice in, an audio player out when the bytes were kept', () => {
	const w = wireOf('openai', '/v1/audio/speech', { body: JSON.stringify({ model: 'gpt-4o-mini-tts', voice: 'alloy', input: 'Welcome to Voxxed Days' }) },
		{ bodyKind: 'binary', contentType: 'audio/mpeg', size: 70521, blobId: '2222222222222222', headers: { 'content-type': 'audio/mpeg' } });
	assert.equal(adapterOf(w).kind, 'speech');
	assert.equal(normResponse(w).audio.blobId, '2222222222222222');
	const html = renderWire(w);
	assert.match(html, /alloy/);
	assert.match(html, /audio · 68\.9 KB/);
	assert.match(html, /<audio class="media" controls[^>]*api\/blobs\/2222222222222222/);
	// Not kept (an imported recording): said so, no broken player.
	const gone = wireOf('openai', '/v1/audio/speech', { body: JSON.stringify({ model: 'tts-1', voice: 'nova', input: 'hi' }) },
		{ bodyKind: 'binary', contentType: 'audio/mpeg', size: 10 });
	assert.match(renderWire(gone), /not kept/);
});

test('transcription: the multipart request shows its fields and file, the response its text', () => {
	const w = wireOf('openai', '/v1/audio/transcriptions',
		{ bodyKind: 'multipart', body: JSON.stringify({ fields: { model: 'whisper-1', language: 'en' }, files: [{ name: 'file', filename: 'question.wav', contentType: 'audio/wav', size: 48000, blobId: '3333333333333333' }] }) },
		{ body: JSON.stringify({ text: 'What is the weather in Antwerp?', language: 'english', duration: 2.4 }) });
	assert.equal(adapterOf(w).kind, 'transcription');
	assert.equal(normRequest(w).params.model, 'whisper-1');
	assert.equal(normResponse(w).text, 'What is the weather in Antwerp?');
	const html = renderWire(w);
	assert.match(html, /question\.wav/);
	assert.match(html, /api\/blobs\/3333333333333333/);
	assert.match(html, /What is the weather in Antwerp\?/);
	assert.match(html, /duration: <b>2\.4 s/);
	// Plain-text responses (response_format=text) are the transcript as is.
	const plain = wireOf('openai', '/v1/audio/transcriptions', { bodyKind: 'multipart', body: JSON.stringify({ fields: { model: 'whisper-1' }, files: [] }) },
		{ body: 'Just text.', headers: { 'content-type': 'text/plain' } });
	assert.equal(normResponse(plain).text, 'Just text.');
});

test('moderation: each input with its verdict and top category scores', () => {
	const w = wireOf('openai', '/v1/moderations', { body: JSON.stringify({ model: 'omni-moderation-latest', input: ['hello there', 'I will hurt you'] }) },
		{ body: JSON.stringify({ model: 'omni-moderation-latest', results: [
			{ flagged: false, categories: { violence: false }, category_scores: { violence: 0.001 } },
			{ flagged: true, categories: { violence: true, harassment: true, hate: false }, category_scores: { violence: 0.92, harassment: 0.71, hate: 0.02 } }] }) });
	assert.equal(adapterOf(w).kind, 'moderation');
	assert.deepEqual(normResponse(w).results[1].flaggedCategories, ['violence', 'harassment']);
	const html = renderWire(w);
	assert.match(html, /flagged · violence, harassment/);
	assert.match(html, /I will hurt you/);
	assert.match(html, /0\.92/);
	assert.match(renderSequence(state.runs.get(w.req.runId), false), /flagged/);
});

test('an image a tool returned is shown in its tool result', () => {
	const block = anthropicBlock({ type: 'tool_result', tool_use_id: 'toolu_1', content: [{ type: 'text', text: 'the chart' },
		{ type: 'image', source: { type: 'base64', media_type: 'image/png', data: '<base64 30000 chars image/png blob:abcdabcdabcdabcd>' } }] });
	assert.equal(block.content, 'the chart\n[image]');
	assert.equal(block.media[0].blobId, 'abcdabcdabcdabcd');
	const html = renderBlock(block);
	assert.match(html, /the chart/);
	assert.match(html, /<img class="media" src="api\/blobs\/abcdabcdabcdabcd"/);
});
