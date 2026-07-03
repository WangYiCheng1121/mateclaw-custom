# User Guide

You opened GLClaw because you want AI to do work for you. Not because you want to learn new software.

This guide does one thing: **get you from "installed" to "it's working for me" as fast as possible.**

---

## 60-Second Launch

| Step | What | Time |
|------|------|------|
| 1 | Double-click, log in with `admin` / `admin123` | 10s |
| 2 | Settings â†?Models â†?Add Provider, **enable one**, paste your key | 30s |
| 3 | Chat â†?pick an Agent â†?say "Hello" | 10s |
| 4 | Watch the reply stream in â†?**the system is alive** | â€?|

The moment you see a response, you're in the product. Everything after this is about making it **useful to you**.

---

## Models: connect one

**A fresh GLClaw install has an empty provider list. That's deliberate â€?you don't need to see 16 providers, you need one that works.**

`Settings â†?Models â†?Add Provider` opens a drawer with the full catalog.

| Your situation | Recommendation |
|---------------|----------------|
| Nothing set up, want the fastest path | **DashScope** â€?paste your key from Alibaba Cloud |
| Already have an OpenAI / Anthropic key | Drop it in |
| Have a ChatGPT Plus / Pro account | **ChatGPT OAuth** â€?browser login, no API key needed |
| Want data to stay on your machine | **Ollama** â€?auto-detects `localhost:11434` |

In the drawer, **click Enable** on the provider you want, then fill in the base URL (pre-filled for known providers) and paste your API key, and save. The model appears in the chat screen's model picker immediately.

::: tip Enable / disable are separate from configure
**Enable** surfaces the provider everywhere; **disable** removes it from the picker but keeps the configuration â€?switching providers temporarily no longer means deleting the config.
:::

**One is enough.** Don't spend time configuring five providers â€?get the system running first, add more later.

---

## Chat: the heart of the product

Click "Chat" in the sidebar. Pick an agent. Pick a model. Type. Hit enter.

That's the entire interaction. There is no other entry point.

### Three things to try right now

**1. Ask a direct question**

> Explain the difference between Java virtual threads and platform threads

The agent answers directly â€?no tools involved. You're seeing pure reasoning.

**2. Make it use tools**

> Search the web for the latest Spring Boot release and summarize the breaking changes

The agent picks up the search tool, reads results, composes an answer. You see the full "think â†?act â†?observe â†?answer" cycle â€?that's ReAct in action.

**3. Give it a multi-step task**

> First check our Wiki for auth design decisions, then compare against Spring Security 6 best practices, and give me a gap analysis

The agent breaks this into steps, executes each one, then consolidates. You see the plan and progress on every step.

If all three work, **you understand 90% of the product.**

---

## Agents: how the AI behaves

`Agents â†?New Agent`

An agent defines exactly five things:

| Config | One line |
|--------|----------|
| **System prompt** | Who it is, how it talks, what attitude |
| **Model** | Which model to use |
| **Tools** | Which tools it can call |
| **Skills** | Which skill packages it can invoke |
| **Wiki** | Which knowledge bases it can read |

Start from a template. Templates ship ready to work â€?rename it, tighten the system prompt, check the tools you want, save. 30 seconds for a new agent.

::: tip When to create a new agent
When you find yourself repeating the same setup instructions every conversation â€?that's the signal. Put those instructions in the system prompt so you never have to say them again.
:::

---

## Memory: it remembers you

GLClaw's memory doesn't require manual management. After each conversation, the system automatically extracts key information and writes it to memory. Next time, the agent works with that context.

What you can shape:

- **PROFILE.md** â€?who you are, your preferences, how you work
- **MEMORY.md** â€?long-term facts and notes that accumulate over time
- **Daily memory** â€?system-generated conversation summaries

Memory is shared across all channels. What you discussed on desktop, the DingTalk agent remembers too.

---

## Wiki: make it read your documents

`Wiki â†?New Knowledge Base`

Drop in PDFs, DOCX, TXT, or point at an entire folder. Wait for digestion â€?every raw material shows a progress bar, no guessing.

Once digested:

1. Bind the knowledge base to an agent
2. Ask about the content
3. The agent automatically retrieves relevant pages and answers with knowledge

::: tip
Wiki isn't full-text search. It's **semantic retrieval** â€?ask "what did we decide about authentication" and get the decision, not every page containing the word "auth."
:::

---

## Skills and MCP: extend the boundary

**Skills** â€?`Agents â†?pick one â†?Skills`. Install from the skill marketplace, or write a `SKILL.md` by hand.

**MCP** â€?`Settings â†?MCP Servers`. Connect external tool servers (filesystem, databases, custom APIs). MCP tools appear in the tool list automatically â€?the agent doesn't know and doesn't need to know they're external.

When the 20 built-in tools aren't enough, these two doors open up.

---

## Channels: find it where you already are

`Channels â†?pick a platform â†?paste credentials`

Eight channels: DingTalk, Feishu, WeCom, WeChat Personal, Telegram, Discord, QQ, Slack.

::: tip DingTalk & Feishu: just scan a QR (v1.1.0+)
No more "go to the open platform â†?create app â†?copy ID and Secret" detour. In the new channel form, click **Bind via QR**, scan with the DingTalk / Feishu app, confirm â€?**client_id / app_id and the secret auto-fill**. Under 30 seconds end to end.
:::

Same agent. Same memory. Every channel.

---

## Security: powerful but not out of control

The **Security** page gives you three controls:

1. **Tool Guard** â€?which tools require your approval before execution (shell, SQL, file writes)
2. **File Guard** â€?which directories the agent can't touch
3. **Audit log** â€?see everything the agent has done

The defaults are already safe. If you're using this in production, tighten the shell and SQL approval rules.

---

## Three starter setups

### A. Personal assistant (fastest)

Configure one model â†?use the default agent â†?start chatting. Memory accumulates automatically.

### B. Knowledge assistant

Create an agent â†?create a Wiki KB â†?import your docs â†?bind Wiki to agent.

### C. Automated worker

Create a role-specific agent â†?install skills â†?connect MCP servers â†?configure approval rules in Security.

---

## Something broke

| Symptom | Most likely cause |
|---------|-------------------|
| Backend won't start | Port 18088 is taken. Check `~/.GLClaw/logs/app.log` |
| Model call fails | Wrong API key or network issue. Go back to Settings |
| UI is blank | Ctrl+Shift+R to hard-refresh |
| Ollama says "does not support tools" | Switch to a function-calling model (qwen3, llama3.1:8b+) |
| Still broken | [GitHub Issues](https://github.com/matevip/GLClaw/issues) with the tail of `app.log` |

---

## What's next

| You want to... | Go to... |
|----------------|----------|
| Understand why the product is built this way | [Introduction](./intro) |
| See the technical architecture | [Architecture](./architecture) |
| Configure more options | [Configuration](./config) |
| Connect more channels | [Channels](./channels) |
| Deep-dive into the agent engine | [Agents](./agents) |
