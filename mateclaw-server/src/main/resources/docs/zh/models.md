# 模型配置

**先配一个。后面随时加�?*

GLClaw 不关心你用哪�?LLM。它通过五个协议适配器跟所有主流供应商对话，支�?15+ 个云端供应商�?4 个本地运行时，你可以在运行时**不动 Agent 配置**直接切模型。MateClaw 唯一的意见是—�?*从一个开始，需要再�?*，不是第一天就把所有东西配好�?

---

## 支持什�?

### 云端供应�?

| 供应�?| 示例模型 | 协议 | 说明 |
|--------|----------|------|------|
| **DashScope**（阿里云�?| Qwen-Max、Qwen-Plus、Qwen-Turbo、Qwen-VL、Qwen-Long | dashscope | 默认开箱即�?|
| **DashScope（兼容模式）** | Qwen3.5-Plus、Qwen3.6-Plus、Qwen3 VL Plus 等点号版本号系列 | openai | 见下�?两个 DashScope 区别" |
| **百炼 Token Plan** | 阿里百炼 token 包月套餐 | dashscope | 7 个种子模型；支持�?token |
| **OpenAI** | GPT-4o、GPT-4o-mini、GPT-5.5、o1、o3、o4-mini | openai | 标准 OpenAI API |
| **OpenAI OAuth（ChatGPT Plus/Pro�?* | 通过订阅�?GPT-4o、o3、o4-mini | openai | 浏览�?OAuth�?*不需�?API Key** |
| **Anthropic** | Claude 4.7、Claude 4.6 Sonnet、Claude 4.5 Haiku | anthropic | 原生 Messages API |
| **Anthropic Claude Code OAuth** | 通过 Claude Pro/Max/Team 订阅�?Claude 4.7 / 4.6 | anthropic | 浏览�?OAuth + 手动粘贴流，**不需�?API Key** |
| **Google Gemini** _(原生)_ | gemini-2.5-flash、gemini-3-pro-image-preview、gemini-2.5-flash-image | gemini | 原生 `generateContent` API（非 OpenAI 兼容）——见下方"原生 Gemini" |
| **xAI / Grok** | Grok 3、Grok 4 | openai | OpenAI 兼容（base URL + API Key）；UI �?xAI 品牌图标 |
| **DeepSeek** | deepseek-chat、deepseek-coder�?*DeepSeek V4 flash + pro**（支持思考模式） | openai | OpenAI 兼容 |
| **Kimi（Moonshot�?* | moonshot-v1-8k/32k/128k | openai | OpenAI 兼容 |
| **智谱 AI** | GLM-5-Turbo、GLM-5V-Turbo、GLM-5、GLM-5.1 | openai | OpenAI 兼容 |
| **MiniMax** | abab6.5、abab5.5；扩展视频模型目�?+ 国内端点 | openai | OpenAI 兼容 |
| **SiliconFlow CN/INTL** | 托管路由推理 | openai | 双端点，OpenAI 兼容 |
| **OpenCode** | 代码场景路由 | openai | OpenAI 兼容 |
| **OpenRouter** | 200+ 模型含免费档 | openai | 一�?key 路由到任何上�?|
| **小米 MiMo** _(1.3.0+)_ | MiMo V2.5 Pro / V2.5 / V2 Pro / V2 Omni / V2 Flash | openai | 小米 MiMo 平台 |
| **任何 OpenAI 兼容服务** | 你自己的 vLLM �?| openai | 自定�?base URL |

### 本地运行�?

| 运行�?| 示例模型 | 协议 | 说明 |
|--------|----------|------|------|
| **Ollama** | Gemma 3/4、Qwen 3、Llama 3.1、DeepSeek R1、Mistral | ollama | **启动时在 `localhost:11434` 自动检�?* |
| **LM Studio** | 任何 GGUF 模型 | openai | OpenAI 兼容服务�?|
| **llama.cpp** | 任何 GGUF 模型 | openai | 通过 llama-server |
| **MLX** | Apple Silicon 上的 mlx-lm | openai | OpenAI 兼容服务�?|

### 协议适配�?

五个协议覆盖一切：

