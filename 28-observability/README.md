# 28 - Observability: Prometheus, Grafana, Tempo and Loki

A conference helpdesk agent (three tools, a FAQ in a vector store, two model providers) kept busy by its own
traffic generator, with Spring AI's Micrometer observations exported three ways: **metrics** to Prometheus,
**traces** to Tempo and **logs** to Loki, all shown in Grafana, cross-linked. Failure modes are switched on live,
from a control page or with `curl`, so the panels move on cue. The Spring AI Inspector, if it is running, records
the same calls one by one: Grafana shows the aggregate, the inspector the single call, from the same observations.

## Run

```bash
cd 28-observability/docker && docker compose up -d        # Prometheus :9090, Tempo :3200/:4318, Loki :3100, Grafana :3000
cd .. && mvn spring-boot:run                              # or run HelpdeskApplication from the IDE
```

Needs `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` (the repo-root `.env` is picked up). The app answers on
`localhost:8080` and starts sending itself one question every 4 seconds, alternating Claude Haiku and GPT mini.
<http://localhost:8080> is a small control page: start and stop the traffic, set the chaos switches with sliders
or presets, ask a question by hand, and links to everything below. Each move on it is also a `curl` away.

Open Grafana at <http://localhost:3000> (no login) → Dashboards → **Spring AI**, and give it a minute of traffic.

## The demo: metrics, traces, logs

### 1. Metrics: what the framework measures

Spring AI wraps every ChatClient call, advisor, model call, tool run and vector store operation in a Micrometer
observation, and Actuator turns each into a timer (plus the token counter). The **Spring AI** dashboard reads them:

