/*
 * 本地 OpenAI 兼容网关。
 *
 * 在 127.0.0.1 上起一个 HTTP 服务，对外提供 OpenAI 兼容接口
 * （GET /v1/models、POST /v1/chat/completions），请求进来后**复用 app 已配的
 * AI 网关账号**（ProviderRepository + ProviderFactory）去真正调上游模型。
 *
 * 用途：沙箱里的 AstrBot / 生活系统 / 终端脚本等，通过
 * `http://127.0.0.1:<port>/v1` 就能用上 app 里配置的 key，不必把 key 暴露
 * 给沙箱，也不必在沙箱里单独配 provider。
 *
 * 实现说明：
 *  - Android 没有 com.sun.net.httpserver，用 ServerSocket 手写 HTTP 解析
 *    （与 OAuthCallbackServer 同款先例）。
 *  - 非流式（stream=false）走 GatewayNet.request —— 复用网络路由/镜像回退/
 *    健康记录，与 app 真实流量一致。
 *  - 流式（stream=true）用 OkHttp 直接透传上游 SSE，本地边读边写。
 *  - key 注入：按 providerType 构建 Authorization / x-api-key / api-key 头，
 *    与 ModelTools.test 完全一致。
 */
package com.openminis.app.mibot.gateway

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ProviderCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地 OpenAI 兼容网关单例。
 *
 * 生命周期：手动 start/stop（Gateway 页开关）；进程内单例，重启后需重新 start。
 * 状态经 [running] / [port] / [lastError] 暴露给 UI。
 */
object LocalGateway {

    private const val TAG = "LocalGateway"
    const val DEFAULT_PORT = 8788

