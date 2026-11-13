# 钱包（wallet/）— INTEGRATION.md

模块目录：`src/android/app/src/main/java/com/openminis/app/mikova/wallet/`

| 文件 | 作用 |
|---|---|
| `WalletStore.kt` | 余额+流水本地存储（JSON 文件，Mika file-backed 模式） |
| `WalletScreen.kt` | 钱包页（glass）：余额卡（大数字）、流水列表、充值入口（置灰「待支付通道」） |
| `PaymentRequestCard.kt` | 支付请求卡（金额+用途+「只问不付」）+ `PaymentGuard`（HELD↔resume/cancel 桥） |

只使用本体 glass 组件（`com.openminis.app.mibot.design.*` + `com.openminis.app.ui.glass.Frost`），
未改任何共享文件。

---

## 1. 与本体现有页面的关系（重要）

**现状**：`Routes.MIBOT_WALLET`（AppNavigation.kt:227，`const val MIBOT_WALLET = "mibot_wallet"`）
当前指向占位「未接入」页 `DestinyV7NotWired(which="wallet")`（AppNavigation.kt:2071-2079，
实现在 `mibot/v7/DestinyV7.kt:1146`）。该页是 `[destiny/honest-state]` 的产物：先前把钱包画成
「¥1,284.50 / 尾号 8842」是写死的假数据，已删掉，改成如实说没接支付通道。

**本模块策略：替换（替换占位页，不是另起炉灶）。**
`WalletScreen.kt` 是同一路由的**真实替代实现**：把 `DestinyV7NotWired` 换成
`mikova.wallet.WalletScreen`。钱包从「未接入占位」变成「真实钱包页」，且保留 honest-state——
**不编造余额/账单**：余额来自本地台账 `WalletStore`（初始 0，如实显示），充值入口明确置灰
「待支付通道」。

> 探查 `com.mibot/` 全目录无 `wallet|钱包` 命中；钱包是本模块全新实现，无既有实现可扩展。

---

## 2. 集成变更清单（含代码片段）

### 2.1 路由：MIBOT_WALLET → WalletScreen

改 `src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt` 的
`composable(Routes.MIBOT_WALLET)` 块（约 2071 行）：

```kotlin
composable(Routes.MIBOT_WALLET) {
    com.openminis.app.mikova.wallet.WalletScreen(
        onBack = { navController.safePopBackStack() },
    )
}
```

原块（待替换）：
```kotlin
composable(Routes.MIBOT_WALLET) {
    com.openminis.app.mibot.v7.DestinyV7NotWired(
        which = "wallet",
        onBack = { navController.safePopBackStack() },
        onOpenPermissions = { navController.safeNavigate(Routes.PERMISSIONS) },
        onOpenProviders = { navController.safeNavigate(Routes.PROVIDER_LIST) },
    )
}
```

> 可选：`ModuleRegistry.register(ModuleManifest(id="mikova_wallet", name="钱包",
> entry={ WalletScreen(onBack=…) }, showInGrid=true))` 从设置「应用」网格进入。

### 2.2 支付请求 → 任务队列 HELD

`PaymentRequestCard` + `PaymentGuard` 与 `TaskRunQueue` 的 HELD 状态桥接，示例（宿主在
agent 要花钱时调用）：

```kotlin
val queue = TaskRunQueue.get(context)
val req = PaymentRequest(amountCents = 1299, purpose = "买一份数据报表", from = "agent-42", runId = null)
// 1) 弹卡前：把当前任务置 HELD（记断点，不取消）
PaymentGuard.holdForPayment(req.runId, queue)
// 2) 展示 PaymentRequestCard(request = req, queue = queue) { settled -> /* 刷新 */ }
// 3) 用户确认 → PaymentGuard.settle(..., approved=true) → queue.resume(runId) 从断点续
//    用户拒绝 → PaymentGuard.settle(..., approved=false) → queue.cancel(runId) 标终态并自动取下一个
```

状态机对照（`TaskRunQueue`）：`QUEUED → RUNNING → (HELD ⇄ RUNNING) → DONE / FAILED`。
`hold` = HELD（`TaskService.handBack`/`putAside` 的落点）；`resume` = HELD→RUNNING（从断点续）；
`cancel` = 任意活态→FAILED 并 `startNext()`。**「只问不付」**：`PaymentRequestCard` 本身不做任何
支付动作，只展示金额/用途并回写队列；真实付款由用户在微信/支付 App 里完成。

### 2.3 权限 / manifest

无新增权限。支付通道未接，`WalletStore` 只读本地文件（`filesDir/mikova-wallet.json`），
无网络/无系统 API。

### 2.4 依赖

无新增 Gradle 依赖。用到 `kotlinx-serialization-json`（已在 app 依赖，
`org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`，序列化插件已 apply）、
`com.openminis.app.mibot.queue.TaskRunQueue`、`com.openminis.app.mibot.design.*`。

---

## 3. 设计说明

- **诚实账本**：余额/流水来自 `WalletStore`（初始空/0），`add()` 只由未来支付通道或
  用户确认后的回调写入，绝不显示编造的余额与流水。
- **金额用「分」**（`amountCents: Long`）避免浮点误差；展示经 `formatCents`（`¥%,.2f`）。
- **file-backed**：参照 Mika `AppAccessStore`（AppAccessStore.kt）——`filesDir` JSON、
  `tmp + Files.move(ATOMIC_MOVE)` 原子写、解析失败回退空状态、DSL 手写不引入 @Serializable。
- **流水顺序**：新的在前（`listOf(tx) + transactions`）；`WalletScreen` 用 `MibotRow` 渲染，
  正负金额分别用 `okColor()`/`errColor()`，图标收入 `CircleCheck` / 支出 `Hand`。
- **充值入口置灰**：`MibotRow` 带 `StatusChip("待支付通道", WARN)`，`onClick = null` 不可点。

## 4. 不确定点

1. **支付通道未接**：本版没有任何真实充值/支付通道。`WalletStore.setBalance`（对账）与
   `add`（记流水）已留好，等未来通道接入后由宿主调用；当前页面如实显示 0 余额 + 空流水。
2. **「只问不付」的边界**：确认卡只负责把任务 HELD↔放行；「用户确实付了钱」这一事实无法
   由 AI 验证——未来若接真实通道，需在通道回调里做对账（`setBalance` + `add`），
   而不是相信确认按钮本身。
3. **`PaymentGuard.holdForPayment` 的 runId 语义**：`runId=null` 时落到当前 RUNNING 任务；
   若当前无 RUNNING 任务则返回 null（无可挂任务），宿主应在这种情况下只弹卡不问任务队列。
4. **线程**：`WalletStore` 方法 `@Synchronized`，适合 UI 线程调用（文件小）；若未来流水大，
   建议挪到 Dispatchers.IO。
