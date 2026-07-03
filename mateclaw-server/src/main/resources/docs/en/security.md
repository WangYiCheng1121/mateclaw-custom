# Security & Approval

**Strong hands, firm limits.**

GLClaw gives agents real capability â€?shell access, file writes, browser automation, delegation to other agents, remote tools over MCP. That's the "strong hands" half. This page is about the other half: the limits that keep strong hands from doing stupid things.

- **JWT auth** â€?who you are
- **Tool Guard (rule-based)** â€?what each agent is allowed to do
- **Approval workflow** â€?when a human needs to decide before execution
- **File Guard** â€?what the filesystem looks like to an agent
- **Workspace isolation** â€?what each team can see
- **Audit log** â€?what everybody did, in order, forever

If you're running GLClaw in production, read this page top to bottom.

::: tip Agentic, but not autonomous
Every IT department and CISO in 2025â€?026 has the same question before buying AI:

> **"What if the agent goes off the rails and deletes the wrong thing?"**

Anyone who tells you "AI won't go off the rails" is lying. GLClaw's answer is different â€?**the agent asks you first when it matters.**

When the agent wants to delete a file, send an email, run a write-side SQL, or hit a paid API â€?any tool call matched by a Tool Guard rule **pauses mid-turn**. An approval notification is pushed to your IM (Feishu / DingTalk / Slack / email). You tap approve, the agent resumes from where it stopped. Every action lands in `mate_tool_guard_audit_log` â€?append-only, retained as long as you want, CSV-exportable.

**Agentic â€?it acts. Not autonomous â€?it doesn't act on its own initiative for the things that matter.**

That's the line between "let AI do work for you" and "let AI make decisions for you." GLClaw stays on the left side of that line â€?which is also the side your CISO doesn't immediately say no to.
:::

---

## JWT authentication

### How it works

1. The user posts credentials to `/api/v1/auth/login`
2. The server validates and returns a JWT
3. Every subsequent request includes the token in the `Authorization` header
4. The server validates the token on each request

### Logging in

```bash
curl -X POST http://localhost:18088/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}'
```

Response:

```json
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

### Password change

Users can change their own password from the profile settings dialog. Admins can reset any member's password from member management.

---

### Sliding window renewal

GLClaw does sliding-window token renewal. When a token's remaining lifetime falls below the configurable `renewal-threshold` (default 2 hours / 7200000ms), the server issues a new token in the `X-New-Token` response header. The frontend picks it up and replaces the stored token transparently. Active users never get kicked out; idle sessions still expire on time.

### Configuration

```yaml
GLClaw:
  auth:
    jwt:
      secret: your-secret-key-must-be-at-least-32-characters-long
      expiration: 86400000    # 24h in milliseconds
      sliding-window: true
