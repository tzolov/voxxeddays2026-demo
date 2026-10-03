// ---------------------------------------------------------------- export / import
export function exportRun(run) {
	const blob = new Blob([JSON.stringify(run.events, null, 1)], { type: 'application/json' });
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
			await fetch('api/import?name=' + encodeURIComponent(file.name), {
				method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(events) });
		}
		catch (e) { alert(`Could not import ${file.name}: ${e.message}`); }
	}
}
