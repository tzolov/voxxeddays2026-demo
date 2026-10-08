import { parseJson } from './util.js';

// ---------------------------------------------------------------- provider adapters
// Each adapter turns a provider's wire JSON into one normalized shape, so a single renderer
// can show Anthropic, OpenAI and Ollama traffic side by side:
//   request:  { params, system: [block], tools: [{name, def}], messages: [{role, blocks, raw}] }
//   response: { stop, usage: {input, output, cacheRead, cacheWrite, reasoning}, blocks: [block], error }
//   usage.input is ALL prompt tokens, cached ones included (cacheRead is a subset of it), so models
//   compare fairly: Anthropic reports cache reads/writes separately from input_tokens, OpenAI includes
//   cached tokens in prompt_tokens. reasoning is a subset of output (OpenAI, DeepSeek).
//   block:    text | tool_use {id, name, input} | tool_result {id, name, content, isError} | thinking | media | raw

export const pick = (obj, keys) => Object.fromEntries(keys.filter((k) => obj[k] !== undefined).map((k) => [k, obj[k]]));
export const asArgs = (a) => typeof a === 'string' ? (parseJson(a) ?? a) : (a ?? {});
export const sseData = (body) => body.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim())
	.filter((d) => d && d !== '[DONE]').map(parseJson).filter(Boolean);

export const anthropicBlock = (b) => {
	switch (b.type) {
		case 'text': return { type: 'text', text: b.text, note: b.cache_control ? 'cache_control' : '' };
		case 'tool_use': case 'server_tool_use': return { type: 'tool_use', id: b.id, name: b.name, input: b.input };
		case 'tool_result': return { type: 'tool_result', id: b.tool_use_id, isError: b.is_error,
			content: typeof b.content === 'string' ? b.content : (b.content || []).map((x) => x.text ?? JSON.stringify(x)).join('\n') };
		case 'thinking': return { type: 'thinking', text: b.thinking, signed: !!b.signature };
		case 'redacted_thinking': return { type: 'thinking', text: '', redacted: true };
		case 'image': case 'document': return { type: 'media', label: b.type + ' · ' + (b.source?.media_type || b.source?.type || '') };
		default: return { type: 'raw', label: b.type, value: b };
	}
};
export const anthropicBlocks = (content) => (typeof content === 'string' ? [{ type: 'text', text: content }] : (content || [])).map(anthropicBlock);

export const openAiParts = (content) => typeof content === 'string' ? [{ type: 'text', text: content }]
	: (content || []).map((p) => p.type === 'text' ? { type: 'text', text: p.text }
		: p.type === 'refusal' ? { type: 'text', text: '⛔ refusal: ' + p.refusal }
		: p.type === 'thinking' ? { type: 'thinking', text: (p.thinking || []).map((t) => t.text ?? '').join('') } // Mistral reasoning
		: { type: 'media', label: p.type });
export const openAiToolCalls = (calls) => (calls || []).map((tc) => ({ type: 'tool_use', id: tc.id, name: tc.function?.name, input: asArgs(tc.function?.arguments) }));
export const openAiTools = (tools) => (tools || []).map((t) => ({ name: t.function?.name || t.name || t.type, def: t }));

