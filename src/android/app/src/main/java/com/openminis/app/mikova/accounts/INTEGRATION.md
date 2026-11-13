# 账号连接中心（accounts/）— INTEGRATION.md

模块目录：`src/android/app/src/main/java/com/openminis/app/mikova/accounts/`

| 文件 | 作用 |
|---|---|
| `AccountModels.kt` | 连接域模型：`AuthType`（OAUTH/APIKEY/BROWSER）、`AppCategory`、`AppTemplate`（内置应用模板）、`AppCatalog`（12 个预置应用）、`ConnectedAccount`（含 JSON 互转） |
| `AccountStore.kt` | 连接账号本地存储（file-backed，一个应用一个 JSON：`filesDir/mikova/accounts/<serviceId>.json`），秘密复用本体 Keystore AES-GCM |
| `ConnectedAccountsScreen.kt` | 连接中心页（glass）：已连接应用分组 + 账号列表/断开、`+ 添加账号`、可连接应用分类目录；内置「权限说明+隐私承诺」浮层与 API Key 录入浮层 |

只使用本体 glass 组件（`com.openminis.app.mibot.design.*` + `com.openminis.app.ui.glass.Frost`），
未改任何共享文件。

---

## 1. 与本体现有页面的关系

**无既有实现可扩展**：探查 `com.openminis.app`（含 `com.mibot`）未见「账号连接中心 / 账号连接域」。
本体的 `mibot/vault`（账号台账）是「一个账号一条记录」的通用密钥/账号库，与「按应用连接」是不同
抽象：连接中心面向「把某个服务（Gmail/Notion/GitHub…）连接进来、同应用多账号、OAuth 前权限说明」。

**策略：新增（独立路由，与 vault 并列）。** 它复用 vault 的底层加密（`VaultCrypto`）但不动 vault 的
数据与页面。OAuth / 浏览器捕获的实际授权流程交给同组的 `mikova/oauth`（见该模块 INTEGRATION.md）。

## 2. 集成变更清单（含代码片段）

### 2.1 路由（AppNavigation.kt）

在 `ui/navigation/AppNavigation.kt` 的 Mibot 页面区块（与 `VaultRoutes.*` 同级）新增：

```kotlin
composable(com.openminis.app.mikova.accounts.AccountRoutes.CENTER) {
    com.openminis.app.mikova.accounts.ConnectedAccountsScreen(
        onBack = { navController.safePopBackStack() },
        onOpenOAuth = { sid ->
            navController.safeNavigate(
                com.openminis.app.mikova.oauth.OAuthRoutes.container(
                    sid, com.openminis.app.mikova.oauth.OAuthMode.OAUTH))
        },
        onOpenBrowser = { sid ->
            navController.safeNavigate(
                com.openminis.app.mikova.oauth.OAuthRoutes.container(
                    sid, com.openminis.app.mikova.oauth.OAuthMode.BROWSER))
        },
    )
}
```

> `onOpenOAuth` / `onOpenBrowser` 指向同组 `mikova/oauth` 的容器页。**若只集成 accounts 不集成 oauth**，
> 把这两个回调暂时指向 Toast / 占位页即可，连接中心本身（API Key 连接、断开、目录浏览）不依赖 oauth。

### 2.2 设置页入口（可选，MibotSettingsScreen.kt）

`MibotSettingsActions` 增加一个动作并在网格/列表暴露（该文件不在禁改清单内，但属共享 UI，故列为可选）：

```kotlin
// MibotSettingsActions 增加字段
val accounts: () -> Unit = {},

// 网格项（apps grid 里）或「账号与服务」卡片里：
MibotGridItem(t("连接", "Connect"), Icons.Outlined.Extension, MibotTokens.Sky, a.accounts)
// 或
MibotRow(t("账号连接", "Connections"), t("连接 Gmail / Notion / GitHub 等应用", "…"),
    Icons.Outlined.Extension, MibotTokens.Sky, onClick = a.accounts, showDivider = true)
```

AppNavigation 构造 `MibotSettingsActions(...)` 处（约 793 行）补一行：

```kotlin
accounts = go(com.openminis.app.mikova.accounts.AccountRoutes.CENTER),
```

### 2.3 MinisApp 初始化

