# 路线�?

> "人们不知道自己想要什么，直到你把它摆在他们面前�?
>
> 这不是一份功能清单。这是一个关�?*你的 AI 助手应该如何存在**的宣言�?

---

## 我们的信�?

每个人都值得拥有一个真正理解自己的 AI 助手�?

不是一个聊天玩具。不是一个技�?demo。而是一�?*数字分身**——它知道你的工作方式，连接你的所有工具，替你思考、替你执行、替你记住�?

GLClaw 就是这个东西�?

---

## 我们已经做到了什�?

### v1.0 —�?它能思考和行动 �?已发�?

让一�?AI 助手成为"会用工具的同�?，不是一个聊天框�?

- ReAct 引擎：思考、行动、观察、再思�?
- Plan-and-Execute 编排：先制定计划，再逐步执行
- StateGraph 架构：基于状态图�?Agent 编排
- DynamicAgent：从数据库加载配置，运行时随时调�?
- 20 个内置工具：搜索 / Shell / 文件 / 委派 / 多模态生�?/ 定时任务 / SQL 查询
- ToolGuard + FileGuard + AuditLog：每一次工具调用有审批、有控制、有记录
- SKILL.md 技能系统：像装 App 一样给 AI 装新能力

### v1.1 —�?它无处不�?�?已发�?

�?AI �?网页上的对话�?搬进你团队真正在用的每一�?IM�?

- **8 个渠�?*：Web / 钉钉 / 飞书 / 企业微信 / Telegram / Discord / QQ / 微信个人 / Slack
- 会话来源追踪：每条消息都知道来自哪个渠道
- 4 层记忆：会话上下�?+ 工作空间记忆 + 对话后提�?+ 每天凌晨 2:00 自动整合
- DREAMS.md 整合日记：人类可读的记忆变更审计
- 工作空间隔离：每�?agent / skill / wiki / conversation / memory 都属于一个工作空�?
- ChatGPT OAuth + Anthropic Claude Code OAuth：用订阅直接登录，不需�?API Key
- LLM Wiki 知识�?+ RAG：把原始文件吃进去变成结构化、有双向链接、有摘要的知识页

### v1.2 —�?它是你的同事 �?已发布（2026-05-05�?

�?智能�?换成**数字员工**——这不是术语洁癖，是世界观换了�?

- **数字员工**：每位有角色（Role）、目标（Goal）、背景故事（Backstory），不是冰冷�?system prompt
- **5 个职业模�?*：产品研究员 / 客户支持 / 知识管理�?/ 数据分析�?/ 行政助理——开箱即�?
- **技能不再是工具的别名，是骨�?*：每个技能有自己�?SKILL.md + LESSONS.md + workspace 文件空间
- **ACP 桥接**：Claude Code、Codex、Gemini CLI 这些顶级编码 Agent �?员工"身份接入
- **Backstage 运行时控制台**：你第一次能**看见每个员工正在干什�?*——谁在跑、跑到哪一步、占多少 token、卡住了一键回�?
- **Onboarding wizard**：首次登录四步从零到第一条消�?
- **Dashboard**：日维度 usage 趋势 + 头部 agent / tool 排行
- **Doctor**：系统健康检�?+ 一键修�?

完整故事：[v1.2.0 Release Notes](./releases/1.2.0.md)�?

---

## v1.3 —�?工作流元�?�?已发布（2026-05-13�?

> "聚焦不是对要关注的事情说 Yes。而是对其他一百个好点子说 No�?

数字员工各自能干活只是起点�?*真正的协作需要编�?*�?

v1.3 的主线是**�?GLClaw �?chatbot 框架"升级�?业务流程 OS"**——一条业务流不再是几个员工各自聊天的总和，而是一份可发布、可触发、可重放�?*线�?step DSL**�?

完整故事：[v1.3.0 Release Notes](./releases/1.3.0.md)�?

### 工作流（Workflow�?

- [x] **7 �?step mode**：sequential / fan_out / collect / conditional / await_approval / dispatch_channel / write_memory
- [x] **Pebble 表达式子�?*作为条件判断 + 变量引用语言（不带副作用、不能跑代码�?
- [x] **JSON-first 编辑**：Monaco + JSON schema 校验 + Pebble 静态检�?+ 模板下拉
- [x] **自然语言 �?工作流草�?*（`POST /workflows/draft/generate`）：用户描述需求，agent 生成 graph_json + 编译诊断；不直接发布，仍要人工审�?
- [x] **整数 revision**：发布写新行不可变；草稿与已发布版本分离
- [x] **运行历史**：每�?step �?input / output / 耗时 / token / 失败链路都被记录
- [x] **payload 内置存储**：大输入输出�?`payload://` URI，不撑库
- [x] **�?workspace ACL**：发布期校验 agent / channel / employeeId 引用都在当前 workspace �?
- [x] **`await_approval` 持久化暂�?*：服务重启不�?

