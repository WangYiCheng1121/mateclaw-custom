# 工作空间

**一个工作空间就是一个团队所有东西外面的一个盒子�?*

GLClaw 在单次部署里支持多个团队的方式，是把每一种资源——Agent、技能、Wiki 知识库、会话、记忆文件、Tool Guard 规则、渠道——组织进**工作空间**。你登录时看到的是你所属的工作空间、其他什么都看不到。切换工作空间时，整�?UI 重新 scope：不同的 Agent、不同的技能、不同的知识、不同的渠道�?

重点�?*一�?GLClaw 部署可以同时服务一个产品组、一个工程组、一个研究组**，他们的数据、Agent、对话不会互相渗透�?

---

## 什么属于工作空�?

几乎所有东西。被 scope 的资源：

| 资源 | 怎么 scope �?|
|------|---------------|
| **Agent** | 每一�?Agent 都有 `workspace_id` 外键 |
| **技�?* | 自定义和 MCP 技能按工作空间 scope；内置技能是全局�?|
| **Wiki 知识�?* | 每个 KB 属于且只属于一个工作空�?|
| **会话和消�?* | scope �?Agent 所在的工作空间 |
| **工作空间记忆文件** | `workspace/{workspaceId}/{agentId}/...` |
| **渠道** | 每个渠道绑一�?Agent，所以传递地绑一个工作空�?|
| **Tool Guard 规则** | 规则可以是全局�?scope 到特定工作空�?|
| **File Guard 路径** | 允许/拒绝路径可以按工作空�?|
| **Cron 任务** | scope 到它触发�?Agent 所在的工作空间 |
| **数据�?* | 外部 DB 连接，按工作空间 scope |
| **审计事件** | 每个审计事件记录它的 `workspace_id` |

**�?*�?scope 的（即全局的）�?

- JWT secret 和认证配�?
- 模型供应商和 API Key（全局，但用量按工作空间追踪）
- MCP 服务定义（全局连接；工作空间访问由权限控制�?
- `mate_system_setting` 里的系统级设�?
- 内置技�?

Agent、技能（catalog + 运行时）、会话、工作空间文件全部按工作空间 ID 隔离�?*跨工作空间访问一律返�?403**�?

---

## 工作空间角色

每个用户在工作空间里被分配四种角色之一。权�?*叠加**——高角色继承低角色的全部能力�?

| 角色 | 能力（继承下层后新增�?|
|------|------------------------|
| **Viewer** | `chat`、`view:wiki`。只读。为了让聊天能跑通，Viewer 还能读取当前激活模型、读取员工的工作空间文件�?|
| **Member** | Viewer + `view:memory`、`view:dashboard`、`manage:wiki`、`manage:agents` |
| **Admin** | Member + `manage:skills`、`manage:channels`、`manage:models`、`manage:security`、`manage:settings` |
| **Owner** | �?Admin 相同，外�?owner 专属：删除工作空间、转移所有权 |

一个用户可以属于多个工作空间�?*在不同工作空间有不同角色**。切换工作空间时，有效权限跟着切换�?

### 全局管理�?vs 工作空间角色

二者是两套独立的权限：

- **全局管理�?*——`mate_user.role='admin'`，系统级。管理用户、创建工作空间，�?owner 等同的权限横�?*所�?*工作空间（即便它不是某工作空间的成员）�?
- **工作空间角色**——`mate_workspace_member.role`，每工作空间一份，就是上表那四种�?

系统级端点（模型 / provider / OAuth / 数据源、用户管理、创建工作空间）要求全局管理员（`@RequireGlobalAdmin`）；工作空间级端点（技�?/ 工具 / 插件）要求工作空间角色——读需�?Member、写需�?Admin�?

### 能力�?scope —�?后端是唯一真相�?

角色控制 **UI 可见�?*�?**API 访问**，�?*后端是能力的唯一真相�?*：后端维护一�?`RoleCapabilities` 映射，前端从不本地推导。切换工作空间后、或遇到与权限相关的 403 时，前端调用 `GET /api/v1/workspaces/{id}/access`，拿�?`memberRole`、`isGlobalAdmin`、`effectiveRole`、`capabilities`�?

