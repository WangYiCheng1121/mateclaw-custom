# 触发器（Triggers�?

::: tip 1.3.0 新增
触发器系统自 v1.3.0 起提供。在 v1.2.0 及更早版本里，工作流和员工对话只能被手动调起�?
:::

**触发器是什�?*：把"系统里发生的事件"�?要执行的动作"连起来。事件可以是定时（cron）、是 webhook 来了、是某个渠道收到消息、是某个员工跑完了某次对话、是另一个工作流跑完了。动作可以是启动某个工作流，也可以是直接给某个员工发消息让它处理�?

**触发器不是什�?*�?
- 不是 cron 任务管理器替代品——`mate_cron_job` 仍然存在并独立运作；触发�?*复用**它的 ShedLock + 调度器底座，�?*不写�?* `mate_cron_job`
- 不是 IFTTT / n8n 风格的可拖拉自动化——触发器只负�?事件 �?动作"的路由；复杂逻辑放到 [工作流](./workflow.md) �?
- 不是 webhook 的全功能 dispatcher——它只做去重 / 限流 / bot self-msg 过滤 / pattern 匹配，不替你解析复杂业务报文

::: warning v1.3.0 范围
v0 = 6 �?pattern type + 2 �?dispatch target（agent / workflow）。安全治理（事件去重、per-trigger rate limit、循环保护、bot 自消息过滤）是默认开的�?
:::

---

## 一分钟看懂

```jsonc
// 触发器：每天早上 9 点跑一�?晨报工作�?
{
  "name": "daily-morning-report",
  "patternType": "cron",
  "patternJson": {
    "cronExpression": "0 0 9 * * *",
    "timezone": "Asia/Shanghai"
  },
  "targetType": "workflow",
  "targetId": 12345,
  "payloadTemplate": "{ \"date\": \"{{ now | date('yyyy-MM-dd') }}\" }",
  "rateLimitPerMin": 10,
  "dedupWindowSecs": 60,
  "botSelfFilter": true,
  "enabled": true
}
```

每天 9 �?�?后端通过 `CronDelegationPort` 抢到 ShedLock �?�?渲染 payload �?调起 workflow `12345` 异步运行。其它实例同一时刻被锁挡住，不会重复触发�?

---

## 6 �?pattern type

实现�?`TriggerPatternMatcher.java`。每�?pattern 对应 trigger 行的 `pattern_json` 列里一�?JSON�?*未列出的字段表示 v0 不识�?*——matcher 对未知字段直接忽略�?

| Pattern | 触发时机 | `pattern_json` 字段 | 复用约束 |
|---|---|---|---|
| `cron` | �?cron 表达式定时（**不进 ingest 管道**，由 scheduler 直跑�?| `cronExpression`、`timezone` | 复用 `cron/` 模块�?ShedLock + Spring TaskScheduler�?*不写 mate_cron_job 实体、不�?CronJobService** |
| `webhook` | 通用事件入口透传�?*v0 不做更细过滤**——secret 校验�?channel 层；trigger 这边只看 `patternType=webhook` 命中�?| （v0 无字段） | 通过 `POST /api/v1/triggers/events` 入口 + envelope wrap |
| `channel_message` | 渠道收到消息 | `channelType`（可选，�?envelope `data.channelType` 比对）、`senderEquals`（可选，�?sender id 精确比对�?| 旁路 `ChannelWebhookController`，原路由不变 |
| `agent_lifecycle` | 员工生命周期事件 | `agentId`（可选）、`phase`（可选，取�?`spawned` / `terminated` / `crashed`�?| 挂在 `ReActLifecycleListener` �?|
| `content_match` | 内容包含 substring 才命�?| `substring`�?*必填**，envelope �?`data.content` 字段大小写不敏感包含匹配�?| 通用过滤层，事件源由 envelope 决定 |
| `workflow_completion` | 工作流跑完进入终�?| `sourceWorkflowId`（可选）、`stateFilter`（可选，取�?`completed` / `failed` / `any`�?| 监听 `WorkflowEngine` 终态事件；A→B→A 递归保护见下�?|

