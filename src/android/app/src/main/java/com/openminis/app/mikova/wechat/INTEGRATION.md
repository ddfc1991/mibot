# 微信接入（wechat/）— INTEGRATION.md

模块目录：`src/android/app/src/main/java/com/openminis/app/mikova/wechat/`

| 文件 | 作用 |
|---|---|
| `WeChatBridgeService.kt` | 微信消息→任务桥（a11y 通知事件流 → TaskRunQueue）+ `WeChatPrefs`（总开关/白名单，SharedPreferences） |
| `WeChatReplyRouter.kt` | 私聊文本→任务解析→回复的纯逻辑路由（`任务:` / `状态` / `取消`） |
| `WeChatBindScreen.kt` | 绑定页（glass）：绑定状态、开关、白名单、任务权限说明、「付款需手机接管确认」说明 |

只使用本体 glass 组件（`com.openminis.app.mibot.design.*` + `com.openminis.app.ui.glass.Frost`），
未改任何共享文件（AppNavigation / MinisApp / strings / manifest）。本页即全部集成变更清单。

---

## 1. 与本体现有页面的关系（重要）

**现状**：`Routes.MIBOT_WECHAT`（AppNavigation.kt:225，`const val MIBOT_WECHAT = "mibot_wechat"`）
当前指向一个**占位「未接入」页** `DestinyV7NotWired(which="wechat")`（AppNavigation.kt:2058-2066，
实现在 `mibot/v7/DestinyV7.kt:1146`）。那个页面是 `[destiny/honest-state]` 的产物：先前把微信画成
「已连接 destiny_bot + 两条写死的最近消息」是假数据，已整文件删掉，改成如实说没接。

**本模块策略：替换（替换占位页，不是另起炉灶）。**
`WeChatBindScreen.kt` 是同一路由的**真实替代实现**：把 `DestinyV7NotWired` 换成
`mikova.wechat.WeChatBindScreen`。这样微信从「未接入占位」变成「真实绑定页」，且保留 honest-state
原则——不编造账号/聊天记录，只显示真实开关状态、无障碍管道状态、白名单与说明。

**Mika 已有微信能力**：探查 `com.mibot/` 全目录，**没有**专门的微信解析/消息监听代码
（`rg -i "wechat|微信|mm" com/mibot/` 无命中）。Mika 可复用的底层是通用的 a11y 事件管道：
`com.mibot.cap.phone.a11y.ScreenAccessibility`（`onAccessibilityEvent` 里
`TYPE_NOTIFICATION_STATE_CHANGED` → `notifications.tryEmit(ScreenEvent("notification", pkg, text))`，
companion 级热流跨实例保留，`notificationEvents(): Flow<ScreenEvent>` 公开）。
`MinisAccessibilityService`（`com.openminis.app.accessibility`）就是它的子类，已注册为无障碍服务。
所以「复用 Mika 的 a11y 事件管道」= 订阅 `ScreenAccessibility.instance → notificationEvents()`，
按包名 `com.tencent.mm` 过滤。**本模块不新增第二条 a11y 监听路径**，只在这条现成管道上做桥。

`TaskRunQueue`（`com.openminis.app.mibot.queue.TaskRunQueue`，P0 已桥好，与任务页同一张表）：
`enqueue(goal, kind=CHAT)` / `current()` / `queue()` / `cancel(id)` / `hold(id, breakpoint)` /
`resume(id)`。`WeChatReplyRouter` 直接用它做入队/状态/取消。

---

## 2. 集成变更清单（含代码片段）

### 2.1 路由：MIBOT_WECHAT → WeChatBindScreen

改 `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt` 的
`composable(Routes.MIBOT_WECHAT)` 块（约 2058 行）：

```kotlin
composable(Routes.MIBOT_WECHAT) {
    com.openminis.app.mikova.wechat.WeChatBindScreen(
        onBack = { navController.safePopBackStack() },
        onOpenPermissions = { navController.safeNavigate(Routes.PERMISSIONS) },
    )
}
```

原块（待替换）：
```kotlin
composable(Routes.MIBOT_WECHAT) {
    com.openminis.app.mibot.v7.DestinyV7NotWired(
        which = "wechat",
        onBack = { navController.safePopBackStack() },
        onOpenPermissions = { navController.safeNavigate(Routes.PERMISSIONS) },
        onOpenProviders = { navController.safeNavigate(Routes.PROVIDER_LIST) },
    )
}
```

