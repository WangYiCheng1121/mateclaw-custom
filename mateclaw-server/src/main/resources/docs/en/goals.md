---
title: Persistent Goals â€?lock in across turns, let the worker follow up
description: GLClaw's Goal system lets a digital worker lock a multi-turn task as a goal, self-evaluate progress, and optionally drive itself forward until done or out of budget.
head:
  - - meta
    - name: keywords
      content: Goal,Agent,multi-turn,auto-evaluation,auto-followup,persistent,GLClaw
---

# Persistent Goals

> **You used to repeat the context every turn. Now you set a goal once, the worker follows.**

You say "deploy this blog to fly.io" in one turn, the worker answers, and stops. Next turn you have to remember to ask "is DNS set? cert signed? tests run?" â€?you're keeping the goal in your head, not the worker.

Goals flip that. **You say it once, the worker locks the goal and self-checks every turn: what's still missing? Should I take the next step myself?**

It is not a new tab or a new feature. It is a **state** the worker has. A ring appears around the assistant avatar. How filled the ring is, is how close you are to done. When done, the ring goes away.

---

## What it looks like

Not a banner. Not a dialog. Not a separate page.

A **ring around the assistant avatar**.

| State | Visual | Meaning |
|---|---|---|
| No goal | Plain avatar | This conversation has no goal â€?same as before |
| Active | Avatar + orange ring | Goal in flight, ring fills to progress |
| Evaluating | Avatar + sand breathing halo | Backend is judging this turn's answer |
| Completed | Avatar + green ring (briefly) | Goal reached; ring fades, conversation continues |
| Exhausted | Avatar + red-orange ring | Budget used up â€?your call to extend or let go |

**Hover the avatar** to see the full tooltip â€?title + what's still missing. Don't hover, don't get bothered. That's the design.

---

## Three ways to set a goal

In increasing order of how much you have to spell out:

### Way 1 â€?Let the worker decide

State the multi-turn nature of the task plus an explicit setGoal request:

> I want to do a complete project: translate the README to English, open a PR, address review feedback, merge. This spans many turns. **Please use setGoal to lock it in**, self-evaluate each turn, turnBudget=8, autoFollowup on.

The worker picks up the two signals ("long task" + "setGoal requested") and creates the goal, auto-summarizing the title from context. You see a ring next to its avatar â€?goal is locked.

### Way 2 â€?Direct tool command

Tell the worker exactly which tool to call with which params:

> Please call setGoal immediately, title="Deploy blog to fly.io", turnBudget=10, autoFollowup=true. Do not ask any clarifying questions.

The "do not ask clarifying questions" clause matters â€?otherwise the worker's instinct is to ask "where's the code? what domain?" first.

### Way 3 â€?Programmatic via the REST API

For automation and external scripts, the endpoint is direct:

```
POST /api/v1/goals
{
  "conversationId": "conv-xxx",
  "agentId": "1000000001",
  "workspaceId": 1,
  "title": "Deploy blog to fly.io",
  "description": "...",
  "exitCriteria": "DNS + SSL + healthcheck + tests pass",
  "turnBudget": 10,
  "llmCallBudget": 200,
  "autoFollowupEnabled": false
}
```

Full surface in the [API reference](./api).

---

## What a goal carries

Four required:

| Field | Meaning |
|---|---|
| **title** | Short label, shown on avatar hover |
| **description** | Full statement of what you want |
| **exitCriteria** | LLM-readable bar the evaluator scores against (e.g. "tests pass + deployed") |
| **budgets (turnBudget + llmCallBudget)** | Failsafes against runaway iteration |

Optional:

- **autoFollowupEnabled** â€?when on, the worker may continue itself if it judges the goal incomplete, without waiting for your next message
- **followupCooldownSeconds** â€?minimum delay between two consecutive auto-followups

---

## How it runs

After every turn, a backend evaluator node runs:

1. Reads the worker's final answer + last few messages of context
2. Calls a lightweight evaluator model (point this at a cheap one) asking: completion 0â€?? what's the gap? continue or done?
3. Writes the result into the `mate_agent_goal_event` timeline
4. Decides next step: complete / exhaust budget / continue / auto-followup

**Key invariant**: evaluation runs *after* the final answer has streamed to your screen â€?it never blocks you seeing the reply. You see the answer appear â†?the ring updates a moment later.

### Auto-followup

When `autoFollowupEnabled=true` and this turn's evaluator decision is "continue", the backend:

1. Writes a `followup_injected` event to the timeline
2. APPENDs a user message to the conversation: *"Continue working on the goal. Still missing: {gap}. Take the next concrete step."*
3. Re-enters the reasoning loop â€?the next assistant reply lands right after the first

Feels like: the worker answers a segment â†?pauses a beat â†?**keeps going** â€?like a person who finished one step, thought for a second, and continued.

