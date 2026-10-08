import { esc, fmtMs, fmtNum, isOpen } from '../util.js';
import { renderAnswerMessage, renderSpringMessage } from './messages.js';
import { state } from '../state.js';

// ---------------------------------------------------------------- model calls without HTTP
// A model running in the JVM (e.g. jinfer) makes no HTTP calls, and a provider not routed
// through the inspector's proxy leaves none on record: no wire round-trips. Such calls are
// shown from what the advisor right before the model saw (model-request / model-response):
// the prompt Spring AI sent, the response, the model and the usage it reported.

const NO_HTTP = 'Seen by the advisor in front of the model: no HTTP traffic was recorded for this call '
	+ '(a model running in the JVM, or a provider not routed through the inspector).';

/**
 * True when no HTTP round-trip was recorded for this model call: the proxy stamps each with its
 * model call, and a provider routed through the proxy has its calls on the wire even when
 * overlapping calls left one unstamped.
 */
export const isAdvisorOnly = (mc) => !mc.run?.wiredModelCalls?.has(mc.id) && !mc.run?.routed?.has(providerOf(mc));

/** What a model call came back with: the tools it asked for, else its finish reason. */
export function modelCallOutcome(mc) {
	const gens = mc.resp?.generations || [];
	return { toolCalls: gens.flatMap((g) => g.toolCalls || []), finish: gens.map((g) => g.finishReason).find(Boolean) };
}

/** The model that answered, else the one asked for. */
export const modelOf = (mc) => mc.resp?.model || mc.req.options?.model || 'model';

/** The provider, from Spring AI's options type: JinferChatOptions -> jinfer. */
export const providerOf = (mc) => (mc.req.options?.type || '').replace(/(Chat)?Options$/, '').toLowerCase() || 'model';

/** As the tokens panel and the sequence lanes key round-trips: `provider|model`. */
export const modelCallKey = (mc) => `${providerOf(mc)}|${modelOf(mc)}`;

export function usageOfModelCall(mc) {
	const u = mc.resp?.usage;
	return u && (u.input != null || u.output != null) ? u : null;
}

/** The run's model calls with no HTTP round-trip on record. */
export const advisorOnlyCalls = (run) => [...run.modelCalls.values()].filter(isAdvisorOnly);

export function renderModelCall(mc) {
	const key = 'model:' + mc.id;
	const msgs = mc.req.messages || []; const tools = mc.req.options?.tools || [];
	const gens = mc.resp?.generations || [];
	const { toolCalls, finish } = modelCallOutcome(mc);
	const u = usageOfModelCall(mc);
	let summary = `<span class="chev">▸</span><span class="num">#${mc.num}</span>
		<span class="pill" title="${esc(NO_HTTP)}">${esc(providerOf(mc))}</span><span class="path" title="${esc(NO_HTTP)}">ChatModel call · no HTTP</span>
		<span class="pill">${esc(modelOf(mc))}</span><span class="pill">${msgs.length} msg${msgs.length === 1 ? '' : 's'}</span>
		${tools.length ? `<span class="pill">${tools.length} tools</span>` : ''}<span class="arrow">→</span>`;
	if (!mc.resp) summary += '<span class="spinner"></span><span class="right-meta">waiting for the model…</span>';
	else if (mc.resp.error) summary += `<span class="pill err">${esc(mc.resp.error)}</span>`;
	else {
		if (toolCalls.length) summary += toolCalls.map((t) => `<span class="pill stop-tool_use">⚙ ${esc(t.name)}</span>`).join('');
		else if (finish) summary += `<span class="pill">${esc(finish)}</span>`;
		if (u) summary += `<span class="right-meta">${fmtNum(u.input)} in · ${fmtNum(u.output)} out</span>`;
		summary += `<span class="right-meta">${fmtMs(mc.resp.durationMs)}</span>`;
	}
	const request = `<div class="msgs">${msgs.map((m) => renderSpringMessage(m)).join('')}</div>
		${tools.length ? `<div class="chain" style="margin-top:.4rem">${tools.map((t) => `<span class="chip" title="${esc(t.description ?? '')}">⚙ ${esc(t.name)}</span>`).join('')}</div>` : ''}`;
	const response = !mc.resp ? '<div class="notice info"><span class="spinner"></span> waiting…</div>'
		: mc.resp.error ? `<div class="notice err">${esc(mc.resp.error)}</div>`
		: `<div class="msgs">${gens.map((g, i) => (g.thinking ? renderAnswerMessage(g, `${key}:${i}`) : renderSpringMessage(g))).join('')}</div>`;
	const body = `<div class="pane on"><div class="notice info" style="margin-bottom:.5rem">${esc(NO_HTTP)}</div><div class="cols">
		<div><div class="col-title">→ request to ${esc(modelOf(mc))}</div>${request}</div>
		<div><div class="col-title">← response</div>${response}</div></div></div>`;
	const hl = state.highlight && state.highlight === modelCallKey(mc) ? ' hl' : '';
	return `<details class="wire${hl}" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary>${summary}</summary>${body}</details>`;
}
