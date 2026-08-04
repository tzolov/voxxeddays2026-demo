# Guardrails: Ontology Checker

Demonstrates `OntologyValidationAdvisor`, a Spring AI guardrail that checks structured
LLM output for *semantic* correctness using an RDFS/OWL ontology and SHACL shapes
(Apache Jena), not just JSON shape.

Inspired by Frank Coyle's talk ["Why Agentic Systems Need Ontologies"](https://youtu.be/Sir59K8ZDPU):
JSON Schema / Pydantic validation catches structural errors ("is this a valid object?");
it cannot catch semantic errors ("is this a *sensible* object?") — a refund paid to the
support desk instead of the customer is perfectly valid JSON. Coyle's summary: *"Pydantic
at the door, ontology at the ledger."* `StructuredOutputValidationAdvisor` (built into
Spring AI) already plays the Pydantic role; this module explores what the ontology role
looks like.

![The convergence: neurosymbolic AI — the agent proposes, the ontology permits; probabilistic reasoning inside, logical constraints outside.](agent-ontology-convergence.png)

## What It Shows

- **Two independent checks**, both run against one merged RDF graph (candidate output +
  background facts):
  - **SHACL shapes** — structural/enumeration constraints, no reasoning required:
    invalid enum values, cardinality limits.
  - **OWL reasoning** — relational/identity constraints that only surface after
    inference: `rdfs:range` + `owl:disjointWith` combine to make an otherwise-valid
    triple logically inconsistent.
- **JSON → RDF lifting** via a JSON-LD `@context`, so the advisor works against the raw
  structured-output JSON without changing the domain records.
- **The same retry-with-feedback loop** as `StructuredOutputValidationAdvisor`: on
  failure, violation messages are appended to the user message and the model is
  re-invoked (up to `maxRepeatAttempts` times).

## The Domain: Order Support Agent

An agent decides an order's status and refund given a customer complaint. The ontology
([`order-support.ttl`](src/main/resources/ontology/order-support.ttl)) declares:

- `Customer` and `SupportRepresentative` are `owl:disjointWith` each other.
- `refundRecipient` has `rdfs:range Customer`.
- Background facts: `cust-1001`, `cust-2002` are customers; `rep-77`, `rep-88` are
  support reps.

This reproduces the three concrete failure modes from the talk:

| Failure mode | Caught by | Example |
|---|---|---|
| Invalid enum value | SHACL (`sh:in`) | `status: "probably shipped"` |
| Duplicate refund | SHACL (`sh:maxCount 1`) | two `refunds[]` entries on one decision |
| Refund to the wrong role | OWL reasoning | `recipientId: "rep-77"` — the reasoner infers `rep-77 a Customer` from the property's range, which contradicts `rep-77`'s asserted type given the disjointness axiom |

## How It Works

```
LLM JSON output
      │  inject "@type" + merge "@context"   (order-support-context.json)
      ▼
JSON-LD document
      │  RDFDataMgr (JSON-LD 1.1)
      ▼
candidate RDF graph  ──merge──►  background ontology + known-entity facts
                                          │
                         ┌────────────────┴────────────────┐
                         ▼                                  ▼
                 ShaclValidator.validate            InfModel(OWL reasoner).validate
                 (order-support.shapes.ttl)          (order-support.ttl)
                         │                                  │
                         └──────────► violations ◄──────────┘
                                          │
                         success → return response
                         failure → append violations to user message, retry
```

See [`OntologyValidationAdvisor`](src/main/java/com/example/demo/OntologyValidationAdvisor.java).

## Running the Tests (no API key needed)

The ontology-checking core is tested directly against canned JSON payloads, bypassing
the LLM entirely:

```bash
mvn -pl 21-guardrails-ontology-checker -am test
```

Covers: a valid decision, an invalid status enum, a duplicate refund, a negative refund
amount, and a refund routed to a support rep.

## Running the Demo

```bash
export OPENAI_API_KEY=your-key-here
mvn -pl 21-guardrails-ontology-checker spring-boot:run
```

`DemoApplication` prompts the model to resolve a damaged-order complaint for a known
customer, validated through `OntologyValidationAdvisor` before printing the final,
ontology-valid `RefundDecision`.

## Key Components

| Component | Purpose |
|---|---|
| `OntologyValidationAdvisor` | The guardrail advisor: JSON → RDF lifting, SHACL + OWL checks, retry loop |
| `order-support.ttl` | Domain ontology (TBox: classes/properties/disjointness) + background facts (ABox) |
| `order-support.shapes.ttl` | SHACL shapes for structural/enum/cardinality constraints |
| `order-support-context.json` | JSON-LD `@context` mapping JSON fields to ontology terms |
| `OrderDomain` | `RefundDecision` / `RefundLine` records — the structured output type |

## Verified Against a Live Model

Running the demo confirms the reasoner catches a real, live violation, not just canned
test JSON: prompted to "refund rep-77 for handling this case," the model produced
`recipientId: "rep-77"` on every one of the 4 attempts (initial + 3 retries), and the
OWL reasoner rejected all of them with the disjoint-class conflict — even after being
told why each attempt failed. It never self-corrected to `cust-1001` within the retry
budget, because the error message ("Individual a member of disjoint classes") doesn't
tell the model *what to do instead*, only that the graph is inconsistent.

That run also caught a real defect: the advisor originally returned the last response
unconditionally after the loop, regardless of whether validation had ever succeeded —
so an ontology-invalid `RefundDecision[..., recipientId=rep-77]` was silently printed
as the "final validated decision." Fixed: the advisor now throws
`OntologyValidationAdvisor.OntologyValidationException` (carrying the last response)
when retries are exhausted without a passing validation, so a persistently-wrong answer
fails loudly instead of leaking through.

## Known Limitations

- **Duplicate-refund detection is single-turn only.** The SHACL cardinality check only
  sees the current response; it doesn't know about refunds issued in earlier turns.
  Real cross-turn dedup would need the background graph updated with prior decisions.
- **Authoring the ontology, shapes, and JSON-LD context is manual, domain-specific
  work** — exactly the "who maintains the ontology?" question the talk itself raises.
  Nothing here generates them from the Java record type automatically.
- **`arrayFieldType`/`rootType` wiring is per-domain**, not inferred from the output
  type the way `StructuredOutputValidationAdvisor`'s JSON Schema is.
- **The full OWL reasoner re-runs inference over the entire merged graph on every
  call.** Fine at this demo's scale (a handful of background facts), and it's the
  reasoner needed for `owl:disjointWith`-based inconsistency detection — the lighter
  Micro/Mini reasoners may not support that. It won't scale to a large background
  knowledge graph without moving to a persistent/indexed store.

## Related

- [`StructuredOutputValidationAdvisor`](https://github.com/spring-projects/spring-ai) — the JSON-Schema ("Pydantic") counterpart to this advisor
- [Why Agentic Systems Need Ontologies — Frank Coyle](https://youtu.be/Sir59K8ZDPU)
- [Apache Jena SHACL](https://jena.apache.org/documentation/shacl/) / [Jena Inference](https://jena.apache.org/documentation/inference/)