**无。** `AccountStore.shared(context)` 是进程内懒加载单例（首次访问建目录、读 JSON），
不需要在 `MinisApp.onCreate` 做任何初始化。

### 2.4 ModuleRegistry（可选）

页面需要 `onBack` / `onOpenOAuth` / `onOpenBrowser` 三个回调，而 `ModuleManifest.entry` 是无参
`@Composable () -> Unit`，**不适合**直接作模块网格入口。建议走 AppNavigation 路由（2.1）。
若确要注册（仅作「能力提供者」被 `CapabilityBus` 发现，不进网格）：

```kotlin
ModuleRegistry.register(
    ModuleManifest(
        id = "mikova_accounts", name = "账号连接", summary = "连接 Gmail / Notion / GitHub 等应用",
        icon = Icons.Outlined.Extension, color = MibotTokens.Sky,
        capabilities = setOf("accounts"),
        showInGrid = false, // entry 无法承载回调，故不进网格，见上
    ),
)
```

### 2.5 AndroidManifest / 权限

**无新增。** 账号连接中心本身不发网络请求、不用系统账号框架；OAuth 授权的网络与回调
由 `mikova/oauth` 负责（其 manifest 需求见该模块文档）。

### 2.6 依赖

**无新增 Gradle 依赖。** 用到 `org.json`（平台自带）、`kotlinx.coroutines`（已依赖）、
`com.openminis.app.mibot.design.*`、`com.openminis.app.ui.glass.*`、
`com.openminis.app.mibot.vault.VaultCrypto`（本体已有，`internal` 同模块可见）。

## 3. 设计说明

- **file-backed per-app**：参照 Mika `AppAccessStore`——`filesDir/mikova/accounts/<serviceId>.json`、
  `tmp + rename` 原子写、解析失败回退空、`MutableStateFlow` 进程内唯一真源。一个应用一个文件，
  天然支持「同应用多账号」（如两个 Gmail 账号并列）。
- **秘密加密**：`AccountStore.seal()/revealSecret()` 直接用本体 `VaultCrypto`（Keystore AES-GCM），
  明文只在加解密瞬间存在；`displayName/connectedAt/scopes/kind/expiresAt` 是明文元数据可安全展示。
- **授权前权限说明 + 隐私承诺**（toki 蓝本）：OAuth/浏览器捕获先弹 `ConsentSheet` 浮层，列出
  「它将获得」+ 三条隐私承诺（只存本机 / 敏感操作确认 / 一键断开），点「继续连接」才进入 OAuth 容器。
  浮层用 `FrostedSurface(level=Sheet)` 在页内铺（对话框窗口拿不到共享毛玻璃 backdrop 会退化成平面）。
- **API Key 直连**：`authType=APIKEY` 的应用（DeepSeek/Kimi/智谱/通义）走 `ApiKeySheet` 录入，
  `seal()` 后落库，**不经网络**。
- **内置目录**：`AppCatalog` 12 个预置模板（DeepSeek/Kimi/智谱/通义/Gemini/OpenAI/Claude/Gmail/
  Outlook/Notion/GitHub/Sesame），各含 `name/icon/category/authType/summary/scopes`；`grouped()`
  按 AI 服务/通讯/工具/其他 分组。

## 4. 不确定点

1. **OAuth/浏览器流程依赖 oauth 模块**：连接中心只负责「选应用 → 说明 → 跳容器」，真正授权/换 token/
   浏览器捕获在 `mikova/oauth`。两者需一起集成才是完整体验。
2. **`ConsentSheet` 的 scope 文案是静态的**：`AppTemplate.scopes` 是手写展示文案，与 OAuth 实际请求的
   `scope` 字符串（在 `OAuthProviders`）对齐但非同一来源；若未来要严格一致，应由 `OAuthProviders`
   反查填 `scopes`。
3. **`IconChip(filled=true)` 忽略品牌色**：按本体设计系统，填充图标统一走 line-glyph + 默认 tint，
   `AppTemplate.color` 仅用于非填充场景；这是与本体一致的表现，非缺陷。
4. **过期状态仅展示**：`expiresAt` 只用于列表里标「已过期」，暂无自动刷新 token 的任务（刷新逻辑
   属 oauth 模块的后续工作）。
