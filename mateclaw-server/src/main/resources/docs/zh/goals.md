---
title: 持久化目�?�?跨多轮锁定，让员工自己跟�?
description: GLClaw �?Goal 系统让数字员工把跨多轮的任务锁成一个目标，自己评估进度、自己续命，直到完成或耗尽预算�?
head:
  - - meta
    - name: keywords
      content: Goal,目标管理,Agent,多轮对话,自动评估,auto-followup,持久�?GLClaw
---

# 持久化目�?

> **以前你每轮都要把上下文重复一遍。现在你定一个目标，员工自己跟�?*

一次对话里你说"帮我把这个博客部署到 fly.io"，员工答完一轮就停了。下一轮你要再�?DNS 配好没？证书呢？测试跑了吗？"——你在替它记目标�?

Goal 把这件事翻过来�?*你说一次，员工锁住目标，自己每轮自检：还差什么？要不要自己再做一步？**

它不是聊天里的一个新功能。它是员工的一�?*状�?*。员工头像周围多了一圈光，光填多少就是离完成多远。完成了，光消失�?

---

## 它在视觉上长什么样

不是一�?banner。不是一�?dialog。不是一个独立的标签页�?

�?**assistant 头像周围的一圈环**�?

| 状�?| 视觉 | 含义 |
|------|------|------|
| 无目�?| 头像就是头像 | 这条对话没绑目标，跟过去一�?|
| 进行�?| 头像 + 橙色�?| 有目标在跟，光填到进度处 |
| 评估�?| 头像 + 沙金呼吸光晕 | 后台正在判断这轮答案 |
| 已完�?| 头像 + 绿色环（短暂出现�?| 目标达成，环随后消失，对话继�?|
| 预算耗尽 | 头像 + 红橙色环 | 用完 budget，需要你决定加预算还是放�?|

**hover 头像**才显示完�?tooltip �?标题 + 还差什么。不 hover 就不打扰你。这是设计意图�?

---

## 怎么定一个目�?

三种方式，按门槛从低到高�?

### 方式 1 �?让员工自己定

你只要在第一次描述任务时让员工知道这是个长任务：

> 我要做一个完整的项目：把 README 翻译成英文、提 PR、走 review、合并。这跨多轮，**请你�?setGoal 锁定**，每轮自我评估，turnBudget=8，autoFollowup 开启�?

员工识别�?长任�?+"明确要求 setGoal"两条信号，会自动调用工具创建目标，title 从对话上下文自动归纳。你只需点开它的回答，看见头像旁边多了一圈光，就知道目标已锁�?

### 方式 2 �?直接命令工具

不想让员工判断，你直接告诉它调哪个工具、传什么参数：

> 请立刻调�?setGoal 工具，title="部署博客�?fly.io"，turnBudget=10，autoFollowup=true。不要问任何前置确认�?

"不要问前置确�?这一句很重要 �?否则员工会先�?代码在哪？域名是什么？" 它的本能就是先澄清�?

### 方式 3 �?通过 API 程序化创�?

对自动化、外部脚本，REST 端点直接可用�?

```
POST /api/v1/goals
{
  "conversationId": "conv-xxx",
  "agentId": "1000000001",
  "workspaceId": 1,
  "title": "部署博客�?fly.io",
  "description": "...",
  "exitCriteria": "DNS+SSL+健康检�?测试通过",
  "turnBudget": 10,
  "llmCallBudget": 200,
  "autoFollowupEnabled": false
}
```

完整接口列表�?[API 参考](./api)�?

---

## 一个目标里有什�?

最少四样：

| 字段 | 含义 |
|---|---|
| **标题 (title)** | 短句，光�?hover 时显�?|
| **描述 (description)** | 完整诉求 |
| **退出判�?(exitCriteria)** | LLM 可读的判据，evaluator 按这个打分（比如 "DNS 配好+测试通过"�?|
| **预算 (turnBudget + llmCallBudget)** | 防失控上�?|

可选：

- **自动延续 (autoFollowupEnabled)**：开了之后，员工答完一轮如果觉�?还没完成"，会自己接着做下一步，不等你催
- **冷却 (followupCooldownSeconds)**：两次自动延续之间至少隔多久

---

## 它在后台是怎么运转�?

每次员工回答完一轮，后台会跑一个评估节点。这个节点：

1. 取员工这一轮的最终回�?+ 最近几条消息上下文
2. 调一个轻�?evaluator（建议指向便宜的小模型）问：完成度多少（0~1）？还差什么？该继续还是已完成�?
3. 把答案写�?`mate_agent_goal_event` 时间线表�?
4. 决定下一步：完成 / 预算耗尽 / 继续 / 自动延续

**关键不变�?*：评估发生在 final answer 已经串给你看完之�?�?**不阻塞用户看回答**。你看到回答出现 �?短暂后头像旁边的光环进度变化�?

### 自动延续是怎么发生�?

如果 `autoFollowupEnabled=true` 且这一�?evaluator �?"continue"，后台会�?

1. 写一�?`followup_injected` 事件到时间线
2. 给对话末�?APPEND 一条用户消息："Continue working on the goal. Still missing: {gap}. Take the next concrete step."
3. 让员工再跑一�?reasoning�?*这一轮的回答就直接接在第一轮后�?*

