package com.openminis.app.mikova.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [mikova/voice] 模型管理:sherpa-onnx 模型的目录、清单、下载(带进度)与删除。
 *
 * 模型全部落到 `filesDir/models/<id>/`;已安装的判定 = 目录里有 `tokens.txt` 且至少一个 `.onnx`。
 * 下载的是 sherpa-onnx release 上的 `.tar.bz2` 模型包,用 commons-compress 解包
 * (bzip2 + tar;依赖见 INTEGRATION.md 的 build.gradle 声明)。
 *
 * URL 来源:github.com/k2-fsa/sherpa-onnx 的 Releases,模型挂在 `asr-models` / `tts-models` 两个 tag 下。
 * 资产名是 sherpa 官方文档里给 Android 用的那几个;若官方改名,改 [CATALOG] 的 url 即可。
 */
object VoiceModelManager {

    private const val TAG = "VoiceModels"
    private const val ASR_TAG = "asr-models"
    private const val TTS_TAG = "tts-models"
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    /** 模型根目录。 */
    fun modelsRoot(context: Context): File = File(context.filesDir, "models")

    fun asrModelDir(context: Context): File = File(modelsRoot(context), ASR_ID)
    fun ttsModelDir(context: Context): File = File(modelsRoot(context), TTS_ID)

    private const val ASR_ID = "streaming-zipformer-zh-en"
    private const val TTS_ID = "vits-zh-aishell3"

    /** 官方模型清单(ASR ~50MB / TTS ~110MB,见 title 里的近似大小)。 */
    val CATALOG: List<VoiceModelInfo> = listOf(
        VoiceModelInfo(
            kind = VoiceModelKind.ASR,
            id = ASR_ID,
            filename = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            url = "$BASE/$ASR_TAG/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            approxBytes = 50L * 1024 * 1024,
            title = "流式中文 ASR(zipformer 中英双语)",
            subtitle = "实时识别 · 约 50MB · 解包后含 encoder/decoder/joiner + tokens",
        ),
        VoiceModelInfo(
            kind = VoiceModelKind.TTS,
            id = TTS_ID,
            filename = "vits-icefall-zh-aishell3.tar.bz2",
            url = "$BASE/$TTS_TAG/vits-icefall-zh-aishell3.tar.bz2",
            approxBytes = 32L * 1024 * 1024,
            title = "中文多音色 TTS(vits · aishell3)",
            subtitle = "离线合成 · 174 个说话人 · 量化包约 31.6MB（解压后更大）",
        ),
    )

    fun catalogFor(kind: VoiceModelKind): List<VoiceModelInfo> = CATALOG.filter { it.kind == kind }

    /** 已安装的模型(有 tokens + onnx 的那几个)。 */
    fun installed(context: Context): List<VoiceModelInfo> = CATALOG.filter { isInstalled(context, it) }

    /** 单个模型是否已安装:目录存在、有 tokens.txt、且至少一个 .onnx。 */
    fun isInstalled(context: Context, info: VoiceModelInfo): Boolean {
        val dir = dirFor(context, info)
        if (!dir.isDirectory) return false
        val names = dir.list().orEmpty()
        val hasTokens = names.any { it.endsWith(".txt") && it.contains("tokens") }
        val hasOnnx = names.any { it.endsWith(".onnx") }
        return hasTokens && hasOnnx
    }

    fun hasAsrModel(context: Context): Boolean = CATALOG.filter { it.kind == VoiceModelKind.ASR }.any { isInstalled(context, it) }
    fun hasTtsModel(context: Context): Boolean = CATALOG.filter { it.kind == VoiceModelKind.TTS }.any { isInstalled(context, it) }

    private fun dirFor(context: Context, info: VoiceModelInfo): File = File(modelsRoot(context), info.id)
    private fun downloadDir(context: Context): File = File(modelsRoot(context), "downloads").apply { mkdirs() }

    /** 单个模型的下载/解包进度。 */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val percent: Int, val bytesSoFar: Long, val total: Long) : DownloadState
        data object Extracting : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val progress = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** 各模型 id → 进度。界面订阅这个流。 */
    val progressFlow: StateFlow<Map<String, DownloadState>> = progress.asStateFlow()

    fun stateOf(id: String): DownloadState = progress.value[id] ?: DownloadState.Idle

    private fun set(id: String, s: DownloadState) {
        progress.value = progress.value + (id to s)
    }

    /**
     * 下载 + 解包一个模型。**串行**(一次一个),失败置 Failed 并保留半成品目录供重试。
     * 已装好的直接 Done。返回最终状态。
     */
    suspend fun download(context: Context, info: VoiceModelInfo): DownloadState = withContext(Dispatchers.IO) {
        if (isInstalled(context, info)) {
            set(info.id, DownloadState.Done)
            return@withContext DownloadState.Done
        }
        val app = context.applicationContext
        val target = dirFor(app, info)
        val archive = File(downloadDir(app), info.filename)
        try {
            // 1) 下载(带 Content-Length 进度)。
            set(info.id, DownloadState.Downloading(0, 0, info.approxBytes))
            val conn = URL(info.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                set(info.id, DownloadState.Failed("HTTP ${conn.responseCode}"))
                return@withContext DownloadState.Failed("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.approxBytes
            conn.inputStream.use { input ->
                archive.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var soFar = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        soFar += n
                        val pct = if (total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            set(info.id, DownloadState.Downloading(pct, soFar, total))
                        }
                    }
                }
            }

            // 2) 解包 tar.bz2 到目标目录。
            set(info.id, DownloadState.Extracting)
            target.mkdirs()
            extractTarBz2(archive, target)

            if (isInstalled(app, info)) {
                archive.delete()
                set(info.id, DownloadState.Done)
                DownloadState.Done
            } else {
                set(info.id, DownloadState.Failed("解包后没找到 tokens + onnx,模型包结构可能变了"))
                DownloadState.Failed("解包后没找到 tokens + onnx")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download ${info.id} failed: ${t.message}")
            set(info.id, DownloadState.Failed(t.message ?: "下载失败"))
            DownloadState.Failed(t.message ?: "下载失败")
        }
    }

    /** 删掉一个已安装模型(连同它的目录)。 */
    fun delete(context: Context, info: VoiceModelInfo): Boolean {
        val dir = dirFor(context, info)
        if (!dir.exists()) return true
        val ok = dir.deleteRecursively()
        progress.value = progress.value + (info.id to DownloadState.Idle)
        return ok
    }

    /** 解包一个 tar.bz2 到 [dest];按 tar entry 逐文件落盘,目录也建。 */
    private fun extractTarBz2(archive: File, dest: File) {
        FileInputStream(archive).use { fin ->
            BZip2CompressorInputStream(fin).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val out = File(dest, entry.name).canonicalFile
                        // 防 tar 路径穿越:必须落在 dest 下。
                        if (!out.path.startsWith(dest.canonicalFile.path)) continue
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                    }
                }
            }
        }
    }
}
