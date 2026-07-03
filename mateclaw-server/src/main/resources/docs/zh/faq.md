# 常见问题（FAQ�?

常见问题 + 真答案。你的问题不在这里就看对应的功能页，或者去 [GitHub issue](https://github.com/matevip/GLClaw/issues) 开一个�?

---

## 安装和搭�?

### 需要什�?Java 版本�?

**Java 17 或更高�?* GLClaw 用了 Java 17 引入的特性。用 `java -version` 验证�?

用桌面端的话�?*完全不需要装 Java**——安装器自带 JRE 21�?

### 要一个云 API key 才能开始？

不用。三条无 key 的路径：

- **Ollama**——本�?GPU 推理；GLClaw 启动时在 `localhost:11434` 自动探测
- **ChatGPT OAuth**——有 ChatGPT Plus �?Pro 订阅的话走浏览器 OAuth 流程—�?*不需�?API Key**
- **OpenRouter 免费�?*—�?00+ 免费模型，一�?key 就能访问

**启动 GLClaw 也不需要把任何 API Key 设成环境变量�?* 所有供应商配置都在启动后通过 UI �?`设置 �?模型` 来做�?

### 怎么�?DashScope API Key�?

1. 去[阿里�?DashScope 控制台](https://dashscope.console.aliyun.com/)
2. 注册或登�?
3. 创建一�?API Key
4. �?GLClaw 里进 `设置 �?模型 �?DashScope` 粘贴

### 后端起不来—�?8088 端口被占�?

换端口：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=19090"
```

**桌面端会动态挑一个空闲端�?*，所以在那里看不到这个错误�?

### 启动�?H2 数据库锁错误

```bash
rm -f data/GLClaw.mv.db.lock
```

或者清空数据目录重新开始：

```bash
rm -rf data/
```

---

## 认证

### 默认凭证是什么？

用户�?`admin`，密�?`admin123`�?*任何真实部署都要立刻改�?*

### 我的 JWT token 老过�?

GLClaw 实现�?*滑动窗口续签**——token 剩余 25% 时服务器在响应头 `X-New-Token` 里发一个新 token。前端自动处理�?

手动�?API（curl、Postman）的话，�?`X-New-Token` header 用新值做后续请求�?

### 怎么�?admin 密码�?

UI �?`设置 �?安全` 最简单。或者直接改数据库（BCrypt 编码）：

```sql
UPDATE mate_user SET password = '$2a$10$...' WHERE username = 'admin';
```

---

## 模型

### 怎么配置模型�?

**全部通过 UI�?* `设置 �?模型 �?添加供应商`。选供应商、粘 API Key（或�?ChatGPT Plus OAuth、或 Ollama 跳过）、保存、测试�?*模型配置 100% 通过 UI 管理**——没�?`spring.ai.*` YAML 需要改�?

LLM API Key 不读环境变量——`DASHSCOPE_API_KEY` 之类的设置不会生效。容器零 Key 就能启动，登录后加供应商即可�?

### 怎么�?GLClaw 里用 GPT-4�?

`设置 �?模型 �?添加供应商`。要么粘你的 OpenAI API Key，要么如果你�?ChatGPT Plus/Pro 就用 **OpenAI OAuth**——浏览器窗口弹出让你登录。保存后从模型选择器挑 `gpt-4o`（或任何模型）�?

### Ollama 模型很慢

本地模型性能取决于硬件：

- 内存小的机器用小模型�?B 而不�?14B�?
- 确保 Ollama �?GPU 访问（`ollama ps` 应该显示 GPU�?
- 有条件调�?Ollama 内存上限
- `qwen2.5:7b` �?`qwen3:latest` 是好平衡

### 能同时用多个供应商吗�?

可以。配多个供应商，把不同的模型配置分给不同�?Agent。每�?Agent 用自己的模型——或者继承全局默认�?

### 怎么给有�?Agent 挂便宜模型、给另一些挂推理模型�?

- **全局活跃模型**设成便宜通用的（`qwen-plus`、`gpt-4o-mini`�?
- �?Agent 覆盖：推理重�?Agent 单独�?`o3` �?`qwen-max`
- 聊天窗口里的分组模型选择器也能按会话�?

---

## 工具和搜�?

### 怎么切换搜索 provider�?

`设置 �?系统 �?搜索服务`。从 Serper、Tavily、DuckDuckGo、SearXNG 里挑。开�?**fallback**。立刻生效�?

�?key 选项（DuckDuckGo、SearXNG）让你不需�?API Key 也能搜索�?

### 怎么加一个自定义工具�?

写一�?Spring `@Component`�?

```java
@Component
public class MyCustomTool {

    @Tool(description = "获取天气信息")
    public String getWeather(@ToolParam(description = "城市�?) String city) {
        return "晴，25°C";
    }
}
```

启动时自动注册。见 [工具系统](./tools)�?

**工具做任何危险的事情时，给它加一�?Tool Guard 规则�?*

### WebSearchTool 返回空结�?

�?`设置 �?系统 �?搜索服务` 里配一个搜索供应商。无 key 选项（DuckDuckGo、SearXNG）不需�?API Key�?

### Tool Guard 一直在挡我的工具调�?

**这是刻意设计�?*——危险工具要审批。三种放宽方式：

1. **给具体的命令模式加一�?allow 规则**（`设置 �?安全与审�?�?Tool Guard 规则`）。例子：`ShellExecuteTool`，参数模�?`^(ls|cat|grep|find)\s` �?`allow`�?
2. **把默认策略调�?`allow`**（`application.yml`）：
   ```yaml
   GLClaw:
     tool:
       guard:
         default-policy: allow   # 生产不推�?
   ```
3. **完全关掉 Tool Guard**�?*只开�?*）：
   ```yaml
   GLClaw:
     tool:
       guard:
         enabled: false
   ```

**生产安全�?* 保持 `default-policy: require_approval`，为你信任的具体模式加有针对性的 allow 规则�?

### 怎么�?MCP 服务�?

UI 里用 `工具 �?MCP 服务`。三种传输模式：stdio、streamable_http、sse。配置变更立刻生效。见 [MCP 协议](./mcp)�?

---

## LLM Wiki

### Wiki 和记忆有什么区别？

**Wiki 是刻意的。记忆是被动的�?*

- **Wiki**——你扔文档进去，系统消化成结构化页面，Agent 读这些页面�?*你建�?*�?*你编辑的**�?*你审核的**�?
- **记忆**——作为对话副产品自动构建。Agent 提取看起来值得记住的东西，每夜整合模式�?

**源材料可查询**�?Wiki（产品规格、设计文档、过去决策）�?*累积的上下文**用记忆（偏好、在做什么）�?

### Agent 有知识库为什么还在瞎猜？

因为你没�?Agent 绑到 KB 上。`Agents �?[某个 Agent] �?知识库`——在那里绑�?*Agent 没显式绑定之前，wiki 工具不会被注入�?*

### 消化很慢

�?`application.yml` 里的 `mate.wiki.digestion-concurrency`。默�?2——LLM 额度允许就调�?4 �?8�?

---

## 记忆

### 记忆不工�?

1. **确认自动提取开着**——检�?`mate.memory.auto-summarize-enabled`
2. **确认对话达到阈�?*——`min-messages-for-summarize`（默�?4）、`min-user-message-length`（默�?10�?
3. **检查冷�?*——同一�?Agent �?`cooldown-minutes`（默�?5 分钟）内不能触发第二�?
4. **看日�?*——`vip.mate.memory` �?DEBUG 级别显示每一次尝�?

### 记忆整合任务没跑

整合�?`mate_cron_job` 里的种子数据驱动，每�?Agent 每天凌晨 2 点。检查：

- `enabled` 列是 `1` 吗？
- 种子 cron 任务在吗？（`SELECT * FROM mate_cron_job WHERE task_type = 'memory_emergence'`�?

### 我不喜欢 Agent 记住的关于我的东�?

直接�?Agent 工作空间视图里编�?`PROFILE.md` �?`MEMORY.md`�?*锁定**你编辑过的页面。见 [记忆系统](./memory)�?

---

## 审批

### 我批准了一个工具调用但 Agent 没恢�?

1. `AWAITING_APPROVAL` 还是 true 吗？（`GET /api/v1/agents/{id}`�?
2. 审批真的持久化了吗？（`GET /api/v1/approvals/{id}`�?
3. Agent 日志�?replay 尝试附近有错误吗�?
4. Replay 失败的话，Agent 应该在聊天里暴露一个错�?

### 我想批量批准这个 Agent 未来的工具调�?

你想要的�?*一�?allow 规则**，不是一次性全批准。`设置 �?安全与审�?�?Tool Guard 规则 �?添加规则`�?

### Pending 审批能放多久�?

默认 10 分钟，之后过期变�?`rejected`。用 `GLClaw.tool.guard.approval-timeout-seconds` 配置�?

---

## Agent

### Agent 卡在 RUNNING 状�?

常见原因�?

1. **工具调用超时**——某个工具在等挂住的外部服务
2. **超过迭代上限**——`MAX_ITERATIONS_REACHED` 处理器强制给尽力而为的答�?
3. **等审�?*——Tool Guard 暂停了执�?
4. **看日�?*�?
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.vip.mate.agent=DEBUG"
   ```

### 怎么判断我的 Agent 在用对的工具�?

展开聊天界面�?*思考面�?*。每次工具调用、参数、结果都看得见。Agent 在调错工具的话，**收紧 system prompt** 引导它�?

---

## 渠道

### 钉钉 / 飞书 webhook 收不到消�?

1. 服务器不是公网可�?
2. 大部分平台要�?HTTPS
3. 验证 token 错了
4. Bot 没被加到群里或没有消息权�?

**更简单：** �?**stream / 长连�?/ WebSocket 模式**而不�?webhook。钉�?Stream、飞�?WebSocket、Telegram Long-Polling、Discord Gateway、Slack Socket mode—�?*都不需要公�?IP**�?

### 能同时用多个渠道吗？

可以。每个渠道独立、绑一�?Agent。可以同时跑 web 控制台、钉钉、Telegram，绑不同�?Agent（或同一个——你说了算）�?

### Telegram / Discord 访问不到 API（国内网络）

在渠道配置里�?`http_proxy`�?

```json
{
  "bot_token": "...",
  "http_proxy": "http://127.0.0.1:7890"
}
```

---

## 数据备份

### 怎么备份数据�?

**H2（开�?/ 桌面）：** 停服务，拷贝 `./data/GLClaw.mv.db`�?

```bash
cp ./data/GLClaw.mv.db ./backup/GLClaw-$(date +%Y%m%d).mv.db
```

**MySQL（生产）�?*

```bash
mysqldump -u root -p GLClaw > GLClaw-backup-$(date +%Y%m%d).sql
```

**Docker�?*

```bash
docker exec GLClaw-mysql mysqldump -u root -p${MYSQL_ROOT_PASSWORD} GLClaw > backup.sql
```

**桌面�?*数据在每个用户目录下�?

- macOS：`~/Library/Application Support/GLClaw/`
- Windows：`%APPDATA%/GLClaw/`
- Linux：`~/.local/share/GLClaw/`

---

## 桌面应用

### 桌面 app 启动不了

安装器自�?JRE 21。看日志�?

- macOS：`~/Library/Logs/GLClaw/`
- Windows：`%APPDATA%/GLClaw/logs/`
- Linux：`~/.local/share/GLClaw/logs/`

从终端启动。Windows 右键 �?解除锁定。macOS "系统设置 �?隐私与安全�?允许未签名应用�?

### 怎么更新桌面 app�?

**自动更新**通过 electron-updater。启动时检�?GitHub Releases 并弹提示。也可以手动�?[Releases](https://github.com/matevip/GLClaw/releases) 下载�?

---

## Docker

### Docker 容器起不�?

```bash
docker compose logs GLClaw-server
docker compose logs GLClaw-mysql
```

常见�?

- MySQL 还没就绪
- 端口冲突�?8080�?306�?
- �?`.env`——从 `.env.example` 拷一�?

### 怎么�?Docker 里访问数据库�?

```bash
docker exec -it GLClaw-mysql mysql -u root -p GLClaw
```

---

## 调试

### 怎么开 DEBUG 日志�?

```yaml
logging:
  level:
    vip.mate: DEBUG
    vip.mate.agent: DEBUG
    vip.mate.agent.graph: DEBUG
    org.springframework.ai: DEBUG
```

或：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.vip.mate=DEBUG"
```

### 怎么访问 H2 console�?

1. 访问 `http://localhost:18088/h2-console`
2. JDBC URL：`jdbc:h2:file:./data/GLClaw`
3. 用户名：`sa`
4. 密码：（空）

**生产环境关掉它�?*

### 怎么观察 SSE 流式事件�?

浏览�?DevTools �?Network �?筛�?`EventStream`。或�?

```bash
curl -N -H "Authorization: Bearer <token>" \
  "http://localhost:18088/api/v1/chat/1/stream?conversationId=1"
```

---

## 前端

### 构建后前端显示空白页

```bash
cd GLClaw-ui
pnpm build
ls ../GLClaw-server/src/main/resources/static/
# 应该包含 index.html 和资源文�?
```

### 深色模式不持久化

存在 `localStorage`。清了浏览器数据会丢�?

### UI 感觉卡顿

- 把日志调�?INFO
- 检�?`java -Xmx` 设置
- 在老会话上�?*清空消息**

---

## 下一�?

- [快速开始](./quickstart)——搭�?walkthrough
- [配置说明](./config)——完整配置参�?
- [贡献指南](./contributing)——怎么�?bug 和提功能请求
- [GitHub Issues](https://github.com/matevip/GLClaw/issues)——文档没答案的时候去这里