```

::: warning
**Change the default JWT secret in production.** At least 32 characters. Set via env var (`JWT_SECRET=...`), never commit.
:::

### Error codes

| Code | Meaning | Response |
|------|---------|----------|
| 401 | Token missing, expired, or invalid | `{"code": 401, "message": "Unauthorized"}` |
| 403 | Valid token but insufficient permissions | `{"code": 403, "message": "Forbidden"}` |

Frontend handles both uniformly â€?redirect to login, clear stored tokens.

### Default credentials

GLClaw ships with `admin` / `admin123`. **Change this immediately in any deployment other than your laptop.**

### Spring Security config

- **Stateless sessions** â€?no server-side session; all state in the JWT
- **Public endpoints** â€?`/api/v1/auth/login`, `/h2-console/**`, `/swagger-ui/**`
- **Protected endpoints** â€?everything else under `/api/v1/**`
- **CSRF disabled** â€?not needed for stateless JWT

---

## Tool Guard â€?rule-based permission engine

Tool Guard is how GLClaw decides what a tool call is allowed to do. **It's not a flat dangerous-tools list.** It's a rule engine. Each rule specifies: *for this tool, optionally matching these arguments, in this workspace, do X* â€?where X is `allow`, `deny`, or `require_approval`.

### The three tables

| Table | Purpose |
|-------|---------|
| **`mate_tool_guard_config`** | Global config â€?enabled, default policy, approval timeout, notification channels |
| **`mate_tool_guard_rule`** | Individual rules â€?tool pattern, optional arg regex, workspace scope, action, priority |
| **`mate_tool_guard_audit_log`** | Every guarded call gets an entry â€?tool, args, rule matched, decision, user, timestamp |

### How a rule is evaluated

```
Tool call arrives
      â”?
      â–?
Load rules for this workspace + global rules, sorted by priority
      â”?
      â–?
For each rule in priority order:
  â”Œâ”€ Does the tool name match the pattern?
  â”? â””â”€ No â†?next rule
  â”œâ”€ Does the arg pattern match (if any)?
  â”? â””â”€ No â†?next rule
  â””â”€ Yes on both â†?apply this rule's action and stop
      â”?
      â–?
No rules matched â†?apply default policy
      â”?
      â–?
Action: allow / deny / require_approval
      â”?
      â–?
Write audit log entry
      â”?
      â–?
Execute / reject / suspend for approval
```

Rules with higher priority run first. First matching rule wins. A rule can be scoped to a specific workspace or global.

### Example rules

```
Rule 1 (priority 100):  ShellExecuteTool, arg matches "^(ls|cat|grep|find)\\s"  â†?allow
Rule 2 (priority 50):   ShellExecuteTool                                        â†?require_approval
Rule 3 (priority 50):   WriteFileTool, arg.path starts with "/tmp"              â†?allow
Rule 4 (priority 40):   WriteFileTool                                           â†?require_approval
Rule 5 (priority 30):   *                                                        â†?allow (default)
```

Read-only shell commands execute immediately. Anything else needs approval. File writes under `/tmp` are free; elsewhere they need approval. Everything else runs.

### Managing rules

`Settings â†?Security & Approval â†?Tool Guard Rules`: list, create, edit, reorder, disable. Or via config:

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
          priority: 100
        - tool: ShellExecuteTool
          action: require_approval
          priority: 50
        - tool: WriteFileTool
          arg-pattern: "^/tmp/"
          action: allow
          priority: 50
        - tool: WriteFileTool
          action: require_approval
          priority: 40
```

Or via API:

```bash
curl -X POST http://localhost:18088/api/v1/security/guard/rules \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "ShellExecuteTool",
    "argPattern": "^(ls|cat|grep|find)\\s",
    "action": "allow",
    "priority": 100
  }'
```

### Credential-rule toggles (1.4.0)

Credential rules now support **per-rule control** â€?each rule can be enabled/disabled individually, each rule carries its own decision (allow / deny / require_approval), and the entire guard rule set can be **exported and imported as JSON** for migrating between deployments or version-controlling your policy.

### Dangerous pattern detection

In addition to user-defined rules, GLClaw's shell tool has built-in detection for patterns that are dangerous no matter what. `find -delete`, `rm -rf /`, piped downloads through `bash`, and similar patterns trigger elevated approval even if a rule would otherwise allow them.

---

## Approval workflow â€?human in the loop

When a rule evaluates to `require_approval`, GLClaw doesn't fail the call. It **suspends the agent mid-turn**, creates a pending approval, surfaces it to the user, and resumes exactly where it left off once the user decides.

::: tip From 1.3.0: workflows ride the same approval rail
The v1.3.0 [workflow](./workflow) `await_approval` step suspends the entire workflow run on the same `mate_tool_approval` table â€?persisted across restarts. Approval requests fan out to the approver's channel (Feishu / DingTalk / Slack / WeCom); once resolved, the workflow runtime auto-resumes the next step. One audit log, one notification pipeline, one "pause / resume" semantic â€?covering both agent tool calls and workflow steps.
:::

### How it flows

```
Agent calls tool
     â”?
     â–?
Tool Guard: require_approval
     â”?
     â–?
Create mate_tool_approval row (status=pending)
     â”?
     â–?
Set AWAITING_APPROVAL=true in graph state
     â”?
     â–?
Emit approval_required SSE event
     â”?
     â–?
Graph terminates cleanly
     â”?
     â–?
Frontend shows approval card
     â”?
     â–?
User clicks Approve or Reject
     â”?
     â–?
POST /api/v1/approvals/{id}/resolve
     â”?
     â”œâ”€ Approved â†?reload agent, replay tool call, continue reasoning
     â””â”€ Rejected â†?send rejection as observation, continue reasoning
```

The "replay" mechanism is important. When the agent resumes, it **doesn't re-reason from scratch** â€?it skips straight to the approved tool call, executes it, and continues from the observation. No duplicate LLM calls, no wasted tokens.

### The `mate_tool_approval` table

| Column | Purpose |
|--------|---------|
| `id` | Primary key |
| `agent_id` | Which agent is waiting |
| `conversation_id` | Which conversation is suspended |
| `tool_name` | The tool being called |
| `tool_args` | JSON of the actual arguments |
| `rule_id` | Which rule triggered the approval |
| `status` | `pending` / `approved` / `rejected` / `expired` |
| `requested_at` | When the approval was created |
| `resolved_at` | When the user decided |
| `resolved_by` | Who decided |
| `notes` | Optional user notes on the decision |

### Placeholder substitution

Sometimes the agent's tool arguments contain placeholders â€?a computed file path, a templated command. The approval workflow **resolves placeholders before showing the dialog**, so users see the actual values they're approving. Approval returns the resolved values too, so what the agent executes is exactly what the user saw.

### Timeouts

Pending approvals expire after a configurable timeout (default: 10 minutes). Expired approvals become `rejected`, and the agent treats expiry the same as user rejection.

### Notifications

GLClaw can notify through `channel/notification/` adapters â€?email, in-app alert, DingTalk/Feishu push. Configure in `Settings â†?Security & Approval â†?Notifications`.

### Resolving via API

```bash
# List pending
curl http://localhost:18088/api/v1/approvals?status=pending \
  -H "Authorization: Bearer <token>"

# Approve
curl -X POST http://localhost:18088/api/v1/approvals/123/resolve \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"decision": "approved"}'

# Reject with reason
curl -X POST http://localhost:18088/api/v1/approvals/123/resolve \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"decision": "rejected", "notes": "Not appropriate for this workspace"}'
```

---

## File Guard

File Guard is filesystem-level access control. It sits underneath any tool or skill that reads or writes files, and decides what paths are in-bounds.

### Evaluation pipeline

```
File access request
     â”?
     â–?
Path normalization (resolve .., symlinks, relative paths)
     â”?
     â–?
Allowlist check: is the path inside an allowed directory?
     â”?
     â–?
Denylist check: is the path inside a denied directory?
     â”?
     â–?
Symlink check: does following the path escape the sandbox?
     â”?
     â–?
Allow / Deny
```

### Rules built in

| Rule | Description |
|------|-------------|
| Workspace isolation | Default access restricted to the workspace directory |
| System path denial | `/etc`, `/usr`, `/bin`, `/boot`, etc. blocked |
| Sensitive file protection | `.ssh`, `.config`, `.env` blocked |
| Path traversal prevention | `../` attacks detected and blocked |
| Symlink check | Symlink targets resolved and re-validated |

### Configuration

```yaml
GLClaw:
  security:
    file-guard:
      enabled: true
      allowed-paths:
        - "${user.dir}/workspace"
        - "${java.io.tmpdir}/GLClaw"
      denied-paths:
        - "/etc"
        - "/usr"
        - "${user.home}/.ssh"
        - "${user.home}/.config"
        - "${user.home}/.env"
```

Visual editor on `Settings â†?Security & Approval â†?File Guard`.

---

## Workspace isolation

Workspaces are how GLClaw keeps multiple teams' data separate. Every agent, skill, wiki, conversation, and memory file belongs to exactly one workspace.

### Security primitives that follow workspace boundaries

- **File Guard** â€?path allowlists default to `workspace/{workspaceId}/...`
- **Tool Guard rules** â€?can be scoped to a specific workspace
- **Wiki knowledge bases** â€?owned by a workspace, readable only by members
- **Memory files** â€?every agent's memory is under its workspace's directory
- **Channels** â€?each channel belongs to a workspace

### Roles (four-tier RBAC)

Capabilities are **additive** â€?a higher role inherits everything below it.

| Role | Capabilities (added on top of the tier below) |
|------|-----------------------------------------------|
| **Viewer** | `chat`, `view:wiki`. Read-only. So that chat works, a Viewer can also read the active model and read an employee's workspace files. |
| **Member** | Viewer + `view:memory`, `view:dashboard`, `manage:wiki`, `manage:agents` |
| **Admin** | Member + `manage:skills`, `manage:channels`, `manage:models`, `manage:security`, `manage:settings` |
| **Owner** | Same as Admin, plus owner-only: delete the workspace, transfer ownership |

**The backend is the single source of truth for capabilities** â€?it holds a `RoleCapabilities` mapping, and the frontend never derives them locally. After a workspace switch, or on a capability-related 403, the frontend calls `GET /api/v1/workspaces/{id}/access`, which returns `memberRole`, `isGlobalAdmin`, `effectiveRole`, and `capabilities`.

**Global admin vs workspace role**: `mate_user.role='admin'` is the system-wide global admin â€?it manages users, creates workspaces, and spans **all** workspaces with owner-equivalent power even where it isn't a member; `mate_workspace_member.role` is per-workspace. System-level endpoints (models / providers / OAuth / datasources, user management, workspace creation) require a global admin (`@RequireGlobalAdmin`); workspace-scoped endpoints (skills / tools / plugins) require a workspace role â€?reads need Member, writes need Admin.

Full details in [Workspaces](./workspaces).

### What isolation does NOT cover

- **Shared global config** â€?JWT secret, model provider keys, MCP server definitions are global
- **Audit logs** â€?all workspaces' security events are in the same audit log; only admins with audit access read across workspaces

---

## Audit log

Every security-relevant action is recorded in `mate_audit_event`. **Append-only** â€?you can't modify an entry, and rows are retained for the configured window (default 90 days).

### What gets logged

| Event type | Captured data |
|------------|---------------|
| **Tool calls** | Tool name, args, result summary, duration, agent, workspace |
| **Tool Guard decisions** | Rule matched, action taken, rule ID |
| **Approvals** | Who approved/rejected, when, notes |
| **File Guard decisions** | Path, allow/deny, reason |
| **Skill executions** | Skill name, parameters, agent |
| **Login events** | User, IP, success/failure |
| **Configuration changes** | Old and new values for security-relevant settings |

### Entry schema

```
timestamp       When it happened
user_id         Who did it (system for automated events)
action          What they did
resource        What it was done to
details         JSON blob with the specifics
result          success / failure / denied
ip_address      Source IP when applicable
workspace_id    Which workspace this belongs to
```

### Querying

`Settings â†?Security & Approval â†?Audit Log`: filterable view by time range, event type, user, workspace, result. Export to CSV.

Via API:

```bash
curl "http://localhost:18088/api/v1/audit/events?from=2026-04-01&to=2026-04-11&action=tool_call" \
  -H "Authorization: Bearer <token>"
```

---

## Skill security scanning

Custom skills are scanned for dangerous patterns before they become active:

| Check | What it looks for |
|-------|-------------------|
| **Prompt injection** | Attempts to override system prompts, hidden instructions |
| **Dangerous tool references** | Tools not in the allowlist, or tools requiring approval without declaration |
| **External URL references** | Links to untrusted external resources |
| **Script injection** | Embedded scripts or code execution attempts |

### Severity levels

| Level | Action |
|-------|--------|
| `CRITICAL` | Install blocked; must be fixed |
| `HIGH` | Warning + admin must confirm |
| `MEDIUM` | Warning displayed; install allowed |
| `LOW` | Logged only |
| `INFO` | Logged only |

Scan reports live in `Settings â†?Security & Approval â†?Skill Scans`.

---

## API key protection

- API keys encrypted at rest in the database
- Keys **masked** (`sk-****abcd`) in every API response â€?never returned in full after creation
- MCP server `env_json` and `headers_json` values sanitized the same way
- Environment variable references (`${VAR}`) in MCP config resolve at runtime from the process environment

---

## Network security

### Production recommendations

| Recommendation | Details |
|----------------|---------|
| **HTTPS** | Reverse proxy with TLS (Nginx or Caddy) |
| **Disable H2 console** | `spring.h2.console.enabled=false` in production |
| **Firewall** | Only expose the public port |
| **Rate limiting** | Configure at the reverse proxy level |
| **MySQL, not H2** | Use a dedicated MySQL 8 instance for production |

### Nginx reverse proxy example

```nginx
server {
    listen 443 ssl;
    server_name GLClaw.example.com;

    ssl_certificate /etc/ssl/certs/GLClaw.pem;
    ssl_certificate_key /etc/ssl/private/GLClaw.key;

    location / {
        proxy_pass http://localhost:18080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE support
        proxy_buffering off;
        proxy_read_timeout 86400s;
    }
}
```

---

## Security best practices

1. **Change the default password.** Right now. On every deployment.
2. **Set a real JWT secret.** At least 32 characters, via environment variable, never committed.
3. **Least privilege.** Only enable the tools agents actually need.
4. **Default to `require_approval`.** Flip the Tool Guard default policy, then add `allow` rules for safe cases. Newly added tools default to safe.
5. **Configure File Guard.** Lock down allowed/denied paths before any agent touches the filesystem in anger.
6. **Review audit logs regularly.** Set a recurring reminder. Look for anomalies.
7. **Watch your skill scans.** CRITICAL findings shouldn't be bypassed lightly.
8. **Isolate networks.** Ollama, H2 console, internal MCP servers â€?none should be public.
9. **Don't skip approvals in production.** Auto-approve rules should be narrow and specific. `allow *` is a crisis waiting to happen.

---

## Security configuration reference

```yaml
GLClaw:
  auth:
    jwt:
      secret: ${JWT_SECRET:your-secret-key-at-least-32-chars}
      expiration: 86400
      sliding-window-ratio: 0.5

  tool:
    guard:
      enabled: true
      default-policy: require_approval
      approval-timeout-seconds: 600
      notifications:
        email-enabled: false
        dingtalk-enabled: false

  security:
    file-guard:
      enabled: true
      allowed-paths:
        - "${user.dir}/workspace"
      denied-paths:
        - "/etc"
        - "${user.home}/.ssh"

    audit-log:
      enabled: true
      retention-days: 90

    skill:
      security-scan:
        enabled: true
        block-critical: true
```

---

## Next

- [Tools](./tools) â€?tool details and Tool Guard rule patterns
- [Skills](./skills) â€?skill security scanning details
- [Workspaces](./workspaces) â€?workspace isolation primitives
- [Agents](./agents) â€?how approval pauses and resumes an agent turn
- [Configuration](./config) â€?full configuration reference
