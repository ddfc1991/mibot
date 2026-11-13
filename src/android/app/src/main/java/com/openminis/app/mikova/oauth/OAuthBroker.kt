package com.openminis.app.mikova.oauth

import android.content.Context
import android.net.Uri
import com.openminis.app.auth.OAuthCallbackServer
import com.openminis.app.auth.OAuthForegroundGate
import com.openminis.app.auth.OAuthTabLauncher
import com.openminis.app.mibot.vault.Pkce
import com.openminis.app.mikova.accounts.AccountStore
import com.openminis.app.mikova.accounts.ConnectedAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * 一个 OAuth 服务的接入描述。clientId / clientSecret 多为应用侧凭据，需在
 * 各服务的开发者后台创建后填入（占位 `<PH:...>`，见 INTEGRATION.md）；其余端点是
 * 公开的、固定的。
 *
 * [redirectUri] 统一用 loopback（`http://localhost:8899/callback`）：
 *   - 内置 WebView 容器：WebViewClient 拦截这个回调 URL，取出 code（不真正联网）。
 *   - 降级 Custom Tab：[OAuthCallbackServer] 在本机起这个端口接收回调。
 */
data class OAuthProvider(
    val id: String,
    val name: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val scopes: String,
    val redirectUri: String = "http://localhost:8899/callback",
    /** PKCE（推荐）。false 则用 state + clientSecret 的传统流程。 */
    val usePkce: Boolean = true,
    /** 非 PKCE 服务（GitHub / Notion 等）换 token 时需要的应用密钥。 */
    val clientSecret: String? = null,
)

/** 内置 OAuth provider 目录（键 = AppCatalog 的 serviceId）。 */
object OAuthProviders {
    val all: Map<String, OAuthProvider> = listOf(
        OAuthProvider("gemini", "Gemini",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "<PH:GOOGLE_CLIENT_ID>",
            "https://www.googleapis.com/auth/cloud-platform openid email profile"),
        OAuthProvider("gmail", "Gmail",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "<PH:GOOGLE_CLIENT_ID>",
            "https://mail.google.com/ openid email profile"),
        OAuthProvider("openai", "OpenAI",
            "https://auth.openai.com/oauth/authorize",
            "https://auth.openai.com/oauth/token",
            "<PH:OPENAI_CLIENT_ID>",
            "openid profile email",
            clientSecret = "<PH:OPENAI_CLIENT_SECRET>"),
        OAuthProvider("claude", "Claude",
            "https://claude.ai/oauth/authorize",
            "https://claude.ai/v1/oauth/token",
            "<PH:CLAUDE_CLIENT_ID>",
            "user:profile"),
        OAuthProvider("outlook", "Outlook",
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            "<PH:MS_CLIENT_ID>",
            "openid email profile Mail.Read Mail.Send offline_access"),
        OAuthProvider("notion", "Notion",
            "https://api.notion.com/v1/oauth/authorize",
            "https://api.notion.com/v1/oauth/token",
            "<PH:NOTION_CLIENT_ID>",
            "",
            usePkce = false,
            clientSecret = "<PH:NOTION_CLIENT_SECRET>"),
        OAuthProvider("github", "GitHub",
            "https://github.com/login/oauth/authorize",
            "https://github.com/login/oauth/access_token",
            "<PH:GITHUB_CLIENT_ID>",
            "repo user:email",
            usePkce = false,
            clientSecret = "<PH:GITHUB_CLIENT_SECRET>"),
    ).associateBy { it.id }

    fun get(serviceId: String): OAuthProvider? = all[serviceId]
}

/** 一次 token 交换的结果。 */
data class TokenResult(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long = 0L,
    val scope: String = "",
)

/**
 * OAuth 编排器（连接域 oauth 模块的核心）。
 *
 * 只做三件事，其它都复用本体已有的 OAuth 基础设施（不重复造轮子）：
 *   1. 拼 authorize URL（标准 OAuth2 参数 + [Pkce] 的 S256 challenge）。
 *   2. 识别回调 URL 里的 code / error（复用 [Pkce.parseCallback]）。
 *   3. 换 token 并把结果加密存进 [AccountStore]（复用本体 Keystore AES-GCM）。
 *
 * 交互载体由调用方选：
 *   - 内置 WebView 容器（默认，见 OAuthContainerScreen）：前台完成，回调被 WebViewClient 拦截。
 *   - 降级 Custom Tab（[runLoopbackFlow]）：复用 [OAuthTabLauncher] + [OAuthCallbackServer] +
 *     [OAuthForegroundGate]，与本体的 OpenRouter/OpenAI/Claude 登录走同一套浏览器回调机制。
 */
class OAuthBroker(private val context: Context, private val store: AccountStore) {

    class OAuthError(msg: String) : Exception(msg)

    // ------------------------------------------------------------- 1. authorize URL