你的体感是：员工答完一�?�?停半�?�?**继续往下做** �?就像一个人做完一步停了一下想了想然后继续�?

---

## 4 个内置工具（员工可用�?

员工的工具集里默认包含这 4 个（无需手动绑定，是 agent-wide 系统级工具）�?

| 工具 | 用�?| 触发提示词示�?|
|---|---|---|
| **setGoal** | 创建目标 | "请用 setGoal 锁定本次目标，title=..." |
| **addGoalCriterion** | 追加子准则到已有目标 | "再加一条准则：必须支持 IPv6" |
| **completeGoal** | 显式标记完成 | "所有事项已做完，请 completeGoal" |
| **getGoalStatus** | 查询当前 goal 状�?| "我们现在进展到哪了？" |

完成�?(`completeGoal` �?evaluator �?score�?.95)，员工会把这个目标的总结同步到[长期记忆](./memory)，后续对话能查得回来�?

---

## 子员工不能改父员工的目标

[多员工协作](./agents)�?parent 员工可以委派 child 员工干活。Child **看不�?*�?4 �?goal 工具 �?目标�?parent 会话的状态，child 是无状态的执行体�?

> 这一条是设计意图，不�?bug。child �?parent 做事，但目标�?所有权"留在 parent 那�?

---

## 预算耗尽�?

```
turnsUsed >= turnBudget  �? (agentLlmCallsUsed + evalLlmCallsUsed) >= llmCallBudget
```

任一条命�?�?目标状态翻�?**exhausted**，不再触发评估、不再注�?follow-up，光环变橙红色。员工的最后一轮回答会正常发送给你�?

你的选择�?

- **加预�?+ 恢复** �?通过 `PATCH /api/v1/goals/{id}` �?budget �?resume（v1 暂未�?UI 提供按钮，可以走 API 或先 abandon 重新创建�?
- **放手** �?�?abandon，conversation 上释放槽位，可以重设新目�?

---

## 状态机

```
   create
     �?
   active
   �?   �?
 paused 
   
 active ──evaluator score�?.95 / completeGoal──�?completed (终�?
   �?
 active ──turns_used/llm_calls 用完 ─────────�?exhausted (终�?
   �?
 active ──user abandon ─────────────────────�?abandoned (终�?
```

终�?(completed / exhausted / abandoned) 不能复活。要继续就开�?goal �?这是有意保留的简单约束，避免 "重启" 带来的预算账目混乱�?

**一会话一目标**：每�?conversation 同一时刻最多一�?active goal。终�?goal 留在历史里不占名额。底层用 H2 / MySQL 的生成列 + 唯一索引保证并发安全，service �?+ DB 层双重防御�?

---

## 这套系统不做什�?

按设计原则保留了几个"不做"�?

- **不做嵌套目标 / 目标�?* �?一�?conversation 一个目标，不堆 OKR
- **不做"目标模板"** �?每个目标是手写的，不是从库里挑的
- **不做�?conversation 迁移目标** �?想要那效果，请用[工作流](./workflow)
- **不暴露评估分数给用户** �?那个 `completionScore` 是工程内部协议，不是用户语言。UI 用一圈光说话，hover 显示 evaluator 写的 gap 文本（自然语言）。后端日志和 API 里仍可见数值，方便调试

---

## 完整事件时间线（drawer 抽屉视图�?

每个目标都有一份只增不删的事件时间线，按时间倒序展示�?

| 事件 | 触发 |
|---|---|
| `created` | setGoal 工具�?REST POST |
| `evaluated` | 每轮答完，evaluator 跑完一�?|
| `followup_injected` | autoFollowup 触发，注入了 prompt |
| `completed` | evaluator 判完成或 completeGoal 工具 |
| `exhausted` | budget 用尽 |
| `paused` / `resumed` / `abandoned` | 用户手动操作 |
| `criterion_added` | addGoalCriterion 工具 |

通过 `GET /api/v1/goals/{id}/events` 拉取（详�?[API 参考](./api)）�?

---

## 配置�?

`application.yml`�?

```yaml
GLClaw:
  goal:
    # 主开关；关闭后图节点对所有调�?pass-through
    enabled: true
    # 默认 turn 预算
    default-turn-budget: 20
    # 默认 LLM 调用预算（agent + evaluator 之和�?
    default-llm-call-budget: 200
    # 自动延续之间至少隔多久（秒）
    auto-followup-cooldown-seconds: 0
    # 评估器使用的模型；空字符�?= 沿用对话当前模型（便宜的小模型推荐：qwen-turbo / glm-4-flash�?
    evaluator-model: ""
    # 评估 prompt 携带的历史消息条数上�?
    evaluator-context-messages: 8
```

---

## 数据�?

两张表，都用 `mate_` 前缀�?

| �?| 用�?|
|---|---|
| `mate_agent_goal` | 目标本体；含 status / budget / �?LLM 计数�?/ 自动延续配置 |
| `mate_agent_goal_event` | 目标的事件追加日志，drawer 时间线读�?|

迁移�?Flyway �?`V120__agent_goal.sql`（H2 + MySQL 双方言）�?

---

## 一句话总结

**Goal 不是给员工加一个功能。是改它的状态�?*

以前的员�?答完就忘"。Goal 让员工跨多轮记住一件事 �?它在干什么、还差什么、什么时候算完。你只用说一次。剩下的，让头像旁边那圈光替你跟�?