> **未知 pattern type 默认 fail-closed**——typo 或将来加�?pattern 不会偷偷�?workspace 内所�?trigger 都点燃�?
>
> **不在 v1.3.0 �?*：`schedule`（不�?cron 的定时如"30 分钟�?）、外�?MQ 监听（Kafka / Pulsar / RocketMQ）、metrics / threshold 告警触发�?

---

## 事件治理（默认开�?

### Bot self-msg 过滤（默认绑定为 noop�?

某些渠道（飞�?/ 钉钉 / 企微）会�?bot 自己发的消息也回流为 `channel_message` 事件�?*框架�?*通过 trigger 行的 `bot_self_filter` 字段（默�?`true`�? `BotSelfFilter` SPI 协作过滤�?

::: warning v0 默认实现�?noop
开箱默认绑的是 `NoopBotSelfFilter`——`isBotSelf(...)` **永远返回 false**。这意味着 `bot_self_filter=true` �?trigger 现在**不真过滤任何事件**。要让过滤真正生效，需�?channel 适配器侧注册一个真正能识别自己 bot id �?`BotSelfFilter` Spring Bean（替换默认实现）。这是有意设计的——避免一个错误的 default 实现把所有合法的 bot 间通讯都误杀�?
:::

要单独让一�?trigger 接受自己 bot 的消息（极少见，比如 bot 发特殊命令触发清理流程），把这条 trigger �?`bot_self_filter` �?`false`�?

### 事件去重

事件�?`TriggerEventIngestService` 派发时，引擎�?`mate_trigger_event` 表上�?`dedup_key` 是否已经�?`dedupWindowSecs`（默�?60s）时窗内入过库。已经在 �?**直接丢弃**，连 `fire_count` 都不++�?

默认 `dedupWindowSecs = 60`。提高这个值可以扛更长时间的网关重投递；调到 `0` 关闭去重�?*不推�?*）�?

### Per-trigger rate limit

每个 trigger 单独限速：1 分钟最�?`rateLimitPerMin` 次（默认 10）。命中限速的事件被丢弃，**�?*重试�?*�?*�?`mate_trigger_event` 行；`mate_trigger.last_error` 字段会被刷成 `"rate-limited"` 便于运维查�?

`channel_message` �?trigger 通常要调高（瞬时群发）；`workflow_completion` 类通常调低（防�?A→B→A 链路加速）�?

### 递归循环保护

`workflow_completion` trigger 启动�?workflow 又触发另一�?`workflow_completion`……dispatch 链超�?5 �?�?引擎切断 + 告警。这是防�?A 写消息触�?B，B 写消息又触发 A"递归�?

### Webhook ACK 时序

HTTP 入口（`POST /api/v1/triggers/events`）收到事�?�?envelope wrap �?dedup check �?bot-self check �?rate limit check �?**立即 ACK 200** �?异步 dispatch。这意味着�?

- 上游网关（飞�?/ 钉钉 等）拿到 200 就不再重�?
- 实际 dispatch 失败 �?`mate_trigger.last_error` 被刷新；�?`dedup_key` 再来仍然被去重挡掉，**不重�?*

如果你需�?dispatch 成功�?ACK"语义�?*目前没有**——v0 故意设计�?fire-and-forget 扛峰值�?

---

## �?UI 里管理触发器

::: tip 1.4.0 调整：合并进"调度中心"
v1.4.0 起，**定时任务**�?*触发�?*合并为单�?*调度中心**页面（`设置 �?调度中心`，路�?`/settings/scheduler`），分三�?tab�?*计划任务**（Scheduled Jobs�? **事件触发�?*（Event Triggers�? **运行历史**（Run History）。每�?tab 标题旁带条目计数；右上角动作按钮随当�?tab 变化（计划任�?/ 触发�?tab �?新建"，历�?tab �?刷新"）；运行历史**横跨两�?*，定时任务和触发器的执行记录都在这里看�?

老路由会自动重定向：`/cron-jobs` �?`/settings/triggers` 分别落到调度中心对应�?tab�?
:::

### 入口