### 触发器（Trigger�?

- [x] **6 �?pattern type**：cron / webhook / channel_message / agent_lifecycle / content_match / workflow_completion
- [x] **事件治理默认开**：去重（60s 窗口）、per-trigger 限速、bot self-msg 过滤、A→B→A 递归切断
- [x] **CronDelegationPort**：和�?cron 模块共享 ShedLock + Spring TaskScheduler，不�?mate_cron_job
- [x] **跨实例一致�?*：`pattern_version` 自取消机�?+ 周期 syncFromDatabase
- [x] **结构化表�?*�? �?pattern 各自有专属字段输入，不需要手�?patternJson

### 升级现有体验

- [x] **图像编辑**（issue #75）：`image_generate` 工具新增 `image` / `images` 参数，支�?5 种引用形式（�?`msg:<id>:<idx>` 引用会话内附件）
- [x] **DashScope 兼容模式**：复用同一�?sk- Key 接通点号版本号系列（qwen3.5-plus / qwen3.6-plus / qwen3-vl-plus 等）
- [x] **新万�?/ qwen-image 系列**�?4 个新图像模型�? 个新视频模型（含 happyhorse-1.0-t2v�?
- [x] **4 个文档生成工�?*：DocxRenderTool / XlsxRenderTool / PptxRenderTool / PdfRenderTool —�?Markdown 直接渲染�?Office 文件，不 fork 子进程不依赖 npm
- [x] **MCP per-agent 工具绑定**：每个员工独立绑�?MCP 工具 + 状态徽标（connected / stale / unavailable / orphan�? 命名空间冲突自动前缀�?+ server 改名自动跟随
- [x] **小米 MiMo provider**：MiMo V2.5 Pro / V2.5 / V2 Pro / V2 Omni / V2 Flash
- [x] **多模态旁路路�?*（issue #87）：纯文本主模型遇到图片附件时自动调用配置好的视觉模型转描述，主对话保持便宜；硬禁令拆掉后用户自定义工具不再被压制；路由徽章 + 输入框提示让决策全程可见

### v1.3 还要做的

- [ ] **画布编辑器（v1�?*：当前画布是只读链式渲染，目标是 `@vue-flow/core` 的可拖拉编辑
- [ ] **运行回放视图**：trace timeline + 任意节点 hover �?input/output diff
- [ ] **`loop` mode**：迭�?N 次或对数组逐项处理
- [ ] **`invoke_skill` mode**：直接调 skill 不经过员�?
- [ ] **trigger 间优先级 / 依赖**：同一事件命中�?trigger 时的串行 / 并行控制
- [ ] **事件回放**：`mate_trigger_event` �?"重新派发"按钮

---

## 下一站：v1.4 —�?场景应用元年

> "当工具足够好，就把工具藏起来，把场景推到前面�?

v1.0 �?v1.3 把基础设施做齐了：员工、记忆、知识库、工具、技能、工作流、触发器、多模态、多渠道�?*下一步不是再造一颗螺�?*，是把这些零件组装成**用户一打开就能落地的场�?*�?

v1.4 的关键词�?*场景应用**。不�?加更多功�?，是**让普通用户不用学 7 �?step mode�? �?trigger pattern 就能直接�?*�?

### 行业场景模板（Workflow + Trigger 联动�?

每一个都是一�?*可一键导入的工作流模�?+ 触发器配�?+ 推荐员工绑定 + 推荐知识库结�?*�?

- [ ] **客户工单分流**：企业微�?/ 飞书入口 �?数字员工分类 �?路由 / 升级 / 自动回复 �?写进客户档案
- [ ] **晨报 / 周报自动�?*：cron trigger �?多员工并行采�?�?数据分析员工汇�?�?生成 PDF/PPTX �?多渠道分�?
- [ ] **合同审批�?*：上传合�?�?法务员工初审 �?审批等待 �?法务员工修订建议 �?写归档记�?
- [ ] **市场情报监控**：webhook trigger（站点变更）�?内容判断（content_match）→ 商业分析员工总结 �?飞书机器人推�?
- [ ] **新员�?onboarding**：webhook（HRIS 入职事件）→ 行政助理拉文档清�?�?培训知识库引�?�?多日跟进 trigger
- [ ] **代码 PR 审查**：GitHub webhook �?代码审查员工�?review �?评论回写 PR �?关键改动�?await_approval

### 场景市场（Scenario Marketplace�?

