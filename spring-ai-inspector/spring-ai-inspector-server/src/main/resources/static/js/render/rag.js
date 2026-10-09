import { esc, fmtMs, isOpen, oneLine } from '../util.js';

// ---------------------------------------------------------------- RAG
/** A similarity score as shown everywhere: 0.869, or – when there is none. */
export const scoreText = (score) => (score != null ? Number(score).toFixed(3) : '–');

/** The page a document came from (e.g. a PDF reader's), if any. */
export const pageOf = (doc) => doc.metadata?.page_number ?? doc.metadata?.page;

const hitsText = (sr) => `${sr.results.length} hit${sr.results.length === 1 ? '' : 's'}`;
const bestScore = (sr) => {
	const scores = sr.results.map((r) => r.score).filter((s) => s != null);
	return scores.length ? Math.max(...scores) : null; // no best when no hit has a score
};

/** A search's hits in words, e.g. "4 hits · best 0.869". */
export function searchSummary(sr) {
	const best = bestScore(sr);
	return hitsText(sr) + (best != null ? ` · best ${scoreText(best)}` : '');
}

/** A search's outcome for a summary line: running, failed, or its hits and best score. */
export function searchStatus(sr) {
	if (sr.pending) return '<span class="spinner"></span><span class="right-meta">searching…</span>';
	if (sr.error) return `<span class="pill err" title="${esc(sr.error)}">${esc(oneLine(sr.error, 60))}</span>`;
	const best = bestScore(sr);
	return `<span class="pill">${hitsText(sr)}</span>` + (best != null ? `<span class="pill">best ${scoreText(best)}</span>` : '');
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
	return [d.score != null && `<span class="pill">similarity <b>${scoreText(d.score)}</b></span>`,
		m['jev.rerank.score'] != null && `<span class="pill">jev rerank <b>${Number(m['jev.rerank.score']).toFixed(2)}</b></span>`,
		m['jev.classification'] && `<span class="pill ${m['jev.classification'] === 'INCLUDED' ? 'cool' : 'hot'}">jev ${esc(m['jev.classification'])}</span>`,
		pageOf(d) != null && `<span class="pill">page ${esc(pageOf(d))}</span>`].filter(Boolean).join('');
}

export function renderDoc(d, cls = '') {
	return `<div class="doc ${cls}"><div class="params">${docPills(d)}</div><div class="text">${esc(d.text)}</div></div>`;
}

export function renderSearch(sr, idx) {
	const key = 'search:' + (sr.opId ?? sr.searchId); // stable from the search's start to its end
	return `<details class="wire" data-key="${esc(key)}" ${isOpen(key, false) ? 'open' : ''}><summary><span class="chev">▸</span>
		<span class="num">🔎</span><span class="fn">“${esc(oneLine(sr.query, 90))}”</span>
		${searchStatus(sr)}${sr.pending ? '' : `<span class="right-meta">${fmtMs(sr.durationMs)}</span>`}</summary>
		<div class="pane on">${sr.results.map((r) => `<div class="search-row"><div class="bar"><span style="width:${(Math.max(0, Math.min(1, r.score || 0)) * 100).toFixed(1)}%"></span></div>
			<span class="val">${scoreText(r.score)}</span><span class="snip" title="${esc(r.text)}">${esc(oneLine(r.text, 140))}</span></div>`).join('')
			|| (sr.pending ? '' : '<div class="notice info">no results above the threshold</div>')}</div></details>`;
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