    /** 生成 PKCE verifier，并返回拼好的授权 URL。verifier 由调用方留存用于换 token。 */
    fun beginAuthorize(provider: OAuthProvider): Pair<String, String> {
        val verifier = Pkce.verifier(ByteArray(64).also { SecureRandom().nextBytes(it) })
        val state = Pkce.verifier(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val url = buildString {
            append(provider.authorizeUrl)
            append(if ('?' in provider.authorizeUrl) '&' else '?')
            append("client_id=").append(enc(provider.clientId))
            append("&redirect_uri=").append(enc(provider.redirectUri))
            append("&response_type=code")
            if (provider.scopes.isNotBlank()) append("&scope=").append(enc(provider.scopes))
            append("&state=").append(enc(state))
            if (provider.usePkce) {
                append("&code_challenge=").append(enc(Pkce.challengeS256(verifier)))
                append("&code_challenge_method=S256")
            }
            append("&access_type=offline") // Google：请求 refresh_token
        }
        return url to verifier
    }

    // ------------------------------------------------------------- 2. 回调识别

    /** [url] 是本 provider 的回调吗？是则解析出 code / state / error，否则 null。 */
    fun matchCallback(provider: OAuthProvider, url: String?): Pkce.Callback? {
        if (url.isNullOrBlank()) return null
        // 只认回调 host（loopback 或自定义 scheme），其余一律放行给 WebView 正常加载。
        return Pkce.parseCallback(url, provider.redirectUri)
    }

    // ------------------------------------------------------------- 3. 换 token + 落库

    /** 用 code 换 token（HttpURLConnection，与本体 OAuthPkce 同款，无额外依赖）。 */
    suspend fun exchange(provider: OAuthProvider, code: String, codeVerifier: String?): TokenResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", provider.redirectUri)
                put("client_id", provider.clientId)
                if (provider.usePkce && !codeVerifier.isNullOrBlank()) put("code_verifier", codeVerifier)
                if (!provider.clientSecret.isNullOrBlank()) put("client_secret", provider.clientSecret)
            }
            val conn = (URL(provider.tokenUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
                // GitHub 的 token 端点默认回 form-urlencoded，要 JSON 得显式要。
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val rc = conn.responseCode
                val text = (if (rc in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (rc !in 200..299) {
                    val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                    throw OAuthError("token HTTP $rc ${msg.take(120)}".trim())
                }
                // 容错：有的端点仍回 form-urlencoded。
                val json = if (text.trimStart().startsWith("{")) JSONObject(text) else parseForm(text)
                val at = json.optString("access_token").takeIf { it.isNotBlank() }
                    ?: throw OAuthError("token 响应缺少 access_token")
                TokenResult(
                    accessToken = at,
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                    expiresInSeconds = json.optLong("expires_in", 0L),
                    scope = json.optString("scope").takeIf { it.isNotBlank() } ?: provider.scopes,
                )
            } finally {
                conn.disconnect()
            }
        }

    /** 把换到的 token 加密存进 [AccountStore]，返回落库后的账号。 */
    fun saveToken(provider: OAuthProvider, token: TokenResult, displayName: String?): ConnectedAccount {
        val payload = JSONObject().apply {
            put("access_token", token.accessToken)
            token.refreshToken?.let { put("refresh_token", it) }
            put("expires_in", token.expiresInSeconds)
            if (token.scope.isNotBlank()) put("scope", token.scope)
        }
        return store.add(
            ConnectedAccount(
                id = store.newId(),
                serviceId = provider.id,
                displayName = (displayName ?: "").trim().ifBlank { provider.name },
                connectedAt = System.currentTimeMillis(),
                scopes = token.scope.split(' ', ',').map { it.trim() }.filter { it.isNotBlank() },
                secretEnc = store.seal(payload.toString()),
                kind = "token",
                expiresAt = if (token.expiresInSeconds > 0) System.currentTimeMillis() + token.expiresInSeconds * 1000 else 0L,
            ),
        )
    }

    /**
     * 降级：Custom Tab + loopback 回调的完整流程（当内置 WebView 被服务拒绝时用，
     * 例如 Google 的 disallowed_useragent）。复用本体登录同款的浏览器回调机制，
     * 前台门控（[OAuthForegroundGate]）保证换 token 时网络未被后台策略掐断。
     * 返回落库后的账号；取消抛 [OAuthError]。
     */
    suspend fun runLoopbackFlow(
        provider: OAuthProvider,
        onStep: (String) -> Unit,
        deadlineMs: Long = 10 * 60_000L,
    ): ConnectedAccount {
        val (url, verifier) = beginAuthorize(provider)
        val callbacks = kotlinx.coroutines.channels.Channel<Pkce.Callback>(capacity = 8)
        var server: OAuthCallbackServer? = null
        try {
            server = OAuthCallbackServer(8899, listOf(8900, 8901), onError = { err ->
                callbacks.trySend(Pkce.Callback(null, null, err.description ?: err.error))
            }) { code, state -> callbacks.trySend(Pkce.Callback(code, state, null)) }
            server.start()
            onStep("已在浏览器打开授权页，请在浏览器里登录并点「授权」")
            OAuthTabLauncher.launch(context, Uri.parse(url), newTask = true)
            val t0 = System.currentTimeMillis()
            var cb: Pkce.Callback? = null
            while (cb == null) {
                if (System.currentTimeMillis() - t0 > deadlineMs) throw OAuthError("授权超时")
                cb = withTimeoutOrNull(500) { callbacks.receive() }
            }
            if (cb.error != null && cb.code == null) throw OAuthError("授权失败：${cb.error.take(120)}")
            val code = cb.code ?: throw OAuthError("回调里没有 code")
            onStep("已授权，正在换取 token…")
            OAuthForegroundGate.awaitForeground("MikovaOAuth")
            val token = exchange(provider, code, verifier)
            onStep("已拿到 token，保存中…")
            return saveToken(provider, token, null)
        } finally {
            server?.stop()
        }
    }

    // ------------------------------------------------------------- helpers

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun parseForm(text: String): JSONObject = JSONObject().apply {
        text.split('&').filter { it.contains('=') }.forEach { kv ->
            val k = java.net.URLDecoder.decode(kv.substringBefore('='), "UTF-8")
            val v = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            put(k, v)
        }
    }

    companion object {
        /** 进程内共享一个 broker（同一个 [AccountStore]）。 */
        @Volatile private var shared: OAuthBroker? = null
        fun shared(context: Context): OAuthBroker = shared ?: synchronized(this) {
            shared ?: OAuthBroker(context.applicationContext, AccountStore.shared(context)).also { shared = it }
        }
    }
}
