# INTEGRATION.md — 语音 sherpa-onnx 模块 (`com.openminis.app.mikova.voice`)

> 本模块**不改任何共享文件**（`AppNavigation` / `MinisApp` / `strings.xml` / `AndroidManifest.xml` / `build.gradle.kts`）。
> 下面列出把它接进 App 需要的集成变更，由集成方（P0 / 路由层）落。代码本体已就位、自包含。

## 1. 文件清单

```
src/android/app/src/main/java/com/openminis/app/mikova/voice/
├── VoiceEngine.kt          # 接口 VoiceEngine + VoiceEngineFactory（降级链：sherpa→平台，一行切换）+ 模型/引擎枚举
├── SherpaVoiceEngine.kt    # sherpa-onnx 离线 ASR+TTS；反射 JNI 层 SherpaJni + WavUtil（编译期不依赖 AAR）
├── PlatformVoiceEngine.kt  # 平台兜底：SpeechRecognizer + TextToSpeech
├── VoiceModelManager.kt    # 模型目录/下载/解包/删除，进度流；依赖 commons-compress 解 tar.bz2
├── VoiceScreen.kt          # glass 语音页：大麦克风（按住说/松手转）+ 实时文本流 + TTS + 引擎状态 chip
└── VoiceSettingsScreen.kt  # glass 设置页：引擎选择（sherpa 优先/平台兜底）+ 模型管理（下载/进度/删除）
```

## 2. 路由（改 `AppNavigation.kt`，本模块不改）

两个 composable，签名如下：

```kotlin
VoiceScreen(
    onBack: () -> Unit,
    onSend: (String) -> Unit,          // 把识别文本交给会话（同 DestinyVoiceScreen 的 onSend）
    prefer: VoiceEngineId = VoiceEngineId.SHERPA,
    onOpenSettings: () -> Unit = {},    // 跳 VoiceSettingsScreen
)
VoiceSettingsScreen(
    onBack: () -> Unit,
    prefer: VoiceEngineId,              // 当前偏好（从偏好读）
    onPreferChange: (VoiceEngineId) -> Unit,  // 回写偏好
)
```

建议路由（名字自定，风格跟现有 `mibot/voice/DestinyVoiceScreen` 一致）：

```
mikova/voice        -> VoiceScreen(onOpenSettings = { nav.navigate("mikova/voice/settings") })
mikova/voice/settings -> VoiceSettingsScreen(prefer = prefs, onPreferChange = { prefs = it })
```

`prefer` 建议存进现有偏好体系（如 `MibotPrefs` / DataStore），键名例如 `mikova.voice.engine`
（取值 `"SHERPA"` / `"PLATFORM"`）。本模块不直接读写偏好文件，避免碰共享存储。

## 3. Manifest / 权限（**无需改动**）

- `RECORD_AUDIO` **已声明**（`AndroidManifest.xml` 已有 `<uses-permission android:name="android.permission.RECORD_AUDIO" />`）。
  `VoiceScreen` 用 `rememberLauncherForActivityResult(RequestPermission())` 运行时请求，不依赖清单新增。
- 无新 Service / Receiver / Provider。离线识别全部在应用进程内完成，不注册前台服务。
- `Internet` 权限已声明（下载模型用；识别/合成本身离线不出网）。

## 4. 依赖（唯一需要改 `build.gradle.kts` 的地方）

### 4.1 sherpa-onnx AAR（离线引擎）

**jitpack 上没有** `com.k2fsa.sherpa:onnx` 这个坐标（sherpa-onnx 不经 jitpack 发布）。两条路：

- **推荐（本地 AAR）**：从 GitHub Releases 拉 Android AAR 放进 `app/libs/`，下载 URL 格式：
  ```
  https://github.com/k2fsa/sherpa-onnx/releases/download/v<版本>/sherpa-onnx-v<版本>-android.aar
  # 例：v1.10.30 -> https://github.com/k2fsa/sherpa-onnx/releases/download/v1.10.30/sherpa-onnx-v1.10.30-android.aar
  ```
  然后 `app/build.gradle.kts` 加：
  ```kotlin
  dependencies {
      implementation(files("libs/sherpa-onnx-v<版本>-android.aar"))
  }
  ```
  AAR 里带 `libsherpa-onnx-jni.so`（各 ABI）+ Kotlin API（`com.k2fsa.sherpa.onnx.*`）。