| Move | Control page / command | Watch |
|---|---|---|
| Baseline | nothing | calls, tokens and p95 per model; tool, retrieval and advisor panels; the spend estimate |
| A flaky tool | preset *flaky tool*, or `curl -X POST 'localhost:8080/chaos?toolFailureRate=0.5'` | *Tool failures* rises (the app's own counter), *Model errors* does not: Spring AI hands the exception to the model as the tool result and the model recovers, at the cost of tokens and latency |
| A slow advisor | preset *slow advisor*, or `curl -X POST 'localhost:8080/chaos?latencyMs=1500'` | `ChaosLatencyAdvisor` appears on the advisor panel; *ChatClient p95* shifts, *Model latency* does not |
| A wrong model name | preset *wrong model*, or `curl -X POST 'localhost:8080/chaos?badModelEveryN=3'` | *Model error rate* and *errors by model*, with the error type; both providers, since the count is per provider |

*Reset all* (or `curl -X POST localhost:8080/chaos/reset`) puts everything back. The **JVM (Micrometer)** dashboard
is linked from the top of the page.

### 2. Traces: one call, span by span

The same observations are spans in Tempo, nested as the calls nest. A traffic-generator question with one tool
call reads, top to bottom: `task userQuestionGenerator.tick` (the scheduler), `spring_ai chat_client`, the advisors
in order (`content_log`, `chaos_latency`, `question_answer` with its `embedding text-embedding-3-small` call and
`simple query` vector store search under it, `tool_calling`), then `chat claude-haiku-5-5` with the HTTP `POST` to
the provider, `execute_tool roomFor`, and the second `chat` round. Each span carries the Spring AI attributes:
provider, request and response model, token counts, finish reason, tool name.

Three ways in, best shown in this order:

1. **From a metric.** On *Model latency p95*, hover a dot and click *Query with Tempo*. The dot is an exemplar: a
   real call behind the percentile, with its trace id. This is the strongest moment: the aggregate and the individual
   in one click.
2. **By search.** Explore → Tempo → TraceQL:
   ```
   { span.gen_ai.request.model = "claude-haiku-5-5" && duration > 3s }     # slow Claude calls
   { name =~ "execute_tool.*" }                                             # every tool run
   ```
   After the *wrong model* preset, filter on `status = error` to find the failed model calls.
3. **The service graph.** Explore → Tempo → Service Graph. Tempo's metrics generator builds it from the spans:
   the app, the two providers and the tools, with rates and error rates on the edges.

With the three commented-out properties in `application.properties` (`log-prompt`, `log-completion`,
`include-content`) the prompt, the completion and the tool arguments and results go into the span events too.
That needs a restart and is off on purpose: see the privacy point below.

### 3. Logs: the lines of one call

Every log line carries the trace and span id in a bracket, `[traceId-spanId]`, on the console and in Loki, where
the same lines are pushed by a Logback appender (`logback-spring.xml`). Two panels at the bottom of the dashboard
show them: *Questions and answers* and *Warnings and errors*.

- **Content stays out by default.** Prompts and completions are in no metric, span or log. Tick *Log questions and
  answers* on the control page (or `curl -X POST 'localhost:8080/chaos?logContent=true'`): from then on the
  question and the answer of each call are logged, with their trace id, and appear in the *Questions and answers*
  panel. That is the privacy point: content is a switch you own, not something the instrumentation does for you.
- **Search.** Explore → Loki:
  ```
  {app="conference-helpdesk"} | logger="helpdesk.content"            # the questions and answers
  {app="conference-helpdesk", level=~"WARN|ERROR"}                    # failed calls
  {app="conference-helpdesk"} | traceId="<id>"                        # everything one call logged
  ```

### 4. Cross-references: the loop

- **Metric → trace:** the exemplar dot on a latency panel.
- **Trace → logs:** on any span in Tempo, *Logs for this span* opens Loki with that trace id.
- **Log → trace:** in a Loki log line, the `[traceId-spanId]` bracket is a link that opens the trace in Tempo
  (Grafana's derived field, see `datasources.yml`).
- **Trace → metrics:** on a span, *Related metrics* runs the model-call rate and p95 queries for that service.

So the loop for the talk: a slow dot on the dashboard → its trace → the span that took the time → its log lines,
with the question that caused it → back to the trace from the log line. Switch content logging on first, or the
log lines only say how long the call took.

## What is where

- `docker/compose.yaml`: Prometheus 3 (exemplar storage on, remote-write receiver for Tempo's span metrics),
  Tempo 2.8 (OTLP on 4318, metrics generator → Prometheus), Loki 3 (push API on 3100), Grafana 12 (anonymous admin,
  provisioning mounted).
- `docker/grafana/provisioning/dashboards/spring-ai.json`: the Spring AI dashboard, built on the 2.1 metric
  names: `gen_ai_client_operation_seconds` and `gen_ai_client_token_usage_total` (models), `spring_ai_chat_client_seconds`,
  `spring_ai_advisor_seconds`, `spring_ai_tool_seconds`, `db_vector_client_operation_seconds`, plus the two Loki
  panels. The spend panel multiplies tokens by the prices in the dashboard variables; set them to your list prices.
- `docker/grafana/provisioning/dashboards/jvm-micrometer.json`: the stock JVM dashboard (grafana.com 4701).
- `docker/grafana/provisioning/datasources/datasources.yml`: Prometheus with exemplars linked to Tempo; Tempo with
  trace-to-metrics queries, the service map and the trace-to-logs link into Loki; Loki with the derived field that
  links a log line back to its trace.
- `static/index.html`: the control page, plain HTML and fetch calls to the endpoints, no build step.
- `logback-spring.xml`: Boot's console output as usual, plus the Loki appender with the trace ids as structured
  metadata.
- `Chaos`: the failure switches. `HelpdeskApplication` adds the `helpdesk.tool.failures` counter in a
  `ToolExecutionExceptionProcessor`, the one metric the app adds next to Spring AI's: it counts every tool failure,
  injected or real.
- `application.properties`: percentile histograms for the Spring AI timers (needed for p95 and exemplars),
  `management.observations.key-values.application` and `management.metrics.tags.application` as the common tag,
  OTLP export of traces, sampling at 100 %.

## How the pieces connect

Spring AI wraps every ChatClient call, advisor, model call, tool run and vector store operation in a Micrometer
observation. Boot's Actuator turns each into a timer (and the token counter) for Prometheus, and Micrometer Tracing
turns each into a span for Tempo, nested as the calls nest, while the trace id lands in the MDC of every log line
written during the call. Because all three come from the same observation, a latency bucket sample can carry the
trace id of a real call (the exemplar), a span can name the log lines written under it, and a log line can name its
trace. The `ChatClient`s are built by hand here (two providers need two builders), so they are given the
application's `ObservationRegistry`, its tool-calling advisor and the registered customizers explicitly; see
`HelpdeskAgent`.
