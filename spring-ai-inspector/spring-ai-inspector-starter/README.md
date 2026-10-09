# Spring AI Inspector Starter

Auto-configured instrumentation that reports what a Spring AI application does to a running
[Spring AI Inspector](../README.md). The inspector records:

- ChatClient calls and the prompt as each advisor leaves it.
- Model round-trips, both on the HTTP wire and in the JVM.
- Tool runs.
- Vector store adds and searches.
- Embedding calls.
- MCP messages.
- Memory contents.

The starter follows three rules:

- **No code changes.** Everything hooks in through Spring Boot extension points. The app keeps its
  `ChatClient`, models, tools and stores as they are.
- **Inactive unless an inspector is reachable.** Without one, nothing is registered and nothing is rerouted.
- **Never breaks or slows the app.** Events are built lazily inside a guard. A failure while describing a call
  drops that event, never the app's call. An unreachable inspector pauses publishing.

It needs only `spring-ai-client-chat` and Spring Boot auto-configuration. It has no web stack: events are posted
with the JDK `HttpClient`. `spring-ai-vector-store`, `spring-ai-mcp` and `spring-ai-autoconfigure-mcp-client-common`
are optional and are used only when the app has them.

## How it hooks in

### Structure

Each instrumentation class implements a Spring, Spring AI, Micrometer or MCP extension point. All of them report
through one `InspectorClient`.

```mermaid
classDiagram
    direction LR

    class EnvironmentPostProcessor {
        <<interface>>
    }
    class CallAdvisor {
        <<interface>>
    }
    class StreamAdvisor {
        <<interface>>
    }
    class ObservationHandler {
        <<interface>>
    }
    class BeanPostProcessor {
        <<interface>>
    }
    class McpClientTransport {
        <<interface>>
    }

    class InspectorEnvironmentPostProcessor {
        +postProcessEnvironment(environment, application)
        isInspectorUp(url)$ boolean
    }
    class InspectorAutoConfiguration {
        <<auto-configuration>>
        inspectorChatClientCustomizer() ChatClientBuilderCustomizer
        inspectorObservationRegistry() ObservationRegistry
    }
    class InspectorAdvisor {
        -phase : Phase
        +adviseCall(request, chain)
        +adviseStream(request, chain)
        currentCallId()$ String
    }
    class InspectorToolObservationHandler {
        +onStart(ToolCallingObservationContext)
        +onStop(ToolCallingObservationContext)
    }
    class InspectorVectorStorePostProcessor {
        +postProcessAfterInitialization(bean, name)
    }
    class InspectorEmbeddingModelPostProcessor {
        +postProcessAfterInitialization(bean, name)
    }
    class InspectorMcpTransportPostProcessor {
        +postProcessAfterInitialization(bean, name)
    }
    class InspectorMcpToolNamePostProcessor {
        +postProcessAfterInitialization(bean, name)
    }
    class InspectorMcpClientTransport {
        -delegate : McpClientTransport
        +sendMessage(message)
        +connect(handler)
    }
    class InspectorToolOrigins {
        +get(toolName, description) Map
        +started(toolId, origin)
        +stopped(toolId)
    }
    class InspectorMemoryReader {
        +snapshot(advisors, context) List
    }
    class InspectorRagDescriber {
        describe(advisors)$ List
    }
    class InspectorClient {
        +send(type, payload)
        +sendAsync(type, payload)
        +sendLater(type, payload)
    }

    EnvironmentPostProcessor <|.. InspectorEnvironmentPostProcessor
    CallAdvisor <|.. InspectorAdvisor
    StreamAdvisor <|.. InspectorAdvisor
    ObservationHandler <|.. InspectorToolObservationHandler
    BeanPostProcessor <|.. InspectorVectorStorePostProcessor
    BeanPostProcessor <|.. InspectorEmbeddingModelPostProcessor
    BeanPostProcessor <|.. InspectorMcpTransportPostProcessor
    BeanPostProcessor <|.. InspectorMcpToolNamePostProcessor
    McpClientTransport <|.. InspectorMcpClientTransport

    InspectorEnvironmentPostProcessor ..> InspectorAutoConfiguration : activates
    InspectorAutoConfiguration ..> InspectorAdvisor : adds two to every ChatClient.Builder
    InspectorMcpTransportPostProcessor ..> InspectorMcpClientTransport : wraps each transport in
    InspectorAdvisor --> InspectorMemoryReader
    InspectorAdvisor ..> InspectorRagDescriber
    InspectorToolObservationHandler --> InspectorToolOrigins
    InspectorMcpClientTransport --> InspectorToolOrigins
    InspectorMcpToolNamePostProcessor --> InspectorToolOrigins

    InspectorAdvisor --> InspectorClient
    InspectorToolObservationHandler --> InspectorClient
    InspectorVectorStorePostProcessor --> InspectorClient
    InspectorEmbeddingModelPostProcessor --> InspectorClient
    InspectorMcpClientTransport --> InspectorClient
```

