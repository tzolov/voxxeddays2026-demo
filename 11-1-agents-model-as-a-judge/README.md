# 11.1 Model as a judge

A self-refine loop with a `DecisionJudge` doing the judging, from
[Spring AI Decision Extensions](https://github.com/spring-ai-community/spring-ai-decision-extensions)
(`spring-ai-decision-self-refine`), built on Spring AI 2.1's `DecisionModel` API.

The weather tool returns an absurd temperature part of the time (-125 or -255 °C). The
`DecisionSelfRefineAdvisor` judges every answer against four criteria, and when one fails it calls the model
again with the defect as feedback:

| Criterion | Kind | Passes when |
|---|---|---|
| `helpfulness` | score, four levels | at least half of the probability is on level 2 or above |
| `is_plausible` | yes/no | the probability of yes reaches `demo.judge.plausible-threshold` |
| `is_grounded` | yes/no | the probability of yes reaches 0.5 |
| `used_weather_tool` | code check | the weather tool was called; never sent to the decision model |

An answer that is fluent and on topic but quotes an impossible temperature fails on `is_plausible` alone.

## Running

Requires `ANTHROPIC_API_KEY` and `TYPESAFE_API_KEY`. The judge is TypeSafe's Jev, through Spring AI's
System One provider.

```bash
mvn -pl 11-1-agents-model-as-a-judge spring-boot:run
```

The `[TOOL]` lines show the temperature the tool returned, and the advisor logs each judgement. The
temperature is random, so the number of attempts varies from run to run. Two temperatures out of three are
absurd, which is why the demo allows 10 retries: with 3, as in the blog post, about one run in five would
still end on a rejected answer. A run where the first temperature was a real one:

```
[TOOL] weather tool response temperature: 15
The judgement passed on attempt 1: passed=true [helpfulness=PASSED, is_plausible=PASSED, is_grounded=PASSED, used_weather_tool=PASSED]
```

### Without a TypeSafe key: the `ollama` profile

The same code runs with a local decision model. The chat model stays Claude, so `ANTHROPIC_API_KEY` is
still needed.

```bash
ollama pull d1-omni:600m
mvn -pl 11-1-agents-model-as-a-judge spring-boot:run -Dspring-boot.run.profiles=ollama
```

The profile sets two properties, in `application-ollama.properties`:

- `spring.ai.model.decision=ollama` picks the Ollama decision model. Both starters are on the classpath.
- `demo.judge.plausible-threshold=0.5`, where the default is 0.7. The small local model gives a plausible
  answer about 0.68 and an absurd one about 0.21, so the 0.7 that fits Jev would reject correct answers.

A run with this profile, where the tool returned absurd temperatures first:

```
[TOOL] weather tool response temperature: -255
The judgement failed on attempt 1: passed=false [helpfulness=FAILED, is_plausible=FAILED, is_grounded=PASSED, used_weather_tool=PASSED]
[TOOL] weather tool response temperature: -255
[TOOL] weather tool response temperature: -125
The judgement failed on attempt 2: passed=false [helpfulness=FAILED, is_plausible=FAILED, is_grounded=PASSED, used_weather_tool=PASSED]
[TOOL] weather tool response temperature: 15
The judgement passed on attempt 3: passed=true [helpfulness=INCONCLUSIVE, is_plausible=PASSED, is_grounded=PASSED, used_weather_tool=PASSED]
```

Thresholds belong to a model. [26-decision-models](../26-decision-models) prints the scores of both models
for the same answers.
