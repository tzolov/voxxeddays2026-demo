// Entry point: the only module that touches the DOM when it loads. Everything else is
// importable without a browser (see src/test/js).
import { exportRun, importFiles } from './io.js';
import { affectsSelected, handle } from './model.js';
import { render, syncSequenceHead } from './render/page.js';
import { replay, startReplay, stepReplay, stopReplay, togglePause } from './replay.js';
import { apiHeaders, apiUrl, pref, savePref, state, touchUi } from './state.js';

// ---------------------------------------------------------------- deep links
// #run=<runId>&view=cards|sequence&scale=ordered|scaled selects a run and view, e.g. to
// bookmark a recording for a talk. Selecting a run or view keeps the hash up to date.
const link = new URLSearchParams(location.hash.slice(1));
let linkedRun = link.get('run');
if (link.get('view')) savePref('view', link.get('view'));
if (link.get('scale')) savePref('seqScale', link.get('scale'));
if (linkedRun) state.follow = false; // stay on the linked run
if (link.has('token')) { link.delete('token'); history.replaceState(null, '', '#' + link); } // read by state.js, not kept in the address bar

function updateHash() {
	const params = new URLSearchParams();
	if (state.selected) params.set('run', state.selected);
	params.set('view', pref('view', 'cards'));
	if (pref('view', 'cards') === 'sequence') params.set('scale', pref('seqScale', 'ordered'));
	history.replaceState(null, '', '#' + params);
}

function selectLinkedRun() {
	if (linkedRun && state.runs.has(linkedRun)) {
		state.selected = linkedRun;
		linkedRun = null;
	}
}

// ---------------------------------------------------------------- interaction
document.addEventListener('toggle', (e) => {
	const key = e.target.dataset && e.target.dataset.key;
	if (key) { state.open.set(key, e.target.open); touchUi(); }
	// A lazy fold renders its content only when open: render it now. Details rendered open also
	// fire toggle, so only when the content is still missing (just the summary), or it would loop.
	if (key && e.target.open && 'lazy' in e.target.dataset && e.target.children.length === 1) render();
}, true);
// Scroll events don't bubble: listen in the capture phase for the sequence diagram's sideways scroll.
document.addEventListener('scroll', (e) => {
	if (e.target.classList?.contains('seq-wrap')) syncSequenceHead(e.target);
}, true);

document.addEventListener('click', (e) => {
	const runBtn = e.target.closest('[data-run]');
	if (runBtn) { state.selected = runBtn.dataset.run; updateHash(); window.scrollTo(0, 0); render(); return; }
	const tabBtn = e.target.closest('[data-tab]');
	if (tabBtn) {
		state.tabs.set(tabBtn.dataset.wire, tabBtn.dataset.tab); touchUi();
		const wire = tabBtn.closest('.wire');
		wire.querySelectorAll(':scope > .tabs .tab').forEach((t) => t.classList.toggle('on', t === tabBtn));
		wire.querySelectorAll(':scope > .pane').forEach((p) => p.classList.toggle('on', p.dataset.pane === tabBtn.dataset.tab));
		return;
	}
	const scaleBtn = e.target.closest('[data-scale]');
	if (scaleBtn) {
		const scale = Math.min(2, Math.max(0.7, parseFloat(pref('scale', '1')) + 0.1 * Number(scaleBtn.dataset.scale)));
		savePref('scale', scale.toFixed(1));
		applyPrefs();
	}
});

document.getElementById('follow').addEventListener('click', () => {
	state.follow = !state.follow;
	savePref('follow', String(state.follow));
	applyPrefs();
});
document.getElementById('theme').addEventListener('click', () => {
	const dark = document.documentElement.dataset.theme
		? document.documentElement.dataset.theme === 'dark'
		: matchMedia('(prefers-color-scheme: dark)').matches;
	savePref('theme', dark ? 'light' : 'dark');
	applyPrefs();
});
document.getElementById('sidebar-toggle').addEventListener('click', () => {
	savePref('sidebar', pref('sidebar', 'open') === 'open' ? 'closed' : 'open');
	applyPrefs();
});
document.getElementById('palette').addEventListener('click', () => {
	savePref('palette', pref('palette', '') === 'spring' ? '' : 'spring');
	applyPrefs();
});
document.getElementById('clear').addEventListener('click', () => fetch('api/events', { method: 'DELETE', headers: apiHeaders() }));