- **备选（自带 so + Kotlin 源）**：只把各 ABI 的 `libsherpa-onnx-jni.so` 放 `app/src/main/jniLibs/<abi>/`，
  再把官方 `kotlin-api` 那几个 `.kt`（`OnlineRecognizer` / `OfflineRecognizer` / `OfflineTts` 及其 Config）拷进
  `com.k2fsa.sherpa.onnx` 包。

**关键设计**：`SherpaVoiceEngine.kt` 对 `com.k2fsa.sherpa.onnx.*` **全部走反射**（见 `SherpaJni`），
所以**没放 AAR 时本模块照样编译**——只是 `SherpaVoiceEngine.isUsable()` 返回 false，
`VoiceEngineFactory.build()` 自动落回 `PlatformVoiceEngine`，界面顶部显示「已降级」chip。
放好 AAR 后，反射即等价于普通调用，无需改代码。

### 4.2 commons-compress（解模型包）

`VoiceModelManager` 用 commons-compress 解 sherpa 的 `.tar.bz2` 模型包。版本目录里已有
（`libs.versions.toml` → `commons-compress = { module = "org.apache.commons:commons-compress", version.ref = "commonsCompress" }`），
但当前只在 `core` 以 `implementation` 引入（不外露给 app）。需在 **`app/build.gradle.kts`** 补一行：

```kotlin
dependencies {
    implementation(libs.commons.compress)
}
```

## 5. 模型下载 URL 清单（`VoiceModelManager.CATALOG`）

首次使用在 `filesDir/models/<id>/` 下载解包，全部来自 k2-fsa/sherpa-onnx 官方 Releases：

| 用途 | id | 文件 | URL | 近似大小 |
|---|---|---|---|---|
| 流式中文 ASR（zipformer 中英双语） | `streaming-zipformer-zh-en` | `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2` | ~50MB |
| 中文 TTS（vits · aishell3） | `vits-zh-aishell3` | `vits-zh-aishell3-icefall.tar.bz2` | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-aishell3-icefall.tar.bz2` | ~110MB |

> 资产名挂在 `asr-models` / `tts-models` 两个 release tag 下（sherpa 官方文档给 Android 用的那几个）。
> 若官方改名，改 `VoiceModelManager.CATALOG` 的 `url` 字段即可。解包后目录需含 `tokens.txt` + 至少一个 `.onnx`，
> 这也是 `isInstalled` 的判定。

## 6. 降级链（接口保证一行切换）

```
VoiceEngineFactory.build(prefer, ctx):
    prefer==SHERPA 且 SherpaVoiceEngine.isUsable(ctx)  ->  SherpaVoiceEngine   // 离线
    否则                                                ->  PlatformVoiceEngine  // 系统
```

`isUsable = System.loadLibrary("sherpa-onnx-jni") 成功 && 已下载 ASR 模型`。
so 加载失败 / 模型缺失 / 反射目标类不在 → 一律落平台，`VoiceEngineFactory.fallbackNotice` 给出原因。
调用方（`VoiceScreen`）只持有 `VoiceEngine`，换实现不改一行。

## 7. 自查

- 括号闭合：已用脚本核（全部 `voice/*.kt` OK）。
- import 真实性：`com.openminis.app.mibot.design.*`（MibotScaffold/MibotCard/MibotButton/MibotRadioRow/StatusChip/Glyph/LineIcons.Mic·Volume2·AudioLines·Settings/MibotTokens/MibotType/MibotSpace/MibotIcon/ChipKind/MibotFootnote/MibotSectionHeader）
  与 `com.openminis.app.ui.glass.*`（frosted/FrostLevel/LocalGlassBottomInset）均 `rg` 验证存在。
- sherpa 类名一律反射，无不可解析 import；commons-compress 为真实第三方坐标（已在 catalog）。
