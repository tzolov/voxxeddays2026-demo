import { esc, fmtMs, fmtNum, isOpen, oneLine } from '../util.js';

// ---------------------------------------------------------------- RAG
export function renderIngest(ev) {
	const sources = [...new Set((ev.sample || []).map((d) => d.metadata?.file_name || d.metadata?.source).filter(Boolean))];
	return `<div class="notice info" style="margin-bottom:.6rem">⤓ Ingested <b>${fmtNum(ev.count)}</b> chunks into <b>${esc(ev.store)}</b>
		${sources.length ? `from <span class="fn">${sources.map(esc).join(', ')}</span>` : ''} · ${fmtMs(ev.durationMs)}</div>`;
}

export const RAG_CONTEXT_KEYS = new Set(['rag_document_context', 'qa_retrieved_documents']);

export function ragStage(label, names) {
	const list = (Array.isArray(names) ? names : [names]).filter(Boolean);
	if (!list.length) return '';
	return `<div class="stage"><div class="stage-label">${esc(label)}</div>${list.map((n) => `<span class="chip">${esc(n)}</span>`).join('')}</div>`;
}

export function renderPipeline(rag, searches) {
	const s0 = searches[0] || {};
	const topK = rag.topK ?? s0.topK; const threshold = rag.similarityThreshold ?? s0.threshold;
	const retrieve = [rag.documentRetriever || 'retriever'].concat(topK != null ? [`topK ${topK}`] : [], threshold != null ? [`≥ ${threshold}`] : []);
	const stages = rag.kind === 'question-answer'
		? [ragStage('advisor', rag.advisor), ragStage('retrieve', retrieve), ragStage('augment', 'prompt template')]
		: [ragStage('transform', rag.queryTransformers), ragStage('expand', rag.queryExpander), ragStage('retrieve', retrieve),
			ragStage('join', rag.documentJoiner), ragStage('post-process', rag.documentPostProcessors), ragStage('augment', rag.queryAugmenter)];
	return `<div class="pipeline">${stages.filter(Boolean).join('<span class="arrow">→</span>')}</div>`;
}

export function docPills(d) {
	const m = d.metadata || {};
	return [d.score != null && `<span class="pill">similarity <b>${Number(d.score).toFixed(3)}</b></span>`,
		m['jev.rerank.score'] != null && `<span class="pill">jev rerank <b>${Number(m['jev.rerank.score']).toFixed(2)}</b></span>`,
		m['jev.classification'] && `<span class="pill ${m['jev.classification'] === 'INCLUDED' ? 'cool' : 'hot'}">jev ${esc(m['jev.classification'])}</span>`,
		(m.page_number ?? m.page) != null && `<span class="pill">page ${esc(m.page_number ?? m.page)}</span>`].filter(Boolean).join('');
}

export function renderDoc(d, cls = '') {
	return `<div class="doc ${cls}"><div class="params">${docPills(d)}</div><div class="text">${esc(d.text)}</div></div>`;
}

export function renderSearch(sr, idx) {
	const key = 'search:' + sr.searchId;
	const top = sr.results.length ? Math.max(...sr.results.map((r) => r.score || 0)) : null;
	return `<details class="wire" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary><span class="chev">▸</span>
		<span class="num">🔎</span><span class="fn">“${esc(oneLine(sr.query, 90))}”</span>
		<span class="pill">${sr.results.length} hit${sr.results.length === 1 ? '' : 's'}</span>
		${top != null ? `<span class="pill">best ${top.toFixed(3)}</span>` : ''}<span class="right-meta">${fmtMs(sr.durationMs)}</span></summary>
		<div class="pane on">${sr.results.map((r) => `<div class="search-row"><div class="bar"><span style="width:${(Math.max(0, Math.min(1, r.score || 0)) * 100).toFixed(1)}%"></span></div>
			<span class="val">${r.score != null ? Number(r.score).toFixed(3) : '–'}</span><span class="snip" title="${esc(r.text)}">${esc(oneLine(r.text, 140))}</span></div>`).join('')
			|| '<div class="notice info">no results above the threshold</div>'}</div></details>`;
}

// What retrieval did for this call: configured stages, every vector search, and which
// documents made it into the prompt (and which post-processing dropped).
export function renderRag(call) {
	const rags = call.req.rag || [];
	const searches = call.searches;
	const contextKey = rags[0]?.contextKey;
	const ctx = call.modelCalls[0]?.req.context || {};
	const finalDocs = Array.isArray(ctx[contextKey]) ? ctx[contextKey]
		: Object.entries(ctx).filter(([k, v]) => RAG_CONTEXT_KEYS.has(k) && Array.isArray(v)).map(([, v]) => v)[0];
	const retrieved = searches.flatMap((sr) => sr.results);
	const joined = new Map();
	for (const r of retrieved) if (!joined.has(r.id) || (joined.get(r.id).score || 0) < (r.score || 0)) joined.set(r.id, r);
	const finalIds = new Set((finalDocs || []).map((d) => d.id));
	const dropped = finalDocs ? [...joined.values()].filter((d) => !finalIds.has(d.id)) : [];

	let html = rags.map((r) => renderPipeline(r, searches)).join('');
	html += `<div class="funnel">
		<span><span class="n">${searches.length}</span><span class="lbl">quer${searches.length === 1 ? 'y' : 'ies'}</span></span><span class="arrow">→</span>
		<span><span class="n">${retrieved.length}</span><span class="lbl">hits</span></span><span class="arrow">→</span>
		<span><span class="n">${joined.size}</span><span class="lbl">unique</span></span><span class="arrow">→</span>
		<span><span class="n">${finalDocs ? finalDocs.length : '…'}</span><span class="lbl">in the prompt</span></span></div>`;
	html += searches.map(renderSearch).join('');
	if (rags.some((r) => r.kind === 'modular' && (r.queryTransformers?.length || r.queryExpander))) {
		html += '<div class="s1-crit" style="margin:.3rem 0">Query rewriting and expansion are LLM calls: they appear as nested ChatClient calls under “On the wire”.</div>';
	}
	if (finalDocs) {
		html += `<div class="col-title" style="margin-top:.6rem">documents in the prompt</div>${finalDocs.map((d) => renderDoc(d)).join('') || '<div class="notice warn">No documents reached the prompt.</div>'}`;
		if (dropped.length) {
			const key = 'dropped:' + call.id;
			html += `<details class="fold" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary>${dropped.length} retrieved but dropped by joining / post-processing</summary>
				${dropped.map((d) => renderDoc(d, 'dropped')).join('')}</details>`;
		}
	}
	return html;
}
