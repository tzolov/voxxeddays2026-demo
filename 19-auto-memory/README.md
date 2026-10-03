# Auto Memory Demo

Demonstrates `AutoMemoryToolsAdvisor` for persistent, file-based memory in Spring AI agents. Unlike conversation history (which is session-scoped), auto memory survives across conversations — storing user preferences, project context, behavioral feedback, and external references.

On top of that, `AutoDreamAdvisor` and `AutoDreamService` keep the memory store tidy: a background **Dreamer** subagent periodically merges duplicates, prunes stale entries and fixes the `MEMORY.md` index, and can search the user's past sessions for decisions and corrections worth remembering.

## What It Shows

- **Durable memory**: Knowledge persists beyond the current session in Markdown files
- **Four memory types**: `user`, `feedback`, `project`, and `reference`
- **Six memory tools**: `MemoryView`, `MemoryCreate`, `MemoryStrReplace`, `MemoryInsert`, `MemoryDelete`, `MemoryRename`
- **In-band consolidation**: The live agent tidies its memory when the user says "bye", or on the first message after 60 seconds of silence
- **MEMORY.md index**: A two-step save workflow — each memory file is created, then registered in an index
- **Out-of-band consolidation (dreaming)**: Every 3rd turn, a dream cycle runs in the background after the answer is returned, so it never adds latency. Type `/dream` to run one immediately
- **Cross-session recall**: The Dreamer gets a read-only `cross_session_search` tool over the user's session history (`spring-ai-session`)
- **Seeded signal**: On first run the memory store gets two duplicate feedback memories and a dangling `MEMORY.md` link, and a two-day-old past session with a decision and a correction is seeded on every run, so the first dream has real work to do

### In-band nudge vs. dreaming

| | `memoryConsolidationTrigger` | Auto-Dream |
|---|---|---|
| Runs | In the live agent's current turn | In a background Dreamer subagent |
| Blocks the user | Adds latency and tokens to the turn | Never |
| Sees | What is already in the live context | The whole memory store and past sessions |
| Cost | Cheap, frequent, shallow | Heavier, periodic, deep |

## Running the Demo

```bash
export ANTHROPIC_API_KEY=your-key

mvn spring-boot:run -pl 19-auto-memory
```

Memory files are stored at `~/.spring-ai-agent/spring-io-2026/memory/`. The seeded memories are only written when that directory has no `MEMORY.md`, so to reset the demo:

```bash
rm -rf ~/.spring-ai-agent/spring-io-2026/memory
```

Session history is in-memory and resets on every run.

## Example Run

**Session 1:**
```
USER> My name is Alice and I prefer concise answers.

ASSISTANT> Got it, Alice! I'll keep things concise.

USER> bye

ASSISTANT> Goodbye!
# Memory consolidated — user preference saved to disk
```

**Dreaming (the summary is written by the model, so it varies):**
```
USER> /dream

DREAM> completed: Merged feedback_testing_a.md and feedback_testing_b.md into one memory,
       removed the dangling project_old_sprint.md link, and saved the PostgreSQL decision
       and the switch to blue-green deployment found in a past session.
```

After 3 turns the same happens on its own; watch the log for `Dream cycle [...] finished with status=...`. The cycle also writes `.dream-state.json` to the memory directory.

**Session 2 (new process):**
```
USER> What do you know about me?

ASSISTANT> You're Alice and you prefer concise answers.
```

## Key Components

| Component | Purpose |
|-----------|---------|
| `AutoMemoryToolsAdvisor` | Advisor that injects memory tools and triggers consolidation |
| `MemoryConsolidationTrigger` | Callback deciding when to consolidate memory (e.g. "bye", idle timeout) |
| `MEMORY.md` | Index file listing all memory file pointers |
| `AutoDreamService` | Runs a dream cycle: a Dreamer subagent with only the memory tools and `cross_session_search` |
| `AutoDreamAdvisor` | Schedules dream cycles in the background from a persisted `DreamTrigger` |
| `SessionMemoryAdvisor` | Session-scoped conversation history, compacted to the last 10 events after 20 turns; also what the Dreamer searches |

## Related Resources

- [AutoMemoryTools Documentation](https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/tools/AutoMemoryTools/)
- [AutoDreamService Documentation](https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/tools/AutoDreamService/)
- [AutoDreamAdvisor Documentation](https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/tools/AutoDreamAdvisor/)
