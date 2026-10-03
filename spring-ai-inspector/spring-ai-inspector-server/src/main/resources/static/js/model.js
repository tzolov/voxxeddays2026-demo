import { replay, stopReplay } from './replay.js';
import { ensureRun, state } from './state.js';

// ---------------------------------------------------------------- event handling
export function handle(ev) {
	if (ev.type === 'clear') {
		if (replay) stopReplay();
		state.runs.clear(); state.selected = null; state.open.clear(); state.tabs.clear();
		return;
	}
	const run = ensureRun(ev.runId || 'unattributed', ev.ts);
	run.events.push(ev);
	switch (ev.type) {
		case 'run-start':
			run.app = ev.app || run.app; run.model = ev.model; run.started = ev.ts; run.pid = ev.pid;
			run.imported = ev.imported; run.replayOf = ev.replayOf;
			if (state.follow) state.selected = run.id;
			break;
		case 'run-end':
			run.ended = ev.ts;
			break;
		case 'client-request': {
			const call = { id: ev.callId, req: ev, resp: null, modelCalls: [], items: [], wires: [], searches: [], memory: {}, seq: ev.seq,
				num: ++run.callCount, parent: run.calls.get(ev.parentId) || null, run,
				inferred: !!ev.parentInferred, linkedFrom: ev.linkedFrom || null };
			run.calls.set(call.id, call);
			(call.parent ? call.parent.items : run.items).push({ kind: 'call', ref: call });
			if (call.linkedFrom) linkRemoteCall(call);
			break;
		}
		case 'client-response': {
			const call = run.calls.get(ev.callId);
			if (call) call.resp = ev;
			break;
		}
		case 'model-request': {
			const mc = { id: ev.callId, req: ev, resp: null };
			run.modelCalls.set(mc.id, mc);
			const call = run.calls.get(ev.parentId);
			if (call) call.modelCalls.push(mc);
			break;
		}
		case 'model-response': {
			const mc = run.modelCalls.get(ev.callId);
			if (mc) mc.resp = ev;
			break;
		}
		case 'wire-request': {
			const wire = { id: ev.wireId, req: ev, resp: null, num: run.wireList.length + 1,
				prev: run.wireList[run.wireList.length - 1] || null };
			run.wires.set(wire.id, wire);
			run.wireList.push(wire);
			const call = run.calls.get(ev.clientCallId);
			if (call) { call.items.push({ kind: 'wire', ref: wire }); call.wires.push(wire); }
			else run.items.push({ kind: 'wire', ref: wire });
			break;
		}
		case 'wire-response': {
			const wire = run.wires.get(ev.wireId);
			if (wire) wire.resp = ev;
			break;
		}
		case 'tool-start': {
			const tool = { id: ev.toolId, start: ev, end: null };
			run.tools.set(tool.id, tool);
			const call = run.calls.get(ev.clientCallId);
			(call ? call.items : run.items).push({ kind: 'tool', ref: tool });
			break;
		}
		case 'tool-end': {
			const tool = run.tools.get(ev.toolId);
			if (tool) tool.end = ev;
			break;
		}
		case 'vector-search': {
			const call = run.calls.get(ev.clientCallId);
			(call ? call.searches : (run.searches ||= [])).push(ev);
			break;
		}
		case 'vector-add':
			run.items.push({ kind: 'ingest', ref: ev });
			break;
		case 'memory-snapshot': {
			const call = run.calls.get(ev.clientCallId);
			if (call) { call.memory[ev.phase] = ev.stores; call.memory[ev.phase + 'Seq'] = ev.seq; }
			break;
		}
	}
}

// A call in another JVM, linked by the server (by timing) to the tool call that was open
// in this run when it started, e.g. an A2A remote agent serving 18's Task tool.
export function linkRemoteCall(call) {
	const caller = state.runs.get(call.linkedFrom.runId);
	const tool = caller && caller.tools.get(call.linkedFrom.toolId);
	if (tool) (tool.remoteCalls ||= []).push(call);
}
