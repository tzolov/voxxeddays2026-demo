# Inspector events

The starter and the inspector talk in JSON events: the starter posts them to `POST /api/events`, the proxy
records its own, the store fans them out to the UI over `GET /api/stream` (server-sent events, one event per
`data:` line), and a run exported from the UI is the array of its events, which `POST /api/import` and
`spring.ai.inspector.preload-dir` read back. This is the contract between the three: what every event
carries, who emits it, and how the format is versioned.

## Envelope

Every event is a JSON object with:

| Field | Set by | Meaning |
|---|---|---|
| `v` | emitter | event format version, `1` today (see [Versioning](#versioning)); absent in recordings made before versioning |
| `type` | emitter | the event type, below |
| `runId` | emitter | the run (one application process) the event belongs to; replaced on import and replay |
| `ts` | emitter | epoch milliseconds when the event was built (the server fills it in when missing) |
| `seq` | server | arrival position in the store, increasing; removed on import and assigned again there. Arrival is not the order of things: the starter posts in the background, so the UI orders by `ts` (then `seq`) |

Ids are short random strings. A `callId` names a ChatClient call (`client-request`) or a model round-trip
(`model-request`); other events point at them as `clientCallId` and `modelCallId`, or at each other with
`parentId`, `toolId`, `wireId`, `opId`. All ids are unique within a run.

Fields marked *server* are added by the inspector when it receives the event from a live starter; an
imported recording keeps whatever it has and gets none of them. A UI replay feeds a run's events again
under a new `runId` with `ts` set to the replay time and the original time kept as `recordedTs`, so a run
exported after being replayed may carry that field too.

## Run

`run-start` (starter, at startup; `announce`d again with `reannounce: true` after the inspector was
unreachable, which does not restart the run): `app` (the application's name), `model` (the configured
models, a string), `pid`, `upstreams` (`{provider: baseUrl}`, where the proxy forwards each routed provider;
only `http(s)` URLs count, and the first `run-start` of a run fixes them), `routed` (the providers whose
calls are on the wire). *Server/UI:* `imported` (the file name, on an imported run), `replayOf` (the run a UI
replay was made from).

`run-end` (starter, at shutdown): nothing else.

`clear` (server, broadcast only, never stored): the store was emptied.

## ChatClient calls (advisors)

`client-request` (starter, the CLIENT advisor, first in the chain): `callId`, `parentId` (the ChatClient
call this one was made from, by observation parentage; null at top level), `parentToolId` (the tool run it
was made from), `thread`, `advisors` (`[{name, order}]`, the chain without the inspector's own), `rag`
(`[{advisor, kind: modular|question-answer, contextKey, queryTransformers, queryExpander,
documentRetriever, topK, similarityThreshold, documentJoiner, documentPostProcessors}]`, when a RAG
advisor is in the chain), `messages`, `options`, `context`. *Server:* `parentId` + `parentToolId` +
`parentInferred: true` when the parent was inferred from an open tool in the same run; `linkedFrom`
(`{runId, toolId, clientCallId}`) when the call was triggered from a tool open in another run.

`client-response` (starter, same advisor): `callId`, `durationMs`, then either `generations`, `model`,
`usage` and `context`, or `error`.

`model-request` / `model-response` (starter, the MODEL advisor, last before the model; once per
round-trip of a tool-calling conversation): as above, with `callId` the round-trip's own id and
`parentId` the ChatClient call's.

Shared shapes:

- A **message**: `role` (`user|assistant|system|tool`), `text`, `toolCalls` (`[{id, name, arguments}]`),
  `toolResponses` (`[{id, name, data}]`), `media` (`[{type, size, blobId?, url?}]`: `blobId` when the bytes
  were uploaded to `/api/blobs/{id}` for a preview, `url` for a link; in recordings before v1 a list of
  mime types).
- A **generation**: a message plus `finishReason` and `thinking: signed|redacted` for a thinking block
  returned as a generation of its own.
- `options`: `type` (the options class), `model`, `maxTokens`, `temperature`, `tools` (`[{name,
  description}]`).
- `context`: the advisor context, values as strings; keys that look like secrets are `…redacted`, the
  inspector's own key is left out.
- `usage`: `input`, `output`, `cacheRead`, `cacheWrite` token counts (null when the provider gives none).
- `error`: `ExceptionClass: message`.

`memory-snapshot` (starter, CLIENT advisor, when a memory advisor is in the chain and
`spring.ai.inspector.memory-snapshots` is on): `clientCallId`, `phase` (`before|after`), `stores`
(`[{memory, items}]`: the memory's class and its items, which are messages for a `ChatMemory`, session
events with `archived`, `synthetic`, `ts` for a session service, files with `name`, `size`, `modified`,
`content` for a file-based memory).

## Tools and MCP

`tool-start` (starter, tool observation handler): `toolId`, `clientCallId`, `toolCallId` (the model's id
for the call), `name`, `toolType`, `description`, `arguments`, `thread`, `mcp` (the connection and server
the tool came from, for an MCP tool). *Server:* `clientCallId` when the handler had none (a tool run on
another thread).

`tool-end`: `toolId`, `durationMs`, `result`, `error`.

`mcp-message` (starter, MCP client transport decorator; one per JSON-RPC message): `connection`,
`direction` (`out` to the server, `in` from it), `kind` (`request|notification|response`), `method`, `id`,
`toolId` (the tool run the message belongs to, for `tools/call` and its response), `payload` (the message,
shortened; a string in older recordings), `error` (a response's error message).

## Model beans (no HTTP through the proxy)

`embedding-call` (starter, embedding model proxy; after the call): `embeddingId`, `clientCallId`,
`provider`, `model`, `inputs` (count), `sample` (the first inputs, shortened), `vectors`, `dimensions`,
`usage` (`{input}`), `durationMs`, `error`. The UI drops it when the provider is routed or an HTTP embedding
round-trip of the same call was recorded meanwhile.

`model-call` (starter, image / speech / transcription / moderation model proxies): `modelCallId`,
`clientCallId`, `kind` (`image|speech|transcription|moderation`), `modelType`, `provider`, `model`,
`thread`, `request`, `response` or `error`, `durationMs`, `streamed: true` for a `stream` call (speech,
transcription), reported once when the stream completes with the chunks put together. The `request` and `response` shapes are the
normalized ones of the wire adapters, in [design/wire-adapters.md](design/wire-adapters.md) (a gitignored
design note) and in `providers.js`.

## Vector stores

`vector-start` (starter, vector store proxy, when an operation begins): `opId`, `op`
(`search|add|delete`), `clientCallId`, `store`, and `query` (search), `count` (add, delete by ids) or
`filter` (delete by expression). *Server:* `clientCallId` of a search when missing.

`vector-search` (when it ends): `opId`, `searchId`, `clientCallId`, `store`, `query`, `topK`, `threshold`,
`filter`, `durationMs`, `thread`, `results` (`[{id, score, text, metadata}]`, text shortened), `error`.
*Server:* `clientCallId` when missing.

`vector-add`: `opId`, `clientCallId`, `store`, `count`, `durationMs`, `sample` (the first documents),
`error`. `vector-delete`: `opId`, `clientCallId`, `store`, `count` or `filter`, `durationMs`, `error`.

Recordings before `vector-start` existed carry only the end events; the UI draws those as closed
operations.

## Wire (the proxy)

`wire-request` (server, as a routed provider call is forwarded): `wireId`, `provider`, `method`, `url`
(the upstream URL with secret query parameters redacted), `path`, `headers` (secret ones redacted),
`clientCallId` and `modelCallId` (from the starter's `X-Inspector-Call` / `X-Inspector-Model-Call` headers,
then `linkedBy: "header"`; otherwise the calls open for the run), and the body:

- text: `body` (inline base64 of recognized media replaced by `<base64 N chars TYPE blob:ID>` markers, other
  long base64 by `<base64 N chars>`), with `truncated: true` and `size` when cut at
  `spring.ai.inspector.max-body-chars`;
- binary: `bodyKind: "binary"`, `contentType`, `size`, `blobId` when kept, `note` when not;
- multipart: `bodyKind: "multipart"`, `body` as JSON `{fields: {name: value}, files: [{name, filename,
  contentType, size, blobId}]}`.

`wire-response` (server, once the upstream's response has been streamed back): `wireId`, `status`,
`durationMs`, `headers`, the body as above, `error` when the stream broke or the upstream was unreachable
(then `status: 502`, no headers or body).

Blobs (`/api/blobs/{id}`) are not part of a recording: an exported run keeps the markers and ids, and a
preview is shown only while the inspector that recorded it still holds the bytes.

## Versioning

`v` is the format version, the same number in three places that must move together:
`InspectorClient.EVENTS_VERSION` (starter), `EventStore.EVENTS_VERSION` (server) and `EVENTS_VERSION` in
`model.js` (UI). `GET /api/ping` reports the server's as `events`.

Readers take events of their own version and of any lower one, including events with no `v`, and read
older shapes as they were (the cases are commented in the UI: media as mime types, MCP payloads as
strings, vector operations without a start). A recording with a higher `v` than the server reads is
refused by `/api/import` and the preloader with a 400 / a warning naming both versions; the UI shows live
events of a higher `v` as far as it understands them and says so in the run header.

Bump the version when a reader of the previous version would show an event wrong: a field renamed, removed
or given a new meaning, an id that stops pointing where it did, a type that is split or merged. Adding a
field or a type is not a bump, since readers ignore what they don't know. When bumping, add the old shape
to the readers (a comment at the place that handles it, like the ones above) and a line to this document
saying what changed.

### History

- **v1** (October 2026): the format as described here. Events before it had no `v`; the differences a reader
  still handles are the ones named above.