| 协议 | 谁在�?|
|------|--------|
| **OpenAI** | OpenAI、Kimi、DeepSeek、MiniMax、智谱、OpenRouter、LM Studio、llama.cpp、MLX |
| **Anthropic** | Claude 家族 |
| **DashScope** | Qwen 家族 |
| **Gemini** | Google Gemini 家族 |
| **Ollama** | 通过 Ollama 跑的本地模型 |

任何 OpenAI 兼容服务都能接——把 `base-url` 指过去就行�?

---

## 两个 DashScope 区别

阿里�?DashScope 同一�?`sk-` API Key�?*两个端点**面向不同模型族：

| �?| DashScope | DashScope（兼容模式） |
|---|---|---|
| 端点 | `dashscope.aliyuncs.com/api/v1`（native�?| `dashscope.aliyuncs.com/compatible-mode/v1`（OpenAI-compatible�?|
| 协议 | DashScope 原生协议 | OpenAI 协议（同 GPT-4 / DeepSeek / Kimi 一样） |
| 内置 web 搜索（`enable_search`�?| �?支持 | �?不支�?|
| 适用模型 | Qwen-Max / Plus / Turbo / Long、Qwen-VL、Qwen3-Max、DeepSeek-V3.2 �?| **带点号版本号**的新模型族：Qwen3.5-Plus、Qwen3.6-Plus、Qwen3 VL-Plus �?|

**为什么分两个**：阿里把 dot-versioned 新模型族（`qwen3.5-*` / `qwen3.6-*` / `qwen3-vl-*`）只放在兼容模式端点上发布；�?native 协议调它们会返回 `400 InvalidParameter`。两�?provider 可以**共用同一�?sk- Key**，复制粘贴一次就好�?

**怎么�?*�?
- 想用 Qwen-Max / Plus / Turbo + 内置搜索 / DeepSeek-V3.2 �?**DashScope**
- 想用 Qwen3.5-Plus / Qwen3.6-Plus / Qwen3 视觉理解 �?**DashScope（兼容模式）**
- **两个都启�?*也可以——同一�?Key，只是模型出现在不同卡片�?

---

## 原生 Gemini

::: tip 1.4.0 新增
Gemini 不再�?OpenAI 兼容层——MateClaw 直接对接 Google �?*原生 `generateContent` API**�?
:::

很多产品�?Gemini 当成"又一�?OpenAI 兼容端点"来接，结果在系统指令、函数调用、内联图片这些地方处处碰壁。MateClaw 走的�?Gemini 自己的协议：

- **原生 chat builder** —�?正确映射 `systemInstruction`（系统指令）、`functionCall` / `functionResponse`（工具调用回合）、以及内联图�?part（多模态输入）
- **流式 SSE 解析** —�?�?Gemini 的流式响应格式逐块解析
- **JSON Schema 清洗** —�?自动剥掉 Gemini 不接受的 JSON Schema 关键字，避免工具定义被拒
- **启动探活** —�?启动时发一个轻量请求确认凭证与模型可用

