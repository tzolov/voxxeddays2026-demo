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

// ---------------------------------------------------------------- media
// The inspector replaces inline base64 (images, audio, documents) by a `<base64 N chars[ TYPE][ blob:ID]>`
// marker and keeps the bytes as a blob when it can; binary bodies and multipart file parts carry a blobId
// too. A media value in the normalized shape: { blobId, type, size } (kept), { url } (a link the provider
// sent), or { chars, type } (stripped, not kept: e.g. an imported recording).
export const blobMarker = (s) => {
	const m = typeof s === 'string' && /^<base64 (\d+) chars(?: ([\w.+-]+\/[\w.+-]+))?(?: blob:([a-z0-9]+))?>$/.exec(s);
	return m ? { chars: Number(m[1]), type: m[2] || '', blobId: m[3] || null } : null;
};
export const mediaOf = (value, type) => {
	const marker = blobMarker(value);
	if (marker) return { ...marker, type: marker.type || type || '' };
	if (typeof value === 'string' && /^https?:\/\//.test(value)) return { url: value, type: type || '' };
	return null;
};

export const anthropicBlock = (b) => {
	switch (b.type) {
		case 'text': return { type: 'text', text: b.text, note: b.cache_control ? 'cache_control' : '' };
		case 'tool_use': case 'server_tool_use': return { type: 'tool_use', id: b.id, name: b.name, input: b.input };
		case 'tool_result': { // text, and images or documents a tool returned (e.g. an MCP image tool)
			const parts = typeof b.content === 'string' ? [{ type: 'text', text: b.content }] : (b.content || []);
			const isMedia = (x) => x.type === 'image' || x.type === 'document';
			return { type: 'tool_result', id: b.tool_use_id, isError: b.is_error,
				content: parts.map((x) => isMedia(x) ? `[${x.type}]` : x.text ?? JSON.stringify(x)).join('\n'),
				media: parts.filter(isMedia).map((x) => mediaOf(x.source?.data ?? x.source?.url, x.source?.media_type)).filter(Boolean) };
		}
		case 'thinking': return { type: 'thinking', text: b.thinking, signed: !!b.signature };
		case 'redacted_thinking': return { type: 'thinking', text: '', redacted: true };
		case 'image': case 'document': return { type: 'media', label: b.type + ' · ' + (b.source?.media_type || b.source?.type || ''),
			media: mediaOf(b.source?.data ?? b.source?.url, b.source?.media_type) };
		default: return { type: 'raw', label: b.type, value: b };
	}
};
export const anthropicBlocks = (content) => (typeof content === 'string' ? [{ type: 'text', text: content }] : (content || [])).map(anthropicBlock);

export const openAiParts = (content) => typeof content === 'string' ? [{ type: 'text', text: content }]
	: (content || []).map((p) => p.type === 'text' ? { type: 'text', text: p.text }
		: p.type === 'refusal' ? { type: 'text', text: '⛔ refusal: ' + p.refusal }
		: p.type === 'thinking' ? { type: 'thinking', text: (p.thinking || []).map((t) => t.text ?? '').join('') } // Mistral reasoning
		: p.type === 'image_url' ? { type: 'media', label: 'image_url', media: mediaOf(p.image_url?.url) }
		: p.type === 'input_audio' ? { type: 'media', label: 'input_audio · ' + (p.input_audio?.format || ''), media: mediaOf(p.input_audio?.data, p.input_audio?.format ? 'audio/' + p.input_audio.format : '') }
		: p.type === 'file' ? { type: 'media', label: 'file · ' + (p.file?.filename || ''), media: mediaOf(p.file?.file_data) }
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
			// Audio out (modalities: ["text", "audio"]): the clip (kept by the inspector) and its transcript.
			const audio = choice.audio && (choice.audio.data || choice.audio.transcript)
				? [...(choice.audio.transcript ? [{ type: 'text', text: choice.audio.transcript }] : []),
					{ type: 'media', label: 'audio' + (choice.audio.id ? ' · ' + choice.audio.id : ''), media: mediaOf(choice.audio.data, 'audio/wav') }] : [];
			return { stop: choice.finish_reason,
				blocks: [...(choice.reasoning_content ? [{ type: 'thinking', text: choice.reasoning_content }] : []),
					...(choice.content ? openAiParts(choice.content) : []), ...audio,
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
							...(m.images || []).map((img) => ({ type: 'media', label: 'image', media: mediaOf(img) })), ...openAiToolCalls(m.tool_calls)],
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

// OpenAI's Responses API (spring.ai.openai.chat.api=responses): a flat list of typed items instead
// of messages. A turn's reasoning, text and function calls are separate items, merged here into one
// assistant message; a tool result is a `function_call_output` item, named after its call.
const responsesText = (content) => typeof content === 'string' ? [{ type: 'text', text: content }]
	: (content || []).map((p) => /^(input|output)_text$/.test(p.type) ? { type: 'text', text: p.text }
		: p.type === 'refusal' ? { type: 'text', text: '⛔ refusal: ' + p.refusal }
		: p.type === 'input_image' ? { type: 'media', label: p.type, media: mediaOf(p.image_url) }
		: { type: 'media', label: p.type });
const responsesItem = (it, names = {}) => {
	switch (it.type) {
		case 'message': case undefined: return responsesText(it.content);
		case 'function_call': case 'custom_tool_call':
			return [{ type: 'tool_use', id: it.call_id, name: it.name, input: asArgs(it.arguments ?? it.input) }];
		case 'function_call_output': case 'custom_tool_call_output':
			return [{ type: 'tool_result', id: it.call_id, name: names[it.call_id],
				content: typeof it.output === 'string' ? it.output : responsesText(it.output).map((p) => p.text ?? p.label).join('\n') }];
		case 'image_generation_call': // the hosted image tool: its result is the image (kept by the inspector)
			return [{ type: 'media', label: 'image_generation_call' + (it.revised_prompt ? ' · ' + it.revised_prompt : ''), media: mediaOf(it.result) }];
		case 'reasoning': {
			const text = [...(it.summary || []), ...(it.content || [])].map((s) => s.text ?? '').join('\n');
			// Unless a summary was asked for, only the item (or its encrypted form) comes back.
			return [{ type: 'thinking', text, redacted: !text && !!it.encrypted_content, hidden: !text && !it.encrypted_content }];
		}
		default: return [{ type: 'raw', label: it.type, value: it }];
	}
};
const responsesRole = (it) => it.type === 'function_call_output' || it.type === 'custom_tool_call_output' ? 'tool'
	: it.type && it.type !== 'message' ? 'assistant' // function calls, reasoning, built-in tool calls
	: it.role === 'developer' ? 'system' : it.role;

export const RESPONSES = {
	matches: (path) => /\/v1\/responses$/.test(path),
	request: (req) => {
		const items = typeof req.input === 'string' ? [{ role: 'user', content: req.input }] : (req.input || []);
		const names = Object.fromEntries(items.filter((it) => it.call_id && it.name).map((it) => [it.call_id, it.name]));
		const messages = [];
		for (const it of items) {
			if (it.type === 'item_reference') continue; // a stored item, by id: its content isn't in the request
			const role = responsesRole(it); const last = messages[messages.length - 1];
			if (role === 'assistant' && last?.role === 'assistant') { last.blocks.push(...responsesItem(it, names)); last.raw.push(it); }
			else messages.push({ role, blocks: responsesItem(it, names), raw: [it] });
		}
		return {
			params: pick(req, ['model', 'max_output_tokens', 'temperature', 'stream', 'tool_choice', 'reasoning', 'text', 'store', 'previous_response_id']),
			system: req.instructions ? [{ type: 'text', text: req.instructions }] : null,
			tools: openAiTools(req.tools),
			messages,
		};
	},
	response: (body, streamed) => {
		let r = parseJson(body);
		if (streamed || !r) {
			// The last lifecycle event carries the whole response; items done so far if the stream was cut.
			const events = sseData(body);
			const err = events.find((e) => e.type === 'error');
			if (err) return { error: `${err.code || 'error'}: ${err.message}` };
			const final = events.filter((e) => /^response\.(completed|incomplete|failed)$/.test(e.type)).pop();
			r = final?.response ?? { status: 'in_progress', output: events.filter((e) => e.type === 'response.output_item.done').map((e) => e.item) };
		}
		if (r.error) return { error: `${r.error.type || r.error.code}: ${r.error.message}` };
		const output = r.output || [];
		const u = r.usage || {};
		return {
			stop: output.some((it) => it.type === 'function_call' || it.type === 'custom_tool_call') && r.status === 'completed' ? 'tool_calls'
				: r.status === 'incomplete' ? `incomplete: ${r.incomplete_details?.reason ?? '?'}` : r.status,
			blocks: output.flatMap((it) => responsesItem(it)),
			usage: { input: u.input_tokens, output: u.output_tokens, cacheRead: u.input_tokens_details?.cached_tokens,
				reasoning: u.output_tokens_details?.reasoning_tokens },
		};
	},
};

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

// Image generation (OpenAI-style /v1/images/*): the prompt and parameters in, the images out (a URL,
// or base64 the inspector kept as a blob). /edits and /variations are multipart: fields and file parts.
export const IMAGES = {
	kind: 'image',
	matches: (path) => /\/v1\/images\/(generations|edits|variations)$/.test(path),
	request: (req) => {
		const f = req.fields ?? req;
		return { params: pick(f, ['model', 'n', 'size', 'quality', 'style', 'response_format', 'background', 'output_format']),
			prompt: f.prompt, files: req.files || [], system: null, tools: [], messages: [] };
	},
	response: (body) => {
		const json = parseJson(body);
		if (!json) return null;
		if (json.error) return { error: `${json.error.type || json.error.code || 'error'}: ${json.error.message}` };
		const u = json.usage || {};
		return { images: (json.data || []).map((d) => ({ media: mediaOf(d.b64_json ?? d.url), revisedPrompt: d.revised_prompt })),
			blocks: [], usage: u.input_tokens != null ? { input: u.input_tokens, output: u.output_tokens } : null };
	},
};

// Text to speech (/v1/audio/speech): the text and voice in, audio bytes out (kept as a blob).
export const SPEECH = {
	kind: 'speech',
	matches: (path) => /\/v1\/audio\/speech$/.test(path),
	request: (req) => ({ params: pick(req, ['model', 'voice', 'response_format', 'speed', 'instructions']), text: req.input,
		system: null, tools: [], messages: [] }),
	binary: (resp) => ({ audio: { blobId: resp.blobId || null, type: resp.contentType || '', size: resp.size }, blocks: [], usage: null }),
	response: (body) => {
		const json = parseJson(body);
		return json?.error ? { error: `${json.error.type || json.error.code || 'error'}: ${json.error.message}` } : { blocks: [], usage: null };
	},
};

// Transcription and translation (/v1/audio/transcriptions, /translations): a multipart request with the
// audio file and the parameters; JSON (text, language, duration, segments) or plain text out.
export const TRANSCRIPTIONS = {
	kind: 'transcription',
	matches: (path) => /\/v1\/audio\/(transcriptions|translations)$/.test(path),
	request: (req) => {
		const f = req.fields ?? req;
		return { params: pick(f, ['model', 'language', 'response_format', 'temperature', 'prompt', 'timestamp_granularities[]']),
			files: req.files || [], system: null, tools: [], messages: [] };
	},
	response: (body) => {
		const json = parseJson(body);
		if (json?.error) return { error: `${json.error.type || json.error.code || 'error'}: ${json.error.message}` };
		if (json && typeof json === 'object') {
			const u = json.usage || {};
			return { text: json.text ?? '', language: json.language, duration: json.duration, segments: json.segments?.length, blocks: [],
				usage: u.input_tokens != null ? { input: u.input_tokens, output: u.output_tokens ?? 0 } : u.seconds != null ? { input: 0, output: 0, seconds: u.seconds } : null };
		}
		return { text: body ?? '', blocks: [], usage: null }; // text, srt, vtt
	},
};

// Moderation (/v1/moderations): the inputs in, per input whether it was flagged and the category scores.
export const MODERATIONS = {
	kind: 'moderation',
	matches: (path) => /\/v1\/moderations$/.test(path),
	request: (req) => ({ params: pick(req, ['model']),
		inputs: (Array.isArray(req.input) ? req.input : [req.input]).map((i) => typeof i === 'string' ? { type: 'text', text: i }
			: i?.type === 'image_url' ? { type: 'media', label: 'image_url', media: mediaOf(i.image_url?.url) } : { type: 'text', text: i?.text ?? JSON.stringify(i) }),
		system: null, tools: [], messages: [] }),
	response: (body) => {
		const json = parseJson(body);
		if (!json) return null;
		if (json.error) return { error: `${json.error.type || json.error.code || 'error'}: ${json.error.message}` };
		return { model: json.model, blocks: [], usage: null,
			results: (json.results || []).map((r) => ({ flagged: !!r.flagged,
				flaggedCategories: Object.entries(r.categories || {}).filter(([, v]) => v).map(([k]) => k),
				scores: r.category_scores || {} })) };
	},
};

/** The adapters recognized by the API's path, whatever provider is routed. */
export const BY_PATH = [IMAGES, SPEECH, TRANSCRIPTIONS, MODERATIONS];

/** How many texts an embedding request embeds (an in-process one only carries a sample of them). */
export const inputCount = (nreq) => nreq?.total ?? nreq?.inputs.length;

export function adapterOf(wire) {
	const { provider, path } = wire.req;
	const a = ADAPTERS[provider];
	if (a && a.matches(path)) return a;
	if (EMBEDDINGS.matches(path) && (EMBEDDINGS.providers.has(provider) || !a)) return EMBEDDINGS;
	// OpenAI's Responses API, also served by OpenAI-compatible providers next to Chat Completions.
	if (RESPONSES.matches(path)) return RESPONSES;
	// Images, speech, transcription, moderation: by the API's path, whoever serves it.
	const special = BY_PATH.find((x) => x.matches(path));
	if (special) return special;
	// A provider routed by a name of its own (spring.ai.inspector.proxy.<name>): recognized by its API's path.
	return a ? null : [...new Set(Object.values(ADAPTERS))].find((x) => x.matches(path)) ?? null;
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
		else if (a && wire.resp.bodyKind === 'binary') wire._nresp = a.binary ? a.binary(wire.resp) : null; // e.g. audio out
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
