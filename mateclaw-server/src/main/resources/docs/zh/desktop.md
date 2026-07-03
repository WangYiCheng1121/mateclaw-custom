# 桌面应用

**双击。等 30 秒。登录。开始用�?*

四句话的桌面版�?*不需要装 Java**，不需要打开浏览器，不需�?docker compose 文件，不需要记住端口号。GLClaw 桌面版把 Electron、JRE 21 运行时、打包的 Spring Boot 服务 JAR **全部装进一个安装包**�?*你的用户永远不会知道下面跑的�?Java�?*

这一页给�?*运行、构建、调�?*桌面版的人看�?

---

## 架构

```
┌──────────────────────────────────────────�?
�?           Electron 外壳                  �?
�? ┌────────────────────────────────────�? �?
�? �?     BrowserWindow (Chromium)      �? �?
�? �? ┌──────────────────────────────�? �? �?
�? �? �?    Vue 3 前端 (dist/)       �? �? �?
�? �? �?  Element Plus + Tailwind    �? �? �?
�? �? └────────────┬─────────────────�? �? �?
�? └───────────────┼────────────────────�? �?
�?                 �?HTTP / SSE             �?
�? ┌───────────────▼────────────────────�? �?
�? �?  Spring Boot 后端 (子进�?         �? �?
�? �?  127.0.0.1 上的动态端�?           �? �?
�? �?  内置 JRE 21 + H2 文件数据�?      �? �?
�? └────────────────────────────────────�? �?
�?                                         �?
�? ┌────────────────────────────────────�? �?
�? �?  electron-updater 自动更新          �? �?
�? └────────────────────────────────────�? �?
└──────────────────────────────────────────�?
```

一个进程树里住着三样东西�?

1. **Electron 主进�?*——窗口、托盘、IPC、后端生命周�?
2. **BrowserWindow（Chromium�?*——渲�?Vue 3 前端（和 Web 版同一份代码）
3. **Spring Boot 后端**——作为子进程被主进程启动，只监听 localhost

后端在启动时**动态挑一个空闲端�?*，这样就不会和你机器上别的东西冲突�?

### 核心特�?

- 原生窗口，不依赖浏览�?
- 系统托盘集成，后台运�?
- **内置 JRE 21**——用�?*永远不用�?Java**
- **自动更新**通过 electron-updater
- **本地优先的数�?*
- **动态后端端�?*
- **UI 热更�?*——前端资源可以独立更新，不用重新打包
- 跨平台（macOS、Windows、Linux�?

---

## 支持的平�?

| 平台 | 架构 | 状�?|
|------|------|------|
| macOS | Intel（x64�?| 稳定 |
| macOS | Apple Silicon（ARM64�?| 稳定 |
| Windows | x64 | 稳定 |
| Linux | x64 | 稳定 |

---

## 前置要求（构建需要，运行不需要）

**运行**这个 app？下�?+ 安装。完�?

**构建**这个 app�?

| 工具 | 版本 | 用�?|
|------|------|------|
| Node.js | 18+ | 前端构建 + Electron |
| pnpm / npm | 8+ / 9+ | 包管理器 |
| Java | 21+ | 后端编译 + 开发模式（生产构建自带 JRE�?|
| Maven | 3.8+ | 后端构建 |

---

## 模块布局

```
GLClaw-desktop/
├── electron/
�?  ├── main/index.ts           # 主进程——后端生命周期、自动更新、托�?
�?  └── preload/index.ts        # IPC �?
├── src/                         # Vue 3 渲染进程源码
├── resources/
�?  ├── jre/                     # 内置 JRE
�?  └── app.jar                  # 打包好的 Spring Boot 后端 JAR
├── build/                       # 应用图标
├── electron-builder.json        # 打包配置
├── package.json
└── vite.config.ts
```

---

## 开发模�?

```bash
cd GLClaw-desktop
pnpm install
pnpm dev
```

开发模式下�?

1. Vite 起前端开发服务器（HMR�?
2. Electron 主进程启动加�?Vite 开�?URL
3. 主进程在空闲端口启动 Spring Boot JAR 子进�?
4. 前端通过 HTTP/SSE 和后端对�?

前端改动触发 HMR。主进程改动自动重启 Electron�?

---

## 生产构建

```bash
cd GLClaw-desktop
pnpm build && npx electron-builder --mac     # macOS
pnpm build && npx electron-builder --win     # Windows
pnpm build && npx electron-builder --linux   # Linux
```

产物落在 `release/`�?

| 平台 | 产物 | 说明 |
|------|------|------|
| macOS | `.dmg` + `.zip` | 拖进 Applications |
| Windows | `.exe`（NSIS�?| 可自定义安装目录 |
| Linux | `.AppImage` | 加执行权限直接跑 |

### 构建的完整前置流�?

