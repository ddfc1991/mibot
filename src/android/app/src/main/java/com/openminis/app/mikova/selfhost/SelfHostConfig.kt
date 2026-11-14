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
    /** Saved connection preference only. No data or task executor consumes this flag yet. */
    val enabled: Boolean = false,
    val serverUrl: String = "",
    /** 只暴露「是否已设置」，不回读明文。 */
    val hasApiKey: Boolean = false,
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

    /** Saving a connection does not activate remote execution or migrate local data. */
    fun save(url: String, replacementKey: String? = null) {
        val normalized = EndpointPolicy.normalize(url, replacementKey?.isNotBlank() ?: state.value.hasApiKey)
        if (replacementKey != null) require(replacementKey.none { it == '\r' || it == '\n' }) { "密钥不能包含换行" }
        // Do not carry an old secret to another origin without an explicit replacement.
        val previous = state.value.serverUrl
        if (previous.isNotBlank() && previous != normalized && replacementKey == null && state.value.hasApiKey) {
            require(java.net.URI(previous).let { Triple(it.scheme, it.host, it.port) } ==
                java.net.URI(normalized).let { Triple(it.scheme, it.host, it.port) }) { "更换服务器时请重新填写密钥，原密钥不会转发到新地址" }
        }
        val edit = prefs.edit().putString(K_URL, normalized).putBoolean(K_ENABLED, false)
        if (replacementKey != null) edit.putString(K_KEY, replacementKey.trim())
        check(edit.commit()) { "配置保存未完成，请重试" }
        state.value = load()
    }

    fun setEnabled(on: Boolean) = persist { it.copy(enabled = on) }
    fun setServerUrl(url: String) = save(url)

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
        val snapshot = state.value
        SelfHostProbe.test(snapshot.serverUrl, apiKey())
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