配置方式：`设置 �?模型 �?添加供应商`，�?**Gemini** 供应商，�?API Key。示例模型：`gemini-2.5-flash`、`gemini-3-pro-image-preview`、`gemini-2.5-flash-image`。图像生成走原生路径，详�?[多模态创�?�?图像生成](./multimodal#图像生成-六个供应�?�?

---

## 添加一个供应商

**新装�?GLClaw 主列表是空的。这是故意的�?*

你不需要看�?16 个供应商，你需�?*一个能跑的**�?

`设置 �?模型 �?添加供应商`——按钮打开一个抽屉，里面是完整目录。本地运行时（Ollama、LM Studio、llama.cpp、MLX�?*不需�?API Key**）排在前面，云端供应商（DashScope、OpenAI、Anthropic、DeepSeek 等）在后面�?

三步�?

1. **找到要的那一行，点启�?*——这个供应商进入主列�?
2. **�?base URL（已知供应商预填�? 粘贴 API Key**——加密存储，UI 脱敏
3. **保存 �?测试连接**——系统发一个轻量请求验�?

抽屉关掉之后，主列表只显示你启用过的供应商�?*模型选择器、聊天页、Agent 编辑器——所有看得到模型的地方，都只看得到你启用过的�?*

::: tip 老用户升级（V55 迁移�?
已经在用的供应商不会被关掉。V55 把符合以下任意一种条件的供应商自动标记为启用�?
- 配过真实 API Key
- �?OAuth token
- 最�?30 天被聊天会话使用�?
- 是当前默认模型所在的供应�?

没用过、留在数据库里占位的供应商，会回到抽屉里——你下次需要时再启用�?
:::

---

## 启用 / 禁用一个供应商

主列表上每张供应商卡片都�?*启用 / 禁用**开关�?*先启用，才可�?*——这�?v1.1.0 之后整个产品契约的核心�?

- **禁用**——供应商从模型选择器、聊天页、Agent 编辑器里立刻消失�?*配置不丢**，重新启用后原样恢复
- **如果你禁用的是当前默认模型所在的供应�?*，系统会自动把默认模型切到一个还启用着的供应商上的模型——不会让下一条消息直接报�?
- **启用**——供应商重新出现在所有看得到模型的地方。从未填�?API Key 的话，会提示你去�?

这把"我有这个供应商的 Key 但今天不想用�?�?我没这个供应�?分开。临时切供应商不需要删配置�?

### ChatGPT OAuth —�?不需�?API Key

�?ChatGPT Plus �?Pro 账号？MateClaw 可以通过**浏览�?OAuth** 对接 OpenAI �?chat 端点——你按平常方式登录，你的订阅被直接使用。GPT-4o、o3、o4-mini 立刻可用�?

`设置 �?模型 �?添加供应�?�?OpenAI OAuth`。浏览器窗口弹出。Token 交换在后端完成，**凭证不离开你的机器**�?

### 设备授权（Device Authorization Grant）—�?远程 / 无头部署专用

浏览器回调式 OAuth 要求 IDP 的重定向能落�?*你的浏览�? 能访问的某个 `localhost` 端口。这事儿�?GLClaw 跑你笔记本上时没问题，一旦你把它放到服务器、容器、或任何不向客户端暴�?loopback socket 的宿主上，就立刻坏掉�?

针对这种情况，OpenAI OAuth 会自动切�?**设备授权（RFC 8628�?*——和 ChatGPT 桌面端、`gh auth login` 用的是同一个流程。不需要回调，不需要端口映射�?

�?localhost 宿主下，`设置 �?模型 �?添加供应�?�?OpenAI OAuth` 会弹出一个对话框，里面有�?

- 一个短�?*用户�?*（等宽字体，可复制）
- 一�?*验证 URL**：`auth.openai.com/codex/device`——任何设备的任何浏览器都能打开
- 一�?*实时倒计�?*，显示设备码还剩多久过期（默�?15 分钟�?

把用户码填进浏览器、授权完成，对话框会在后端轮询拿�?`COMPLETED` 的瞬间自动关闭�?

**GLClaw 怎么决定走哪个流�?*

| `GLClaw.oauth.openai.deployment-mode` | 行为 |
|---|---|
| `auto` *（默认）* | `localhost` / `127.0.0.1` / `::1` �?浏览器回调；其它 host �?设备授权 |
| `local` | 强制走浏览器回调（loopback 服务器） |
| `device_code` | 强制走设备授�?|
| `manual_paste` | 强制走旧�?复制回调 URL 粘回�?�?|

如果 `local` 模式起不�?loopback 端口（端口被占、沙箱拒绝），会自动降级�?`manual_paste`�?

**后端端点**（`/api/v1/oauth/openai/device`）：

| Method | Path | 用�?|
|---|---|---|
| `POST` | `/start` | 开一个会话，返回 `deviceAuthId` / `userCode` / `verificationUrl` / `intervalSeconds` / `expiresInSeconds` |
| `POST` | `/poll` | �?`deviceAuthId` 轮询，返�?`PENDING` / `COMPLETED` / `EXPIRED` |
| `POST` | `/cancel` | 丢弃会话（比如用户关了对话框�?|

前端�?OpenAI 返回�?`intervalSeconds`（一�?5 秒）轮询；服务端再设一个最小轮询间隔（默认 3 秒）兜底，避免被打。过期的会话�?5 分钟扫一次清掉�?

token 持久化和刷新走的是和浏览器回调流**完全相同**的代码路径，所以对话框关了之后行为没有任何差别�?

### Anthropic Claude Code OAuth

同样的套路、同样的结果：有 Claude Pro / Max / Team 订阅？走 **Claude Code 自己用的那套 OAuth 流程** 登录——不需�?`sk-ant-…` �?API Key。Claude 4.7 / 4.6 / 4.5 Haiku 通过订阅上线�?

`设置 �?模型 �?添加供应�?�?Anthropic Claude Code OAuth`。支持两种流程：

- **浏览器回�?* —�?本地安装，浏览器弹窗，点完授�?token 落到 GLClaw
- **MANUAL_PASTE** —�?远程服务器部署、浏览器到不了后端时，本地浏览器完成授权后把 token 粘回�?

通过 anti-abuse 反滥用门：注�?Claude Code 身份到系�?prompt，请求形态（UA / accept �?/ `system` 数组形式 / `mcp_` 工具名前缀）与 Claude Code 在线协议完全对齐，请求不会被拒绝�?

---

## 模型发现

提供模型列表的供应商（OpenAI、Ollama、LM Studio、OpenRouter 等）支持**模型发现**——一键让 GLClaw 拉取这个供应商下的所有模型�?

- `设置 �?模型 �?[供应商卡片] �?发现模型`
- 系统查询供应商的 `/v1/models` 端点
- 发现的模型带名字、上下文窗口、价�?
- 逐个或批量添�?

�?OpenRouter 特别有用—�?*�?200+ 免费档模型全都可�?*。挑一个免费模型零成本有一套能用的环境�?

### Ollama 启动时自动检�?

不用手动配。启动时�?

1. **Ping** `http://127.0.0.1:11434`
2. **发现**——通过 `/v1/models` 拉取已拉的模�?
3. **注册**——加�?`mate_model_config`
4. **启用**——自动启用匹配的预配置模�?
5. **标签重写**——把种子里的 `:latest` 重写为实际安装的版本（`deepseek-r1:latest` �?`deepseek-r1:7b`），不再因为 `model not found` �?404

Ollama 没跑�?*静默跳过**�?

::: tip 默认行为
- 无工具支持的模型（`deepseek-r1`、`gemma*`、`phi3/4` 等）不会被意外激活为默认——它们进入黑名单
- �?native DashScope 协议下不可用的模型在启动时自动清理；带点号版本号�?Qwen 系列改由 DashScope（兼容模式）provider 承载
- DashScope 模型发现做协议感知探测，跳过非聊天模�?
:::

**预配置的 Ollama 模型**（默认禁用，发现后自动启用）�?

| 模型 | `model_name` |
|------|-------------|
| Gemma 3 | `gemma3:latest` |
| Gemma 4 | `gemma4:latest` |
| Qwen 3 | `qwen3:latest` |
| Llama 3.1 | `llama3.1:latest` |
| DeepSeek R1 | `deepseek-r1:latest` |
| Mistral | `mistral:latest` |

配置�?

```bash
# �?ollama.com 安装 Ollama，然后：
ollama pull gemma3
ollama pull qwen3
```

重启 GLClaw。自动发现、添加、启用�?

---

## 数据�?schema

### `mate_model_provider`

| �?| 用�?|
|----|------|
| `id` | 主键 |
| `name` | 供应商标识符 |
| `display_name` | 人类可读的名�?|
| `protocol` | `dashscope` / `openai` / `ollama` / `anthropic` / `gemini` |
| `base_url` | API 基础 URL |
| `api_key` | 加密�?API Key |
| `oauth_tokens` | OAuth tokens（ChatGPT Plus/Pro�?|
| `is_local` | 本地运行时为 true |
| `enabled` | 供应商总开关——禁用后从所有模型选择器消失，配置保留（v1.1.0+�?|

### `mate_model_config`

| �?| 用�?|
|----|------|
| `id` | 主键 |
| `provider_id` | 外键�?`mate_model_provider` |
| `model_name` | 实际的模型标识符 |
| `display_name` | 人类可读的名�?|
| `temperature` | 默认温度�?.0�?.0�?|
| `max_tokens` | 最大输�?token |
| `top_p` | top-p 采样 |
| `group_name` | UI 分组�?Reasoning"�?Fast"�?Vision" 等） |
| `enabled` | 模型开�?|

### 嵌入模型

不用�?`EMBEDDING_API_KEY` 环境变量。嵌入模型就�?`mate_model_config` �?`model_type='embedding'` 的普通行。`设置 �?模型` 里和聊天模型列在一起。知识库从下拉里选它的嵌入模型�?

::: tip 1.4.0 新增（[issue #79](https://github.com/matevip/GLClaw/issues/79)�?
**任意供应商都能提供嵌入模型�?* �?`设置 �?模型` 的嵌入区域里，配一个来自任何供应商的嵌入模型——直�?*复用那家供应商的 API Key**，不再单独要 `EMBEDDING_API_KEY`。每个知识库从下拉里挑自己的嵌入模型。无密钥的本地代理用一个空操作占位 key；协议从该供应商的聊天模�?/ protocol 设置里自动解析，不用再手填�?
:::

### Anthropic prompt 缓存

系统 prompt、Agent 人格、工具定义——在 Anthropic 兼容端点上自动带 `cache_control: ephemeral`。第一次请求热身，之后每次缓存命中。Dashboard 里有 `cache_read_tokens` / `cache_write_tokens` 日维度统计�?

### 思考深�?/ `reasoning_effort`

**哪些模型会看这个参数**：`reasoning_effort` 只对 OpenAI reasoning 族（`gpt-5*` / `o1*` / `o3*` / `o4*`）有效，且只通过 OpenAI / Azure-OpenAI 两家 provider 下发。任何别�?provider（DeepSeek、Kimi、DashScope、Ollama、自托管 OpenAI-兼容网关等）收到这个参数都会报错或触发异常行为�?

**三点产品契约**�?

1. **Chat 类不带思维链的模型**，即使用户在前端 UI 选择"深度思�?= high"，系统也**不执�?* thinking——不�?UI 问题，是能力属性。模型选择器换到不支持的模型后"思考深�?选项自动灰掉�?
2. **Provider �?`generateKwargs.reasoningEffort` 配置**只对白名�?provider 有效。在 DeepSeek / Kimi / 其他 OpenAI-兼容 provider 上配它会�?*无条件丢�?*并打 WARN，不会实际下发�?
3. **Failover 切换**时会再次校验：如�?primary �?GPT-5 �?fallback �?DeepSeek，`reasoning_effort` 会在出站前被剥除，泄漏到 DeepSeek 的不会触�?400�?

**DeepSeek thinking 的正确用�?*：DeepSeek �?thinking 模式**不接�?* `reasoning_effort` 参数�?

- `deepseek-reasoner`：模型本身自�?thinking，无需任何配置�?
- `deepseek-chat` 想开�?thinking：按 DeepSeek 官方文档�?provider �?`generateKwargs.extra_body` 里加 `{"thinking": {...}}`�?*不要**�?`reasoningEffort`�?

**Kimi K2.5 thinking**：模型自�?thinking，也不接�?`reasoning_effort`�?

**多轮 tool call + thinking**：带 thinking 的模型（DeepSeek-Reasoner / GPT-5 / Kimi K2.5 / 小米 MiMo）在 ReAct 多轮 tool call 场景下，历史消息�?`reasoning_content` 会正确回传给 provider；跨用户问题边界时自动清除，同一问题内的子轮次全部保留——符�?DeepSeek �?同问题子轮必须回传、跨问题时清"契约�?

**小米 MiMo 思考模式多轮修�?*（[issue #189](https://github.com/matevip/GLClaw/issues/189)）：MiMo 思考模式的 `reasoning_content` 现在能在多轮对话里正确保留，不再在后续轮次丢失�?

---

## 分组模型选择�?

当你部署里配了一堆模型之后，聊天界面上的模型选择器按供应商和标签分组。带搜索的下拉框允许你按名字、供应商、分组过滤—�?所�?Qwen"�?所�?reasoning 模型"�?所�?7B 以下"。分组通过 `group_name` 列定义�?

�?Agent 可以按任务绑定不同模型之后，这变成了**刚需**——Plan-Execute �?reasoning 模型、Chat 用便宜快速的、图像理解用视觉模型�?

---

## 运行时切换活跃模�?

GLClaw 用一�?*活跃模型**作为全局默认。没有指定自己模型的 Agent 都用它�?

- **UI�?* `设置 �?模型 �?[模型卡片] �?设为活跃`
- **API�?* `PUT /api/v1/models/active`

**立刻生效**——不需要重启。下一条消息用新模型。进行中的对话不受影响�?

也支持按 Agent 覆盖：把某个 Agent 绑定到特定模型配置�?

::: tip 1.4.0 新增
- **按会话选模�?*（[issue #150](https://github.com/matevip/GLClaw/issues/150)）：在聊天界面里可以�?*当前这一条会�?*临时切换模型，不影响全局活跃模型和别的会话。详�?[聊天与消息](./chat)�?
- **单个坏模�?id 不再连累整个供应�?*：发�?/ 探活时遇到一个无效的模型标识符，只跳过那一个模型，供应商下其余模型照常可用�?
:::

---

## 单模型测�?

每个模型卡片都有**测试**按钮。点一下，系统发一个简�?prompt，给你看�?

- 实际响应文本
- 延迟
- Token 用量
- 错误

加了新供应商或怀�?key 过期时用它�?

---

## 多模态旁路（系统级）

::: tip 1.3.0 新增
让纯文本主模型也�?看图回答"，参�?[issue #87](https://github.com/matevip/GLClaw/issues/87)�?
:::

入口�?*设置 �?模型 �?多模态旁�?*。两个独立的卡片�?

| 卡片 | 用�?| 状�?|
|------|------|------|
| **视觉旁路模型** | 用户上传图片时调用一次，把图片转成结构化描述，再交给主对话模�?| 已上�?|
| **视频旁路模型** | 同样的思路用于视频 | 预留（v1 不接路由，仅持久化配置） |

数据库存的是 `mate_model_config.id`（不�?modelName）——同一 `model_name` 在不�?provider 下都能存在（�?`qwen-vl-max` 同时�?DashScope �?OpenAI-Compatible），存名字会撞。两�?setting key�?

- `default.vision_model`
- `default.video_model`

下拉只列**支持对应 modality 的模�?*——筛选逻辑走后�?`ModelCapabilityService.supports(...)`，未启用 / 没声�?vision 能力的模型都不会出现在选项里。每张卡片有独立�?保存"按钮，互不干扰�?

什么时候触发？运行时由 `MultimodalRouter` 决策（[源码](https://github.com/matevip/GLClaw/blob/main/GLClaw-server/src/main/java/vip/mate/llm/routing/MultimodalRouter.java)）：

- 主模型已支持图片 �?不路由（走原 native multimodal 路径�?
- 主模型不支持图片 + 配了视觉旁路 �?SIDECAR 策略，视觉模型转描述
- 主模型不支持图片 + 没配视觉旁路 �?跳过附件 + 文本提示让用户去�?

具体的用户流程、徽章、提示条详见 [聊天与消�?�?主模型不支持图片？走"多模态旁�?](./chat#主模型不支持图片走多模态旁�?�?

---

## 多模�?Failover

::: tip OpenAI 挂了 30 分钟，我�?AI 没停过一�?
上次 DashScope 限流抽风�?30 分钟里，我们的服务可用率�?100%�?

用户看到的是回答正常说完——没有红�?error，没�?服务暂时不可用，请稍后再�?�?*�?provider 在用户那一句话回答的中�?*自动切到下一个健康的 provider，断点之后的 token 直接接上�?

不是工程师讲�?自动重试"——是**用户感知不到的故障转�?*�?
:::

每个 provider 加进来都进入 `AvailableProviderPool`，启动时探活，配置变更自动重探�?

- **自动 fallback** —�?�?provider 返回 `AUTH_ERROR` / `BILLING` / `MODEL_NOT_FOUND` / `NETWORK` / `5xx` 时，运行时滚到下一�?provider，而不是把错误抛到 UI
- **每个 agent 自定义优先级** —�?�?`设置 �?模型` 的拖拽编辑器里把某个 agent 锁成 "OpenAI 优先 �?Anthropic �?DashScope"
- **池子状态实时可�?* —�?每个 provider 用绿/琥珀/红徽章标健康状�?
- **4 协议探活** —�?DashScope、OpenAI 兼容、Anthropic、Ollama 风格
- **手动重探 + 配置变更自动重探** —�?�?key 不用重启
- **出口 sanitizer** —�?provider 专属选项（如 OpenAI 推理模型�?`reasoning_effort`）在 failover 到不支持�?provider 时被剥离，泄漏的选项不会�?fallback �?400
- **UI 区分 401 与会话过�?* —�?provider 认证错误和用户会话过期现在显示不同消息、不同处�?

---

## API 配置

```bash
# 列已启用的供应商（主列表看到的）
curl http://localhost:18088/api/v1/models \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 列完整目录（含未启用项）——Add Provider 抽屉用的就是这个
curl http://localhost:18088/api/v1/models/catalog \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 启用一个供应商
curl -X POST http://localhost:18088/api/v1/models/{providerId}/enable \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 禁用一个供应商（如其下模型为当前默认会自动切换�?
curl -X POST http://localhost:18088/api/v1/models/{providerId}/disable \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 添加一个模型配�?
curl -X POST http://localhost:18088/api/v1/models \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "providerId": 1,
    "modelName": "qwen-plus",
    "displayName": "Qwen Plus",
    "temperature": 0.7,
    "maxTokens": 4096,
    "groupName": "Fast",
    "enabled": true
  }'

# 设置活跃模型
curl -X PUT http://localhost:18088/api/v1/models/active \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"providerId": "openai", "model": "gpt-4o"}'

# 发现模型
curl -X POST http://localhost:18088/api/v1/models/{providerId}/discover \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 测试连接
curl -X POST http://localhost:18088/api/v1/models/{providerId}/test-connection \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## 所有配置都�?UI

::: tip
**模型配置 100% 通过 UI 管理�?* 没有任何 `spring.ai.*` �?YAML 需要你手动改。所有供应商、所�?API Key、所有模型配置、所有切换——全部在 `设置 �?模型` 里，底层存在 `mate_model_provider` �?`mate_model_config` 数据库表�?
:::

UI 处理了你原本�?YAML 里会做的一切，外加几件 YAML 做不到的事：

- **添加供应�?*——选类型、粘 key、保存。数据库加密存储，UI 里脱敏显示�?
- **测试连接**——上线前先验证供应商�?
- **模型发现**——支�?`/v1/models` 的供应商一键拉取整个列表�?
- **单模型测�?*——发一个测�?prompt，看真实响应、延迟、token 用量�?
- **运行时切换活跃模�?*——不重启、不重载配置，下一条消息生效�?
- **�?Agent 覆盖**——把某个 Agent 绑定到特定的模型配置�?

LLM API Key **不再读取环境变量**——`DASHSCOPE_API_KEY` / `OPENAI_API_KEY` 这类设置已经没有任何效果。所有供应商、Key、模型都住在 UI 里。新装的实例启动时数据库里没有供应商，到「设�?�?模型 �?添加供应商」加你的第一家即可�?

### 参考：Qwen 模型怎么�?

如果你用 DashScope，大致阵容是这样�?

| 模型 | 上下�?| 适合 |
|------|--------|------|
| `qwen-max` | 32K | 复杂推理、分�?|
| `qwen-plus` | 32K | 通用 |
| `qwen-turbo` | 8K | 快速响�?|
| `qwen-vl-max` | 32K | 视觉 + 语言 |
| `qwen-long` | 1M | 超长文档 |

---

## 下一�?

- [配置说明](./config)——完整配置参�?
- [Agent 引擎](./agents)——Agent 怎么使用模型
- [控制台](./console)——模型管�?UI
