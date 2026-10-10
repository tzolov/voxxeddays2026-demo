import { apiHeaders } from './state.js';

// ---------------------------------------------------------------- export / import
export function exportRun(run) {
	// Without the UI's own order key (see model.js): a recording holds what was received.
	const blob = new Blob([JSON.stringify(run.events.map(({ ord, ...e }) => e), null, 1)], { type: 'application/json' });
	const a = document.createElement('a');
	a.href = URL.createObjectURL(blob);
	a.download = `${run.app.split(' · ')[0].replace(/[^\w.-]+/g, '_')}-${run.id}.json`;
	a.click();
	setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

export async function importFiles(files) {
	for (const file of files) {
		try {
			const events = JSON.parse(await file.text());
			if (!Array.isArray(events)) throw new Error('not an exported run');
			// Imported on the server so the run survives page reloads; it arrives over the stream.
			const res = await fetch('api/import?name=' + encodeURIComponent(file.name), {
				method: 'POST', headers: apiHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(events) });
			// A recording in a newer event format than the server reads is refused with the versions.
			if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || res.statusText);
		}
		catch (e) { alert(`Could not import ${file.name}: ${e.message}`); }
	}
}