---

## Four built-in tools (worker-callable)

These four ship as agent-wide system tools â€?no binding setup needed:

| Tool | Purpose | Prompt example |
|---|---|---|
| **setGoal** | Create a goal | "Use setGoal to lock in this task, title=..." |
| **addGoalCriterion** | Append a sub-criterion to the active goal | "Add: must support IPv6" |
| **completeGoal** | Explicitly mark done | "All items done â€?call completeGoal" |
| **getGoalStatus** | Inspect current state | "How are we doing?" |

On completion (`completeGoal` or evaluator score â‰?0.95), the worker forwards a summary to its [long-term memory](./memory) so future conversations can recall it.

---

## Sub-agents cannot mutate the parent's goal

In [multi-agent collaboration](./agents) a parent worker can delegate to a child worker. Children **don't see** the four goal tools â€?the goal is the parent conversation's state, the child is a stateless executor.

> This is intentional. Children do work for the parent, but the goal stays owned by the parent.

---

## When the budget runs out

```
turnsUsed >= turnBudget  OR  (agentLlmCallsUsed + evalLlmCallsUsed) >= llmCallBudget
```

Either one hit â†?goal status flips to **exhausted**, no more evaluations, no more follow-ups, ring turns red-orange. The last turn's assistant reply still goes through.

Your options:

- **Raise the budget and resume** â€?`PATCH /api/v1/goals/{id}` to widen budgets then resume (no UI button in v1 â€?use the API or abandon and re-create)
- **Let it go** â€?call abandon; the conversation slot is freed for a new goal

---

## State machine

```
   create
     â†?
   active
   â†?  â†?
 paused

 active â”€â”€evaluator scoreâ‰?.95 / completeGoalâ”€â”€â†?completed (terminal)
   â†?
 active â”€â”€turns_used / llm_calls exhausted â”€â”€â”€â”€â†?exhausted (terminal)
   â†?
 active â”€â”€user abandon â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â†?abandoned (terminal)
```

Terminal states (completed / exhausted / abandoned) cannot revive. To keep going, create a fresh goal â€?intentional simplicity, avoids messy "resurrect with what budget" semantics.

**One active goal per conversation**: at most one active row at any time. Terminal rows stay in history, don't count against the slot. Enforced at the DB layer with a generated column + unique index (H2 / MySQL), plus service-level precheck and audit â€?defense in depth.

---

## What this system does not do

A few deliberate non-features:

- **No nested goals / goal trees** â€?one goal per conversation, no OKR stack
- **No "goal templates"** â€?every goal is hand-written
- **No cross-conversation goal migration** â€?use a [workflow](./workflow) for that
- **No completion score in the UI** â€?`completionScore` is an internal engineering protocol, not user vocabulary. The UI speaks via a ring; hover reveals the natural-language gap the evaluator wrote. The numeric score stays in logs and the API for debugging

---

## Full event timeline (drawer view)

Each goal has an append-only event log, newest first:

| Event | Trigger |
|---|---|
| `created` | setGoal tool or REST POST |
| `evaluated` | every turn after evaluator runs |
| `followup_injected` | autoFollowup fired and injected a prompt |
| `completed` | evaluator concluded done, or completeGoal tool |
| `exhausted` | budget hit |
| `paused` / `resumed` / `abandoned` | user actions |
| `criterion_added` | addGoalCriterion tool |

Pull via `GET /api/v1/goals/{id}/events`. See [API reference](./api).

---

## Configuration

`application.yml`:

```yaml
GLClaw:
  goal:
    # Master switch; when off, the graph node passes through for every call.
    enabled: true
    # Default turn budget when the user doesn't override.
    default-turn-budget: 20
    # Default combined (agent + evaluator) LLM call budget.
    default-llm-call-budget: 200
    # Minimum seconds between two consecutive auto-followups.
    auto-followup-cooldown-seconds: 0
    # Model used by the evaluator. Empty = same model as the chat agent.
    # Recommended: a cheap model like qwen-turbo / glm-4-flash.
    evaluator-model: ""
    # Max recent messages included in the evaluator prompt.
    evaluator-context-messages: 8
```

---

## Database

Two tables, all `mate_`-prefixed:

| Table | Purpose |
|---|---|
| `mate_agent_goal` | Goal itself; status / budgets / dual LLM counters / auto-followup config |
| `mate_agent_goal_event` | Append-only event log; powers the timeline view |

Flyway migration `V120__agent_goal.sql` (H2 + MySQL dialects).

---

## One-liner

**A goal isn't a new feature on the worker. It's a state change.**

Before, the worker forgot the moment it answered. Goals make a worker remember one thing across many turns â€?what it's working on, what's still missing, when it counts as done. You say it once. The ring next to the avatar tracks the rest.
