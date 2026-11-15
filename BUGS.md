# Mikova Bug 台账

> 这是可直接读取的当前问题记录。处理任何问题前先读本文件；修复后回填「根因、改动、验证、提交」。
>
> 当前分支：`fix/preserve-mibot-ui`  
> 当前基线：`c0ad761`  
> 最近全绿 CI：`43725162123`（Build + API30 + API31 smoke）

## 状态约定

- **OPEN**：已确认或用户明确反馈，尚未修复。
- **INVESTIGATING**：正在定位，根因还没有证据闭环。
- **BLOCKED**：需要真实凭据、系统权限或设备条件。
- **FIXED**：修复已提交，并有针对原问题的验证证据。
- **WONTFIX / EXPECTED**：当前行为是设计限制或外部配置问题，记录原因和替代路径。

---

## BUG-2026-11-15-001：账号连接中心进入后黑屏

- **状态**：`INVESTIGATING`
- **优先级**：P0
- **模块**：`mikova/accounts`、`AppNavigation`、`MibotScaffold`、转场/Haze
- **用户描述**：连接中心黑屏；用户后续确认触发点是「一进页面就黑」。
- **入口**：设置 → 账号与服务 → 应用连接中心；路由 `mikova_accounts` / `Routes.MIBOT_APP_ACCESS`。
- **关联代码**：
  - `src/android/app/src/main/java/com/openminis/app/mikova/accounts/ConnectedAccountsScreen.kt`
  - `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt`（转场 `enterTransition` mibot 分支 `fadeIn(delayMillis=90)+scaleIn`）
  - `src/android/app/src/main/java/com/openminis/app/ui/glass/Frost.kt`（`frosted()` Haze）

### 已有证据

1. 隐藏屏 407 冷启动后直接 deep link 到 `minis://settings/app-access`，连接中心**正常显示**：标题、空状态卡、`+ 添加账号`。
2. 用户路径是「设置 → 应用连接中心」（push 转场），不是 deep link 根入口。两者差异点唯一在转场层。
3. 当前进程日志无 `ConnectedAccountsScreen`/`AccountStore` 异常，无 `FATAL EXCEPTION`；历史崩溃只有 11-13/14 的 `BootRestoreHandler ClassNotFoundException`（与本页无关）。
4. ConsentSheet/ApiKeySheet 初始为 null，只在点击目录项后才出现，且只铺 45% 半透明——排除「一进就黑」由它们造成。

### 排序假设（可证伪）

- **H1（最可能）** push 转场 `fadeIn(delayMillis=90)+scaleIn` 叠加整页 `frosted()`（Haze 实时模糊）在转场首帧合成异常，导致黑帧/无内容。预言：`adb shell settings put global animator_duration_scale 0` 后黑屏消失或变单帧闪黑。
- H2 AutoKey `ConnectWebLayer` 全屏变暗遮罩（底部有取消按钮、过几分钟自动恢复）。
- H3/H4/H5 已基本排除（空态渲染路径无问题；safeNavigate 失败会静默留在设置页而非黑）。

### 下一步（闭环回路）

本轮已加入 **BugJournal** 自动记录：进入页面记 `screen.entered`，首帧绘制完成记 `screen.shown`。
- 装新版后复现「设置 → 应用连接中心黑屏」，立即读取 `filesDir/logs/bug-events.log`：
  - 有 `screen.entered` 无 `screen.shown` → 命中 H1/H4（导航到了但没绘制）。
  - 两者都有但屏黑 → H2（外部遮罩）。
  - 无 `navigation.accepted` → H4（safeNavigate 被丢弃）。
- 配合 `adb shell settings put global animator_duration_scale 0` 复现一次，确认 H1。
- 确认后最小修复：mibot 转场去掉 `delayMillis=90`，改 `tween(150)` 直接淡入。**当前不动**，等证据闭环再改。

---

## BUG-2026-11-15-002：本地网关 8788 端口冲突，启动报 EADDRINUSE

- **状态**：`FIXED`（待 CI 验证）
- **优先级**：P1
- **模块**：`mibot/gateway/LocalGateway.kt`、`GatewayListener.kt`、`GatewayHttpReader.kt`、`Gateway.kt`
- **用户描述**：点击本地 OpenAI 兼容端点「启动」后显示：
  `启动失败：bind failed: EADDRINUSE (Address already in use)`。
