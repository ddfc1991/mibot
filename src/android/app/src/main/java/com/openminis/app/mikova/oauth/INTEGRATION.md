# OAuth 容器（oauth/）— INTEGRATION.md

模块目录：`src/android/app/src/main/java/com/openminis/app/mikova/oauth/`

| 文件 | 作用 |
|---|---|
| `OAuthBroker.kt` | `OAuthProvider` 描述 + `OAuthProviders` 内置目录 + `OAuthBroker`：生成授权 URL（PKCE S256）、识别回调、`exchange()` 换 token、`saveToken()` 加密落库、`runLoopbackFlow()` 降级 Custom Tab 全流程 |
| `OAuthContainerScreen.kt` | WebView 容器页（glass）：地址栏 + 进度条 + 「拦截到 token」状态行；`OAUTH` 标准模式拦截回调换 token；`BROWSER` 降级模式登录后抓 cookie/localStorage |

只使用本体 glass 组件 + WebView，未改任何共享文件。

---

## 1. 复用本体的 OAuth 基础（按要求，不重复造）

探查 `rg -l "OAuth" src/android/app/src/main/java/com/openminis/app/`，本体已有相当完整的 OAuth 栈，
本模块**直接复用**以下类（均在同一 Gradle 模块，可直接 import）：

| 复用 | 出处 | 用途 |
|---|---|---|
| `com.openminis.app.mibot.vault.Pkce` | `mibot/vault/AutoKeyCore.kt` | PKCE 原语：`verifier` / `challengeS256` / `parseCallback`（`Callback` 数据类）。**未复用其 `authUrl` / `exchangeBody`**（写死 OpenRouter 参数名，不通用，故本模块自拼标准 OAuth2 参数与 token 请求体） |
| `com.openminis.app.auth.OAuthCallbackServer` | `auth/OAuthCallbackServer.kt` | loopback 回调服务器（`start/stop/boundPort`），降级 Custom Tab 时收 `code` |
| `com.openminis.app.auth.OAuthTabLauncher` | `auth/OAuthTabLauncher.kt` | 打开授权页（Custom Tab） |
| `com.openminis.app.auth.OAuthForegroundGate` | `auth/OAuthForegroundGate.kt` | `awaitForeground(tag)`：换 token 前等回到前台，避开后台网络策略 |
| `com.openminis.app.service.OAuthWaitService` | `service/OAuthWaitService.kt` | 前台服务保活（已由 `OAuthWaitKeepAlive` 驱动）。本模块**未直接调用**，见不确定点 4 |
| `com.openminis.app.mikova.accounts.AccountStore` | 同组 accounts | token 落库（复用其 Keystore 加密） |

**未重复造**：没有自建 PKCE、没有自建 loopback 服务器、没有自建 Custom Tab 启动器、没有自建 token 存储。

## 2. 集成变更清单（含代码片段）

### 2.1 路由（AppNavigation.kt）

容器页带 `serviceId` 与 `mode` 两个参数（`mode` 是 `OAuthMode` 枚举名）。在 Mibot 页面区块新增：

```kotlin
composable(
    com.openminis.app.mikova.oauth.OAuthRoutes.CONTAINER,
    arguments = listOf(
        navArgument("sid") { type = NavType.StringType },
        navArgument("mode") { type = NavType.StringType },
    ),
) { entry ->
    com.openminis.app.mikova.oauth.OAuthContainerScreen(
        serviceId = entry.arguments?.getString("sid").orEmpty(),
        mode = runCatching {
            com.openminis.app.mikova.oauth.OAuthMode.valueOf(
                entry.arguments?.getString("mode") ?: "OAUTH")
        }.getOrDefault(com.openminis.app.mikova.oauth.OAuthMode.OAUTH),
        onBack = { navController.safePopBackStack() },
    )
}
```

> 需要的 import（AppNavigation.kt 里通常已有）：`androidx.navigation.NavType`、
> `androidx.navigation.navArgument`。路由由 `accounts` 模块的连接中心触发（见其 INTEGRATION.md 2.1）。

### 2.2 MinisApp 初始化

**无。** `OAuthBroker.shared(context)` 懒加载单例，内部持有 `AccountStore.shared(context)`；
无需在 `MinisApp` 做初始化。

### 2.3 ModuleRegistry（可选）

OAuth 容器是「被连接中心拉起的流程页」，不是独立能力入口，**不建议**注册为网格模块。若要注册，
与 accounts 同理：`ModuleManifest.entry` 无参，无法传 `serviceId/mode/onBack`，故仅作能力提供者：

