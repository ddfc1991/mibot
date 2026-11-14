package com.openminis.app.mikova.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update
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
 * 模型落到 `filesDir/models/<id>/`；按模型类型校验完整权重/词表/词典，支持官方包的顶层目录。
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

    fun asrModelDir(context: Context): File = resolveModelDir(File(modelsRoot(context), ASR_ID), VoiceModelKind.ASR)
    fun ttsModelDir(context: Context): File = resolveModelDir(File(modelsRoot(context), TTS_ID), VoiceModelKind.TTS)

    private const val ASR_ID = "streaming-zipformer-zh-en"
    private const val TTS_ID = "vits-zh-aishell3"

    /** Official Release asset sizes verified 2026-11-14; compressed bytes, not installed footprint. */
    val CATALOG: List<VoiceModelInfo> = listOf(
        VoiceModelInfo(
            kind = VoiceModelKind.ASR,
            id = ASR_ID,
            filename = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            url = "$BASE/$ASR_TAG/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            approxBytes = 511_274_346L,
            title = "流式中文 ASR(zipformer 中英双语)",
            subtitle = "实时识别 · 下载约 511.3MB · 解包后更大",
        ),
        VoiceModelInfo(
            kind = VoiceModelKind.TTS,
            id = TTS_ID,
            filename = "vits-icefall-zh-aishell3.tar.bz2",
            url = "$BASE/$TTS_TAG/vits-icefall-zh-aishell3.tar.bz2",
            approxBytes = 31_559_701L,
            title = "中文多音色 TTS(vits · aishell3)",
            subtitle = "离线合成 · 下载约 31.6MB · 音色数以加载模型后为准",
        ),
    )

    fun catalogFor(kind: VoiceModelKind): List<VoiceModelInfo> = CATALOG.filter { it.kind == kind }

    fun installed(context: Context): List<VoiceModelInfo> = CATALOG.filter { isInstalled(context, it) }

    fun isInstalled(context: Context, info: VoiceModelInfo): Boolean =
        validModelDir(resolveModelDir(dirFor(context, info), info.kind), info.kind)

    internal fun validModelDir(dir: File, kind: VoiceModelKind): Boolean {
        fun present(name: String) = File(dir, name).let { it.isFile && it.length() > 0 }
        if (!present("tokens.txt")) return false
        return when (kind) {
            VoiceModelKind.ASR -> listOf("encoder", "decoder", "joiner").all { prefix ->
                dir.listFiles().orEmpty().any { it.isFile && it.length() > 0 && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            }
            VoiceModelKind.TTS -> present("model.onnx") && present("lexicon.txt")
        }
    }

    internal fun resolveModelDir(root: File, kind: VoiceModelKind): File {
        if (validModelDir(root, kind)) return root
        return root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
            .firstOrNull { validModelDir(it, kind) } ?: root
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
        progress.update { it + (id to s) }
    }

    private val downloadMutex = Mutex()

    /** Stage extraction before publishing. Cancellation never marks a partial model installed. */
    suspend fun download(context: Context, info: VoiceModelInfo): DownloadState = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (isInstalled(context, info)) {
                set(info.id, DownloadState.Done)
                return@withLock DownloadState.Done
            }
            val app = context.applicationContext
            val target = dirFor(app, info)
            val archive = File(downloadDir(app), info.filename)
            val staging = File(modelsRoot(app), "${info.id}.staging")
            var connection: HttpURLConnection? = null
            try {
                currentCoroutineContext().ensureActive()
                set(info.id, DownloadState.Downloading(0, 0, info.approxBytes))
                val conn = URL(info.url).openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.connect()
                check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                val declaredLength = conn.contentLengthLong
                val total = declaredLength.takeIf { it > 0 } ?: info.approxBytes
                conn.inputStream.use { input ->
                    archive.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var soFar = 0L
                        var lastPct = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            soFar += n
                            val pct = if (total > 0) (soFar * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                set(info.id, DownloadState.Downloading(pct, soFar, total))
                            }
                        }
                        check(declaredLength <= 0 || declaredLength == soFar) { "下载不完整，请重试" }
                    }
                }
                currentCoroutineContext().ensureActive()
                set(info.id, DownloadState.Extracting)
                staging.deleteRecursively()
                check(staging.mkdirs()) { "Cannot create staging directory" }
                extractTarBz2(archive, staging)
                check(validModelDir(resolveModelDir(staging, info.kind), info.kind)) { "模型包缺少完整权重、tokens 或词典" }
                currentCoroutineContext().ensureActive()
                check(!target.exists() || target.deleteRecursively()) { "Cannot replace incomplete model" }
                check(staging.renameTo(target)) { "Cannot install model" }
                set(info.id, DownloadState.Done)
                DownloadState.Done
            } catch (e: CancellationException) {
                set(info.id, DownloadState.Idle)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download ${info.id} failed", e)
                DownloadState.Failed(e.message ?: "下载失败").also { set(info.id, it) }
            } finally {
                connection?.disconnect()
                staging.deleteRecursively()
                archive.delete()
            }
        }
    }

    /** 删掉一个已安装模型(连同它的目录)。 */
    fun delete(context: Context, info: VoiceModelInfo): Boolean {
        val dir = dirFor(context, info)
        if (!dir.exists()) return true
        val ok = dir.deleteRecursively()
        if (ok) set(info.id, DownloadState.Idle)
        return ok
    }

    /** 解包一个 tar.bz2 到 [dest];按 tar entry 逐文件落盘,目录也建。 */
    private suspend fun extractTarBz2(archive: File, dest: File) {
        val root = dest.canonicalFile
        FileInputStream(archive).use { fin ->
            BZip2CompressorInputStream(fin).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val out = File(dest, entry.name).canonicalFile
                        require(out == root || out.path.startsWith(root.path + File.separator)) { "Unsafe archive path" }
                        require(!entry.isSymbolicLink && !entry.isLink) { "Archive links are unsupported" }
                        if (entry.isDirectory) {
                            check(out.isDirectory || out.mkdirs()) { "Cannot create model directory" }
                        } else {
                            require(entry.isFile && out != root) { "Unsupported archive entry" }
                            out.parentFile?.mkdirs()
                            out.outputStream().use { output ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