- [ ] **场景包格�?*：一个场�?= `workflow.json` + `triggers.json` + `agents/*.md` + `knowledge/*.md` + `README.md`，可分享 / 安装
- [ ] **场景市场 UI**：浏�?/ 试运�?/ 一键安�?/ 评分评论
- [ ] **场景包版本管�?*：升级提�?+ diff 预览 + 回滚

### 让数字员工跨场景协作

- [ ] **员工目录画像**：每位员工自动生�?擅长 / 不擅�?标签（基于历史交�?+ 技�?+ 工具集）
- [ ] **场景智能推荐**：用户描�?我想�?X 流程" �?推荐最适合的场景模�?+ 已有员工
- [ ] **跨场景记忆共�?*：客户工单分流和合同审批流见到的都是同一个客户档�?

### 把基础设施进一步藏起来

- [ ] **自然语言 �?完整场景�?*：v1.3 已有"自然语言 �?工作流草�?，v1.4 把它扩展�?*整个场景**——一句话描述�?workflow + trigger + 推荐员工 + 推荐 KB 结构的完整草�?
- [ ] **典型问题向导**：把"我的工作流卡在审批没人审"这种问题做成自助诊断
- [ ] **场景级仪表盘**：不�?今天 token 用了多少"，是"今天客户工单平均处理多久"

### 同步推进的基础能力

- [ ] **场景�?ACL**：场景包安装时一次性把所需�?channel / agent / KB / 工具�?allowlist 都配�?
- [ ] **�?workspace 场景共享**：场景模板能在多个工作空间间复用（克�?+ 覆盖配置�?
- [ ] **场景运行成本预估**：安装前看见预期 token / API 调用 / 触发频率

---

## 我们故意不做的事

> "我对我们没做过的事情和我们做过的事情一样感到自豪�?

| 砍掉的功�?| 为什�?| 什么时候才该做 |
|-----------|--------|--------------|
| **完整 RBAC 权限模型** | GLClaw 是数字员工系统，不是企业管理平台。单团队不需要管�?100 种权限组�?| 当真正出现需要细粒度权限的多团队 SaaS 客户�?|
| **多租�?* | 同上。过早的多租户是架构癌症 | 当有明确�?SaaS 商业化路径时 |
| **SSO / LDAP / SAML** | 企业集成是个无底�?| 当付费企业客户明确要求时 |
| **30+ 节点的可视化工作流编辑器** | 用户大多用不上�?*v1.3 �?7 �?step mode 已经覆盖 90% 实际场景**，剩下的复杂度推�?LLM 自然语言生成 | 真有用户场景需�?30+ 节点时（很少�?|
| **移动端原�?App** | 8 �?IM 渠道 + 桌面�?+ Web 已经覆盖。你在手机上用钉�?/ 飞书 / Telegram 就在�?GLClaw | �?Web / IM 渠道有不可替代的移动专属能力�?|
| **替代 ReAct / Plan-Execute** | 工作流和这两条引�?*是协作关�?*，不是替代——单 agent 多轮推理仍在那两条引擎里 | 永远不替�?|

---

## 版本里程�?

| 版本 | 一句话 | 用户体验目标 | 状�?|
|------|--------|-------------|------|
| **v1.0** | 它能思考和行动 | 一个能用工具解决问题的 AI 助手 | �?已发�?|
| **v1.1** | 它无处不�?| 8 个渠�?+ 4 层记�?+ 工作空间 + LLM Wiki | �?已发�?|
| **v1.2** | 它是你的同事 | 数字员工 + 5 个职业模�?+ 骨架式技�?+ ACP 桥接 + Backstage 运行�?| �?已发�?|
| **v1.3** | 它能编排业务�?| 工作�?+ 触发�?+ 图像编辑 + 文档生成 + per-agent 工具绑定 | �?已发�?|
| **v1.4** | **它能落地场景** | **行业场景模板 + 场景市场 + 自然语言生成工作�?+ 跨场景员工画�?* | 📋 规划�?|

---

## One More Thing

我们�?GLClaw，不是为了追�?ChatGPT、不是为了做下一�?Dify、不是为了融�?PPT 上多一�?buzzword�?

我们做它，是因为我们相信一件事�?

**AI 不应该是一个网页上的对话框。它应该是你的第二个大脑�?*

它住在你的钉钉里、你的飞书里、你�?Telegram 里。它读过你所有的文档。它记得你三个月前说过的话。它会用你公司的内部工具。它在你睡觉的时候整理记忆�?*它能替你跑一整条业务流程**�?

总有一天，你会忘记它是一个程序�?

**那一天，就是我们成功的那一天�?*

---

*Stay hungry. Stay foolish.*