export const ADAPTERS = {
	anthropic: {
		matches: (path) => /\/v1\/messages$/.test(path),
		request: (req) => ({
			params: pick(req, ['model', 'max_tokens', 'temperature', 'stream', 'tool_choice', 'thinking', 'output_format']),
			system: req.system ? anthropicBlocks(req.system) : null,
			tools: (req.tools || []).map((t) => ({ name: t.name || t.type, def: t })),
			messages: (req.messages || []).map((m) => ({ role: m.role, blocks: anthropicBlocks(m.content), raw: m })),
		}),
		response: (body, streamed) => {
			let m = parseJson(body);
			if (streamed || !m) {
				m = { content: [], usage: {} };
				for (const ev of sseData(body)) {
					if (ev.type === 'message_start') Object.assign(m, ev.message, { content: [] });
					else if (ev.type === 'content_block_start') m.content[ev.index] = { ...ev.content_block, _json: '' };
					else if (ev.type === 'content_block_delta' && m.content[ev.index]) {
						const b = m.content[ev.index]; const d = ev.delta;
						if (d.type === 'text_delta') b.text = (b.text || '') + d.text;
						else if (d.type === 'input_json_delta') b._json += d.partial_json;
						else if (d.type === 'thinking_delta') b.thinking = (b.thinking || '') + d.thinking;
					}
					else if (ev.type === 'content_block_stop' && m.content[ev.index]) {
						const b = m.content[ev.index];
						if (b._json) b.input = asArgs(b._json);
						delete b._json;
					}
					else if (ev.type === 'message_delta') { Object.assign(m, ev.delta); m.usage = { ...m.usage, ...ev.usage }; }
					else if (ev.type === 'error') m.error = ev.error;
				}
			}
			if (m.error) return { error: `${m.error.type}: ${m.error.message}` };
			const u = m.usage || {};
			return { stop: m.stop_reason, blocks: anthropicBlocks(m.content),
				usage: { input: u.input_tokens == null ? undefined
					: u.input_tokens + (u.cache_read_input_tokens || 0) + (u.cache_creation_input_tokens || 0),
				output: u.output_tokens, cacheRead: u.cache_read_input_tokens, cacheWrite: u.cache_creation_input_tokens } };
		},
	},

	openai: {
		matches: (path) => /\/chat\/completions$/.test(path),
		request: (req) => ({
			params: pick(req, ['model', 'max_completion_tokens', 'max_tokens', 'temperature', 'stream', 'tool_choice', 'reasoning_effort', 'response_format']),
			system: null, // OpenAI sends system / developer prompts as regular messages
			tools: openAiTools(req.tools),
			messages: (req.messages || []).map((m) => ({
				role: m.role === 'developer' ? 'system' : m.role,
				blocks: m.role === 'tool'
					? [{ type: 'tool_result', id: m.tool_call_id, content: typeof m.content === 'string' ? m.content : openAiParts(m.content).map((p) => p.text).join('\n') }]
					: [...(m.reasoning_content ? [{ type: 'thinking', text: m.reasoning_content }] : []),
						...(m.content != null ? openAiParts(m.content) : []), ...openAiToolCalls(m.tool_calls)],
				raw: m,
			})),
		}),
		response: (body, streamed) => {
			let choice; let usage; const json = parseJson(body);
			if (json && json.error) return { error: `${json.error.type || json.error.code}: ${json.error.message}` };
			if (!streamed && json) { choice = { ...json.choices?.[0]?.message, finish_reason: json.choices?.[0]?.finish_reason }; usage = json.usage; }
			else {
				choice = { content: '', reasoning_content: '', tool_calls: [] };
				for (const chunk of sseData(body)) {
					if (chunk.usage) usage = chunk.usage;
					const c = chunk.choices?.[0];
					if (!c) continue;
					if (c.finish_reason) choice.finish_reason = c.finish_reason;
					if (typeof c.delta?.content === 'string') choice.content += c.delta.content;
					else if (Array.isArray(c.delta?.content)) choice.content = [...openAiParts(choice.content), ...openAiParts(c.delta.content)];
					if (c.delta?.reasoning_content) choice.reasoning_content += c.delta.reasoning_content;
					for (const d of c.delta?.tool_calls || []) {
						const tc = choice.tool_calls[d.index] ||= { id: '', function: { name: '', arguments: '' } };
						if (d.id) tc.id = d.id;
						if (d.function?.name) tc.function.name += d.function.name;
						if (d.function?.arguments) tc.function.arguments += d.function.arguments;
					}
				}
			}
			const u = usage || {};
			return { stop: choice.finish_reason,
				blocks: [...(choice.reasoning_content ? [{ type: 'thinking', text: choice.reasoning_content }] : []),
					...(choice.content ? openAiParts(choice.content) : []),
					...(choice.refusal ? [{ type: 'text', text: '⛔ refusal: ' + choice.refusal }] : []), ...openAiToolCalls(choice.tool_calls)],
				usage: { input: u.prompt_tokens, output: u.completion_tokens,
					cacheRead: u.prompt_tokens_details?.cached_tokens ?? u.prompt_cache_hit_tokens,
					reasoning: u.completion_tokens_details?.reasoning_tokens } };
		},
	},

	ollama: {
		matches: (path) => /\/api\/(chat|generate)$/.test(path),
		request: (req) => ({
			params: pick(req, ['model', 'stream', 'format', 'think', 'options', 'keep_alive']),
			system: req.system ? [{ type: 'text', text: req.system }] : null,
			tools: openAiTools(req.tools),
			messages: req.prompt != null ? [{ role: 'user', blocks: [{ type: 'text', text: req.prompt }], raw: req.prompt }]
				: (req.messages || []).map((m) => ({
					role: m.role,
					blocks: m.role === 'tool' ? [{ type: 'tool_result', name: m.tool_name, content: m.content }]
						: [...(m.thinking ? [{ type: 'thinking', text: m.thinking }] : []), ...(m.content ? [{ type: 'text', text: m.content }] : []),
							...(m.images || []).map(() => ({ type: 'media', label: 'image' })), ...openAiToolCalls(m.tool_calls)],
					raw: m,
				})),
		}),
		response: (body) => {
			// Non-streaming: one JSON object. Streaming: newline-delimited JSON objects.
			const lines = body.split('\n').map((l) => parseJson(l.trim())).filter(Boolean);
			if (lines[0]?.error) return { error: lines[0].error };
			const last = lines[lines.length - 1] || {};
			let text = ''; let thinking = ''; const calls = [];
			for (const l of lines) {
				text += l.message?.content ?? l.response ?? '';
				thinking += l.message?.thinking ?? '';
				calls.push(...(l.message?.tool_calls || []));
			}
			return { stop: last.done_reason,
				blocks: [...(thinking ? [{ type: 'thinking', text: thinking }] : []), ...(text ? [{ type: 'text', text }] : []), ...openAiToolCalls(calls)],
				usage: { input: last.prompt_eval_count, output: last.eval_count } };
		},
	},
};

