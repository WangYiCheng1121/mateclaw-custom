# 工具系统

**一个工具就�?Agent 能伸出去的一只手�?*

把语言模型单独放在那里，它只是一个包在文本里的模式匹配器。它不知道现在几点。它不知道你的文件里写了什么。它不能搜索网页、执行命令、看一�?PDF、把任务交给另一�?Agent、打开一个浏览器。它只能**谈论**做这些事�?

工具�?GLClaw 解决这件事的方式。每一个工具是一�?Agent 被允许调用的具体操作——读文件、搜网页、执�?shell 命令、从 PDF 抽文字、把任务委托给另一�?Agent。Agent 判断需要某个工具时，发出一�?*工具调用**，运行时执行它，结果作为**观察**回到 Agent 下一步推理里�?

**二十个内置工�?*开箱即用。无限多个可以通过 MCP 服务、自定义技能脚本、或者你自己写的 `@Tool` Spring bean 加进来�?

---

## 一次工具调用实际发生了什�?

```
Agent 判断需要一个工�?
        �?
        �?
  发出工具调用：{"name": "WebSearchTool", "args": {"query": "..."}}
        �?
        �?
  ┌─────────────────────�?
  �?   工具注册�?      �? �?按名字查工具
  └─────────────────────�?
        �?
        �?
  ┌─────────────────────�?
  �?  Tool Guard        �? �?基于规则的检查：allow / deny / 审批
  └─────────────────────�?
        �?
   ┌────┴────�?
   �?        �?
   �?        �?
 允许     审批挂起 �?用户决定 �?允许 / 拒绝
   �?
   �?
  ┌─────────────────────�?
  �? 执行（带超时�?    �? �?异步，按工具单独设超�?
  └─────────────────────�?
        �?
        �?
  结果 �?观察 �?Agent 下一步推�?
```

Tool Guard 是守门员。超时是**每个工具独立**的（这样一个慢工具冻不了整个回合）。在一�?Action 阶段里，Agent 同时调多个独立工具时它们可以**并发执行**�?

这整套对 Agent �?prompt �?*不可�?*的�?

---

## 工具注册的三条路

**1. 内置工具�?* GLClaw 出厂带的二十个工具，启动时自动注册到工具表里�?

**2. MCP 服务�?* �?Model Context Protocol 的外部进程动态暴露工具。GLClaw 通过 `tools/list` 发现它们。见 [MCP 协议](./mcp)�?

> **�?Agent �?MCP 工具范围�?.4.0+�?117�?*：当一�?Agent **没有勾选任何具体的 MCP 工具�?*时，已启用的 MCP 工具�?*自动并入**它的工具集；一旦它勾选了某些具体 MCP 工具，就**只限定在这个集合**内。只绑技�?/ 内置工具�?Agent 仍保留对全部 MCP 工具的访问�?

**3. 技能脚本�?* 技能包可以带可执行脚本，运行时被包装成工具。见 [技能系统](./skills)�?

工具发现�?*黑名单式**的——默认所有可发现的工具都会被注册，需要排除哪个就显式排除。这样新加进来的工具不会因为白名单遗漏被默默忽略�?

---

## 渐进式工具披露（1.4.0+�?

工具一多，系统 prompt 就会被几十个完整的工�?schema 撑大——哪怕这次任务只用得上一两个�?*渐进式披�?*把工具分成两层，�?prompt 跟着**任务**走，而不是跟着**工具总数**走�?

| 层级 | 系统 prompt 里怎么呈现 | 能不能直接调 |
|------|------------------------|--------------|
| **核心层（CORE�?* | 始终完整广播，带完整 schema | 开箱即�?|
| **扩展层（EXTENSION�?* | 只列一份压缩目录——名�?+ 来源 + 一行说明，完整 schema 隐藏 | 先用 `enable_tool` 激活才能调 |

**默认分层**：生成类工具（`image_generate`、`music_generate`、`video_generate`、`model3d_generate`）和 `browser_use` 默认放进**扩展�?*；其余全部是**核心�?*�?

- **页面控制**——Tools 页面分「核�?/ 扩展」两栏，内置工具和渠道工具每行有一个层级开关；MCP / ACP 工具的层级是锁定的�?
- **持久�?*——层级存�?`mate_tool.disclosure_tier` �?`mate_mcp_server.disclosure_tier`�?
- **配置**——`GLClaw.tools.disclosure.mode`，默�?`progressive`；设�?`legacy` 则恢�?全部广播"的老行为�?

**为什么这么做**：不让上下文被白白撑爆——系�?prompt 的体积应该跟当前任务的需要成正比，而不是跟你装了多少工具成正比�?

---

## 二十个内置工�?

