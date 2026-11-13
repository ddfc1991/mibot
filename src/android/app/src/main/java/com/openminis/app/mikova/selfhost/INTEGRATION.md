# 自托管（selfhost/）— INTEGRATION.md

模块目录：`src/android/app/src/main/java/com/openminis/app/mikova/selfhost/`

| 文件 | 作用 |
|---|---|
| `SelfHostConfig.kt` | 配置读写（EncryptedSharedPreferences 经本体 `EncryptedPrefsFactory`）：本地/远端开关、服务器地址、API Key（密文落盘、不回读明文）、`testConnection()` 测连 |
| `SelfHostScreen.kt` | 自托管页（glass）：数据自托管开关（本地↔远端）、服务器地址 / API Key 输入、`测试连接` + 状态 chip、`一键部署指南` 折叠卡（docker 命令 + 一键复制） |

只使用本体 glass 组件（`com.openminis.app.mibot.design.*` + `com.openminis.app.ui.glass.Frost`），
未改任何共享文件。

---

## 1. 与本体现有页面的关系

**无既有「自托管」页可扩展**：探查未见 `selfhost|自托管` 的独立配置页。本体的 `mibot/vault`（账号台账）、
`mibot/sandbox`（沙箱）是别的域；自托管是「把 Mibot 的数据/能力指向你自己的服务器」的配置域，属新增。

**加密方式按任务要求对齐本体**：任务给的选项是「EncryptedSharedPreferences 或参照 `vault/VaultStore.kt`」。
本模块选 **EncryptedSharedPreferences**，并复用本体已有的 `com.openminis.app.util.EncryptedPrefsFactory`
（它已解决 Tink keyset 被 system backup 换 key 后 `AEADBadTagException` 的自愈问题），
而不是裸调 `EncryptedSharedPreferences.create`——既满足要求，又继承本体的健壮性。

## 2. 集成变更清单（含代码片段）

### 2.1 路由（AppNavigation.kt）

```kotlin
composable(com.openminis.app.mikova.selfhost.SelfHostRoutes.SCREEN) {
    com.openminis.app.mikova.selfhost.SelfHostScreen(
        onBack = { navController.safePopBackStack() },
    )
}
```

### 2.2 设置页入口（可选，MibotSettingsScreen.kt）

`MibotSettingsActions` 加 `val selfHost: () -> Unit = {}`，在「运行时/沙箱」或「账号与服务」卡里加一行：

```kotlin
MibotRow(t("数据自托管", "Self-hosting"), t("本地 vs 远端、服务器地址、测连、部署指南", "…"),
    Icons.Outlined.Storage, MibotTokens.Steel, onClick = a.selfHost, showDivider = true)
```

AppNavigation 构造 `MibotSettingsActions(...)` 处补：

```kotlin
selfHost = go(com.openminis.app.mikova.selfhost.SelfHostRoutes.SCREEN),
```

### 2.3 MinisApp 初始化

**无。** `SelfHostConfig.shared(context)` 懒加载单例（内部 `EncryptedPrefsFactory.safeCreate` 打开
`mikova_selfhost` 加密 prefs，进程内缓存），无需 `MinisApp.onCreate` 初始化。

### 2.4 ModuleRegistry（可选）

页面需要 `onBack` 回调，`ModuleManifest.entry` 无参；同 accounts，建议走 AppNavigation 路由。
若要注册为能力提供者（不进网格）：

```kotlin
ModuleRegistry.register(
    ModuleManifest(
        id = "mikova_selfhost", name = "自托管", summary = "本地 vs 远端、服务器配置与测连",
        icon = Icons.Outlined.Storage, color = MibotTokens.Steel,
        capabilities = setOf("selfhost"),
        showInGrid = false,
    ),
)
```

### 2.5 AndroidManifest / 权限

**无新增权限。** `testConnection()` 用 `HttpURLConnection` 访问用户填写的 https 地址，走普通 INTERNET
权限（app 已有）。加密 prefs 由 `EncryptedPrefsFactory` 管理，不新增组件/权限。

### 2.6 依赖

**无新增 Gradle 依赖。**
- `androidx.security:security-crypto`（已依赖，`1.1.0-alpha06`）——经 `EncryptedPrefsFactory` 间接用。
- `com.openminis.app.util.EncryptedPrefsFactory`（本体已有）。
- `kotlinx.coroutines`、`com.openminis.app.mibot.design.*`、`com.openminis.app.ui.glass.*`（均已有）。

## 3. 设计说明

- **密钥不回读明文**：`SelfHostState` 只暴露 `hasApiKey: Boolean`，`apiKey()` 仅供 `testConnection()`
  本地读取并放进 `Authorization: Bearer`；UI 上 API Key 输入框在已保存时显示占位「已保存，留空保持不变」，
  留空提交不覆盖旧值。
- **测连语义**：先 `setServerUrl`（并按需 `setApiKey`）落盘，再 `GET <server>`（`Accept: application/json`，
  带 Bearer Key 若已设），按 HTTP 状态码 + 往返耗时给结论；`http(s)://` 前缀自动补全。8s 超时，
  异常归一成 `SelfHostTestResult(ok=false, message=…)`。
- **一键部署指南**：折叠卡（`MibotCard` 内 chevron 旋转 `Modifier.rotate`），展开显示 `DEPLOY_DOCKER`
  等宽命令块（`FrostedSurface` + `FontFamily.Monospace`），`复制命令` 用 `LocalClipboardManager`。
  命令里密钥为占位 `<PH:YOUR_API_KEY>`，提示用户替换、默认监听 8899。
- **开关只改数据落点、不改权限模型**：`enabled` 切本地/远端；敏感操作确认仍按本体权限与自动化设置，
  这点在页面 footnote 明说，避免「开了自托管就绕过确认」的误解。

## 4. 不确定点

1. **服务端协议未定**：`testConnection()` 目前是通用「GET / 带 Bearer、看 HTTP 状态 + 延迟」，
   不假设具体 API 形状；真正的自托管服务端契约（端点、鉴权方式、数据同步协议）尚未定，测连只验证
   「可达 + 鉴权通过」。
2. **仅配置、未接线**：本模块只提供配置与测连 UI；把 Mibot 的实际数据/能力流量切到该服务器（`enabled`
   的下游生效逻辑）需宿主在各功能点读取 `SelfHostConfig.shared(ctx).config` 后分流——这属集成方后续工作，
   本模块未改动任何现有数据通路。
3. **`EncryptedPrefsFactory` 的同模块可见性**：它是 `public object`，可直接 import；但仓库里另有
   `com.openminis.app.util.n` 的混淆残留引用（历史遗留，非本模块引入）。本模块统一引用真实类名
   `EncryptedPrefsFactory`。
4. **部署命令的镜像/端口是示例**：`ghcr.io/openminis/mibot-server:latest` 与 8899 端口为占位示例，
   需按真实发布的镜像与端口调整。
