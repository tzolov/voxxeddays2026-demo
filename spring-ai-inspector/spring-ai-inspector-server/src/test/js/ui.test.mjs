// Node tests for the inspector UI modules (no browser, no build step):
//   node --test spring-ai-inspector/spring-ai-inspector-server/src/test/js
// Recorded runs from the demos drive the real model and render code, so a missing import
// or a broken view fails here instead of on stage.
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { state } from '../../main/resources/static/js/state.js';
import { handle } from '../../main/resources/static/js/model.js';
import { ADAPTERS, normRequest, normResponse, usageOf } from '../../main/resources/static/js/providers.js';
import { diffTools, renderCall, renderItems } from '../../main/resources/static/js/render/cards.js';
import { renderRag } from '../../main/resources/static/js/render/rag.js';
import { renderMemory } from '../../main/resources/static/js/render/memory.js';
import { buildSequence, renderSequence } from '../../main/resources/static/js/render/sequence.js';
import { renderWire } from '../../main/resources/static/js/render/wire.js';
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
	assert.match(memory, /\+2 written by this call/);
	assert.match(memory, /2 already in memory before this call/);
	assert.ok(renderCall(first, false).includes('ChatClient'));
});

test('modular RAG: searches, funnel, documents in the prompt, folded Jev checks', () => {
	const [run] = load('modular-rag');
	const [call] = topCalls(run);

	assert.equal(call.searches.length, 4);
	const rag = renderRag(call);
	assert.match(rag, /RewriteQueryTransformer/);
	assert.match(rag, /in the prompt/);
	assert.match(rag, /jev rerank/);
	assert.match(rag, /dropped by joining \/ post-processing/);
	assert.match(renderItems(call.items, null), /\d+ Jev systemOne checks/);

	const { lanes } = buildSequence(run);
	assert.deepEqual(lanes.map((l) => l.label).filter((l) => ['Jev', 'Vector store'].includes(l)), ['Jev', 'Vector store']);
	assert.match(renderSequence(run, false), /<svg/);
});

test('tools: tool runs sit between the round-trips that requested and consumed them', () => {
	const [run] = load('tools');
	const [call] = topCalls(run);

	assert.deepEqual(call.items.map((i) => i.kind), ['wire', 'tool', 'tool', 'wire']);
	assert.ok(call.wires.every((w) => usageOf(w).input > 0));
	assert.match(renderCall(call, true), /getTemperature/);
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

test('typesafe: systemOne requests and answers', () => {
	const w = wire('typesafe', '/v1/systemone',
		{ model: 'jev-latest', state: { text: 'hi' }, questions: { jailbreak: { type: 'noul', instructions: 'x' } } },
		JSON.stringify({ model: 'jev-1', answers: { jailbreak: { type: 'noul', noul: 0.99 } }, usage: { input_tokens: 5, output_tokens: 1 } }));

	assert.deepEqual(Object.keys(normRequest(w).questions), ['jailbreak']);
	assert.match(renderWire(w), /jailbreak 0\.99/);
});

// ---------------------------------------------------------------- escaping

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