> 若暂不想动 AppNavigation，可选：在 `ModuleRegistry.register` 里注册 `ModuleManifest(
> id="mikova_wechat", name="微信接入", entry={ WeChatBindScreen(onBack=…, onOpenPermissions=…) },
> showInGrid=true)`，从设置页「应用」网格进入。两者取其一即可。

### 2.2 桥启动：WeChatBridgeService.start

在 `com.openminis.app.MinisApp.onCreate` 启动（长生命周期 scope，进程级）：

```kotlin
// 文件顶部已有 import kotlinx.coroutines.*
private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
override fun onCreate() {
    // ... 现有逻辑 ...
    com.openminis.app.mikova.wechat.WeChatBridgeService.start(this, bridgeScope)
}
```

桥只订阅 a11y 通知事件（无网络/无前台服务），`stop()` 在进程销毁时由系统回收。
白名单为空 = 接收所有联系人；非空时按文本包含匹配。

### 2.3 权限

- **无障碍服务**：已由 `MinisAccessibilityService`（`ScreenAccessibility` 子类）提供，
  无需新增 manifest 声明。用户需在系统设置开启（绑定页「无障碍管道」行会显示未连接）。
- **通知访问**：`ScreenAccessibility` 已处理 `TYPE_NOTIFICATION_STATE_CHANGED`，无需
  NotificationListener。但要在微信里看到消息通知本体，微信 App 的通知权限须开启，且建议
  微信 → 我 → 设置 → 新消息通知 →「显示消息详情」打开（否则 a11y 拿到的是「你收到1条消息」）。
- **POST_NOTIFICATIONS**：仅影响系统通知横幅，不影响 a11y 事件；如需弹自己的通知再声明。

### 2.4 依赖

无新增 Gradle 依赖。用到：`com.mibot.cap.phone.a11y.ScreenAccessibility`、
`com.mibot.core.uistate.ScreenEvent`（core 模块，已在 app 依赖图）、
`com.openminis.app.mibot.queue.TaskRunQueue`、`com.openminis.app.mibot.design.*`（本体 glass）。

---

## 3. 设计说明

- **只读桥**：`WeChatBridgeService` 只解析+入队，**不写回微信**。回复文本由
  `WeChatReplyRouter.route` 算出后返回给调用方；真正的微信文本注入需要 a11y 文本注入
  （focus 输入框 + `AccessibilityNodeInfo.performAction(SET_TEXT)`），这是下一步工作（见不确定点）。
- **指令约定**：`任务: <目标>` 入队；`状态`/`状态?` 查当前；`取消`/`取消任务` 停当前。
  其它文本返回 null（普通消息不消费，避免把无关聊天当任务）。
- **白名单**：空=全部；非空=按文本包含匹配（联系人名是备注名，a11y 通知文本可能带「备注名: 内容」，
  故用 contains 而非 equals）。
- **付款**：绑定页只展示「付款需手机接管确认」说明，实际确认卡在
  `mikova.wallet.PaymentRequestCard`（只问不付，见 wallet/INTEGRATION.md）。

## 4. 不确定点

1. **a11y 通知文本结构**：微信通知的 `ScreenEvent.text` 是「title + body」拼接（
   `Notification.EXTRA_TITLE + EXTRA_TEXT`）。不同微信版本/消息类型（文字/语音/转账/群消息）
   格式不同，`WeChatReplyRouter` 目前只取整段文本按指令前缀匹配，**未按微信协议解出
   联系人名与消息体**。稳定做法是订阅 `ScreenAccessibility.eventsFor`（窗口事件）在聊天页
   内读节点树，而不是靠通知文本——需要 `AccessibilityNodeInfo` 遍历，建议后续接。
2. **写回微信**：文本注入（SET_TEXT + 点发送）未实现。桥暂只把回复文本返回给调用方，
   由宿主决定（如转成 Minis 任务、或接入 a11y 注入）。
3. **群聊 vs 私聊**：未区分。群消息可能被误当指令；白名单可先过滤到私聊联系人降低误伤。
4. **bridge 的线程**：`onLog` 监听回调在收集协程线程触发，绑定页日志列表更新可能跨线程；
   当前用 `mutableStateListOf` 接受后台写（Compose 会调度重组），如需严格可 post 到主线程。
