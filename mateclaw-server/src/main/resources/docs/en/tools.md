# Tools

**A tool is a hand the agent can reach out with.**

Left to its own devices, a language model is a pattern-matcher wrapped in text. It doesn't know what time it is. It doesn't know what's in your files. It can't search the web, run a command, look at a PDF, delegate to another agent, or open a browser. It can only *talk about* doing those things.

Tools are how GLClaw fixes this. Each tool is a concrete operation the agent is allowed to invoke ‚Ä?read a file, search the web, execute a shell command, extract text from a PDF, delegate to another agent. When the agent decides it needs one, it emits a **tool call**, the runtime executes it, and the result comes back as an **observation**.

Fourteen tools ship built-in. Unlimited more can be added through MCP servers, custom skill scripts, or your own `@Tool`-annotated Spring beans.

---

## How a tool call actually happens

```
Agent decides it needs a tool
        ‚î?
        ‚ñ?
  Emits a tool call:  {"name": "WebSearchTool", "args": {"query": "..."}}
        ‚î?
        ‚ñ?
  ‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚î?
  ‚î?  Tool registry     ‚î? ‚Ü?look up the tool by name
  ‚îî‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚î?
        ‚î?
        ‚ñ?
  ‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚î?
  ‚î?  Tool Guard        ‚î? ‚Ü?rule-based check: allow / deny / approval
  ‚îî‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚î?
        ‚î?
   ‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚î¥‚îÄ‚îÄ‚îÄ‚îÄ‚î?
   ‚î?        ‚î?
   ‚ñ?        ‚ñ?
 allowed  approval pending ‚Ü?user decides ‚Ü?allowed / rejected
   ‚î?
   ‚ñ?
  ‚îå‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚î?
  ‚î? Execute (timeout)  ‚î? ‚Ü?async, per-tool timeout
  ‚îî‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚îÄ‚î?
        ‚î?
        ‚ñ?
  Result ‚Ü?observation ‚Ü?agent's next reasoning step
```

