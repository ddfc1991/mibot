package com.openminis.app.mikova.selfhost

import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A successful HTTP response is connectivity evidence, not remote execution or data migration. */
data class SelfHostTestResult(val ok: Boolean, val message: String, val latencyMs: Long)

internal object EndpointPolicy {
    fun normalize(raw: String, credential: Boolean = false): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "请填写服务器地址" }
        require(value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() }) { "地址包含空白或无效字符" }
        val full = if (value.contains("://")) value else "https://$value"
        val uri = try { URI(full) } catch (_: Exception) { throw IllegalArgumentException("地址格式不正确") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "仅支持 HTTP 或 HTTPS 地址" }
        require(!uri.host.isNullOrBlank()) { "请填写有效的主机名或 IP 地址" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "地址不能包含账号、查询密钥或片段；凭据请填在密钥栏" }
        require(uri.port == -1 || uri.port in 1..65535) { "端口必须在 1–65535 之间" }
        require(!credential || scheme == "https") { "发送 API Key 需要 HTTPS；HTTP 只能做不带密钥的连通性测试" }
        return URI(scheme, null, uri.host.lowercase(), uri.port, uri.path.ifEmpty { "/" }, null, null).toASCIIString()
    }

    fun error(raw: String, credential: Boolean = false): String? =
        try { normalize(raw, credential); null } catch (e: IllegalArgumentException) { e.message }
}

internal object SelfHostProbe {
    /** No redirects, no response-body display, bounded timeout. Never log URL credentials or keys. */
    fun test(raw: String, key: String = "", timeoutMs: Int = 8_000): SelfHostTestResult {
        val url = try {
            require(key.none { it == '\r' || it == '\n' }) { "API Key 不能包含换行" }
            EndpointPolicy.normalize(raw, key.isNotBlank())
        } catch (e: IllegalArgumentException) { return SelfHostTestResult(false, e.message ?: "地址无效", 0) }
        val start = System.nanoTime()
        var connection: HttpURLConnection? = null
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        return try {
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }
            val code = connection.responseCode
            val detail = when (code) {
                in 200..299 -> "HTTP $code 可达；仅证明此地址响应，不证明远端执行、同步或 Computer Use 已接通"
                in 300..399 -> "HTTP $code 重定向已停止；请核对并填写最终地址，密钥不会自动转发"
                401, 403 -> "HTTP $code 认证被拒绝；核对密钥与服务权限后重试"
                404 -> "HTTP 404 地址不存在；核对服务地址及路径"
                in 500..599 -> "HTTP $code 服务端异常；检查服务器日志后重试"
                else -> "HTTP $code；服务器已响应，但此地址尚未通过连通性检查"
            }
            SelfHostTestResult(code in 200..299, detail, elapsed())
        } catch (_: SocketTimeoutException) {
            SelfHostTestResult(false, "连接或响应超时，检查服务器是否在线、端口和网络后重试", elapsed())
        } catch (_: UnknownHostException) {
            SelfHostTestResult(false, "无法解析服务器域名，检查地址和 DNS 后重试", elapsed())
        } catch (_: SSLException) {
            SelfHostTestResult(false, "TLS 证书校验失败，请检查服务器证书和设备时间", elapsed())
        } catch (_: Exception) {
            SelfHostTestResult(false, "无法连接服务器，请检查地址、端口和网络；输入已保留", elapsed())
        } finally { connection?.disconnect() }
    }
}
