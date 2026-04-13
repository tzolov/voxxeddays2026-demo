# Auto Memory Demo

Demonstrates `AutoMemoryToolsAdvisor` for persistent, file-based memory in Spring AI agents. Unlike conversation history (which is session-scoped), auto memory survives across conversations — storing user preferences, project context, behavioral feedback, and external references.

## What It Shows

- **Durable memory**: Knowledge persists beyond the current session in Markdown files
- **Four memory types**: `user`, `feedback`, `project`, and `reference`
- **Six memory tools**: `MemoryView`, `MemoryCreate`, `MemoryStrReplace`, `MemoryInsert`, `MemoryDelete`, `MemoryRename`
- **Consolidation triggers**: Memory is saved when the user says "bye" or after 60 seconds of inactivity
- **MEMORY.md index**: A two-step save workflow — each memory file is created, then registered in an index

## Running the Demo

```bash
export ANTHROPIC_API_KEY=your-key

mvn spring-boot:run -pl 19-auto-memory
```

Memory files are stored at `~/.spring-ai-agent/spring-io-2026/memory/`.

## Example Run

**Session 1:**
```
USER> My name is Alice and I prefer concise answers.

ASSISTANT> Got it, Alice! I'll keep things concise.

USER> bye

ASSISTANT> Goodbye!
# Memory consolidated — user preference saved to disk
```

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
| `MessageWindowChatMemory` | Session-scoped conversation history (last 100 messages) |

## Related Resources

- [AutoMemoryTools Documentation](https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/tools/AutoMemoryTools/)
