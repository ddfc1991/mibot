# INTEGRATION.md — 虚拟屏多开模块 (`com.openminis.app.mikova.vd`)

> 本模块**不改任何共享文件**（`AppNavigation` / `MinisApp` / `strings.xml` / `AndroidManifest.xml` / `build.gradle.kts`）。
> 全部复用 Mika 的 `com.mibot.cap.phone` 引擎（P0 后唯一的手机栈）。下面列出接进 App 的集成变更。

## 1. 文件清单

```
src/android/app/src/main/java/com/openminis/app/mikova/vd/
├── MikovaDisplayManager.kt  # 多虚拟屏池（上限 3，低内存自动降 1）；shell-backed / local 两后端；displayId 池+释放/复用
├── MikovaVdController.kt    # 单屏操作：输入注入（a11y 跨屏手势→shell input -d 兜底）、启动 App、截屏、跑 shell 命令
└── VdPoolScreen.kt          # glass 多屏管理页：屏幕卡片（displayId+任务名+缩略截图+状态）、新建/关闭/切焦点、启动应用到焦点屏
```

## 2. 路由（改 `AppNavigation.kt`，本模块不改）

```kotlin
VdPoolScreen(
    onBack: () -> Unit,
    manager: MikovaDisplayManager,      // 进程内一份，见 §4
    controller: MikovaVdController,     // 进程内一份
)
```

建议路由：`mikova/vd -> VdPoolScreen(...)`。`manager` / `controller` 建议挂在
`MinisApp`（或一个 `remember` 的 ViewModel）里做**单例**——虚拟屏是进程级资源，跨页面共用一个池：

```kotlin
val vdManager by lazy { MikovaDisplayManager(applicationContext) }
val vdController by lazy { MikovaVdController(applicationContext, vdManager) }
```

## 3. Manifest / a11y 配置（**无需改动**）

跨屏 a11y 手势依赖的配置**本体已具备**，无清单改动：

- `AndroidManifest.xml` 已注册 `com.openminis.app.accessibility.MinisAccessibilityService`
  （它是 `com.mibot.cap.phone.a11y.ScreenAccessibility` 的子类），带
  `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`。
- `res/xml/minis_accessibility_service.xml` 已声明：
  `canPerformGestures="true"`、`canRetrieveWindowContent="true"`、`canTakeScreenshot="true"`、
  `accessibilityFlags="flagDefault|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows|flagReportViewIds"`。
  —— `flagRetrieveInteractiveWindows` + `canPerformGestures` 正是跨屏读窗口 + 下发 `GestureDescription`
  （`Builder.setDisplayId`，API 30+）所需。
- 无障碍服务**须用户在系统设置里手动开启**（App 不能编程启动，Android 禁止）。未开启时
  `ScreenAccessibility.instance.value == null` → `MikovaVdController` 自动降级 shell `input -d`（若 shell 通），
  或提示不可用。
- **无新权限**：注入/启动/截屏的特权操作都在内置助手进程（shell uid 2000）里执行，经无线调试配对授权，
  走 Mika 现有的 `PhoneServices` / `IMikaShell` 通道。

## 4. **关键集成：把 `PhoneServices.Owner` 接进 `MinisApp`**（唯一影响共享文件的一步）

可信虚拟屏（能跑第三方 App、有独立焦点）必须由**内置助手进程（shell uid 2000）**经
`IMikaShell.createDisplay` 创建。这条链路的入口是 `com.mibot.cap.phone.PhoneServices`，
它通过 `PhoneServices.Owner` 接口从 `Application` 取进程内那一份共享服务：

```kotlin
// com.mibot.cap.phone.PhoneServices
interface Owner { val phone: PhoneServices }
fun of(context: Context): PhoneServices =
    (context.applicationContext as? Owner)?.phone
        ?: error("the Application must implement PhoneServices.Owner ...")
```

**当前 `MinisApp` 没实现 `Owner`**（本仓是 port 到一半的状态：`com.mibot.cap.phone.*` 代码都在，
但 `Application` 没接）。未接时 `PhoneServices.of()` 抛错 → `MikovaDisplayManager.acquireShell()`
`runCatching` 吃掉 → **自动退到 local 后端**（`DisplayManager.createVirtualDisplay` own-content 屏），
不崩，但只能渲染本应用内容、不能 `am start --display` 跑第三方 App、没有跨屏焦点。
界面上会显示「本进程屏 / 无焦点·不能跑第三方 App」chip，Footnote 指向本文件。

集成方补一行即可启用可信屏（改 `MinisApp.kt`，**由集成方落，非本模块**）：

```kotlin
import com.mibot.cap.phone.PhoneServices

class MinisApp : Application(), /* 现有接口…, */ com.mibot.cap.phone.PhoneServices.Owner {
    override val phone: PhoneServices by lazy { PhoneServices(this) }
}
```

另外，可信屏还需**内置助手已配对**（无线调试 / 已 root 起助手），`IMikaShell` 才连得上
（`MikovaDisplayManager.shellAvailable()` 为 true）。未配对时同样是 local 后端。

## 5. 依赖（**无需改动**）

- 不引入任何新第三方库。复用 `com.mibot.cap.phone.*`（AgentDisplay / ShellBinding / ScreenAccessibility /
  ShellService / IMikaShell / PhoneServices）与 `com.mibot.task.ParallelCapability`，全部已在 app 模块内。
- a11y 手势、ImageReader、VirtualDisplay、`input`/`am start`/`screencap` 均为 Android SDK 自带。

## 6. 注入可行性结论（详见交付报告）

- **a11y 手势支持跨屏**：`GestureDescription.Builder.setDisplayId(displayId)` 是 API 30+ 公开 API，
  本仓 `ScreenAccessibility.gesture(displayId, path, durationMs)` 已用它对 Agent 虚拟屏下发单指手势。
  → `MikovaVdController.tap/swipe` **首选 a11y 跨屏手势**。
- **兜底 shell**：a11y 未连时用 `IMikaShell.runSystemCommand(displayId, "input -d <id> …")`
  （复用 cap.phone 的 shell 通道）。按键一律走 shell（`injectKey` / `input keyevent`，a11y 不发按键）。
- **截屏**：首选屏自身的 ImageReader 回流帧（不经系统截屏、不受防截屏影响），无帧时 `screencap -d <id>`。

## 7. 自查

- 括号闭合：已用脚本核（`vd/*.kt` 全部 OK）。
- import 真实性（`rg` 验证均存在）：
  - `com.mibot.cap.phone.display.AgentDisplay`、`com.mibot.cap.phone.shell.ShellBinding`、
    `com.mibot.cap.phone.shell.ShellService`、`com.mibot.cap.phone.a11y.ScreenAccessibility`、
    `com.mibot.shell.IMikaShell`、`com.mibot.cap.phone.PhoneServices`、`com.mibot.task.ParallelCapability`。
  - `com.openminis.app.mibot.design.*` 与 `com.openminis.app.ui.glass.*` 同 voice 模块，均验证。
  - `START_OK`/`START_ERROR` 取自 `ShellService`（不是 `IMikaShell`——AIDL 里没定义这俩常量）。