```bash
# 1. 构建前端静态资�?
cd GLClaw-ui
pnpm install && pnpm build

# 2. 构建后端 JAR
cd ../GLClaw-server
mvn clean package -DskipTests

# 3. �?JAR 拷到桌面项目
cp target/GLClaw-server.jar ../GLClaw-desktop/resources/app.jar

# 4. 下载平台特定�?JRE
cd ../GLClaw-desktop
bash scripts/download-jre.sh

# 5. 构建桌面安装�?
pnpm build && npx electron-builder
```

---

## Java 后端生命周期管理

Electron 主进程通过 Node.js `child_process` 管理 Spring Boot 后端�?

1. **启动**——用内置 JRE �?JAR 作为子进程启动，传入一个动态端口，等就�?
2. **就绪检�?*——轮�?`http://127.0.0.1:{port}` 直到响应，然后加载前�?
3. **运行�?*——前端通过 REST + SSE 通信
4. **关闭**——发出优雅关机信号，等进程退出，关窗�?

后端在会话中途崩了的话，主进程会发现并弹带日志尾巴的错误对话框�?*不会白屏发呆�?*

---

## 自动更新

集成 electron-updater，从 GitHub Releases 自动检测和下载新版本�?

### 流程

1. 启动时检�?GitHub Releases
2. 发现新版本时，UI 弹通知显示版本 + changelog
3. 用户确认后下载，带实时进度条
4. 下载完成后�?*立即安装**�?*下次启动时安�?*
5. App 退出、替换文件、重�?

### 配置

```json
{
  "publish": [
    {
      "provider": "github",
      "owner": "matevip",
      "repo": "GLClaw"
    }
  ]
}
```

### UI 热更新（不用重新打包�?

**前端资源可以独立热更�?*——只改前端的修复不需要重发新安装器。看 `GLClaw-desktop/scripts/` �?`desktop-ui-hot-update.md`�?

---

## 数据存储

| OS | 路径 |
|----|------|
| macOS | `~/Library/Application Support/GLClaw/data/` |
| Windows | `%APPDATA%/GLClaw/data/` |
| Linux | `~/.local/share/GLClaw/data/` |

日志、工作空间文件、技能脚本、Wiki 内容都在同一个用户目录下。做重大变更�?*备份**�?

---

## `electron-builder.json` 参�?

| 设置 | 用�?|
|------|------|
| `appId` | `vip.mate.GLClaw`——系统注册和代码签名 |
| `productName` | 标题栏和安装器里显示的应用名 |
| `publish` | 自动更新源（GitHub Releases�?|
| `extraResources` | JRE �?`app.jar` |
| `mac.target` | `dmg` + `zip`，`arm64` �?`x64` |
| `win.target` | `nsis` 安装�?|
| `linux.target` | `AppImage` |
| `mac.hardenedRuntime` | 签名和公证必需 |
| `nsis.oneClick` | `false`——让 Windows 用户选安装目�?|

---

## 环境变量

桌面 app 读环境变量和独立后端一样。但有更简单的方式�?*启动之后通过设置页面配置所有东�?*。API key 进到加密�?`mate_model_provider` 表里�?

---

## 故障排查

### 白屏

1. 后端没起来——看日志
2. 端口冲突——动态端口选择器处理大部分情况，严格防火墙可能导致失败
3. 内置 JRE 损坏——重�?app
4. 看日志（位置见下面）

### 代码签名警告

- **macOS**——右键�?*打开**绕过 Gatekeeper（首次启动）。生产分发：Apple Developer 证书做签名和公证。看 `GLClaw-desktop/CODESIGNING.md`�?
- **Windows**——SmartScreen 警告 �?**更多信息 �?仍要运行**。生产分发：EV 代码签名证书�?

### 桌面 app 启动不了

1. 安装版自�?JRE——不需要装 Java。开发版：确�?`java -version` 显示 21+�?
2. 看日志：
   - macOS：`~/Library/Logs/GLClaw/`
   - Windows：`%APPDATA%/GLClaw/logs/`
   - Linux：`~/.local/share/GLClaw/logs/`
3. 从终端启动看控制台输�?
4. 确认后端选的端口没被防火墙挡

### 企业微信授权弹窗

企业微信二维码授�?*必须在应用内弹窗打开**（不是系统浏览器），这样 `postMessage` 回调才能工作。GLClaw �?`setWindowOpenHandler` 里对 `work.weixin.qq.com` 域名做了特殊处理—�?*自动作为应用内弹窗打开**�?

---

## 注意

- 首次启动 10�?0 秒（数据库初始化�?
- 关窗�?*不会**停后台服务——用系统托盘菜单完全退�?
- **定期备份用户数据目录**
- 内置 JRE 意味着安装�?80�?20 MB

---

## 下一�?

- [快速开始](./quickstart)——最快走完桌面体�?
- [配置说明](./config)——运行时设置
- [控制台](./console)——跑�?Electron 窗口里的 UI