### One ChatClient call

This is the event flow of a call in which the model asks for a tool. Spring AI's `ToolCallingAdvisor` runs the
tool-calling loop inside the advisor chain, so the advisors after it, including the `MODEL` advisor, run once per
model round-trip.

```mermaid
sequenceDiagram
    autonumber
    actor App as Application
    participant C as InspectorAdvisor (CLIENT)
    participant TC as ToolCallingAdvisor
    participant A as Other advisors (memory, RAG, ...)
    participant M as InspectorAdvisor (MODEL)
    participant CM as ChatModel
    participant Tool
    box Spring AI Inspector
        participant E as /api/events
        participant P as /r/{runId}/{provider} proxy
    end
    participant LLM as Provider API

    App->>C: chatClient.prompt(...).call()
    C->>E: client-request, memory-snapshot (before)
    C->>TC: nextCall
    loop until the model asks for no more tools
        TC->>A: nextCall
        A->>M: nextCall (prompt with memory, RAG context, ...)
        M->>E: model-request
        M->>CM: call(prompt)
        CM->>P: HTTP POST to the base URL rewritten at startup
        P->>E: wire-request, linked to the open model call
        P->>LLM: forward to the original base URL
        LLM-->>P: response
        P->>E: wire-response
        P-->>CM: response, unchanged
        CM-->>M: ChatResponse
        M->>E: model-response
        M-->>A: response
        A-->>TC: response
        opt the response has tool calls
            TC->>Tool: execute, inside a Micrometer observation
            Note over TC,Tool: InspectorToolObservationHandler sends tool-start and tool-end
        end
    end
    TC-->>C: final response
    C->>E: memory-snapshot (after), client-response
    C-->>App: answer
```

### Summary

