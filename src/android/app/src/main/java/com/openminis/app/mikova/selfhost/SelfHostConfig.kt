package com.openminis.app.mikova.selfhost

import android.content.Context
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 自托管配置（本地 vs 远端 / 服务器地址 / API Key）。 */
data class SelfHostState(
    /** false = 数据只存本机；true = 把 Mibot 的数据/能力指向你自己的服务器。 */
    val enabled: Boolean = false,
    val serverUrl: String = "",
    /** 只暴露「是否已设置」，不回读明文。 */
    val hasApiKey: Boolean = false,
)

/** 「测试连接」的结果。 */
data class SelfHostTestResult(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long,
)

/**
 * 自托管配置读写。凭据用本体的 [EncryptedPrefsFactory]（EncryptedSharedPreferences +
 * Android Keystore MasterKey，自带 AEADBadTag 自愈），API Key 落地即密文、不回读明文。
 *
 * 读写方式与 vault / 连接中心一致：进程内 [StateFlow] 观察当前值，写路径先更新内存再落盘。
 */
class SelfHostConfig(context: Context) {

    private val prefs = EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
    private val state = MutableStateFlow(load())

    val config: StateFlow<SelfHostState> = state.asStateFlow()

    private fun load(): SelfHostState = SelfHostState(
        enabled = prefs.getBoolean(K_ENABLED, false),
        serverUrl = prefs.getString(K_URL, "").orEmpty(),
        hasApiKey = !prefs.getString(K_KEY, "").isNullOrEmpty(),
    )

    private fun persist(transform: (SelfHostState) -> SelfHostState) {
        val next = transform(state.value)
        state.value = next
        prefs.edit()
            .putBoolean(K_ENABLED, next.enabled)
            .putString(K_URL, next.serverUrl)
            .apply()
    }

    /** 数据自托管开关（本地 ↔ 远端）。 */
    fun setEnabled(on: Boolean) = persist { it.copy(enabled = on) }

    fun setServerUrl(url: String) = persist { it.copy(serverUrl = url.trim()) }

    /** 写入即加密落盘；只更新「是否已设置」标志，不把明文放进 StateFlow。 */
    fun setApiKey(key: String) {
        prefs.edit().putString(K_KEY, key.trim()).apply()
        state.value = state.value.copy(hasApiKey = key.isNotBlank())
    }

    fun clearApiKey() {
        prefs.edit().remove(K_KEY).apply()
        state.value = state.value.copy(hasApiKey = false)
    }

    /** 仅供「测试连接」本地读取，不外传、不展示。 */
    fun apiKey(): String = prefs.getString(K_KEY, "").orEmpty()

    /**
     * 测试连接：GET [serverUrl]（带 Bearer Key，若已设置），按 HTTP 状态与耗时给出结论。
     * 纯 HttpURLConnection，无额外依赖。
     */
    suspend fun testConnection(): SelfHostTestResult = withContext(Dispatchers.IO) {
        val raw = state.value.serverUrl.trim()
        if (raw.isBlank()) return@withContext SelfHostTestResult(false, "请先填写服务器地址", 0)
        val full = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val t0 = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(full).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                val key = apiKey()
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - t0
            if (code in 200..299) {
                SelfHostTestResult(true, "连接成功 · HTTP $code", ms)
            } else {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(120)
                SelfHostTestResult(false, "HTTP $code $body".trim(), ms)
            }
        } catch (e: Exception) {
            SelfHostTestResult(false, "${e.javaClass.simpleName}: ${e.message}".take(160), System.currentTimeMillis() - t0)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val FILE = "mikova_selfhost"
        private const val K_ENABLED = "enabled"
        private const val K_URL = "server_url"
        private const val K_KEY = "api_key"

        /** 进程内共享一份（同一个加密 prefs 文件）。 */
        @Volatile private var shared: SelfHostConfig? = null
        fun shared(context: Context): SelfHostConfig = shared ?: synchronized(this) {
            shared ?: SelfHostConfig(context).also { shared = it }
        }
    }
}