前端据此 gating：路由声明所需能力；侧栏按能力过滤（加载完成前不会闪现菜单）；Viewer 登录后落�?`/chat`；侧栏还会显示通知角标（待审批、卡住的员工）。后端在每个 API 端点上执行同样的规则，所以能力不足的请求返回 `403 Forbidden`�?

---

## 创建一个工作空�?

`设置 �?工作空间 �?新建工作空间`�?

1. �?**团队在做什�?*"起名，不�?团队叫什�?�?产品调研"�?Alpha �?好）
2. 可选描�?
3. 保存

**只有全局管理员能创建工作空间�?* 创建者自动成为这个工作空间的 **Owner**，现在可以添加成员了�?

### �?API

```bash
curl -X POST http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "产品调研",
    "description": "竞品调研和产品规�?
  }'
```

---

## 成员与角�?

`设置 �?成员`。所有成员管理操作都需�?**Admin 及以�?*�?

### 添加成员

输入用户名，选角色（默认 `member`），保存�?

- 用户�?*不存�?*时，会顺�?*创建账号**——此时必须提供密码�?
- 用户�?*已存�?*且你又填了密码，�?*重置该用户的密码**（管理员把人移除后用新密码重新加回来时很有用）�?
- 昵称可选�?

成员**下次页面加载�?*立刻在工作空间切换器里看到这个工作空间。没有邀请邮件，没有接受流程�?

```bash
# �?username 添加；不存在则按提供的密码建�?
curl -X POST http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "alice",
    "password": "init-pass-123",
    "nickname": "Alice",
    "role": "member"
  }'
```

### 更新成员角色（Admin+，不能改 Owner�?

```bash
curl -X PUT http://localhost:18088/api/v1/workspaces/1/members/42 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"role": "admin"}'
```

> 路径�?`/members/{memberId}`�?*不是** `/members/{memberId}/role`�?

### 移除成员（Admin+，不能移�?Owner�?

```bash
curl -X DELETE http://localhost:18088/api/v1/workspaces/1/members/42 \
  -H "Authorization: Bearer <token>"
```

### 列出成员

```bash
curl http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>"
```

---

## 切换工作空间

管理控制台左上角。点工作空间名字打开切换器，选另一个切。整�?UI 重新 scope�?

- 侧栏菜单按新工作空间的角色重新渲�?
- Agent 列表刷新显示这个工作空间里的 Agent
- Wiki 列表、技能列表、渠道列表等全部改变
- 活跃的对�?*保持打开**（它们属于自己的工作空间�?

当前工作空间 ID �?*字符�?*形式存在浏览�?localStorage 里（Snowflake ID 安全，不会被 `Number` 截断）。任何时候都有一�?*默认工作空间兜底**——即便本地没有记录，你也总会落到一个可用的工作空间�?

`GET /api/v1/workspaces` 返回的每个工作空间都�?`memberRole` / `effectiveRole` / `isGlobalAdmin`，前端据此渲染切换器和侧栏�?

---

## 沿工作空间边界生效的安全基元

这是工作空间隔离真正起作用的地方�?

### File Guard

File Guard 的默�?allowed-path 列表�?`workspace/{workspaceId}/...`。工作空�?A 里一�?Agent 发出的工具调�?*读不到也写不�?*属于工作空间 B 的文件，不管它怎么用路径穿越的 trick——符号链接检查和路径规范化会逮住它�?

### Tool Guard 规则

规则可以 scope 到特定工作空间。你可以有：

- 一�?*全局**规则�?`ShellExecuteTool` 需要审�?
- 一�?*工作空间特定**的规则说命令匹配一个狭窄的只读模式�?`ShellExecuteTool` 被允�?

**只有第二条规则在那个工作空间里生效�?* 其他工作空间只看到全局规则�?

### Wiki 知识�?

Wiki KB 的数�?*永远不会离开它的工作空间**。工作空�?B 里的 Agent 读不到属于工作空�?A �?KB�?*即使它尝�?*。Wiki 检索和读取工具从绑�?Agent 的工作空间解析知识库 ID；跨工作空间读在 API 层被拒绝�?

### 记忆文件

工作空间记忆文件（PROFILE.md、MEMORY.md、每日笔记）住在 `workspace/{workspaceId}/{agentId}/` 下面。File Guard 执行工作空间边界；记忆工具的 list/read/write 操作被限定到调用者的工作空间内�?