| 工具 | 作用 | 危险 |
|------|------|------|
| `DateTimeTool` | 获取任意时区的当前日期时�?| �?|
| `WebSearchTool` | 通过搜索引擎链搜索（Serper / Tavily / DuckDuckGo / SearXNG�?| �?|
| `ReadFileTool` | 读文�?| �?|
| `WriteFileTool` | 写内容到文件 | ⚠️ |
| `EditFileTool` | 查找替换编辑 | ⚠️ |
| `ShellExecuteTool` | 执行 shell 命令 | ⚠️ |
| `FileTypeDetectorTool` | 检�?MIME 类型和编�?| �?|
| `DocumentExtractTool` | �?PDF / DOCX / XLSX 抽文�?| �?|
| `WorkspaceMemoryTool` | 读写 Agent 的工作空间记�?| �?|
| `SkillFileTool` | 读取和管�?`SKILL.md` 文件 | �?|
| `SkillScriptTool` | 执行技能脚�?| ⚠️ |
| `SkillManageTool` | 创建 / 编辑 / 删除技能包 | ⚠️ |
| `BrowserUseTool` | 驱动无头浏览�?| ⚠️ |
| `DelegateAgentTool` | 把任务委托给另一�?Agent（支持并行） | �?|
| `GLClawDocTool` | 读取内置项目文档 | �?|
| `ImageGenerateTool` | 文生�?/ **图生图（1.3.0+�?* | �?|
| `VideoGenerateTool` | 文生视频 / 图生视频 | �?|
| `DocxRenderTool` | **1.3.0+** Markdown �?.docx（Word 文档�?| �?|
| `XlsxRenderTool` | **1.3.0+** Markdown 表格 �?.xlsx（Excel�?| �?|
| `PptxRenderTool` | **1.3.0+** Markdown（Marp 风格 `---` 分页�?�?.pptx | �?|
| `PdfRenderTool` | **1.3.0+** Markdown �?出版�?PDF（中文字体内嵌） | �?|
| `CronJobTool` | 创建和管理定时任�?| ⚠️ |
| `DatasourceTool` | 管理外部数据源连�?| ⚠️ |
| `SqlQueryTool` | 对已连接数据源执�?SQL 查询 | ⚠️ |
| `send_file` | **1.4.0+** 把服务器上已有文件作为原�?IM 附件投递（#199�?| �?|
| `enable_tool` | **1.4.0+** 在本次会话里激活一个扩展层工具 | �?|
| `load_skill` | **1.4.0+** 按需加载某个技能的 `SKILL.md` | �?|

此外还有 [多模态创作](./multimodal) 的音乐生成工�?`MusicGenerateTool`。以�?[LLM Wiki](./wiki) �?14 �?Wiki 工具：`wiki_read_page`、`wiki_read_many`、`wiki_list_pages`、`wiki_search_pages`、`wiki_semantic_search`、`wiki_compile_page`、`wiki_trace_source`、`wiki_create_page`、`wiki_delete_page`、`wiki_archive_page`、`wiki_unarchive_page`、`wiki_related_pages`、`wiki_explain_relation`、`wiki_enrich_page`�?

### DateTimeTool

返回给定时区的当前日期时间。没有意外�?

```
输入：{"timezone": "America/New_York"}
输出�?2026-04-11T14:30:22"
```

### WebSearchTool

通过**供应商链**搜索——DuckDuckGo �?SearXNG 作为�?Key �?fallback，Serper �?Tavily 在你�?Key 时启用。在 `设置 �?系统设置 �?搜索服务` 里配置，**改完即生效，不需要重�?*�?

```
输入：{"query": "Spring AI Alibaba 最新版�?, "freshness": "month", "count": 5}
输出�?Spring AI Alibaba 1.1 发布..."
```

特性：

- **供应商链**——主 provider 失败时链�?fallback 到下一�?
- **高级参数**——`freshness`、`language`、`count`
- **结果缓存**——近期查询被缓存
- **安全包装**——结果返回前先净�?
- **原生搜索 + 工具搜索共存**——自带搜索的模型用原生搜索，工具搜索作为 fallback

### ShellExecuteTool

跨平�?shell 执行。Linux/macOS �?`/bin/sh -c`，Windows �?`cmd.exe /D /S /C`�?*每一次调用都�?Tool Guard�?*

安全设计�?

- **超时**——默�?60 秒，硬上�?300 �?
- **输出上限**——stdout �?stderr 各自上限 10,000 字节
- **文件支撑输出**——写到临时文件，不是管道
- **结构化结�?*——`{exitCode, stdout, stderr, timedOut}`
- **危险模式检�?*——`find -delete`、`rm -rf /`、管�?bash 下载触发更高级别审批

### ReadFileTool / WriteFileTool / EditFileTool

读是安全的。写和编辑都�?Tool Guard�?

### DocumentExtractTool

PDF、DOCX、XLSX 之类变成纯文本。扫描件在可用的地方降级�?OCR�?