Tool Guard is the gatekeeper. Timeouts are per-tool (so one slow tool can't freeze a turn). Execution can be concurrent inside a single Action phase ‚Ä?if the agent calls three independent tools at once, they run in parallel.

None of this shows up in the agent's prompt. The agent just asks for a tool. The runtime handles everything in front of, during, and after the call.

---

## Tool registration ‚Ä?three paths

**1. Built-in tools.** The twenty tools that ship with GLClaw ‚Ä?registered into the tool table on startup.

**2. MCP servers.** External processes speaking the Model Context Protocol expose tools dynamically. GLClaw discovers them via `tools/list` and they appear in the registry alongside built-in ones. See [MCP](./mcp).

> **Per-agent MCP tool scoping (1.4.0+, #117)**: when an agent has **ticked no specific MCP tool rows**, enabled MCP tools **auto-join** its tool set; once it ticks specific MCP tools, it's **restricted to that set**. Agents bound to skills / built-in tools only keep full access to all MCP tools.

**3. Skill scripts.** Skill packages can ship executable scripts that get wrapped as tools at runtime. See [Skills](./skills).

Tool discovery is **blacklist-style** ‚Ä?every discoverable tool is registered by default. Exclude specific tools explicitly. Newly added tools don't get silently missed.

---

## Progressive tool disclosure (1.4.0+)

As the tool count grows, the system prompt balloons with dozens of full tool schemas ‚Ä?even when a task needs only one or two of them. **Progressive disclosure** splits tools into two tiers so the prompt scales with the **task**, not with the **total tool count**.

| Tier | How it appears in the system prompt | Callable out of the box? |
|------|-------------------------------------|--------------------------|
| **CORE** | Always advertised in full, with the complete schema | Yes |
| **EXTENSION** | Only a compressed directory ‚Ä?name + source + one-line description; the full schema stays hidden | No ‚Ä?activate with `enable_tool` first |

**Default tiering**: the generative tools (`image_generate`, `music_generate`, `video_generate`, `model3d_generate`) and `browser_use` default to **EXTENSION**; everything else is **CORE**.

- **Page control** ‚Ä?the Tools page has Core and Extension sections with a per-row tier toggle for built-in and channel tools; MCP / ACP tools are locked.
- **Persistence** ‚Ä?the tier is stored in `mate_tool.disclosure_tier` and `mate_mcp_server.disclosure_tier`.
- **Config** ‚Ä?`GLClaw.tools.disclosure.mode`, default `progressive`; set it to `legacy` to restore the old "advertise everything" behavior.

**Why** ‚Ä?to stop context bloat. The system prompt should scale with what the current task needs, not with how many tools you've installed.

---

## The twenty built-in tools

| Tool | What it does | Dangerous |
|------|--------------|-----------|
| `DateTimeTool` | Current date/time in any timezone | ‚Ä?|
| `WebSearchTool` | Search via the provider chain (Serper / Tavily / DuckDuckGo / SearXNG) | ‚Ä?|
| `ReadFileTool` | Read file contents | ‚Ä?|
| `WriteFileTool` | Write content to a file | ‚ö†Ô∏è |
| `EditFileTool` | Find-and-replace edit | ‚ö†Ô∏è |
| `ShellExecuteTool` | Execute a shell command | ‚ö†Ô∏è |
| `FileTypeDetectorTool` | Detect MIME type and encoding | ‚Ä?|
| `DocumentExtractTool` | Extract text from PDF, DOCX, XLSX | ‚Ä?|
| `WorkspaceMemoryTool` | Read/write the agent's workspace memory | ‚Ä?|
| `SkillFileTool` | Read and manage `SKILL.md` files | ‚Ä?|
| `SkillScriptTool` | Execute skill scripts | ‚ö†Ô∏è |
| `SkillManageTool` | Create / edit / delete skill packages | ‚ö†Ô∏è |
| `BrowserUseTool` | Drive a headless browser | ‚ö†Ô∏è |
| `DelegateAgentTool` | Delegate a task to another agent (parallel supported) | ‚Ä?|
| `GLClawDocTool` | Read built-in project documentation | ‚Ä?|
| `ImageGenerateTool` | Text-to-image / **image-to-image (1.3.0+)** | ‚Ä?|
| `VideoGenerateTool` | Text-to-video / image-to-video generation | ‚Ä?|
| `DocxRenderTool` | **1.3.0+** Markdown ‚Ü?.docx (Word document) | ‚Ä?|
| `XlsxRenderTool` | **1.3.0+** Markdown tables ‚Ü?.xlsx (Excel) | ‚Ä?|
| `PptxRenderTool` | **1.3.0+** Markdown (Marp-style `---` slide breaks) ‚Ü?.pptx | ‚Ä?|
| `PdfRenderTool` | **1.3.0+** Markdown ‚Ü?publication-grade PDF (CJK fonts embedded) | ‚Ä?|
| `CronJobTool` | Create and manage scheduled tasks | ‚ö†Ô∏è |
| `DatasourceTool` | Manage external datasource connections | ‚ö†Ô∏è |
| `SqlQueryTool` | Execute SQL queries on connected datasources | ‚ö†Ô∏è |
| `send_file` | **1.4.0+** Deliver an existing server file as a native IM attachment (#199) | ‚Ä?|
| `enable_tool` | **1.4.0+** Activate an extension-tier tool for this conversation | ‚Ä?|
| `load_skill` | **1.4.0+** Load a skill's `SKILL.md` on demand | ‚Ä?|

Plus the `MusicGenerateTool` from [Multimodal](./multimodal). And the 14 Wiki tools from [LLM Wiki](./wiki): `wiki_read_page`, `wiki_read_many`, `wiki_list_pages`, `wiki_search_pages`, `wiki_semantic_search`, `wiki_compile_page`, `wiki_trace_source`, `wiki_create_page`, `wiki_delete_page`, `wiki_archive_page`, `wiki_unarchive_page`, `wiki_related_pages`, `wiki_explain_relation`, `wiki_enrich_page`.

### DateTimeTool

Returns the current date and time for a given timezone. Zero surprises.

```
Input:  {"timezone": "America/New_York"}
Output: "2026-04-11T14:30:22"
```

### WebSearchTool

Web search via a **provider chain** ‚Ä?DuckDuckGo and SearXNG as keyless fallbacks, Serper and Tavily when you have keys. Configured in `Settings ‚Ü?System ‚Ü?Search Service` and takes effect without restart.

```
Input:  {"query": "Spring AI Alibaba latest version", "freshness": "month", "count": 5}
Output: "Spring AI Alibaba 1.1 was released..."
```

Features:

- **Provider chain** ‚Ä?falls through to the next on failure. Keyless providers provide baseline coverage.
- **Advanced parameters** ‚Ä?`freshness` (day/week/month/year), `language`, `count`.
- **Result caching** ‚Ä?recent queries are cached.
- **Security wrapping** ‚Ä?results sanitized before return.
- **Provider-native + tool search coexistence** ‚Ä?models with their own search (ChatGPT, Gemini) can use that natively while tool search is available as fallback.

### ShellExecuteTool

Cross-platform shell execution. Linux/macOS uses `/bin/sh -c`; Windows uses `cmd.exe /D /S /C`. **Every call is gated by Tool Guard.**

Safety design:

- **Timeout** ‚Ä?60s default, 300s hard cap
- **Output caps** ‚Ä?stdout and stderr capped at 10,000 bytes each
- **File-backed output** ‚Ä?stdout/stderr to temp file, not pipe
- **Structured result** ‚Ä?`{exitCode, stdout, stderr, timedOut}`
- **Dangerous-pattern detection** ‚Ä?`find -delete`, `rm -rf /`, piped bash downloads trigger elevated approval

```
Input:  {"command": "ls -la /tmp"}
Output: "total 48\ndrwxrwxrwt 12 root root..."
```

### ReadFileTool / WriteFileTool / EditFileTool

Read is safe. Write and Edit are both gated by Tool Guard.

### DocumentExtractTool

PDF, DOCX, XLSX, and friends become plain text. Scanned documents get OCR fallback where available.

### Office document generation (1.3.0+)

Four new tools that render Markdown directly into downloadable Office files ‚Ä?**no subprocess fork, no npm dependency**. Generated bytes are cached in memory and returned as a one-time download URL:

| Tool | Use for | Key capabilities |
|---|---|---|
| `DocxRenderTool.renderDocx` | Reports / memos / contracts / resumes | Headings (# ## ###) / bold (**text**) / lists / tables / images (PNG/JPG/GIF/BMP/SVG ‚Ü?PNG) |
| `DocxRenderTool.renderDocxFromFile` | Same, but markdown is in a workspace file | Avoids the LLM having to repeat its own large markdown body as a tool argument |
| `XlsxRenderTool.renderXlsx` | Financial sheets / data exports / templates | Markdown table syntax ‚Ü?multiple sheets (split by `## SheetName`) |
| `PptxRenderTool.renderPptx` | Decks / project plans / briefings | Marp-style `---` slide breaks; `16:9` (default) / `4:3` aspect |
| `PptxRenderTool.renderPptxFromFile` | Same, but markdown in a file | Preferred when the deck body exceeds 5KB |
| `PdfRenderTool.renderPdf` | Publication-grade documents / weekly reports / templated docs | 1in margins / smart pagination / page numbers / cover page / mixed CJK + Latin (CJK fonts embedded) |

::: tip Relationship with the existing `skills/docx` skill
The `skills/docx` skill **stays** ‚Ä?it's good at **editing existing .docx** (tracked changes, complex XML ops) and runs `npm install docx` on first use. The four new tools handle the "create-from-scratch" path with **no npm warm-up cost**. Agents prefer these RenderTools; fall back to the skill only when modifying an existing .docx.
:::

### ImageGenerateTool ‚Ä?image edit support from 1.3.0

In v1.2.0 this tool was text-to-image only. v1.3.0 adds two parameters ‚Ä?`image` and `images` ‚Ä?for **multi-image input editing**. See [Multimodal](./multimodal#image-edit).

### WorkspaceMemoryTool

Lets an agent read, write, and edit its own workspace memory files ‚Ä?`MEMORY.md`, `PROFILE.md`, daily notes, anything under `workspace/{agentId}/`. Safety rules: `.md` only, no directory traversal. See [Memory](./memory).

### BrowserUseTool

Drives a headless browser. Navigate, click, type, extract. Every call gated by Tool Guard.

### DelegateAgentTool ‚Ä?agents delegating to agents

One agent can hand off a subtask to another:

- **`delegateToAgent(agentName, task)`** ‚Ä?call a specific agent by name, run in isolated conversation, return the result
- **`listAvailableAgents()`** ‚Ä?list all available agents with name, type, description

```
User: Search for Spring AI news and have Writer summarize it
Agent A: [calls WebSearchTool]
         [calls delegateToAgent(agentName="Writer", task="Summarize: ...")]
         [receives Writer's response]
         Replies with the combined result
```

Safety:

- **Recursion cap** ‚Ä?maximum 3 delegation levels deep
- **Isolated sessions** ‚Ä?the delegated agent runs in its own conversation
- **Result truncation** ‚Ä?delegated results capped at 4000 characters

### GLClawDocTool

Reads the built-in GLClaw project documentation. Lets an agent answer "how does X work in GLClaw" questions by consulting actual docs rather than guessing.

### enable_tool ‚Ä?activate an extension-tier tool (1.4.0+)

`enable_tool(toolName)` activates an **EXTENSION**-tier tool so it becomes fully callable for the **rest of the conversation**.

- **Validated** ‚Ä?only tools in the agent's effective set can be activated.
- **Takes effect next turn** ‚Ä?activation lands on the **next reasoning turn** of the same ReAct loop (the agent sees the full schema, then emits the real call).
- **Conversation-scoped, not persisted** ‚Ä?activation lasts only for the current conversation; nothing is written to the database, and a new conversation reverts to the default tiering.

### load_skill ‚Ä?load a skill on demand (1.4.0+)

`load_skill(skillName, filePath?)` pulls a skill's `SKILL.md` in only when it's needed ‚Ä?omit `filePath` for the main file, or pass one to read a sub-file inside the skill package.

- **Injected via message history** ‚Ä?the loaded content goes into **message history**, not the system prompt, so the **prompt cache stays stable** (the system prompt is unchanged, so the cache isn't invalidated).
- **Pinned in later turns** ‚Ä?a loaded skill stays **pinned** for the rest of the conversation, so it doesn't have to be reloaded.
- **Config** ‚Ä?`GLClaw.skill.disclosure.load-skill-tool.enabled`, default true.

See [Skills](./skills).

### send_file ‚Ä?deliver an existing file as a native attachment (1.4.0+, #199)

`send_file(filePath, fileName?)` reads an **existing file** on the server and delivers it as a **native IM attachment** ‚Ä?not a text download link.

- **Stored in the generated-file cache** ‚Ä?the file is placed in the generated-file cache, and channel adapters (Feishu / DingTalk / Telegram) **auto-detect and deliver** it.
- **Any common file type**, up to a **20 MB** limit.
- **Contrast with `ReadFileTool`** ‚Ä?`ReadFileTool` **extracts text** from a file to feed the agent's reasoning; `send_file` ships the file **as-is** to the user.

### ReadFileTool ‚Ä?oversized-line paging (1.4.0+, #190)

For files with a very long single line, `ReadFileTool` adds an optional `startColumn` (a 1-based character offset within `startLine`) to **resume the tail** of that line from where you left off.

- On truncation it **always returns** `nextStartLine`;
- it **additionally returns** `nextStartColumn` when more of that line remains.

Feed both back into the next call to page through a giant single-line file in segments.

---

## Tool Guard ‚Ä?the permission layer

Tool Guard is how GLClaw keeps strong tools from doing stupid things. It's **rule-based**, not a flat dangerous-tools list. Each rule says: *for this tool, with these arguments, in this context, do X* ‚Ä?where X is `allow`, `deny`, or `require_approval`.

Core pieces:

- **`mate_tool_guard_rule`** ‚Ä?individual rules with tool pattern, optional arg pattern, action
- **`mate_tool_guard_config`** ‚Ä?global config: enabled/disabled, default policy, approval timeout
- **`mate_tool_guard_audit_log`** ‚Ä?every guarded call leaves an entry

Example rule: *allow `ShellExecuteTool` when the command starts with `ls`, `cat`, `grep`, or `find`. Require approval for anything else.*

```yaml
GLClaw:
  tool:
    guard:
      enabled: true
      default-policy: require_approval
      rules:
        - tool: ShellExecuteTool
          arg-pattern: "^(ls|cat|grep|find)\\s"
          action: allow
        - tool: WriteFileTool
          action: require_approval
```

Or manage interactively on `Settings ‚Ü?Security & Approval`. When a rule requires approval, the runtime persists a row in `mate_tool_approval` and suspends the agent turn. When the user decides, the agent resumes where it paused. Full mechanism in [Security & Approval](./security).

### Declarative hook system

Tool Guard rules are a special case of a more general mechanism ‚Ä?the **declarative hook system**. Five lifecycle hooks cover every critical moment in tool and LLM execution:

| Hook | Fires when | Typical use |
|------|-----------|-------------|
| `before_tool` | Before tool execution | Argument redaction, context injection, extra validation |
| `after_tool` | After tool execution | Result filtering, audit logging |
| `before_llm` | Before LLM call | Prompt enrichment, cache hit check |
| `after_llm` | After LLM returns | Output filtering, token accounting |
| `on_error` | On error | Alerting, fallback strategy |

Hooks run in-process. They can transform arguments, transform results, mask sensitive fields, and add audit log entries. You can use hooks for things beyond Tool Guard ‚Ä?like injecting a security policy before every LLM call, or auto-redacting sensitive fields from tool returns.

---

## Execution: concurrent, isolated, bounded

- **Concurrent execution** ‚Ä?within a turn, independent tool calls run in parallel. Guard checks are sequential; execution is concurrent where safe.
- **Per-tool timeouts** ‚Ä?every tool has its own timeout. Defaults: fast tools 30s, shell/browser 60s, generation tools up to 300s.
- **Segment isolation** ‚Ä?when approvals are needed mid-turn, the segment splits at the approval boundary.
- **Observation truncation** ‚Ä?long tool results are automatically truncated before being added to observation history.
- **Error isolation** ‚Ä?one tool failure does not abort the turn.

---

## Tool management via API

```bash
# List all tools
curl http://localhost:18088/api/v1/tools \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Enable / disable
curl -X PUT http://localhost:18088/api/v1/tools/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"enabled": false}'

# Test a tool directly
curl -X POST http://localhost:18088/api/v1/tools/WebSearchTool/test \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"query": "Spring AI"}'
```

Every provider-backed tool has a test button in the Tools page so you can verify API keys before shipping.

---

## Creating a custom tool

### Option 1: a `@Tool`-annotated Spring bean

```java
@Component
public class FactorialTool {

    @Tool(description = "Calculate the factorial of a number")
    public String factorial(
            @ToolParam(description = "The number to compute factorial for") int n) {
        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return String.valueOf(result);
    }
}
```

- Spring `@Component`
- Every `@Tool` method becomes a callable tool
- Use `@ToolParam` on every parameter ‚Ä?that's the LLM description
- Return value is what the agent sees
- **If the tool is dangerous, add a Tool Guard rule for it**

Restart and the tool is live.

### Option 2: a skill script

Don't want to write Java? Bundle behavior into a skill package with a `SKILL.md` and a script. See [Skills](./skills).

### Option 3: an MCP server

Capability already exists as an MCP server? Just add the server configuration. See [MCP](./mcp).

---

## Next

- [Skills](./skills) ‚Ä?higher-level capabilities built on tools
- [MCP](./mcp) ‚Ä?external tool providers
- [Security & Approval](./security) ‚Ä?Tool Guard rules, approval flow, audit log
- [Multimodal](./multimodal) ‚Ä?generation tools (image, video, music, TTS, STT)