### 渠道

每个渠道绑一�?Agent，传递地绑一个工作空间。工作空�?A 里配置的一个钉钉机器人和工作空�?B 里配置的一个钉钉机器人**完全独立**，即使它们被配置成连接同一个钉钉应用（你大概率不想这样，但技术上允许）�?

---

## 工作空间隔离**�?*覆盖�?

- **共享的全局配置**——JWT secret、模型供应商 API Key、MCP 服务定义是全局的。工作空间管理员改不了�?
- **审计日志的跨工作空间访问**——带正确权限的安全管理员可以跨所有工作空间查询审计事件。这�?*刻意�?*——你想看到可疑活动，不管它发生在哪个工作空间�?
- **Token 用量报告**——全局聚合，在仪表盘里按工作空间、按 Agent、按模型细分�?
- **模型供应商成�?*——全局层面每个 provider 一个计费关系；按工作空间的配额在[路线图](./roadmap)上�?

---

## 在工作空间之间移动资�?

**不直接支持�?* 你有两个选项�?

1. **导出导入**——一些资源有 JSON 导出（Agent �?API、Wiki KB �?API）。在目标工作空间重新创建�?
2. **改所有权**——admin �?owner 可以直接在数据库里更新简单资源的 `workspace_id` 列。这不是官方支持的；**自担风险而且一定要带备�?*�?

我们希望在未来版本里支持一等公民的移动。需要这个就�?[GitHub issue](https://github.com/matevip/GLClaw/issues) 上留言�?

---

## 删除一个工作空�?

**只有 Owner 能删工作空间�?* `设置 �?工作空间 �?[工作空间] �?删除`。如果工作空间下�?*拥有 Wiki 知识�?*，删除会失败——先迁移或删掉这�?KB 再删工作空间�?

删工作空间会�?

- 软删除它下面的每一个资源——Agent、技能、KB、会话、记忆文件、渠�?
- 移除所有成员关�?
- 记录一个审计事�?

**软删�?*意味着数据不被物理移除——它被标记为 `deleted = 1`、从查询里隐藏。误删的话数据库管理员可以通过翻转标记恢复。配置的保留期过后，删除的数据可能被清理任务永久清除�?

---

## 工作空间管理 API

```bash
# 列出你所属的工作空间
curl http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>"

# 获取单个工作空间详情
curl http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>"

# 创建
curl -X POST http://localhost:18088/api/v1/workspaces \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"name": "产品调研"}'

# 更新
curl -X PUT http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"description": "更新后的描述"}'

# 删除（仅 owner�?
curl -X DELETE http://localhost:18088/api/v1/workspaces/1 \
  -H "Authorization: Bearer <token>"

# 成员管理
curl http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>"

curl -X POST http://localhost:18088/api/v1/workspaces/1/members \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "init-pass-123", "role": "member"}'

curl -X DELETE http://localhost:18088/api/v1/workspaces/1/members/42 \
  -H "Authorization: Bearer <token>"

# 更新角色：路径是 /members/{memberId}，不�?/members/{memberId}/role
curl -X PUT http://localhost:18088/api/v1/workspaces/1/members/42 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"role": "admin"}'
```

---

## 数据模型

**`mate_workspace`**

| �?| 用�?|
|----|------|
| `id` | 主键 |
| `name` | 工作空间�?|
| `description` | 简短描�?|
| `owner_id` | Owner 的用�?ID |
| `create_time` / `update_time` | 时间�?|
| `deleted` | 逻辑删除标志 |

**`mate_workspace_member`**

| �?| 用�?|
|----|------|
| `id` | 主键 |
| `workspace_id` | 外键�?`mate_workspace` |
| `user_id` | 外键�?`mate_user` |
| `role` | `owner` / `admin` / `member` / `viewer` |
| `joined_at` | 用户加入这个工作空间的时�?|
| `create_time` / `update_time` | 时间�?|

---

## 下一�?

- [控制台](./console)——工作空间切换器�?UI
- [安全与审批](./security)——工作空间隔离和 Tool Guard、File Guard 的交�?
- [LLM Wiki](./wiki)——工作空�?scope 的知识库
- [记忆系统](./memory)——工作空间记忆文�?
