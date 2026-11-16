# 05-1 Modular RAG

The [05-rag](../05-rag) demo answers a question about Hurricane Milton with a single `QuestionAnswerAdvisor`:
embed the question, fetch similar chunks, stuff them into the prompt.

This demo answers the same kind of question through Spring AI's
[Modular RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html) support
(`spring-ai-rag`). The `RetrievalAugmentationAdvisor` runs a pipeline where every stage is a pluggable
building block, following the paper
[Modular RAG: Transforming RAG Systems into LEGO-like Reconfigurable Frameworks](https://arxiv.org/abs/2407.21059).

## Pipeline used in this demo

| Phase | Component | What it does here |
|---|---|---|
| Pre-retrieval | `RewriteQueryTransformer` | Turns the verbose, chatty user question into a search-friendly query (LLM call) |
| Pre-retrieval | `MultiQueryExpander` | Expands that query into 3 semantically diverse variants plus the original (LLM call) |
| Retrieval | `VectorStoreDocumentRetriever` | Similarity search per query, top 4 above a 0.5 threshold; the default `ConcatenationDocumentJoiner` dedups the union |
| Post-retrieval | lambda `DocumentPostProcessor` | Prints each chunk's similarity score and a snippet (used before and after the decision stages) |
| Post-retrieval | `DecisionDocumentFilter` | Decision model screening: drops injected or irrelevant passages (see below) |
| Post-retrieval | `DecisionDocumentReranker` | Decision model reranking by "could this passage answer the query?", keeps the top 3 |
| Generation | `ContextualQueryAugmenter` | Stuffs the context into the prompt; `allowEmptyContext(true)` lets the model answer even with no hits |

### Flow

What `RetrievalAugmentationAdvisor` does for one `chatClient.prompt().user(...).call()`:

```mermaid
flowchart TD
    U([User question<br/>verbose, chatty]) --> RW

    subgraph PRE[Pre-retrieval]
        RW[RewriteQueryTransformer<br/>LLM call: search-friendly query] --> EX[MultiQueryExpander<br/>LLM call: original + 3 variants]
    end

    subgraph RET[Retrieval - one branch per query, in parallel]
        EX --> Q1[Query 1] & Q2[Query 2] & Q3[Query 3] & Q4[Query 4]
        Q1 & Q2 & Q3 & Q4 --> VS[(VectorStoreDocumentRetriever<br/>similarity ≥ 0.5, top 4 each)]
        VS --> J[ConcatenationDocumentJoiner<br/>union, duplicates removed]
    end

    subgraph POST[Post-retrieval]
        J --> P1[print retrieved chunks<br/>+ similarity score]
        P1 --> F[DecisionDocumentFilter<br/>injection? contradiction? relevant? evidence?]
        F -- EXCLUDED --> X[/dropped/]
        F -- INCLUDED / CONFLICTING --> R[DecisionDocumentReranker<br/>could this answer the query? keep top 3]
        R --> P2[print reranked chunks<br/>+ decision score]
    end

    subgraph GEN[Generation]
        P2 --> A[ContextualQueryAugmenter<br/>context + original question into one prompt]
        A --> LLM[Chat model]
    end

    LLM --> ANS([Answer])
```

Pre-retrieval stages receive the query as the user typed it. The retrieval branches fan out on the advisor's
`TaskExecutor` and are joined before post-processing. Note that the post-processors and the augmenter work on
the *original* question, not the rewritten one, which is why the final answer still addresses the user's phrasing.

Everything lives in [DemoApplication.java](src/main/java/com/example/demo/DemoApplication.java).
The small `logging(...)` wrapper around the retriever prints every query that reaches the vector store,
so you can see the rewrite and expansion stages at work.

The LLM-backed stages get their own cloned `ChatClient.Builder` so they do not inherit the main client's
advisors. The Spring AI docs recommend a low temperature for these stages; it is omitted here because the
Claude model used in this demo no longer accepts the `temperature` option.

## Prerequisites

- Java 17+ and Maven
- `ANTHROPIC_API_KEY` for the Claude chat model
- `TYPESAFE_API_KEY` for the decision model of the post-retrieval stages (TypeSafe's Jev); the app fails fast at
  startup without it
- Embeddings run locally through the transformers starter (ONNX model downloaded on first run)

## Running

```bash
mvn -pl 05-1-modular-rag spring-boot:run
```

Expected console output, in order:

1. `[retrieve] ...` lines: the rewritten question and its expanded variants (interleaved, retrieval runs in parallel)
2. `[retrieved] <similarity> <snippet>...` lines, one per chunk the joiner produced
3. `[decision-reranked] <decision score> <snippet>...` lines, the top 3 after screening and reranking
4. The `MyLoggingAdvisor` dump of the augmented user prompt
5. The final answer

The advisor runs the per-query retrievals in parallel. The demo passes it Spring Boot's auto-configured
`TaskExecutor` (virtual threads, see `spring.threads.virtual.enabled`) because the advisor's own default
pool uses non-daemon threads that would keep this command-line app running after the answer is printed.

## Decision model screening and reranking

Spring AI 2.1 (`spring-ai-rag`) ships two `DocumentPostProcessor`s backed by a `DecisionModel`, a small
classifier that answers yes/no questions about a query and a passage with a calibrated probability:

| Component | What it does |
|---|---|
| `DecisionDocumentFilter` | Asks four questions per passage (prompt injection, contradicts the query's premise, relevant, contains answer evidence) and drops injected or irrelevant passages. Contradicting passages are kept and tagged in metadata. |
| `DecisionDocumentReranker` | Asks "could this passage answer the query?" per passage, sorts by that score (stored under `decision.rerank.score`) and keeps the top K. |

The decision model here is TypeSafe's Jev, through Spring AI's System One provider:
`spring-ai-starter-model-systemone` auto-configures a `DecisionModel` bean from `spring.ai.systemone.api-key`,
which [application.properties](src/main/resources/application.properties) reads from `TYPESAFE_API_KEY`
(add it to the repo-root `.env` or export it). The OpenAI starter is on the classpath too and could contribute
its own decision model, so `spring.ai.model.decision=systemone` picks one. The demo injects the `DecisionModel`
and passes it to both builders; any other provider (OpenAI, Ollama) works the same way.

Both components fail open: if the decision model is unreachable the passages pass through unscreened or
unscored and a warning is logged, so a wrong key or an outage degrades to plain modular RAG rather than breaking
the demo. A missing key is different: the System One auto-configuration refuses to start without one.
`DecisionDocumentFilter.builder(...).failClosed(true)` drops unscreened passages instead.

## Compared with 05-rag

The ingestion code is identical. The only change is `QuestionAnswerAdvisor` becoming a
`RetrievalAugmentationAdvisor` with explicit, swappable stages. Any stage can be replaced with your own
implementation of `QueryTransformer`, `QueryExpander`, `DocumentRetriever`, `DocumentJoiner`,
`DocumentPostProcessor` or `QueryAugmenter`. The decision stages are an example of dropping in
post-processors backed by a different kind of model without touching the rest of the pipeline.