```kotlin
ModuleRegistry.register(
    ModuleManifest(
        id = "mikova_oauth", name = "OAuth 容器", summary = "内置 OAuth 授权 / 浏览器捕获",
        icon = Icons.Outlined.VpnKey, color = MibotTokens.Amber,
        capabilities = setOf("oauth"),
        showInGrid = false,
    ),
)
```

### 2.4 AndroidManifest / 权限

**无新增权限、无新增组件。** 本模块用内置 `WebView`（容器内），回调优先被 WebViewClient 拦截，
不依赖外部 scheme；降级 Custom Tab 走 loopback `http://localhost:8899/callback`，
**不需要**在 manifest 声明 intent-filter（loopback 由 `OAuthCallbackServer` 在本机收）。

> 说明：本体的 `OAuthRedirectActivity` / deep-link 回调（`mibot://…`）是 vault/AutoKey 那套流程用的；
> 本模块默认走「内置 WebView 拦截」或「loopback」，故不新增 manifest 声明。若某 provider 只支持
> 自定义 scheme 回调，才需要按本体既有 intent-filter 模式补声明（当前内置 provider 都用 loopback，无需）。

### 2.5 依赖

**无新增 Gradle 依赖。** `android.webkit.*`（平台）、`kotlinx.coroutines`（已依赖）、
`com.openminis.app.mibot.design.*` / `com.openminis.app.ui.glass.*`、复用本体 `auth/*` 与 `vault.Pkce`。

## 3. 设计说明

- **两种模式**：`OAUTH`（标准 OAuth2 + PKCE，WebViewClient 拦截 `redirect_uri` 取 code → `exchange`
  换 token → `saveToken` 加密落库）；`BROWSER`（非标准服务的降级：可编辑地址栏登录任意站点，点
  「我已登录，捕获凭据」抓 cookie + `localStorage`，`kind="cookie"` 落库）。
- **provider 目录**：`OAuthProviders` 覆盖 7 个 OAuth 应用（Gemini/Gmail/OpenAI/Claude/Outlook/Notion/
  GitHub）。`clientId/clientSecret` 为占位 `<PH:...>`，**需在对应开发者后台创建后填入**；端点 URL 是
  公开固定的。GitHub/Notion 不用 PKCE（`usePkce=false`，换 token 带 `client_secret`）。
- **换 token 容错**：`exchange()` 用 `HttpURLConnection`（与本体 `OAuthPkce` 同款，无额外依赖），
  响应同时兼容 JSON 与 form-urlencoded（GitHub token 端点默认回 form）。
- **降级链路**：服务拒绝内置 WebView（如 Google 的 `disallowed_useragent`）时，容器页右上「在浏览器
  打开」→ `runLoopbackFlow()`：`OAuthTabLauncher` 开 Custom Tab + `OAuthCallbackServer` 收 loopback
  回调 + `OAuthForegroundGate.awaitForeground` 后换 token——与本体的 OpenAI/Claude/OpenRouter 登录
  同一套浏览器回调机制。
- **进度条用 design token**：细 accent 条按 `progress/100` 宽度绘制，不引入额外组件，保持 glass 视觉统一。

## 4. 不确定点

1. **clientId/secret 是占位**：`<PH:GOOGLE_CLIENT_ID>` 等需真实凭据才能跑通。端点、参数拼装、PKCE、
   回调识别、换 token、落库链路均已就绪；填入凭据即可用。
2. **Google / Microsoft 在内置 WebView 里可能被拒**：Google 明确禁止 WebView OAuth。已提供「在浏览器
   打开」降级（`runLoopbackFlow`）；但 Google 还要求 redirect_uri 是已注册的 loopback/https，需在
   Google Cloud Console 把 `http://localhost:8899/callback` 登记为授权重定向 URI。
3. **`OAuthWaitService` 未直接接入**：前台保活服务由 `OAuthWaitKeepAlive.begin/end` 驱动；本模块为
   控制风险未调用它（内置 WebView 全程前台，loopback 流程有 `OAuthForegroundGate` 兜底）。
   若长时间 Custom Tab 流程需要保活，可在 `runLoopbackFlow` 里补 `OAuthWaitKeepAlive.begin(...)`。
4. **`localStorage` 捕获是尽力而为**：通过 `evaluateJavascript` 取 `JSON.stringify(localStorage)` 后做
   去引号反转义（`runCatching` 兜底），站点若把 token 放 sessionStorage / cookie 之外的非常规位置可能
   取不到；cookie 走 `CookieManager` 更可靠。
5. **未做 token 自动刷新**：`refresh_token` 已随 token 一起加密保存，但刷新调用未实现（属后续工作）。