function applyPrefs() {
	document.documentElement.style.setProperty('--scale', pref('scale', '1'));
	const theme = pref('theme', '');
	if (theme) document.documentElement.dataset.theme = theme; else delete document.documentElement.dataset.theme;
	// An optional color scheme next to light / dark: the Spring look (spring.io colors, Spring AI logo).
	const palette = pref('palette', '');
	if (palette) document.documentElement.dataset.palette = palette; else delete document.documentElement.dataset.palette;
	document.getElementById('palette').classList.toggle('on', palette === 'spring');
	document.getElementById('follow').classList.toggle('on', state.follow);
	const sidebarOpen = pref('sidebar', 'open') === 'open';
	document.querySelector('.layout').classList.toggle('collapsed', !sidebarOpen);
	document.getElementById('sidebar-toggle').setAttribute('aria-expanded', String(sidebarOpen));
}

document.addEventListener('keydown', (e) => {
	if (!replay || e.target.closest('input, select, textarea')) return;
	if (e.key === 'ArrowRight' || e.key === 'n') { e.preventDefault(); stepReplay(); }
	else if (e.key === 'p') togglePause();
	else if (e.key === 'Escape') stopReplay();
});

document.addEventListener('click', (e) => {
	const msg = e.target.closest('[data-goto]');
	if (msg) {
		const path = JSON.parse(msg.dataset.goto);
		path.forEach((k) => state.open.set(k, true)); touchUi();
		savePref('view', 'cards');
		render();
		requestAnimationFrame(() => requestAnimationFrame(() => {
			const el = document.querySelector(`[data-key="${CSS.escape(path[path.length - 1] || '')}"]`);
			if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' });
		}));
		return;
	}
	const btn = e.target.closest('[data-action]');
	if (!btn) return;
	const run = state.runs.get(state.selected);
	switch (btn.dataset.action) {
		case 'export': if (run) exportRun(run); break;
		case 'replay': if (run) startReplay(run, document.getElementById('replay-speed').value); break;
		case 'step': stepReplay(); break;
		case 'pause': togglePause(); break;
		case 'stop': stopReplay(); break;
		case 'view-cards': savePref('view', 'cards'); render(); break;
		case 'view-sequence': savePref('view', 'sequence'); render(); break;
		case 'seq-ordered': savePref('seqScale', 'ordered'); render(); break;
		case 'seq-scaled': savePref('seqScale', 'scaled'); render(); break;
		case 'pin-tokens': e.preventDefault(); savePref('pinTokens', String(pref('pinTokens', 'true') !== 'true')); render(); break;
		case 'hl-model': state.highlight = state.highlight === btn.dataset.model ? null : btn.dataset.model; touchUi(); render(); break;
	}
	if (btn.dataset.action.startsWith('view-') || btn.dataset.action.startsWith('seq-')) updateHash();
});
document.addEventListener('change', (e) => {
	if (e.target.id === 'replay-speed') savePref('replaySpeed', e.target.value);
	if (e.target.id === 'import-file') { importFiles([...e.target.files]); e.target.value = ''; }
});
document.getElementById('import').addEventListener('click', () => document.getElementById('import-file').click());

// ---------------------------------------------------------------- live stream
function connect() {
	const source = new EventSource(apiUrl('api/stream'));
	const dot = document.getElementById('conn');
	source.onopen = () => {
		// The server replays everything on (re)connect.
		stopReplay();
		state.runs.clear();
		dot.classList.add('live');
		dot.title = 'connected';
	};
	source.onmessage = (msg) => {
		const ev = JSON.parse(msg.data); const selected = state.selected;
		handle(ev); selectLinkedRun();
		render({ main: affectsSelected(ev, selected) });
	};
	source.onerror = () => { dot.classList.remove('live'); dot.title = 'reconnecting…'; };
}

applyPrefs();
render();
connect();
