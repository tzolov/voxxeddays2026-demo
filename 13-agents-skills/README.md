# Skills Demo

A focused demonstration of the SkillsTool system for building custom AI agent capabilities.

## Overview

This demo shows how to create and use AI agent skills - specialized capabilities defined in Markdown files that can include helper scripts, reference documentation, and custom tool restrictions.

## Features

- **ai-tutor** skill - Creates educational PDF tutorials explaining technical concepts
- **pdf** skill - Generates PDF documents from content
- YouTube transcript integration for research-based content
- Classpath-based skill loading for packaged applications
- Python helper scripts for extended functionality

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.6+
- AI provider API key (Anthropic, OpenAI, or Google)
- Python 3.9+ (for YouTube transcript fetching)

### Setup

1. Set your API key:
```bash
export ANTHROPIC_API_KEY=your-key-here
# Or use OpenAI: export OPENAI_API_KEY=your-key-here
# Or use Google: export GOOGLE_CLOUD_PROJECT=your-project-id
```

2. Optional - for web search:
```bash
export BRAVE_API_KEY=your-brave-key
```

3. Run the demo:
```bash
mvn spring-boot:run
```

## How It Works

The demo executes a single prompt that:
1. Requests an explanation of a technical concept (reinforcement learning)
2. References a YouTube video for supporting content
3. Automatically invokes the ai-tutor skill
4. Fetches the YouTube transcript using Python
5. Generates an educational explanation
6. Can create a PDF tutorial

### Example Code

```java
var answer = chatClient.prompt("""
    Explain reinforcement learning in simple terms and use.
    Use required skills.
    Then use the Youtube video https://youtu.be/vXtfdGphr3c transcript
    to support your answer.
    """).call().content();
```

## Skills Configuration

Skills are loaded from classpath resources in `src/main/resources/.claude/skills/`:

```
src/main/resources/.claude/skills/
├── ai-tutor/
│   ├── SKILL.md                    # Skill definition
│   ├── REFERENCE.md                # Supporting documentation
│   └── scripts/
│       └── get_youtube_transcript.py
└── pdf/
    ├── SKILL.md
    └── scripts/
```

Configure in `application.properties`:
```properties
agent.skills.dirs=classpath:/.claude/skills
```

## Creating Your Own Skills

1. Create a directory under `.claude/skills/`
2. Add a `SKILL.md` with YAML frontmatter:

```markdown
---
name: my-skill
description: What the skill does and when to use it
allowed-tools: Read, Bash, Grep
model: claude-sonnet-4-5-20250929
---

# Skill Instructions
Your detailed prompt for the AI agent...
```

3. Optionally add helper scripts and reference files

## Architecture

The demo uses:
- **SkillsTool** - Loads and manages agent skills
- **ShellTools** - Executes shell commands (for running Python scripts)
- **FileSystemTools** - Reads/writes files (for PDF generation)
- **SmartWebFetchTool** - Fetches web content
- **BraveWebSearchTool** - Web search capabilities

## Switching AI Providers

Update `pom.xml` and `application.properties` to use different providers:

**Anthropic Claude** (default):
```properties
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5-20250929
```

**OpenAI**:
```properties
spring.ai.openai-sdk.api-key=${OPENAI_API_KEY}
spring.ai.openai-sdk.chat.options.model=gpt-5-mini-2025-08-07
```

**Google Gemini**:
```properties
spring.ai.google.genai.project-id=${GOOGLE_CLOUD_PROJECT}
spring.ai.google.genai.chat.options.model=gemini-3.1-pro-preview
```

## Learn More

- [SkillsTool Documentation](../../spring-ai-agent-utils/docs/SkillsTool.md)
- [Examples Overview](../README.md)
- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)

## License

Apache License 2.0