### Office 文档生成�?.3.0+�?

四个新工具，�?Markdown 直接渲染为可下载�?Office 文件—�?*�?fork 子进程，不依�?npm**。生成的字节缓存在内存里，返回一个一次性下载链接：

| 工具 | 适用 | 关键能力 |
|---|---|---|
| `DocxRenderTool.renderDocx` | 报告 / 备忘�?/ 合同 / 简�?| 标题�? ## ###�?/ 加粗�?*text**�?/ 列表 / 表格 / 图片（PNG/JPG/GIF/BMP/SVG �?PNG�?|
| `DocxRenderTool.renderDocxFromFile` | 同上，但 markdown 在工作区文件�?| 用于 LLM 不想把已经写好的大段 markdown �?tool 参数再发一�?|
| `XlsxRenderTool.renderXlsx` | 财务�?/ 数据导出 / 模板 | Markdown 表格语法 �?�?sheet（用 `## SheetName` 切分�?|
| `PptxRenderTool.renderPptx` | 演讲�?/ 项目方案 / 简�?| Marp 风格 `---` 分页；`16:9`（默认）/ `4:3` 比例 |
| `PptxRenderTool.renderPptxFromFile` | 同上，markdown 在文件里 | 内容大于 5KB 时优�?|
| `PdfRenderTool.renderPdf` | 出版级文�?/ 周报 / 制式文件 | 1in 边距 / 智能分页 / 页码 / 封面�?/ 中英混排（CJK 字体内嵌�?|

::: tip 跟原�?`skills/docx` 的关�?
现成�?`skills/docx` skill **保留**——它擅长的是**编辑已有 .docx**（tracked changes / 复杂 XML 操作）和首次安装时跑 `npm install docx`。新四个工具专门处理"从零创建"路径�?*没有 npm 启动延迟**。Agent 首选这四个 RenderTool；要修已�?.docx 才转�?skill�?
:::

### ImageGenerateTool —�?1.3.0 起支持图像编�?