// Mistral and DeepSeek speak the OpenAI Chat Completions format (plus the reasoning extras above).
ADAPTERS.mistralai = ADAPTERS.openai;
ADAPTERS.deepseek = ADAPTERS.openai;

// TypeSafe Jev systemOne: a JSON `state` plus named questions (noul | score | choice), answered with probabilities.
ADAPTERS.typesafe = {
	kind: 'systemone',
	matches: (path) => /\/v1\/systemone$/.test(path),
	request: (req) => ({ params: pick(req, ['model']), state: req.state, questions: req.questions || {} }),
	response: (body) => {
		const json = parseJson(body);
		if (!json) return null;
		if (!json.answers) return { error: json.message || json.error || JSON.stringify(json) };
		const u = json.usage || {};
		return { model: json.model, answers: json.answers, usage: { input: u.input_tokens, output: u.output_tokens } };
	},
};

// Who served a round-trip, from the URL the proxy forwarded it to: e.g. `ollama` for TypeSafe
// pointed at a local Ollama.
export function servedBy(wire) {
	const url = wire.req.url || '';
	if (/:11434\b|ollama/i.test(url)) return 'ollama';
	if (/typesafe\.ai/i.test(url)) return 'typesafe';
	try { return new URL(url).hostname || wire.req.provider; } catch { return wire.req.provider; }
}

// The provider shown for a round-trip. systemOne is shown by protocol and who served it, since
// Jev is also served by e.g. a local Ollama; the key stays `typesafe` (proxy path, upstream
// property, exported runs).
export function providerLabel(wire) {
	return wire.req.provider === 'typesafe' ? `${servedBy(wire)} · system-one` : wire.req.provider;
}

// Embedding requests (e.g. ingesting documents for RAG): OpenAI-compatible /embeddings (OpenAI,
// Mistral) and Ollama's /api/embed (/api/embeddings before). Normalized to the inputs sent, the
// vectors returned and the prompt tokens they cost.
export const EMBEDDINGS = {
	kind: 'embedding',
	providers: new Set(['openai', 'mistralai', 'ollama']),
	matches: (path) => /\/(embeddings|api\/embed)$/.test(path),
	request: (req) => {
		const input = req.input ?? req.prompt;
		// An array of token ids is one input (OpenAI accepts pre-tokenized text).
		const tokens = Array.isArray(input) && input.length > 0 && input.every((t) => typeof t === 'number');
		const inputs = input == null ? [] : Array.isArray(input) && !tokens ? input : [input];
		return { params: pick(req, ['model', 'dimensions', 'encoding_format']), inputs, system: null, tools: [], messages: [] };
	},
	response: (body) => {
		const json = parseJson(body);
		if (!json) return null;
		const err = json.error ?? (json.object === 'error' ? json : null); // OpenAI / Ollama, Mistral
		if (err) return { error: typeof err === 'string' ? err : [err.type || err.code, err.message].filter(Boolean).join(': ') || JSON.stringify(err) };
		const vectors = json.data || json.embeddings || (json.embedding ? [json.embedding] : []);
		const first = vectors[0]?.embedding ?? vectors[0];
		const u = json.usage || {};
		return { model: json.model, vectors: vectors.length, dimensions: Array.isArray(first) ? first.length : undefined, blocks: [],
			usage: { input: u.prompt_tokens ?? u.total_tokens ?? json.prompt_eval_count, output: 0 } };
	},
};

export function adapterOf(wire) {
	const a = ADAPTERS[wire.req.provider];
	if (a && a.matches(wire.req.path)) return a;
	return EMBEDDINGS.providers.has(wire.req.provider) && EMBEDDINGS.matches(wire.req.path) ? EMBEDDINGS : null;
}

export function normRequest(wire) {
	if (wire._nreq === undefined) {
		const a = adapterOf(wire); const req = parseJson(wire.req.body);
		wire._nreq = a && req ? a.request(req) : null;
	}
	return wire._nreq;
}

export function normResponse(wire) {
	if (!wire.resp) return null;
	if (wire._nresp === undefined) {
		const a = adapterOf(wire);
		if (wire.resp.error) wire._nresp = { error: wire.resp.error };
		else if (!a || wire.resp.body == null) wire._nresp = null;
		else {
			const ct = String((wire.resp.headers && (wire.resp.headers['content-type'] || wire.resp.headers['Content-Type'])) || '');
			try { wire._nresp = a.response(wire.resp.body, ct.includes('event-stream')); }
			catch (e) { wire._nresp = null; }
		}
	}
	return wire._nresp;
}

export function usageOf(wire) {
	const r = normResponse(wire);
	return r && r.usage ? r.usage : null;
}