    private val running = AtomicBoolean(false)
    private val port = AtomicReference(DEFAULT_PORT)
    private val error = AtomicReference<String?>(null)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "LocalGateway").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()
    val currentPort: Int get() = port.get()
    val lastError: String? get() = error.get()

    /** 已启动的模型数（用于 UI 显示），启动时刷新。 */
    val modelCount = AtomicReference(0)
    /** 启动网关。绑定失败时抛异常（调用方捕获并展示 [lastError]）。 */
    @Synchronized
    fun start(context: Context, desiredPort: Int = DEFAULT_PORT): Boolean {
        if (running.get()) return true
        val app = context.applicationContext as? MinisApp
        val repo = app?.providerRepositoryOrNull
        if (repo == null) {
            error.set("AI 网关未就绪（provider 仓库未初始化）")
            return false
        }
        return try {
            val sock = ServerSocket()
            sock.reuseAddress = true
            sock.bind(InetSocketAddress("127.0.0.1", desiredPort), 32)
            port.set(desiredPort)
            serverSocket = sock
            running.set(true)
            error.set(null)
            // 启动时统计可用模型数
            runCatching { repo.ensureConfigLoaded() }
            modelCount.set(repo.config.value.modelEntries.count { !it.isHidden })
            serverThread = Thread {
                try {
                    while (running.get()) {
                        val client = try { sock.accept() } catch (e: Exception) { break }
                        pool.execute { handleClient(app, repo, client) }
                    }
                } catch (e: Exception) {
                    if (running.get()) { error.set(e.message); running.set(false) }
                } finally {
                    runCatching { sock.close() }
                }
            }.apply { isDaemon = true; name = "LocalGateway-Accept" }
            serverThread?.start()
            Log.i(TAG, "started on 127.0.0.1:$desiredPort")
            true
        } catch (e: Exception) {
            error.set("启动失败：${e.message ?: e.javaClass.simpleName}")
            running.set(false)
            false
        }
    }

    /** 停止网关。 */
    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread = null
        Log.i(TAG, "stopped")
    }

    // ---------------------------------------------------------------------
    // 请求处理
    // ---------------------------------------------------------------------

    private fun handleClient(app: MinisApp?, repo: com.openminis.app.data.repository.ProviderRepository, client: Socket) {
        try {
            client.soTimeout = 60_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val out = client.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { writeError(out, 400, "bad request line"); return }
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            // 读请求头
            val headers = LinkedHashMap<String, String>()
            var contentLength = 0
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0 && method == "POST") {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(buf, read, contentLength - read)
                    if (r < 0) break
                    read += r
                }
                String(buf, 0, read)
            } else ""

            when {
                method == "GET" && path == "/v1/models" -> handleModels(repo, out)
                method == "GET" && (path == "/" || path == "/v1" || path == "/health") -> handleHealth(out)
                method == "POST" && path == "/v1/chat/completions" ->
                    kotlinx.coroutines.runBlocking { handleChat(app, repo, body, headers, out) }
                method == "POST" && path == "/v1/responses" ->
                    kotlinx.coroutines.runBlocking { handleChat(app, repo, body, headers, out, responses = true) }
                else -> writeError(out, 404, "not found: $method $path")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle failed: ${e.message}")
            runCatching { writeError(client.getOutputStream(), 500, e.message ?: "internal error") }
        } finally {
            runCatching { client.close() }
        }
    }

    // ---------------------------------------------------------------------
    // GET /v1/models
    // ---------------------------------------------------------------------

    private fun handleModels(repo: com.openminis.app.data.repository.ProviderRepository, out: OutputStream) {
        val data = JSONArray()
        val seen = HashSet<String>()
        val config = repo.config.value
        // 每个 model entry → {id, object:model, owned_by:providerLabel}
        for (entry in config.modelEntries) {
            if (entry.isHidden) continue
            val m = entry.baseModel
            val inst = config.instances.find { it.id == entry.providerInstanceId }
            if (!seen.add(m.id)) continue
            data.put(JSONObject().apply {
                put("id", m.id)
                put("object", "model")
                put("created", 0)
                put("owned_by", inst?.label ?: m.provider)
            })
        }
        val resp = JSONObject().put("object", "list").put("data", data)
        writeJson(out, 200, resp.toString())
    }

    // ---------------------------------------------------------------------
    // GET /health
    // ---------------------------------------------------------------------

    private fun handleHealth(out: OutputStream) {
        val resp = JSONObject()
            .put("ok", true)
            .put("service", "mibot-local-gateway")
            .put("models", modelCount.get())
        writeJson(out, 200, resp.toString())
    }

    // ---------------------------------------------------------------------
    // POST /v1/chat/completions（及 /v1/responses）
    // ---------------------------------------------------------------------

    private suspend fun handleChat(
        app: MinisApp?,
        repo: com.openminis.app.data.repository.ProviderRepository,
        body: String,
        headers: Map<String, String>,
        out: OutputStream,
        responses: Boolean = false,
    ) {
        val req = try { JSONObject(body) } catch (e: Exception) {
            writeError(out, 400, "invalid JSON body"); return
        }
        val modelId = req.optString("model")
        if (modelId.isBlank()) { writeError(out, 400, "missing model"); return }
        val stream = req.optBoolean("stream", false)

        // 1) 按 model id 定位候选 entry 列表（支持模型组：组名 → 成员按策略排序）
        val config = repo.config.value
        val candidates: List<Pair<ModelEntry, ProviderInstance>> = resolveCandidates(repo, config, modelId)
        if (candidates.isEmpty()) {
            writeError(out, 404, "unknown model: $modelId")
            return
        }
        // 模型组负载均衡：从命中的组里按轮询选一个起始成员，其余按顺序回退
        val startIdx = if (candidates.size > 1 && config.modelGroups.any { it.name == modelId && it.strategy == com.openminis.app.data.model.RoutingStrategy.loadBalance }) {
            loadBalanceIndex(modelId)
        } else 0

        // 2) 依次尝试成员：失败（网络/上游错误）回退下一个
        var lastErr: String? = null
        for (off in candidates.indices) {
            val idx = (startIdx + off) % candidates.size
            val (entry, inst) = candidates[idx]
            if (!inst.isEnabled) { lastErr = "provider disabled: ${inst.label}"; continue }

            val apiKey = withContext(Dispatchers.IO) { repo.requestCredential(inst) }
            if (apiKey == null) { lastErr = "no API key for provider ${inst.label}"; continue }

            val base = baseFor(inst)
            if (base == null) { lastErr = "provider ${inst.label} has no base URL"; continue }
            val upstreamUrl = buildUpstreamUrl(base, inst, entry.baseModel.id, responses)
            val upstreamHeaders = buildUpstreamHeaders(inst, apiKey, headers)
            // 组名路由时 body.model 是组名，上游不识别：替换成成员的真实 model id
            val upstreamBody = if (modelId != entry.baseModel.id) {
                runCatching { JSONObject(body).put("model", entry.baseModel.id).toString() }.getOrDefault(body)
            } else body

            if (stream) {
                // 流式：透传 SSE（单个成员，不回退——SSE 中途无法重放）
                forwardStream(inst, upstreamUrl, upstreamHeaders, upstreamBody, out)
                return
            }
            val outcome = GatewayNet.request(inst.id, upstreamUrl, "POST", upstreamHeaders, upstreamBody)
            if (outcome.code != null && outcome.code in 200..299) {
                writeRaw(out, 200, outcome.body, "application/json")
                return
            }
            // 成员失败：记下错误，回退下一个（AUTH/QUOTA 类不重试，直接报）
            lastErr = "provider ${inst.label}: ${outcome.detail.take(160)}"
            if (outcome.kind == GatewayNet.Kind.AUTH || outcome.kind == GatewayNet.Kind.QUOTA) break
        }
        val err = JSONObject().apply {
            put("error", JSONObject().put("message", lastErr ?: "all members failed").put("type", "upstream_error"))
        }
        writeRaw(out, 502, err.toString(), "application/json")
    }

    /**
     * 解析 model 请求对应的候选（entry, instance）列表。
     *  - 直接命中 entry.model.id（含去前缀容错）→ 单候选；
     *  - 命中组名 → 组内成员按 entry 顺序组成候选列表；
     *  - 否则空。
     */
    private fun resolveCandidates(
        repo: com.openminis.app.data.repository.ProviderRepository,
        config: com.openminis.app.data.model.ProviderConfig,
        modelId: String,
    ): List<Pair<ModelEntry, ProviderInstance>> {
        val byModel = config.modelEntries.firstOrNull { !it.isHidden && it.baseModel.id == modelId }
            ?: config.modelEntries.firstOrNull { !it.isHidden && it.baseModel.id == modelId.substringAfterLast('/') }
        if (byModel != null) {
            val inst = config.instances.find { it.id == byModel.providerInstanceId }
            return if (inst != null) listOf(byModel to inst) else emptyList()
        }
        // 组名路由
        val group = config.modelGroups.firstOrNull { it.name == modelId }
            ?: return emptyList()
        val out = ArrayList<Pair<ModelEntry, ProviderInstance>>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            if (entry.isHidden) continue
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            out.add(entry to inst)
        }
        return out
    }

    /** 模型组负载均衡：按组名轮询起始索引（进程内原子计数）。 */
    private val loadBalanceCounter = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun loadBalanceIndex(groupName: String): Int {
        val counter = loadBalanceCounter.getOrPut(groupName) { java.util.concurrent.atomic.AtomicInteger(0) }
        return counter.getAndIncrement() and Int.MAX_VALUE
    }

    private fun baseFor(inst: ProviderInstance): String? =
        inst.effectiveBaseURL ?: when (inst.providerType) {
            ProviderType.openAI -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.xAI -> "https://api.x.ai/v1"
            else -> null
        }

    /** 上游 chat/responses URL。Azure 模式走部署路径。 */
    private fun buildUpstreamUrl(base: String, inst: ProviderInstance, modelId: String, responses: Boolean): String {
        val trim = base.trimEnd('/')
        return when {
            inst.azureMode -> "$trim/openai/deployments/$modelId/chat/completions?api-version=2024-02-01"
            inst.useResponsesAPI || responses -> "$trim/responses"
            else -> "$trim/chat/completions"
        }
    }

    /** 构建上游请求头。与 ModelTools.test 一致 + 透传 content-type。 */
    private fun buildUpstreamHeaders(inst: ProviderInstance, apiKey: String, clientHeaders: Map<String, String>): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        when (inst.providerType) {
            ProviderType.gemini -> {} // key 走 query
            ProviderType.anthropic -> { h["x-api-key"] = apiKey; h["anthropic-version"] = "2023-06-01" }
            else -> if (apiKey.isNotEmpty()) h["Authorization"] = "Bearer $apiKey"
        }
        // 透传自定义 UA / 额外头
        inst.customUserAgent?.takeIf { it.isNotBlank() }?.let { h["User-Agent"] = it }
        h["Content-Type"] = "application/json"
        h["Accept"] = if (clientHeaders["accept"]?.contains("event-stream") == true) "text/event-stream" else "application/json"
        return h
    }

    // ---------------------------------------------------------------------
    // 流式透传（SSE）
    // ---------------------------------------------------------------------

    private fun forwardStream(
        inst: ProviderInstance,
        url: String,
        headers: Map<String, String>,
        body: String,
        out: OutputStream,
    ) {
        val client = OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        rb.post(body.toRequestBody("application/json".toMediaType()))
        client.newCall(rb.build()).execute().use { resp ->
            val code = resp.code
            if (code !in 200..299) {
                val text = resp.body?.string().orEmpty()
                val err = JSONObject().put("error", JSONObject().put("message", "upstream HTTP $code: ${text.take(160)}").put("type", "upstream_error"))
                writeRaw(out, code, err.toString(), "application/json")
                return
            }
            // 200：写 SSE 头 + 逐行透传
            writeHead(out, 200, "text/event-stream", null)
            val src = resp.body?.source()
            if (src != null) {
                val buf = ByteArray(8192)
                while (running.get()) {
                    val r = src.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    out.flush()
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // HTTP 写出工具
    // ---------------------------------------------------------------------

    private fun writeJson(out: OutputStream, code: Int, json: String) = writeRaw(out, code, json, "application/json")

    private fun writeError(out: OutputStream, code: Int, msg: String) {
        val json = JSONObject().put("error", JSONObject().put("message", msg).put("type", "invalid_request_error")).toString()
        writeRaw(out, code, json, "application/json")
    }

    private fun writeHead(out: OutputStream, code: Int, contentType: String?, body: ByteArray?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(" ").append(reason(code)).append("\r\n")
        sb.append("Content-Type: ").append(contentType ?: "application/json").append("\r\n")
        if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body != null) out.write(body)
        out.flush()
    }

    private fun writeRaw(out: OutputStream, code: Int, text: String, contentType: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        writeHead(out, code, contentType, body)
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "OK"
    }
}