- **用户证据**：
  - [网关正常停止截图](minis://attachments/uploads/1000135588.jpg)
  - [网关端口冲突截图](minis://attachments/uploads/1000135589.jpg)

### 已确认根因

手机上 8788 并非空闲端口：

```text
0.0.0.0:8790  LISTEN
*:8788       LISTEN
```

请求 `http://127.0.0.1:8788/health` 返回：

```json
{"service":"workbuddy2api-android","status":"ok"}
```

因此 8788 被设备上已有的 WorkBuddy2API 服务占用，不能由 Mikova 抢占，也不应停止该服务。

### 修复

- `GatewayListener.bind`：优先 8788，被占则依次试 8789–8797，最后系统分配端口；每次失败 bind 都 `close()`，**绝不触碰已占用端口的其它服务**。
- UI 显示实际绑定地址 `http://127.0.0.1:<实际端口>/v1` + 冲突说明 + 模型数；「复制地址」按钮；启动走 `Dispatchers.IO` + 「启动中…」防重复点击。
- stop/restart 代际守卫：旧 accept 线程不会误杀新实例。
- `GatewayHttpReader`：按字节读 body（原 CharArray 读法对中文 UTF-8 会阻塞到 60s 超时）。
- 网关启停记 BugJournal 事件（端口/模型/原因/结果，无 body/凭据）。

### 验证

- 纯 JVM 单测 13 用例真实 ServerSocket/Socket 验证：端口占用→回退且占用方仍可用、全占→OS 端口、close 幂等、restart 不串扰、中文 body 不卡。
- 真实 HTTP smoke：模拟 8788 被占→回退 8789，`/health` 200、`/v1/chat/completions`（中文）200。
- 限制：Compose UI（Gateway.kt）本地未编译，由 CI `:app:assembleDebug` 把关。

---

## BUG-2026-11-15-003：虚拟屏只能显示「本进程屏」，不能启动第三方 App

- **状态**：`BLOCKED`
- **优先级**：P1
- **模块**：`mikova/vd`、`PhoneServices`
- **用户证据**：[虚拟屏多开截图](minis://attachments/uploads/1000135583.jpg)
- **现象**：`0/3 块`、`本进程屏`、`无焦点·不能跑第三方 App`。
- **已知边界**：本进程屏只能渲染 own-content；第三方 App 需要 `PhoneServices.Owner` 对应的手机控制运行时、无线调试配对和助手连接。
- **下一步**：在真实设备确认 `PhoneServices.sharedShell` 是否已绑定助手；记录配对状态、助手进程和 `shellAvailable()` 返回值。不要把仅有 Shizuku 能力列表当成已接入可信屏。

---

## BUG-2026-11-15-004：Google OAuth 使用占位 client_id，报 invalid_client

- **状态**：`BLOCKED`
- **优先级**：P1
- **模块**：`mikova/oauth`
- **用户证据**：
  - [Google invalid_client 截图](minis://attachments/uploads/1000135575.jpg)
  - [OAuth 网页失败截图](minis://attachments/uploads/1000135576.jpg)
- **根因**：内置配置仍是 `<PH:GOOGLE_CLIENT_ID>`，Google 必然返回 `401 invalid_client`。
- **代码状态**：`OAuthContainerScreen` 已加入占位凭据拦截，并对 Google 使用系统浏览器 + loopback callback；不会再把占位符发给 Google。
- **阻塞条件**：要真正登录，必须配置真实 Google OAuth client ID（以及对应 redirect / consent screen 配置）。

---

## BUG-2026-11-15-005：微信无障碍管道未连接

- **状态**：`BLOCKED`
- **优先级**：P2
- **用户证据**：[微信接入截图](minis://attachments/uploads/1000135580.jpg)
- **现象**：微信通道已开，但无障碍管道未连接，收不到微信消息。
- **处理路径**：开启 Mibot/对应手机控制无障碍服务，并确认通知访问权限；这不是仅改 UI 文案能解决的问题。

---

## BUG-2026-11-15-006：api.breeze.blue 显示「未检测」

- **状态**：`EXPECTED`
- **优先级**：P3
- **用户证据**：[AI 网关截图](minis://attachments/uploads/1000135582.jpg)
- **说明**：该服务实际返回 `401 AUTH_REQUIRED`，需要给该供应商配置有效 Key 后点「检测」。这是供应商凭据/配置状态，不是网关渲染故障。

---

## 已修复记录

### FIX-2026-11-15-001：沙箱发行版 HTTP 403

- `LinuxDistro.kt` 对每个镜像独立容错，清华 tuna 403 时回退到 dl-cdn / aliyun / linuxcontainers。
- 已在 CI `43725162123` 的 API30/API31 smoke 验证。

### FIX-2026-11-15-002：会话归档

- DB v15、`archived_at`、迁移、侧边栏长按归档、归档列表恢复/删除已入库。
- `DatabaseVersionGuard.CODE_DB_VERSION` 已同步为 15；此前遗漏会导致「数据来自更新的版本」并阻止主壳渲染。
- CI `43725162123` 全绿。

### FIX-2026-11-15-003：本地网关基础实现

- `127.0.0.1:8788` 的 OpenAI 兼容接口已实现：`/health`、`/v1/models`、`/v1/chat/completions`、`/v1/responses`，支持供应商和模型组路由。
- 端口被外部服务占用时自动回退（见 BUG-002 修复）、字节级 body 读取、启停竞态守卫、UI 显示实际地址 + 复制。

### FIX-2026-11-15-004：浮层语音字幕双工

- 识别部分实时上字幕；回复订阅 `streamingById` 实时更新；结束后 TTS 朗读。
- 浮层可聚焦，BACK 可关闭。

### FIX-2026-11-15-005：自动 Bug 记录器（BugJournal）

- 新增 `diagnostics.BugJournal`：进程启动、Activity 生命周期、导航请求/接受/丢弃/失败、页面进入/首帧绘制、未捕获异常、网关启停——全部自动落盘到 `filesDir/logs/bug-events.log`（256KB 轮转保留前一份）。
- 只记数值/常量/路由模板/页面标题；**不记**密钥、URL、聊天正文、异常 message（只记类名 + 项目内栈帧）。
- 读取：`run-as com.mikova.app cat files/logs/bug-events.log`，或在应用内 设置 → 日志 查看（与 AppLogger 同目录，已自动出现在日志列表）。
- 单测 `BugJournalFileTest`：白名单字段过滤、密钥/正文丢弃、异常只记类名、轮转、中文页面标题保留。
