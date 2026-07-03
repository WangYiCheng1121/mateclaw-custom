# 企业微信深度优化

**让一个真正能被企业内部群里几十号人用起来�?bot，远不止"接通就�?�?*

[多渠道接�?�?企业微信](./channels#企业微信) 那一节是�?bot 跑起来；这一篇是�?bot **跑稳**——所�?GLClaw 在企业微信适配层做过的非显然优化、踩过的平台边角，以及为什么这么处理�?

阅读对象�?

- 已经把企业微信渠道连通、想理解"为什么我的群聊体验是这样"的运�?/ 一�?
- 想加新功能但需要先知道平台限制的开发�?
- 想把 bot 推给真实业务团队前做技术评估的负责�?

---

## 平台一句话总结

**企业微信 AI Bot 是个"看起来像聊天 SDK，本质是个事件回�?的平台�?*

它给你三种能力：

1. **接收事件** —�?用户在群�?@ bot，平台通过长连接（WebSocket）或 webhook 把消息推过来
2. **回复**（同一会话内）—�?�?`aibot_respond_msg` 把答�?�?到对应的 frame �?
3. **主动�?*（不限于回复）—�?�?`aibot_send_msg` �?*仅限单聊**

**最关键的隐藏规�?*：第 2�? 条在群聊里是不一样的，单聊里也不一样。下面的所有优化都围绕这个矩阵展开�?

---

## 群聊多用户协�?

### 平台默认行为

群聊�?A、B、C 三个人都�?@ bot，平台会把每个人的消息当一条独�?frame 推过来，但都打到**同一�?chatId** 上�?

如果你直接按 chatId 分会话（这是最自然的做法），后果是�?

- 持久化的对话历史里全�?`user: ...` 没有发送人前缀，模型读历史看到的是一锅粥
- 防抖窗口�?00ms / 2.5s 自适应）会�?A �?B 的连发消息合并成一�?
- A �?我想�?X"，B 接着�?我想�?Y"，bot 看到的是"用户问了 X �?Y 两个不相关的�?

### GLClaw 的处�?

**两层修复**�?

**1. 防抖�?sender 切边界�?* 同一会话内连续两条消息进来时，先�?senderId�?

- 同一个人 �?合并（典型场景：粘贴长文�?IM 客户端切片）
- 不同�?�?立即 flush 已有 pending，给新发送人开新窗�?

代码层面�?[`ChannelMessageRouter.isSameSender`](https://github.com/anthropics/GLClaw/blob/main/GLClaw-server/src/main/java/vip/mate/channel/ChannelMessageRouter.java)。null 防御：任一 senderId 缺失都不合并，宁可多 flush 一次也不要错串归属�?

**2. 持久�?+ Prompt 都带 `[@sender]` 前缀�?* 群聊（`chatId != null`）的每条 user 消息在落库时和送给 LLM 之前都会�?`applyGroupTag(message, content)` 包一层：

```
[@XuZhanFu] @迈特云的机器�?我想�?X
[@xuzf] @迈特云的机器�?我想�?Y
```

这样�?

- �?30 条历史消息也能让模型知道是谁说的
- 持久化的对话时间线读起来�?`[@A] ...; [@B] ...; [@A] ...`，模型能正确处理跟问、引用回复、互相纠�?
- 单聊（`chatId == null`）零开销，行为不�?

senderName 优先�?senderId（友好），都没有时返�?null（避�?`[@null]` 这种垃圾标签）�?

### 你能观察到什�?

```
[wecom] Sender boundary in conversation wecom:{chatId}: flushing pending from sender=A, accepting new sender=B
```

DB �?`mate_message.content` 列直接看 `[@xxx]` 前缀�?

---

## 上传约束矩阵

企业微信平台�?bot 上传的媒体有**硬性大小限�?*，超限的请求�?**chunk-finish 阶段**被拒（已经传完所有字节才报错），用户体验�?传了三分钟然后什么都没发出来"�?

### 限制

| 类型 | 大小上限 | 格式要求 |
|------|---------|---------|
| 文件 | **20 MB** | 任意 |
| 图片 | **10 MB** | 任意常见格式 |
| 视频 | **10 MB** | 任意常见格式 |
| 语音 | **2 MB** | **必须 AMR**（其他格式平台拒收） |
| 全局 | **20 MB** | 兜底硬上�?|

### GLClaw 的处�?

**客户端预检**，避免无效上传。`applyWeComUploadLimits(fileSize, mediaType, contentType)` 在上传前判定结果�?

- 文件 > 20 MB �?拒绝，告诉用�?超过 20MB 上限"
- 图片 > 10 MB �?降级为文件上传（用户在群里能看到附件，只是不再是缩略图）
- 视频 > 10 MB �?降级为文件上�?
- 语音 > 2 MB **�?* mime 不是 `audio/amr` �?降级为文件上�?
- 文件 + 任何类型 > 20 MB �?直接拒绝（绝对硬上限�?

降级时附带一段说明文字（"图片超过 10MB，已转为文件附件发�?），用户立刻知道发生了什么，不会以为 bot 抽风�?

### 智能识别没有 filename 的文�?

WeCom 群里转发的文件经�?*没有 filename 字段**。落地存�?`file.bin` 的话，下游所有按扩展�?dispatch 的工具（PDF 阅读、DOCX 解析等）会全部失效�?

修复：通过 magic-byte 嗅探还原扩展名：

- `%PDF` �?`.pdf`
- `PK\x03\x04` �?ZIP 容器；进一�?peek 内部条目区分 `.docx` / `.xlsx` / `.pptx` / `.odt` / `.epub` / `.jar`
- 其他常见格式（PNG / JPEG / MP4 / MP3 / WAV）都能正确识�?
- 实在认不�?�?保留 `.bin`，至少不假装是其他格�?

实现�?`WeComChannelAdapter.sniffMagic()` + `refineZipKind()`�?

---

## 引用消息（quote�?

WeCom 用户引用前一条消息（图片、文件、文本、语音、小程序）然后追加问题，�?*最常见的群聊交互模�?*�?

### 支持的引用类�?

| 引用类型 | bot 看到�?| 是否能进一步处�?|
|----------|------------|------------------|
| 引用文本 | `[引用消息: 之前的文本内容]\n用户的新问题` | �?文本一并送给模型 |
| 引用语音 | `[引用消息: [语音] ASR 转文字]\n用户的新问题` | �?语音 ASR 结果作为上下�?|
| 引用图片 | `[引用消息: [图片]]\n用户的新问题` + 图片 attached part | �?视觉模型 sidecar 看图 |
| 引用文件 | `[引用消息: [文件: report.pdf]]\n用户的新问题` + 文件 attached part | �?文件 tool 可读 |
| 引用混合 | 各子类按上面规则展开 | �?|

### 实现要点

- **媒体一并下�?*：引用的图片 / 文件不只是个标记字符串，会真的下载、AES-256-CBC 解密、落�?`data/chat-uploads/{conversationId}/...`，然后作�?MessageContentPart �?agent
- **路径一�?*：媒体落盘的 conversationId **必须**等于 `mate_conversation` 表里�?conversationId，否则下�?`/api/v1/chat/files/{convId}/{name}` 会因 `isConversationOwner` 查不到行直接 403，前�?`<img>` 显示图裂

历史 bug：早期版�?`inboundConversationId()` 给群聊路径加�?`wecom:group:` 中缀，但 router 持久化时�?`wecom:{chatId}` 没中缀，两边一对不上整批群聊引用图片全部图裂。已修�?

---

## appmsg 消息类型

`msgtype=appmsg` �?WeCom 给富媒体卡片留的扩展点，常见四种子变体：

| 变体 | 实际是什�?| bot 怎么处理 |
|------|-----------|--------------|
| `appmsg.file` | 转发的文件（PDF / Word / Excel�?| 走完整下�?pipeline，等�?`msgtype=file` |
| `appmsg.image` | 图片卡片 | 走完整下�?pipeline，等�?`msgtype=image` |
| `appmsg.url` | **公众号文�?/ 外链** | 见下一�?|
| `appmsg.miniprogram` | 小程�?| �?title 暴露给模型，附件无法获取 |

未知子类�?fallback �?`[appmsg: title]` 标记，至少模型知�?用户分享了某种富媒体"�?

### 公众号文�?

mp.weixin.qq.com 的文章页�?*�?captcha-gated SSR �?*，任�?LLM 工具都抓不到正文。如�?bot 假装能读，模型会**凭标题瞎编内�?*（生产里观察到："本文讲了三个要点…�? 完全是幻觉）�?

GLClaw �?link 分支检测到 `mp.weixin.qq.com` 后，会自动给模型追加一段提示：

> （提示：该链接为公众号文章，正文需要用户在微信内打开后复制粘贴，请优先请用户粘贴正文，不要凭标题猜测内容。）

效果：模型不再编造，主动让用户粘贴正文。其他正常网址（github、维基、随便一个外链）**�?*触发提示，因为它们的 body 是普通工具能 fetch 的�?

---

## 群聊主动推送（aibot_send_msg vs aibot_respond_msg�?

### 平台规则

```
单聊：aibot_send_msg �?    aibot_respond_msg �?
群聊：aibot_send_msg �?    aibot_respond_msg �?(必须绑定一�?inbound frame �?reqId)
```

群聊�?bot 任何主动消息（cron 推送、异步任务回推、图像生成完成）都必�?*搭一辆顺风车**——绑到一个之前用�?inbound �?frameReqId 上，否则平台拒收�?

### GLClaw 的处�?

**LRU 缓存最�?inbound reqId**。`lastChatReqIds: ConcurrentHashMap<chatId, latest-reqId>` 在每条群�?inbound 进来时被更新，上�?1000 �?chat�?

**统一出口 `sendOutboundFrame(chatId, body)`**�?

- 缓存命中 �?`aibot_respond_msg` + 缓存�?reqId
- 缓存未命�?�?降级 `aibot_send_msg`（单聊或�?chat�?

这样�?

- cron 定时摘要 �?群聊有人说过�?�?�?respond 推送成功；从来没说过话 �?降级 send_msg 失败，但至少不会一刀切都失败
- 异步任务（图�?/ 音乐 / 视频生成）完成后 �?`AsyncTaskMediaDispatcher` 调用统一出口
- 同一�?LLM 回复跨多�?chunk �?同一�?reqId 复用

### 你能观察到什�?

```
[wecom] Group send via aibot_respond_msg: chatId=..., reqId=...
```

---

## 异步任务回推

图像生成 (`image_generate`) / 音乐生成 (`music_generate`) / 视频生成 (`video_generate`) / 3D 模型生成 (`model3d_generate`) 都是**异步任务**——agent 拿到 task id 立刻返回，真正的产物 30 秒～几分钟后才出来�?

历史问题：产物只出现�?Web 控制台的会话历史里，**WeCom 群里看不�?*�?

修复：`AsyncTaskMediaDispatcher.forwardToImIfBound(conversationId, parts)`—�?

- 任务完成后，�?`ChannelSessionStore` 反查 conversationId 绑的渠道
- 跳过 `web` / `webchat`（SSE 已经覆盖�?
- 调对应渠道适配器的 `sendContentParts(targetId, parts)`
- WeCom：image / audio / video / file 全部支持，走原生附件
- Slack：通过 `filesUploadV2` 直传（参�?[Slack channel](./channels#slack)�?
- 不支�?`sendContentParts` 的渠道（QQ 等）：catch UnsupportedOperationException + log，不让一个不支持的渠道卡住整批分�?

文件路径�?`data/chat-uploads/{conversationId}/`，serve URL �?`/api/v1/chat/files/{conversationId}/{storedName}`，前�?/ 渠道附件视图都按这个 URL 读�?

---

## 模型行为：假装调用工�?

观察�?*qwen3.6-plus** 在长上下�?+ 工具调用密集的场景下偶发�?�?——它会用 Markdown 代码�?*伪装**自己调了工具，但实际 `toolCallCount=0`�?

````
🎵 《在熟悉的路口�?重新创作任务已提交！
�?生成约需 1-2 分钟，完成后音频会自动推送到对话�?..

```json
{ "prompt": "...", "lyrics": "..." }
```
````

后端没拿�?tool_call �?永远不会真的发起音乐生成 �?用户永远收不到歌�?

**目前的应�?*：换更稳定执�?tool_calls 的模型（kimi-for-coding、claude-sonnet-4.5、deepseek-r1）。在 [模型配置](./models) 里把 agent 的默认模型改掉即可�?

未来可能加：服务端检�?任务已提�?+ toolCallCount=0"模式 �?自动注入纠正提示重试一次�?

---

## 模型行为：自循环输出

另一种偶发故障：模型陷入"思�?输出"自循环，重复同一段中文回答几十次直到耗尽 max_tokens�?6384）。生产上观察到的模式�?

```
"Wait, I should X." �?写中文答�?�?"Done." �?写同一份中文答�?�?"Wait, Y." �?同一份答�?�?...
```

用户全程�?"生成�?.." 等几十秒到几分钟，最后收到一坨重复文本�?

### GLClaw 的处�?

**两层守卫**�?

1. **检�?*：[`hasRepeatingSuffix`](https://github.com/anthropics/GLClaw/blob/main/GLClaw-server/src/main/java/vip/mate/agent/graph/NodeStreamingChatHelper.java) 探测 buffer 尾部是否被同一�?24~240 字符�?unit 连续重复 4 次以�?�?立即 dispose 上游订阅
2. **去重 + 标记**：`dedupTrailingRepeats` 把已累积�?buffer 尾部 N 份拷贝缩�?1 份；ReasoningNode �?finishReason 设为 `INCOMPLETE`，前端展示截断卡 + "重新生成"按钮

为什么不无脑发警告就算了：用户已经在 SSE 流里看到那坨重复文本（SSE 单向 push 没法 unsend），�?**DB 持久�?* �?**WeCom 回推** 用的都是 `finalAnswer`——所�?IM 群里只看到一份干净的回�?+ INCOMPLETE 提示�?

阈值选得**特别�?*�? �?verbatim 连续）就是为了不误伤合法�?TL;DR / body / TL;DR 三段�?输出�?

---

## 网络层稳定�?

### TLS / Socket 瞬时错误重试

DashScope / OpenAI / 各家 LLM 网关在公网传输中偶发产生�?

- `bad_record_mac`（TLS RFC 5246 §7.2.2 fatal alert 20�?
- `SSLHandshakeException`
- `SocketException: Connection reset by peer`
- `Premature close` / `Broken pipe`

之前这些一旦发生直接给 agent �?`LLM 调用失败` 红字，没有重试�?

修复：把这些都归类成 `SERVER_ERROR`，走现有的指数退避重试链�?s �?6s �?12s（带 jitter）最�?5 次。详�?[agents 引擎](./agents#错误恢复)�?

### keepalive

群聊回复�?`aibot_respond_msg` 时，平台�?*单条�?*�?60 �?TTL——超�?60 秒不发新数据，平台会丢弃这个 stream slot，后续真正的 reply 静默失败�?

agent 处理复杂任务（多次工具调�?+ LLM 推理）经常超�?60 秒。`WeComKeepaliveScheduler` �?30 秒往 stream 上发一�?noop "正在处理..." 心跳，slot 永不过期�?80 秒兜底强�?finish，避免任务真挂了 keepalive 一直续命�?

### 重连 + 指数退�?

WeCom 长连接断开时（NAT 超时、网络抖动），适配器自动重连：2s �?4s �?8s �?16s �?30s 封顶�?*永远不会放弃**——只要进程还活着，下次能连上就立刻恢复消息接收�?

控制台健康视图能看到当前重连次数，运维心里有数�?

---

## 平台级约束（不是 bug，是限制�?

这些�?*企业微信平台本身**的约束，没法在代码层绕过，只能配置层规避�?

### 数据权限�?

API 模式 bot 在企业微信管理后台勾�?*任何一项数据使用权�?*（如"读取消息"�?获取群信�?），bot �?*自动锁定为仅创建者可�?*。其他成员发消息 bot 不响应�?

**解决**：在管理后台**取消勾�?*全部 7 项数据权限，bot 即可对所有授权成员可见。MateClaw 通过 webhook 拿消息，不需要这些数据权限�?

### 可见范围 + 数据权限二维矩阵

| 可见范围 | 数据权限 | 实际效果 |
|---------|---------|---------|
| 全员 | 全部勾�?| **仅创建�?*可用（数据权限锁覆盖可见范围�?|
| 全员 | 全部取消 | 全员可用（推荐） |
| 指定部门 | 全部取消 | 指定部门成员可用 |
| 指定人员 | 全部取消 | 指定人列表内可用 |

### 群聊�?@bot 才会触发

WeCom 群的 bot 必须�?`@` 才收到消息。私聊不需�?`@`。这是平台行为，没办法绕过。MateClaw 不会在群�?broadcast 监听所有消息（也做不到）�?

---

## 调试技�?

### 看群聊归属是否生�?

```sql
SELECT content FROM mate_message
WHERE conversation_id = 'wecom:{chatId}' AND role = 'user'
ORDER BY id DESC LIMIT 5;
```

期望：每�?user 消息都以 `[@username]` 开头�?

### 看媒体落盘路�?

```bash
ls data/chat-uploads/wecom:{chatId}/
```

**不应�?*�?`wecom:group:{chatId}` 这种�?`group:` 中缀的目录（早期 bug 残留可以手动清理）�?

### 看群聊回推路�?

后端日志里：

```
[wecom] Group send via aibot_respond_msg: chatId=..., reqId=...
```

如果群聊�?bot 没回复，但日志里看到这行 + reqId 不为空，说明回推到了平台但平台拒收（一般是 reqId 已被消费过、或 bot 已被踢出群）�?

### �?keepalive 状�?

```bash
grep "wecom-keepalive" logs/GLClaw.log | tail
```

期望看到周期性的 "Heartbeat sent" + "Heartbeat ACK received" / 偶尔�?"force-finished stream" 强制完成�?

---

## 已知 corner case

| 场景 | 当前行为 | 后续可能 |
|------|---------|---------|
| 群里第一条消息就�?cron 推送（chat 还没人说过话�?| 缓存里没�?reqId，降�?`aibot_send_msg` 被平台拒 | �?ring buffer 缓存多条历史 reqId（仅修复有限场景，不上） |
| 模型在长会话�?�?得调工具 | 用户重发 / 换模�?| 加服务端检测注入纠正提�?|
| 同一群同时来 3 条不�?sender 的消�?| 串行处理，每个用户独立窗口（生效�?| �?|
| 公众号文章用户拒绝粘贴正�?| bot 礼貌引导用户复制 | �?|
| OOXML 文档 magic-byte 误判（极小概率） | 退回到 `.zip` | 已通过 ZIP 内部条目 peek 解决 90% 场景 |

---

## 一图概�?

```
                  ┌─────────────────────�?
                  �? 企业微信群里的用�?  �?
                  └──────────┬──────────�?
                             �?inbound (�?chatId)
                             �?
       ┌────────────────────────────────────────�?
       �? WeComChannelAdapter                    �?
       �? ├─ chunk upload pre-check (4 类限�?    �?
       �? ├─ magic-byte sniff (OOXML peek)       �?
       �? ├─ AES 解密 + �?chat-uploads/{convId}/ �?
       �? ├─ quote 引用解析�? 子类型）            �?
       �? ├─ appmsg 解析�? 子类�?+ 公众号提示）   �?
       �? └─ 缓存 lastChatReqIds[chatId]         �?
       └──────────────┬─────────────────────────�?
                      �?ChannelMessage(content="[@xxx] ...")
                      �?
       ┌────────────────────────────────────────�?
       �? ChannelMessageRouter                   �?
       �? ├─ 自适应 debounce (500ms / 2.5s)      �?
       �? ├─ sender boundary 切断（群聊关键）      �?
       �? ├─ applyGroupTag 落库 + �?LLM         �?
       �? └─ 队列 + sessionLock 串行             �?
       └──────────────┬─────────────────────────�?
                      �?
                      �?
                ┌──────────�?
                �? Agent   �? �?StateGraph + ReAct
                └─────┬────�?
                      �?finalAnswer / tool_calls
                      �?
       ┌────────────────────────────────────────�?
       �? sendOutboundFrame(chatId, body)        �?
       �? ├─ 缓存命中 �?aibot_respond_msg         �?
       �? ├─ 缓存未命�?�?aibot_send_msg          �?
       �? ├─ keepalive scheduler (60s TTL 续命)   �?
       �? └─ 重连退避（NAT / 抖动自愈�?           �?
       └────────────────────────────────────────�?
```

---

## 相关阅读

- [多渠道接入](./channels) �?9 个渠道的总览 + 设置
- [Agent 引擎](./agents) �?TLS 重试、错误分类、自循环检�?
- [模型配置](./models) �?怎么换默认模型、failover chain
- [安全与审批](./security) �?群里执行高风险工具的审批�?
- [Doctor 健康检查](./doctor) �?怎么用诊断命令排查渠道问�?
