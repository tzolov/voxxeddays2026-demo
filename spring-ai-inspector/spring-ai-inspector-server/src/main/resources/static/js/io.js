import { EVENTS_VERSION } from './model.js';
import { apiHeaders, apiUrl } from './state.js';

// ---------------------------------------------------------------- export / import
/** The blobs a run's events point at: markers in bodies, blobId fields (see EVENTS.md). */
export function blobIdsOf(events) {
	const ids = new Set();
	for (const m of JSON.stringify(events).matchAll(/blob:([a-f0-9]{8,32})>|\\"blobId\\":\\"([a-f0-9]{8,32})\\"|"blobId":"([a-f0-9]{8,32})"/g)) ids.add(m[1] || m[2] || m[3]);
	return [...ids];
}

/** A recording: the run's events as received, then the media the inspector still holds, as blob events. */
export async function recordingOf(run, fetchBlob) {
	const events = run.events.filter((e) => e.type !== 'blob').map(({ ord, ...e }) => e); // without the UI's own order key (see model.js)
	for (const id of blobIdsOf(events)) {
		const got = await fetchBlob(id).catch(() => null);
		if (got) events.push({ v: EVENTS_VERSION, type: 'blob', runId: run.id, id, contentType: got.contentType, size: got.bytes.byteLength, data: base64(got.bytes) });
	}
	return events;
}

function base64(buffer) {
	const bytes = new Uint8Array(buffer); let s = '';
	for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
	return btoa(s);
}

async function fetchBlob(id) {
	const res = await fetch(apiUrl('api/blobs/' + encodeURIComponent(id)), { headers: apiHeaders() });
	if (!res.ok) return null; // gone from the inspector's budget: the marker is exported alone
	return { contentType: res.headers.get('content-type') || '', bytes: await res.arrayBuffer() };
}

export async function exportRun(run) {
	const events = await recordingOf(run, fetchBlob);
	const blob = new Blob([JSON.stringify(events, null, 1)], { type: 'application/json' });
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