`设置 �?调度中心`（侧栏）�?**事件触发�?* tab。触发器列表�?v1.4.0 里从原来的宽表格改版�?*规则卡片**——每�?trigger 一张卡，pattern type / target / 启停状态一目了然。点 **+ 新建触发�?* 打开抽屉�?

### 创建 trigger

抽屉里按 6 �?pattern type 各自结构化表单填字段——不需要手�?`pattern_json`�?

- �?`cron` �?cron 表达式输入框 + 时区下拉 + 下一次触发时间预览。表达式可手输，也可点输入框旁的编辑按钮打开**可视�?cron 编辑�?*（见下）
- �?`channel_message` �?渠道类型可�?+ （可选）�?sender id 精确匹配
- �?`agent_lifecycle` �?agent 可�?+ phase（spawned / terminated / crashed）可�?
- �?`content_match` �?substring 输入�?*必填**），匹配 envelope �?`data.content`
- �?`workflow_completion` �?上游 workflow 可�?+ state filter（completed / failed / any）可�?
- �?`webhook` �?v0 没有额外字段（透传一切）

填完保存 �?trigger 入库；`enabled=true` 时立即注册到对应引擎（cron 注册�?ShedLock；其它走 envelope 路由）�?

### 可视�?cron 编辑器（1.4.0 新增�?

cron 表达式不必手写。点表达式输入框旁的编辑按钮打开**分段编辑�?*：分�?/ 小时 / �?/ �?/ 星期 各占一�?tab，每段可�?每个 / 指定�?/ 区间 / 步进"；上方一�?*预设**（每分钟、整点、每天午夜、每周一……）一键填入；底部�?*实时可读预览**，把当前表达式翻译成人话（例�?每天 09:00"）�?

这个编辑器是**计划任务和触发器共用**的同一个组件：

- **计划任务**�?**5 �?* cron（分 �?�?�?周）
- **触发�?*�?**6 �?* cron（带秒：�?�?�?�?�?周）——多出最前面的秒字段

输入框本身也带一行可读预览，不打开编辑器也能确认你手输的表达式解析成了什么�?

---

## 调度任务类型（task type�?

调度中心 **计划任务** tab 里的每条任务都有一�?`task_type`，决定它跑起来做什么。这�?cron 任务类型的权威清单（事件触发器的 6 �?pattern type 见上文）�?

| task type | 行为 | 是否绑定员工 | 备注 |
|---|---|---|---|
| `text` / `agent` / `reminder` | �?cron 调起一次员工对�?| **�?*（必�?agent�?| 经典定时对话；结果路由到对应会话 |
| `wiki_process` | �?cron 离线处理某个知识�?| **�?* | 1.4.0 新增——见�?|

### `wiki_process`：错峰处理知识库�?.4.0 新增�?

`wiki_process` 让你�?*知识库的处理**安排到业务低峰时段离线跑，而不是上传完就立刻占满处理队列。它**不绑定任何员�?*——它是个系统任务，不开对话、不进聊天�?

新建时只需要填�?

- **cron 表达�?*（用上面的可视化编辑器，5 段）
- **知识库选择�?*——这次任务要处理哪个 KB
- 可选的 **"强制重新处理"** 开关——开了就连已处理过的原始材料一起重跑（`force`�?

每次到点，任务把�?KB 的原始材�?*异步入队**处理，并在运行历史里记一行结果，形如 `queued N raw material(s)`（开了强制会�?`(force)` 后缀）�?*注意它不路由到任何对�?*——它只是把活儿丢进处理队列，进度�?[LLM Wiki](./wiki.md) 页面看�?

### Payload template

`payload_template` 字段�?Pebble 模板字符串，渲染后作�?dispatch target（agent 对话�?workflow run）的输入�?

```jsonc
"payload_template": "{
  \"date\": \"{{ now | date('yyyy-MM-dd') }}\",
  \"trigger\": \"{{ trigger.name }}\",
  \"sourceEvent\": {{ event | toJson }}
}"
```

模板可访问的变量�?
- `now` —�?当前时间
- `trigger.{name,id,workspaceId}` —�?当前触发�?
- `event` —�?当前事件 envelope（`workspaceId` / `senderId` / `data` JSON 等）

### 查看触发历史