| Mechanism | Spring extension point | What it sees | Events |
|---|---|---|---|
| [Detection and routing](#1-detection-and-traffic-routing) | `EnvironmentPostProcessor` (`spring.factories`) | the inspector, the providers' base URLs, the configured models | `run-start`, `run-end` |
| [Recording proxy](#2-the-recording-proxy) | rewritten `spring.ai.<provider>.base-url` | every HTTP request and response to a model provider | `wire-request`, `wire-response` (sent by the server) |
| [Advisors](#3-chatclient-advisors) | `ChatClientBuilderCustomizer` | the prompt as written and as sent, answers, usage, advisor chain, RAG setup, memory | `client-*`, `model-*`, `memory-snapshot` |
| [Tool observations](#4-tool-calling-observations) | Micrometer `ObservationHandler` | every tool execution, including MCP tools | `tool-start`, `tool-end` |
| [Bean proxies](#5-aop-proxies-for-vector-stores-and-embedding-models) | `BeanPostProcessor` + Spring AOP `ProxyFactory` | vector store adds and searches, embedding calls | `vector-*`, `embedding-call` |
| [MCP transport decorator](#6-mcp-client-transports) | `BeanPostProcessor` on the auto-configured transports | every JSON-RPC message, both directions | `mcp-message` |
| [Read-only reflection](#7-read-only-reflection) | none, read from the advisors' fields | chat memory, session memory, RAG stages | part of `client-request`, `memory-snapshot` |

## Instrumentation techniques

### 1. Detection and traffic routing

`InspectorEnvironmentPostProcessor` is registered in `META-INF/spring.factories`. It runs before any bean exists:

1. It checks `<spring.ai.inspector.url>/api/ping` with a 300 ms connect timeout. It goes on only if the response
   identifies itself as `spring-ai-inspector`, so another service on port 9001 (MinIO, SonarQube, ...) is never
   mistaken for it. If there is no inspector, it returns and the app is untouched.
2. It creates a run id (8 hex characters) and works out a readable app name from the main class location, e.g.
   `03-chat-memory · DemoApplication`. It also lists the configured chat models from
   `spring.ai.<provider>.chat[.options].model` and `spring.ai.<provider>.responses.model`. Environment variables count too.
3. It **points each provider's base URL at the inspector's proxy**:
   `spring.ai.anthropic.base-url=http://localhost:9001/r/<runId>/anthropic`. The new values go into a property
   source added *first*, so they win over `application.properties`, environment variables and command-line
   arguments.
4. It records where each provider pointed before, as `spring.ai.inspector.upstream.<provider>`. The run reports
   it, and the proxy forwards there. A custom gateway, a mitmweb or a local Ollama in front of the provider keeps
   working.
5. It sets `spring.ai.inspector.active=true`, which switches on `InspectorAutoConfiguration`.

```mermaid
flowchart LR
    start([App starts]) --> found{"enabled, and<br/>/api/ping answers<br/>as the inspector?"}
    found -- no --> untouched([App unchanged])
    found -- yes --> run["Create run id,<br/>app name, models"]
    run --> rewrite["Point each routable<br/>base-url at<br/>/r/{runId}/{provider}"]
    rewrite --> publish["Add property source first,<br/>set active=true"]
    publish --> active([Auto-configuration on,<br/>run-start sent])
```

Which base URLs are rewritten:

| Provider | Property | Routed when |
|---|---|---|
| Anthropic | `spring.ai.anthropic.base-url` | always: paths are simply appended to the base URL |
| TypeSafe (Jev) | `spring.ai.typesafe.base-url` | always |
| OpenAI | `spring.ai.openai.base-url`, `spring.ai.openai.responses.base-url` | both unset or `api.openai.com`, or with `spring.ai.inspector.route.openai=always` |
| Ollama | `spring.ai.ollama.base-url` | unset or `localhost:11434` |
| Mistral AI | `spring.ai.mistralai.base-url`, `spring.ai.mistralai.chat.base-url` | unset or `api.mistral.ai` |
| DeepSeek | `spring.ai.deepseek.base-url` | unset or `api.deepseek.com` |
| any other HTTP provider | named by `spring.ai.inspector.proxy.<name>=<property>[,...]` | always, when the property holds an `http(s)` URL |

OpenAI, Ollama, Mistral and DeepSeek are left alone when they point elsewhere. Their base URLs can imply
provider-specific paths (Azure, GitHub Models, ...) that the proxy should not guess.

When the app starts, `RunLifecycle` sends `run-start`. The event carries the app name, the models, the pid, the
upstreams and the *routed* providers, whose model calls are already visible on the wire. When the context closes,
it sends `run-end`.

### 2. The recording proxy

The proxy lives in the inspector server, not in the starter, but the starter's routing is what feeds it.
Requests to `/r/<runId>/<provider>/...` are forwarded to that run's upstream. The response streams back unchanged,
and both directions are published as `wire-request` and `wire-response`, with API keys redacted. The run id in the
path tells the server which app made the call.

The server links each wire call to the ChatClient call and model call open for that run. This works because the
advisors post their events **synchronously** (about 1 ms on localhost). The `model-request` event has reached the
server before the model's HTTP request reaches the proxy.

Models that make no HTTP calls, such as jinfer running in the JVM, and providers that aren't routed are still
shown. Their round-trips come from the `MODEL` advisor's events instead.

### 3. ChatClient advisors

`InspectorAutoConfiguration` contributes a `ChatClientBuilderCustomizer`. Spring AI applies it to every
auto-configured `ChatClient.Builder`, and it adds two `InspectorAdvisor`s as default advisors:

| Phase | Order | Sees |
|---|---|---|
| `CLIENT` | `HIGHEST_PRECEDENCE`, first in the chain | the prompt exactly as the app wrote it, and the final answer |
| `MODEL` | `LOWEST_PRECEDENCE - 1`, right before the model | the prompt after memory, RAG, guardrails and other advisors have run |

Comparing the two shows what the advisors in between added. Spring AI's `ToolCallingAdvisor` (order
`HIGHEST_PRECEDENCE + 300`) loops through the rest of the chain once per model round-trip, so the `MODEL` advisor
reports every round-trip of a tool-calling conversation, while the `CLIENT` advisor reports the call once. Each request event carries the messages, including
tool calls, tool responses and media types. It also carries the options (model, max tokens, temperature, tool
definitions) and the advisor context. Retrieved RAG documents keep their id, score, text and metadata. The `CLIENT`
request also lists the advisor chain with names and orders.

Each response event carries the generations with their finish reasons and thinking blocks, the model, token usage
(including cache reads and writes), the duration, and any error.

**Correlation.** The `CLIENT` advisor gives each call an id:

- The id goes into the advisor context (`inspector.callId`), so the `MODEL` advisor can name its parent.
- The id is also pushed onto a thread-local stack. A `ChatClient` call made while another runs on the same thread,
  such as a sub-agent called from a tool, records the outer call as its `parentId`. The calls form a tree.
- Tool runs, vector store operations and embedding calls read the top of that stack (`currentCallId()`). That is how
  they know which ChatClient call they belong to.
- A running tool is kept on a thread-local stack too. A `ChatClient` call made by that tool, such as a sub-agent
  started by a `Task` tool, records it as its `parentToolId`. The inspector shows the sub-agent inside the tool's
  card. When the sub-agent runs on another thread, the inspector server infers the parent call and tool from timing.

**Streaming.** `adviseStream` aggregates the `Flux` with `ChatClientMessageAggregator`. The completion and error
callbacks run on Reactor threads, so they post with `sendAsync` and never block.

### 4. Tool-calling observations

Spring AI wraps each tool execution in a Micrometer observation with a `ToolCallingObservationContext`.
`InspectorToolObservationHandler` listens to those observations. It does not wrap tools, so it works for
`@Tool` methods, function callbacks and MCP tools alike.

- Spring AI emits these observations only when an `ObservationRegistry` bean exists. If the app has none, the
  starter provides one (`@ConditionalOnMissingBean`). A static `BeanPostProcessor` registers the handler on
  whichever registry the app ends up with, its own or Actuator's.
- Tools run on the thread of the ChatClient call that asked for them, so `currentCallId()` attributes them.
- MCP tools are observed twice for one execution. The handler keys each execution by the model's tool-call id
  and reports only the outermost start and stop.
- `tool-start` carries the name, type, description and arguments. For an MCP tool it also carries its origin:
  the connection, the server, its version and the original tool name (see [section 6](#6-mcp-client-transports)).
  `tool-end` carries the result (up to 20,000 characters), the duration and any error.

### 5. AOP proxies for vector stores and embedding models

Two `BeanPostProcessor`s replace beans with Spring AOP proxies, built with `ProxyFactory` and a `MethodInterceptor`.
Each proxies the concrete class when it can, so injection points typed as the implementation (e.g.
`SimpleVectorStore`) still work. For final classes it falls back to an interface proxy.

**`InspectorVectorStorePostProcessor`** wraps every `VectorStore` bean:

- It intercepts `add`, and the `DocumentWriter` methods `accept` and `write`. It also intercepts
  `similaritySearch(SearchRequest)` and `similaritySearch(String)`.
- It reports each operation **when it starts** (`vector-start`) and **when it ends** (`vector-add` or
  `vector-search`). The inspector can then draw the embedding calls the store makes meanwhile *inside* the
  operation.
- Searches carry the query, `topK`, threshold, filter and scored results. Adds carry the count and a sample.
- A default method calling another method on the same object bypasses the proxy (self-invocation). So
  `similaritySearch(String)` is reported itself, with the defaults it searches with.

Spring AI has its own vector store observations, but they fire only when the store was built with an
`ObservationRegistry`, which hand-built stores usually are not. The proxy works regardless.

**`InspectorEmbeddingModelPostProcessor`** wraps every `EmbeddingModel` bean:

- It intercepts `call`, `embed` and `embedForResponse`. It reports the provider (from the class name, e.g.
  `JinferEmbeddingModel` → `jinfer`), the model, the inputs, the vector count, the dimensions, the usage, the
  duration and any error.
- The default `embed(...)` methods delegate to `call(...)` on the model itself, past the proxy. So only the
  outermost call is reported, never twice.
- This covers models in the JVM that make no HTTP calls. For remote models the inspector prefers the wire
  round-trip and doesn't count the call twice.

### 6. MCP client transports

Only when Spring AI's MCP client is on the classpath:

- **`InspectorMcpTransportPostProcessor`** replaces the auto-configured `List<NamedClientMcpTransport>` bean before
  the MCP clients are built from it. Each transport (stdio, Streamable HTTP or SSE) is wrapped in an
  **`InspectorMcpClientTransport`**. This decorator reports every JSON-RPC message in both directions:
  - the client's requests and notifications;
  - the server's responses, requests (sampling, elicitation) and notifications (logging, progress, list changes).

  Responses are named after the method they answer. Long strings are cut to 4,000 characters, so the payload
  stays valid JSON. Messages travel on Reactor threads, so each one is classified there and serialized later on
  the sender thread (`sendLater`).
- **Tool origins.** A tool name alone doesn't say which server it comes from. `InspectorToolOrigins` learns it
  two ways:
  - from each connection's `initialize` and `tools/list` responses;
  - from the auto-configured `McpToolNamePrefixGenerator`, which `InspectorMcpToolNamePostProcessor` wraps. The
    generator knows the exact names, including clashes renamed `alt_<n>_<name>`. The starter wraps it rather
    than calling it again, because the default generator is stateful.

  A tool of the app with the same name as an MCP tool is told apart by its description.
- **Attribution.** MCP messages are attributed to the tool run that caused them:
  - a `tools/call` goes to the running tool of that name;
  - its response, by JSON-RPC id, goes to the same tool run;
  - logs, progress and sampling sent in between go to the connection's most recently started tool run;
  - session messages (`initialize`, `ping`, `*/list`, list changes) stay with the connection.

```mermaid
sequenceDiagram
    participant TC as ToolCallingAdvisor
    participant H as InspectorToolObservationHandler
    participant O as InspectorToolOrigins
    participant CB as MCP tool callback
    participant T as InspectorMcpClientTransport
    participant S as MCP server
    participant E as Inspector /api/events

    Note over T,S: When the connection starts
    T->>S: initialize, tools/list
    S-->>T: serverInfo, tools
    T->>O: putListed(tool name → connection, server)
    T->>E: mcp-message (connection only)

    Note over TC,S: When the model calls an MCP tool
    TC->>H: observation starts
    H->>O: get(name, description)
    O-->>H: origin (connection, server, tool)
    H->>O: started(toolId, origin)
    H->>E: tool-start, with the origin
    TC->>CB: call(arguments)
    CB->>T: tools/call (JSON-RPC id 7)
    T->>O: toolCall(connection, 7, name)
    O-->>T: toolId
    T->>E: mcp-message (toolId)
    T->>S: tools/call
    S-->>T: notifications/progress
    T->>O: current(connection)
    O-->>T: toolId
    T->>E: mcp-message (toolId)
    S-->>T: response (id 7)
    T->>O: toolCallResponse(connection, 7)
    O-->>T: toolId
    T->>E: mcp-message (toolId)
    T-->>CB: result
    CB-->>TC: result
    TC->>H: observation stops
    H->>O: stopped(toolId)
    H->>E: tool-end
```

### 7. Read-only reflection

Some things have no hook. The starter reads them from the fields of the advisors in the chain, without
compile-time dependencies on the libraries that define them. Every failure is swallowed.

- **Memory** (`InspectorMemoryReader`): the `CLIENT` advisor sends a `memory-snapshot` before and after each
  call. The snapshot holds:
  - any `ChatMemory` field, e.g. `MessageChatMemoryAdvisor`, for the call's conversation id;
  - any spring-ai-session `SessionService` field, including archived (compacted) events;
  - the files in `spring.ai.inspector.memory-dirs`.
- **RAG setup** (`InspectorRagDescriber`): the stages of a `RetrievalAugmentationAdvisor` (query transformers,
  expander, retriever with `topK` and threshold, joiner, post-processors, augmenter) or a
  `QuestionAnswerAdvisor`. They are added to the `client-request` event.

### 8. Event delivery

`InspectorClient` posts JSON events to `<url>/api/events`. Each event carries `type`, `runId` and `ts`.

| Method | Builds the event on | Posts on | Used for |
|---|---|---|---|
| `send` | the caller | the caller, synchronously | advisor, tool, vector store and embedding events: the server sees them in their real order relative to the wire |
| `sendAsync` | the caller | one background thread, in order | Reactor callbacks that must not block (streaming) |
| `sendLater` | the background thread | the background thread | costly payloads such as MCP messages; the timestamp is still taken at the call |

Payloads are `Supplier`s, built inside a guard. An exception or `LinkageError` drops the event. After a failed
post, publishing pauses for 5 seconds, so a stopped inspector costs at most one short timeout per pause. A
restarted inspector is picked up again.

## Instrumenting an existing Spring AI application

### Requirements

- Spring Boot 4 and Spring AI 2.x. The starter is built against Spring AI 2.1.0-M1.
- Java 17 or newer.

### 1. Build and install the starter

The starter is not published to a Maven repository. Install it into your local repository from the root of this
repository:

```bash
mvn install -pl spring-ai-inspector/spring-ai-inspector-starter -am -DskipTests
```

### 2. Add the dependency

Maven:

```xml
<dependency>
	<groupId>org.springaicommunity</groupId>
	<artifactId>spring-ai-inspector-starter</artifactId>
	<version>0.0.1-SNAPSHOT</version>
</dependency>
```

Gradle (add `mavenLocal()` to `repositories`):

```kotlin
implementation("org.springaicommunity:spring-ai-inspector-starter:0.0.1-SNAPSHOT")
```

The dependency can stay in place permanently: with no inspector running, the starter does nothing.

### 3. Start the inspector, then the app

```bash
mvn -pl spring-ai-inspector/spring-ai-inspector-server spring-boot:run
open http://localhost:9001
```

Start the app as usual. It appears in the inspector's run list as soon as its context starts.

**Order matters.** Detection and routing happen once, at startup:

- An app started *before* the inspector is not instrumented. Restart it.
- An app routed through the inspector needs the inspector to keep running. If the inspector stops, the app's
  model calls fail until it is back on the same port. A restarted inspector is fine.

### 4. Check what is captured automatically

Nothing else is needed when the app uses the usual Spring Boot beans:

| The app uses | Captured |
|---|---|
| the injected `ChatClient.Builder` | ChatClient calls, the advisor chain, model calls, memory, RAG setup |
| Anthropic, TypeSafe, or OpenAI / Ollama / Mistral / DeepSeek at their default endpoints | HTTP wire traffic |
| `@Tool` methods, tool callbacks, MCP tools | tool runs |
| a `VectorStore` bean | adds and searches, with scored results |
| an `EmbeddingModel` bean | embedding calls |
| the auto-configured MCP client (`spring.ai.mcp.client.*.connections.*`) | MCP messages, and which server each tool comes from |

### 5. Cover what is built by hand

The starter instruments beans and auto-configured builders. Objects the app creates itself need a small change.

**A `ChatClient` built from the model**, e.g. `ChatClient.builder(chatModel)`, gets no advisors and uses
`ObservationRegistry.NOOP`, so neither its ChatClient calls nor its tool runs are recorded. Its wire traffic still is.
Prefer the injected builder:

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {   // auto-configured, already instrumented
	return builder.defaultSystem("You are a helpful assistant").build();
}
```

If you need several builders or a specific model, pass the registry and apply the registered customizers yourself:

```java
@Bean
ChatClient reviewer(ChatModel chatModel, ObjectProvider<ObservationRegistry> observationRegistry,
		ObjectProvider<ChatClientBuilderCustomizer> customizers) {
	ChatClient.Builder builder = ChatClient.builder(chatModel,
			observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP), null, null);   // reports tool runs
	customizers.orderedStream().forEach(c -> c.customize(builder));   // adds the inspector advisors when active
	return builder.build();
}
```

**A vector store or embedding model created with `new`** inside another bean is invisible to the bean
post-processors. Expose it as a `@Bean` instead.

**A hand-built MCP client** still has its tool runs recorded, but not its MCP messages. To record those, wrap its
transport:

```java
@Bean
McpSyncClient weatherClient(ObjectProvider<InspectorClient> inspector, ObjectProvider<InspectorToolOrigins> origins) {
	McpClientTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:8080").build();
	if (inspector.getIfAvailable() != null) {   // the inspector beans exist only while it is active
		transport = new InspectorMcpClientTransport("weather", transport, inspector.getObject(), origins.getIfAvailable());
	}
	return McpClient.sync(transport).build();
}
```

### 6. Route other model providers

Providers not in the routing table, or OpenAI-compatible endpoints at a custom URL, are not routed by default.
Their calls still appear from the `MODEL` advisor's events, marked "no HTTP". To record their HTTP traffic, name
the base-url property to route:

```properties
# Groq through Spring AI's OpenAI client
spring.ai.openai.base-url=https://api.groq.com/openai
spring.ai.inspector.proxy.groq=spring.ai.openai.base-url

# or: route a non-default OpenAI base URL ending in /v1 (e.g. Amazon Bedrock mantle)
spring.ai.inspector.route.openai=always
```

The proxy forwards to the property's original value. The inspector recognizes the wire format (OpenAI-compatible,
Anthropic, Ollama, embeddings) from the request path.

Google GenAI and Bedrock (Converse) are not proxied. Those apps still show their advisor-level model calls.

### 7. Settings

| Property | Default | |
|---|---|---|
| `spring.ai.inspector.enabled` | `true` | `false` turns the starter off completely: no ping, no routing |
| `spring.ai.inspector.url` | `http://localhost:9001` | where the inspector runs |
| `spring.ai.inspector.memory-dirs` | `${agent.memory.dir}` | comma-separated folders to show as file-based memory |
| `spring.ai.inspector.route.openai` | | `always` routes a non-default OpenAI base URL ending in `/v1` |
| `spring.ai.inspector.proxy.<name>` | | base-url properties of another provider to route, comma-separated |

The starter sets `spring.ai.inspector.active`, `run-id`, `app`, `models`, `routed` and `upstream.<provider>` for
itself. Don't set them by hand.

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| The run doesn't appear | The inspector wasn't reachable at startup (check `spring.ai.inspector.url`), it started after the app, or `spring.ai.inspector.enabled=false`. |
| A run, but no "On the wire" calls | The provider isn't routed: a custom base URL or an unlisted provider. See [step 6](#6-route-other-model-providers). |
| Wire calls, but no ChatClient calls | The `ChatClient` was built with `ChatClient.builder(chatModel)`. See [step 5](#5-cover-what-is-built-by-hand). |
| No tool runs | The app's `ObservationRegistry` is `ObservationRegistry.NOOP`, which ignores handlers, e.g. a `ChatClient` built with `ChatClient.builder(chatModel)`. See [step 5](#5-cover-what-is-built-by-hand). |
| `Spring AI Inspector unreachable ..., pausing events for 5s` | The inspector stopped. Events resume when it's back. Routed model calls fail meanwhile. |
| Model calls fail with "connection refused" to port 9001 | The app was routed through an inspector that has since stopped. Start it again, or restart the app. |

The inspector listens on `127.0.0.1` by default. Its event stream contains prompts, tool results and memory
contents, so keep it local.