v1.2.0 时这个工具只�?文生�?。v1.3.0 起新�?`image` / `images` 两个参数，支�?*多图输入的图像编�?*。详�?[多模态创作](./multimodal#image-edit)�?

### WorkspaceMemoryTool

�?Agent 读、写、编辑自己的工作空间记忆文件——`workspace/{agentId}/` 下面的任�?`.md`。见 [记忆系统](./memory)�?

### BrowserUseTool

驱动一个无头浏览器。每次调用都�?Tool Guard�?

### DelegateAgentTool —�?Agent 之间委托

一�?Agent 可以把子任务交给另一�?Agent�?

- **`delegateToAgent(agentName, task)`**——按名字调用指定 Agent，在隔离会话里执行任�?
- **`listAvailableAgents()`**——列出所有可�?Agent

```
用户：搜一�?Spring AI 的新闻，�?Writer 总结一�?
Agent A：[�?WebSearchTool]
        [�?delegateToAgent(agentName="Writer", task="总结�?..")]
        [收到 Writer 的回复]
        合并后回复用�?
```

安全�?

- **递归上限**——委托最多嵌�?3 �?
- **隔离会话**——被委托�?Agent 跑在自己的会话里
- **结果截断**——委托结果上�?4000 字符

### GLClawDocTool

读取内置�?GLClaw 项目文档。让 Agent 回答"GLClaw �?X 是怎么工作�?这种问题时，**去查真文�?*而不是猜�?

### enable_tool —�?激活扩展层工具�?.4.0+�?

`enable_tool(toolName)` 把一�?*扩展�?*工具激活，使它�?*本次会话剩余的回�?*里完整可调�?

- **会校�?*——只有在 Agent 的有效工具集里的工具才能激活�?
- **下一回合生效**——激活在同一�?ReAct 循环�?*下一次推�?*时生效（Agent 先看到完�?schema，再发真正的调用）�?
- **会话级，不持久化**——激活只对当前会话有效，不写库；新会话回到默认分层�?

### load_skill —�?按需加载技能（1.4.0+�?

`load_skill(skillName, filePath?)` 在需要时才把某个技能的 `SKILL.md` 加载进来——不�?`filePath` 读主文件，传了就读技能包内的子文件�?

- **走消息历史注�?*——加载的内容是注入到**消息历史**里，而不是系�?prompt，这�?**prompt 缓存保持稳定**（系�?prompt 不变，缓存不失效）�?
- **后续回合保持**——已加载的技能在之后的回合里**钉住**，不用反复加载�?
- **配置**——`GLClaw.skill.disclosure.load-skill-tool.enabled`，默认开启�?

详见 [技能系统](./skills)�?

### send_file —�?把已有文件作为原生附件投递（1.4.0+�?199�?

`send_file(filePath, fileName?)` 读取服务器上**一个已经存在的文件**，把它作�?*原生 IM 附件**投递——不是一条文本下载链接�?

- **进生成文件缓�?*——文件被放进生成文件缓存，渠道适配器（飞书 / 钉钉 / Telegram�?*自动识别并投�?*�?
- **任意常见文件类型**，上�?**20 MB**�?
- **�?`ReadFileTool` 的区�?*——`ReadFileTool` 把文�?*抽成文本**喂给 Agent 推理；`send_file` 把文�?*原样发给用户**�?

### ReadFileTool —�?超长行分页（1.4.0+�?190�?

针对单行特别长的文件，`ReadFileTool` 新增可选的 `startColumn`（在 `startLine` 内的 1-based 字符偏移），用来**从一行的中间续读**它的尾部�?

- 截断�?*始终返回** `nextStartLine`�?
- 当这一行还有剩余没读完时，**额外返回** `nextStartColumn`�?

把两者回填到下一次调用，就能把一个巨大的单行文件分段读完�?

---

## Tool Guard —�?权限�?

Tool Guard �?GLClaw 不让强工具干蠢事的机制。它�?*基于规则�?*，不是一个扁平的"危险 / 不危�?清单。每条规则说�?对这个工具，带这些参数，在这个上下文里，�?X*——X �?`allow`、`deny`、或 `require_approval`�?

核心几张表：

- **`mate_tool_guard_rule`**——单条规�?
- **`mate_tool_guard_config`**——全局配置
- **`mate_tool_guard_audit_log`**——每一次受守护的调用一条记�?

示例规则�?`ShellExecuteTool`，命令以 `ls`、`cat`、`grep`、`find` 开头时允许。其他情况要求审批�?

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

或者在 `设置 �?安全与审批` 里可视化管理。规则判定为需要审批时，运行时会在 `mate_tool_approval` 持久化一条记录并�?Agent 回合挂起。完整机制在 [安全与审批](./security)�?

### 声明�?Hook 系统

Tool Guard 规则是一种更通用机制的特例—�?*声明�?Hook 系统**�? 个生命周期钩子覆盖工具调用和 LLM 调用的全部关键时刻：

| Hook | 触发时机 | 典型用�?|
|------|----------|----------|
| `before_tool` | 工具执行�?| 参数脱敏、注入上下文、额外校�?|
| `after_tool` | 工具执行�?| 结果过滤、审计记�?|
| `before_llm` | LLM 调用�?| prompt 增强、缓存命中检�?|
| `after_llm` | LLM 返回�?| 输出过滤、token 统计 |
| `on_error` | 错误发生�?| 告警、降级策�?|

Hook 在进程内执行，可以改参数、改结果、脱敏、加审计日志。你可以�?Hook �?Tool Guard 之外的事——比如在每次 LLM 调用前注入安全策略，或者在工具返回后自动脱敏敏感字段�?

---

## 执行：并发、隔离、有�?

- **并发执行**——一个回合里独立的工具调用并发跑。Guard 检查是顺序的，执行在安全的地方并发�?
- **每工具超�?*——每个工具有自己的超时。默认：快工�?30s，shell/browser 60s，生成类 300s�?
- **段隔�?*——回合中间需要审批时，段在审批边界处分裂�?
- **观察截断**——结果超长会被自动截断�?
- **错误隔离**——单个工具失败不会中止整个回合�?

---

## API 管理

```bash
# 列出所有工�?
curl http://localhost:18088/api/v1/tools \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# 启用 / 禁用
curl -X PUT http://localhost:18088/api/v1/tools/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"enabled": false}'

# 直接测试一个工�?
curl -X POST http://localhost:18088/api/v1/tools/WebSearchTool/test \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"query": "Spring AI"}'
```

每个依赖 provider 的工具在 Tools 页面都有测试按钮�?

---

## 自定义工�?

### 路线 1：`@Tool` Spring bean

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
- 每个 `@Tool` 方法变成一个可调用的工�?
- 每个参数上用 `@ToolParam`——这�?LLM 读的描述
- 返回值就�?Agent 看到的观�?
- **工具做任何危险的事情时，为它加一�?Tool Guard 规则**

重启后工具就活了�?

### 路线 2：技能脚�?

不想�?Java？把行为打包成一个技能包，带 `SKILL.md` 和脚本。见 [技能系统](./skills)�?

### 路线 3：MCP 服务

能力已经�?MCP 服务形式存在？加个服务配置就行。见 [MCP 协议](./mcp)�?

---

## 下一�?

- [技能系统](./skills)——建立在工具之上的更高层能力
- [MCP 协议](./mcp)——外部工具提供�?
- [安全与审批](./security)——Tool Guard 规则、审批流程、审计日�?
- [多模态创作](./multimodal)——生成类工具
