import { esc, fmtMs, highlightJson, isOpen, oneLine, parseJson, recordedTs } from '../util.js';

// ---------------------------------------------------------------- MCP messages
// JSON-RPC messages of the app's MCP client connections, reported by the starter's transport
// wrapper: `out` is client to server, `in` server to client. The starter attributes messages
// to the MCP tool run they belong to (see model.js); the rest (initialize, tools/list, ...)
// belong to the connection.

// Parsed payloads, kept off the events so exports stay as recorded.
const parsed = new WeakMap();

/** What an MCP tool's badge and sequence lane are called: its connection, else its server. */
export const mcpName = (mcp) => mcp.connection || mcp.server || 'MCP';

/** The JSON-RPC message, parsed once (older recordings carry it as a string). */
export function payloadOf(m) {
	if (!parsed.has(m)) parsed.set(m, (typeof m.payload === 'string' ? parseJson(m.payload) : m.payload) ?? null);
	return parsed.get(m);
}

/** Requests by direction and id, to pair each response with the request it answers. */
export function requestIndex(msgs) {
	const index = new Map();
	for (const m of msgs) if (m.kind === 'request') index.set(`${m.direction}|${m.id}`, m);
	return index;
}

/** The request a response answers: same id, sent the other way. */
export const requestOf = (index, response) => index.get(`${response.direction === 'in' ? 'out' : 'in'}|${response.id}`);

const text = (v) => (typeof v === 'string' ? v : JSON.stringify(v ?? ''));

/** A short, human summary of a message, e.g. a log line or the tools a server lists. */
export function mcpSummary(m) {
	const p = payloadOf(m)?.params; const r = payloadOf(m)?.result;
	if (m.kind === 'response') {
		if (m.error) return '⚠ ' + m.error;
		switch (m.method) {
			case 'initialize': return [r?.serverInfo?.name, r?.serverInfo?.version, r?.protocolVersion].filter(Boolean).join(' · ');
			case 'tools/list': return (r?.tools || []).map((t) => t.name).join(', ');
			case 'tools/call': return `${r?.isError ? '⚠ ' : ''}${oneLine((r?.content || []).map((c) => c.text ?? c.type).join(' '), 90)}`;
			case 'sampling/createMessage': return oneLine(r?.content?.text ?? '', 90);
			default: return '';
		}
	}
	switch (m.method) {
		case 'notifications/message': return `${p?.level ?? 'log'}: ${oneLine(text(p?.data), 90)}`;
		case 'notifications/progress': return `${p?.progress ?? ''}${p?.total != null ? '/' + p.total : ''} ${oneLine(p?.message ?? '', 60)}`;
		case 'tools/call': return p?.name ?? '';
		case 'sampling/createMessage': return oneLine((p?.messages || []).map((x) => x.content?.text ?? '').join(' '), 90);
		case 'initialize': return [p?.clientInfo?.name, p?.protocolVersion].filter(Boolean).join(' · ');
		default: return '';
	}
}

/** What a message is: the method, a response marked as answering it. */
export const mcpLabel = (m) => (m.kind === 'response' ? `${m.method ?? 'response'} ✓` : m.method);

export function renderMcpMessages(msgs) {
	const index = requestIndex(msgs);
	return `<div class="mcp-msgs">${msgs.map((m) => {
		const req = m.kind === 'response' ? requestOf(index, m) : null;
		const key = 'mcp:' + m.seq;
		return `<details class="fold mcp-msg ${esc(m.kind)}${m.error ? ' err' : ''}" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary>
			<span class="mcp-dir" title="${m.direction === 'out' ? 'client to server' : 'server to client'}">${m.direction === 'out' ? '→ server' : '← server'}</span>
			<b>${esc(mcpLabel(m))}</b>${m.id != null ? `<span class="muted">#${esc(m.id)}</span>` : ''}
			<span class="mcp-sum">${esc(mcpSummary(m))}</span>
			${req ? `<span class="right-meta">${fmtMs(recordedTs(m) - recordedTs(req))}</span>` : ''}</summary>
			<pre class="json">${payloadOf(m) ? highlightJson(payloadOf(m)) : esc(m.payload ?? '')}</pre></details>`;
	}).join('')}</div>`;
}

/** Per connection: the server it talks to and its tools, from initialize and tools/list. */
export function mcpConnectionInfo(msgs) {
	const info = {};
	for (const m of msgs) {
		if (m.kind !== 'response' || m.error) continue;
		const r = payloadOf(m)?.result;
		if (m.method === 'initialize') Object.assign(info, { server: r?.serverInfo?.name, version: r?.serverInfo?.version, protocol: r?.protocolVersion });
		if (m.method === 'tools/list') info.tools = (r?.tools || []).map((t) => t.name);
	}
	return info;
}

export function renderMcpPanel(run) {
	if (!run.mcp?.size) return '';
	const key = 'mcp-panel:' + run.id;
	const conns = [...run.mcp.entries()].map(([name, msgs]) => {
		const info = mcpConnectionInfo(msgs);
		const ck = key + ':' + name;
		return `<div class="mem-head"><b>${esc(name)}</b>
			${info.server ? `<span class="pill mcp">${esc(info.server)}${info.version ? ' ' + esc(info.version) : ''}</span>` : ''}
			${info.protocol ? `<span class="pill">protocol ${esc(info.protocol)}</span>` : ''}
			${info.tools ? `<span class="pill" title="${esc(info.tools.join(', '))}">${info.tools.length} tool${info.tools.length === 1 ? '' : 's'}</span>` : ''}</div>
			<details class="fold" data-key="${esc(ck)}" ${isOpen(ck, false) ? 'open' : ''}><summary>${msgs.length} connection message${msgs.length === 1 ? '' : 's'} (outside tool runs)</summary>${renderMcpMessages(msgs)}</details>`;
	}).join('');
	return `<details class="fold mcp-panel" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}>
		<summary>MCP connections · ${[...run.mcp.keys()].map(esc).join(', ')}</summary><div class="mem-store">${conns}</div></details>`;
}