`mate_trigger_event` 表存的是**去重元数�?*——一行记录含 `trigger_id` / `dedup_key` / `received_at` / `expires_at`，不�?envelope 副本本身。要审计具体一次事件的内容，查 `mate_trigger.last_error` + dispatch 日志�?

`mate_trigger.fire_count` 诚实记录有效 dispatch 次数（不计被去重 / 限速过滤掉的）；`mate_trigger.last_error` 记录最近一次失败原因�?

---

## API 参�?

所�?endpoint �?`/api/v1/triggers/` 下。`v1.3.0` 实际暴露的就这些——RFC 里规划的 `/webhook/{slug}` / `/test-fire` / `/{id}/events` 暂未实装�?

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/triggers` | 列当�?workspace 所�?trigger |
| `GET` | `/api/v1/triggers/{id}` | 获取详情 |
| `POST` | `/api/v1/triggers` | 新建 trigger；若 `enabled=true` 立即注册�?scheduler / 路由 |
| `PUT` | `/api/v1/triggers/{id}` | 更新（包括启�?/ 禁用——改 `enabled` 字段即可）；`pattern_json` 改动�?`pattern_version++`，跨实例自取消旧 future |
| `DELETE` | `/api/v1/triggers/{id}` | 软删（等同禁用） |
| `POST` | `/api/v1/triggers/events` | **统一事件入口**——任�?webhook / channel adapter / 内部模块送一�?envelope 进来；引擎做 dedup / bot-self / rate limit / pattern match / dispatch；返�?per-trigger 命中 / 丢弃汇�?|

---

## 跟现�?cron 模块的关�?

::: tip 不取代，只复�?
v1.3.0 之前 GLClaw 已经有一个独立的 cron 子系统（`mate_cron_job` �?+ `CronJobService`）。Trigger 系统**不取代它**—�?
- 老的 cron 任务（task_type = `text` / `agent` / `reminder`）仍然在 `Cron Jobs` 页面管理
- 新的 trigger cron �?`Triggers` 页面管理
- 两�?*共享**底层 ShedLock 锁表 + Spring TaskScheduler 线程�?
- `mate_cron_job` 列表**不会**显示 trigger cron；反过来也是
:::

为什么不合并？因�?`mate_cron_job` 老表�?`task_type` / `agentId` 必填等字段不适合 workflow target。强行扩列会破坏既有 product 约束。`CronDelegationPort` �?v0 的最小化解——共享调度底座，分离持久层。`mate_cron_job` 整体收敛�?trigger 是后续版本的工作�?

---

## 跨实例一致性（多副本部署）

`CronDelegationPort` 的所有方法是**进程局�?*的——本�?ScheduledFuture 只在�?JVM 注册，不持久�?handle。跨实例靠：

1. 每个实例启动时调 `syncFromDatabase()` 扫所�?enabled cron trigger 注册本地
2. 修改 trigger �?`pattern_version++` + 取消本地 future
3. 每次 fire 前重新读 trigger 行，`patternVersion` 不匹配则**本地短路自取�?*（说明被别的实例改过�?
4. ShedLock 锁名 = `"mate-trigger-{triggerId}"`，跨实例互斥
5. 周期 `@Scheduled(fixedDelay=60s) syncFromDatabase()` 兜底收敛

实战意义：你正常 rolling-deploy 多副本不需要做任何额外动作——新实例起来自动接管，老实例本�?future 走完最后一轮就停�?

---

## 数据模型

### `mate_trigger` —�?触发器配�?

主要字段�?

| 字段 | 类型 | 用�?|
|---|---|---|
| `pattern_type` | varchar | 6 �?pattern 之一 |
| `pattern_json` | TEXT | �?pattern 的过滤参�?JSON |
| `target_type` | varchar | `agent` �?`workflow` |
| `target_id` | bigint | 对应 agent / workflow 主键 |
| `payload_template` | TEXT | Pebble 渲染模板 |
| `dedup_window_secs` | int | 去重窗口（秒�?|
| `rate_limit_per_min` | int | 每分钟最�?fire 次数 |
| `bot_self_filter` | bool | 是否启用 bot self 过滤（默�?true，但默认实现�?noop�?|
| `pattern_version` | bigint | 乐观并发 lamport 计数器，**每次 `pattern_json` 改动 +1**；跨实例 fire 前比对自取消 |
| `fire_count` | bigint | 有效 dispatch 次数（不计去�?/ 限速过滤掉的） |
| `last_error` | varchar | 最近一次失败原因（�?`"rate-limited"` / 异常 message�?|
| `enabled` | bool | 软启停开�?|
| `deleted` | int | 软删标志 |

### `mate_trigger_event` —�?去重元数�?

仅用于去重判定，**不存 envelope 副本本身**�?

| 字段 | 类型 | 用�?|
|---|---|---|
| `id` | bigint | 主键 |
| `trigger_id` | bigint | 关联 trigger |
| `dedup_key` | varchar | **唯一索引**，引擎按�?key �?`dedup_window_secs` 时窗内做去重判定 |
| `received_at` | timestamp | 入库时间 |
| `expires_at` | timestamp | 去重窗口过期时间，超过此点同 key 可以重新入库 |

::: tip 设计取舍
v0 故意**不把 envelope 全文写进 `mate_trigger_event`**——大体量渠道事件全量持久化撑不住库。事件正文的审计依赖 channel 层日�?+ agent / workflow 层的 run 记录。如果未来需�?事件回放"等能力，再加 envelope 持久化列�?
:::

---

## 已知限制（v1.3.0�?

- **没有可视�?trigger �?workflow 串联�?*——多 trigger 投递到�?workflow �?UI 上看是两个独立列�?
- **没有 trigger 间优先级 / 依赖**——同一事件命中�?trigger 时按数据�?id 升序串行 dispatch
- **Webhook 入口没鉴�?IP allowlist**——只�?secret header；如果你需要更强的 IP 限制，前�?nginx / 网关
- **`agent_lifecycle` 不区分会话级�?step �?*——员工一次对话内多次 step 失败只会触发一�?`failed`
- **没有事件回放**——`mate_trigger_event` 是只读历史，没有"重新派发这条事件"的按钮（v1 加）

---

## 故障排查

| 现象 | 排查 |
|---|---|
| Cron trigger 没触�?| 1) `enabled=true`�?2) cron 表达�?+ 时区是否解析为下次时间？UI 编辑器有预览�?3) ShedLock 锁是否被另一实例长持？查 `shedlock` �?|
| 事件 `POST /events` 返回 200 �?dispatch 没发�?| 返回体里�?per-trigger fire / drop 汇总——看是否�?`BOT_SELF` / `RATE_LIMITED` / `DEDUPED` / `PATTERN_MISMATCH` 标了原因 |
| `channel_message` 触发不起�?| 1) envelope �?`data.channelType` 拼写大小写是否和 trigger �?`pattern_json.channelType` 匹配�?) `bot_self_filter=true` 但有自定�?`BotSelfFilter` 实现把它过掉了？3) `content_match` �?`substring` 是否真的出现�?envelope �?`data.content` �?|
| `agent_lifecycle` 没触�?| 检�?`pattern_json.phase` �?`spawned` / `terminated` / `crashed` 之一（不�?`started` / `completed` / `failed`�?|
| 重启�?cron trigger 不再触发 | 看启动日�?`syncFromDatabase()` 是否报错；常见是表损�?/ `pattern_json` 反序列化失败 |
| `mate_trigger.last_error` �?`"rate-limited"` | 调高 `rate_limit_per_min` 或者把 trigger 拆成多条�?group 分流 |
| `bot_self_filter=true` 没起作用 | 确认 `BotSelfFilter` 是否真有�?noop 实现——默�?`NoopBotSelfFilter` 永远返回 false |

---

## 相关链接

- [工作流（Workflow）](./workflow.md) —�?`target_type=workflow` �?dispatch 到这�?
- [数字员工](./agents.md) —�?`target_type=agent` �?dispatch 到这�?
- [多渠道接入](./channels.md) —�?`channel_message` pattern 监听的事件来�?
- [审批与安全](./security.md) —�?webhook secret + ACL 兜底
