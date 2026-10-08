// Node tests for the inspector UI modules (no browser, no build step):
//   node --test spring-ai-inspector/spring-ai-inspector-server/src/test/js
// Recorded runs from the demos drive the real model and render code, so a missing import
// or a broken view fails here instead of on stage.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { state } from '../../main/resources/static/js/state.js';
import { handle } from '../../main/resources/static/js/model.js';
import { ADAPTERS, adapterOf, anthropicBlock, normRequest, normResponse, usageOf } from '../../main/resources/static/js/providers.js';
import { diffTools, renderCall, renderItems, renderTool } from '../../main/resources/static/js/render/cards.js';
import { renderMcpPanel } from '../../main/resources/static/js/render/mcp.js';
import { renderRag } from '../../main/resources/static/js/render/rag.js';
import { renderMemory } from '../../main/resources/static/js/render/memory.js';
import { renderAnswerMessage, renderSpringMessage } from '../../main/resources/static/js/render/messages.js';
import { buildSequence, renderSequence } from '../../main/resources/static/js/render/sequence.js';
import { noulLeaning, renderBlock, renderWire } from '../../main/resources/static/js/render/wire.js';
import { renderTokenPanel, tokensByModel } from '../../main/resources/static/js/render/tokens.js';

const fixture = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url)));

function load(name) {
	for (const event of fixture(name)) handle(event);
	return [...state.runs.values()];
}

const runOf = (runs, app) => runs.find((r) => r.app.startsWith(app));
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

	assert.deepEqual(call.items.map((i) => i.kind), ['wire', 'tool', 'tool', 'wire']);
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
	assert.match(renderCall(topCalls(caller)[0], true), /1 remote call/);
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
	assert.match(renderAnswerMessage(m, 'ans:c1:0'), /^<details class="msg assistant" data-key="ans:c1:0" >/);
	assert.match(renderAnswerMessage(m, 'ans:c1:0'), /sys-preview">Shops open now: Foo, Bar\.</);
	state.open.set('ans:c1:0', true);
	assert.match(renderAnswerMessage(m, 'ans:c1:0'), /data-key="ans:c1:0" open>/);
	// Thinking returned as a generation of its own is shown as thinking, not as an empty answer.
	const thinking = renderAnswerMessage({ role: 'assistant', text: '', thinking: 'signed' }, 'k');
	assert.match(thinking, /thinking · hidden \(signature only\)/);
	assert.doesNotMatch(thinking, /msg assistant/);
	assert.doesNotMatch(renderAnswerMessage({ role: 'assistant', toolCalls: [{ name: 'weather', arguments: '{}' }] }, 'k'), /<details/);
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
