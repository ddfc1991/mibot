package com.openminis.app.mibot.gateway

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotFootnote
import com.openminis.app.mibot.design.MibotRow
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotSectionHeader
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [fork/mibot-v2/v4] AI gateway health, layered on the existing model groups
 * (which already do fallback / load-balance routing). Each provider instance
 * is one account in a pool.
 *
 * v4: the status shown is derived from the LATEST real probe (health check,
 * model test or chat failure) — never "OK" just because a check ran. A
 * network failure triggers auto-repair (retry with backoff, DNS-over-HTTPS,
 * the instance's fallback/mirror base URL), a 401/403 marks the key invalid,
 * 429 rate-limited, 402 out of credit. A relay answering "没有可用的国内版账号
 * (cn:)" marks that region of the account unavailable for 30 minutes so
 * fallback stops retrying it. State persists across restarts.
 */
object GatewayHealth {
    enum class Repair { NONE, RUNNING, FIXED, FAILED }

    data class State(
        val failures: Int = 0,
        val consecutive: Int = 0,
        val cooldownUntil: Long = 0L,
        val dead: Boolean = false,
        val lastError: String? = null,
        val lastCheckAt: Long = 0L,
        val lastCheck: String? = null,
        val okCount: Int = 0,
        val kind: GatewayNet.Kind? = null,
        val latencyMs: Long = 0L,
        val repair: Repair = Repair.NONE,
        val repairNote: String? = null,
        val repairAt: Long = 0L,
        /** region prefix (e.g. "cn") -> unavailable until (ms). */
        val regionsDown: Map<String, Long> = emptyMap(),
    )

    private val map = ConcurrentHashMap<String, State>()
    val version = MutableStateFlow(0L)
    @Volatile private var appCtx: Context? = null
    @Volatile private var loaded = false

    private const val PREFS = "mibot_gateway"
    const val REGION_COOLDOWN_MS = 30 * 60_000L
    const val REPAIR_COOLDOWN_MS = 5 * 60_000L

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            appCtx = ctx.applicationContext
            loaded = true
            runCatching {
                val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("health", null) ?: return@runCatching
                val o = org.json.JSONObject(raw)
                o.keys().forEach { id ->
                    val j = o.getJSONObject(id)
                    val regions = j.optJSONObject("regions")?.let { r -> r.keys().asSequence().associateWith { r.getLong(it) } } ?: emptyMap()
                    map[id] = State(
                        failures = j.optInt("failures"),
                        dead = j.optBoolean("dead"),
                        lastError = j.optString("lastError").ifEmpty { null },
                        lastCheckAt = j.optLong("lastCheckAt"),
                        lastCheck = j.optString("lastCheck").ifEmpty { null },
                        okCount = j.optInt("okCount"),
                        kind = j.optString("kind").ifEmpty { null }?.let { k -> runCatching { GatewayNet.Kind.valueOf(k) }.getOrNull() },
                        latencyMs = j.optLong("latencyMs"),
                        repair = j.optString("repair").let { r -> runCatching { Repair.valueOf(r) }.getOrDefault(Repair.NONE) }.let { if (it == Repair.RUNNING) Repair.NONE else it },
                        repairNote = j.optString("repairNote").ifEmpty { null },
                        repairAt = j.optLong("repairAt"),
                        regionsDown = regions,
                    )
                }
            }
        }
    }

    private fun persist() {
        val ctx = appCtx ?: return
        runCatching {
            val o = org.json.JSONObject()
            map.forEach { (id, s) ->
                o.put(id, org.json.JSONObject()
                    .put("failures", s.failures).put("dead", s.dead).put("lastError", s.lastError ?: "")
                    .put("lastCheckAt", s.lastCheckAt).put("lastCheck", s.lastCheck ?: "").put("okCount", s.okCount)
                    .put("kind", s.kind?.name ?: "").put("latencyMs", s.latencyMs)
                    .put("repair", s.repair.name).put("repairNote", s.repairNote ?: "").put("repairAt", s.repairAt)
                    .put("regions", org.json.JSONObject(s.regionsDown.mapValues { it.value })))
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("health", o.toString()).apply()
        }
    }

    private fun put(id: String, s: State) { map[id] = s; version.value++; persist() }

    fun state(id: String): State = map[id] ?: State()

    fun isUsable(id: String): Boolean {
        val s = map[id] ?: return true
        if (s.dead) return false
        if (System.currentTimeMillis() < s.cooldownUntil) return false
        return true
    }

    /** Region prefix of a model id: "cn:deepseek-v4" -> "cn". */
    fun regionOf(modelId: String): String? =
        Regex("^([A-Za-z]{2,8}):").find(modelId)?.groupValues?.get(1)?.lowercase()

    /** Whether one model entry (instance + model id) can be routed to now. */
    fun isEntryUsable(instanceId: String, modelId: String): Boolean {
        if (!isUsable(instanceId)) return false
        val s = map[instanceId] ?: return true
        if (s.regionsDown.isEmpty()) return true
        val now = System.currentTimeMillis()
        val r = regionOf(modelId) ?: ""
        val until = s.regionsDown[r] ?: return true
        return now >= until
    }

    /** A chat request failed on [instanceId]/[modelId] with [reason] (the real error text). */
    fun recordFailure(ctx: Context?, id: String, reason: String, modelId: String? = null) {
        ctx?.let { init(it) }
        val s = state(id)
        val now = System.currentTimeMillis()
        val kind = GatewayNet.classifyText(reason)
        if (kind == GatewayNet.Kind.REGION) {
            // Only that region of the account is out; the rest keeps working.
            val region = modelId?.let { regionOf(it) }
                ?: Regex("\\b([a-z]{2,8}):\\s*[)）]").find(reason)?.groupValues?.get(1)
                ?: ""
            put(id, s.copy(failures = s.failures + 1, regionsDown = s.regionsDown + (region to now + REGION_COOLDOWN_MS),
                lastError = reason.take(200)))
            return
        }
        val n = s.consecutive + 1
        val cool = when (kind) {
            GatewayNet.Kind.RATE -> 120_000L
            else -> (30_000L shl (n - 1).coerceAtMost(5)).coerceAtMost(600_000L)
        }
        put(id, s.copy(
            failures = s.failures + 1, consecutive = n, cooldownUntil = now + cool, lastError = reason.take(200),
            dead = s.dead || kind == GatewayNet.Kind.AUTH,
            kind = if (kind == GatewayNet.Kind.OTHER) (s.kind ?: kind) else kind,
            lastCheckAt = now, lastCheck = reason.take(200),
        ))
    }

    fun recordSuccess(id: String) {
        val s = state(id)
        put(id, s.copy(consecutive = 0, cooldownUntil = 0L, dead = false, okCount = s.okCount + 1, kind = GatewayNet.Kind.OK))
    }

    /** One real probe finished. Status follows it exactly. */
    fun recordProbe(id: String, o: GatewayNet.Outcome, label: String = o.detail) {
        val s = state(id)
        val now = System.currentTimeMillis()
        val base = s.copy(lastCheckAt = now, lastCheck = label.take(200), kind = o.kind, latencyMs = o.latencyMs)
        put(id, when (o.kind) {
            GatewayNet.Kind.OK -> base.copy(consecutive = 0, cooldownUntil = 0L, dead = false, okCount = s.okCount + 1, lastError = null)
            GatewayNet.Kind.AUTH -> base.copy(dead = true, lastError = label)
            GatewayNet.Kind.RATE -> base.copy(cooldownUntil = now + 120_000L, lastError = label)
            GatewayNet.Kind.QUOTA -> base.copy(cooldownUntil = now + 600_000L, lastError = label)
            GatewayNet.Kind.NETWORK -> base.copy(cooldownUntil = now + 30_000L, lastError = label)
            else -> base.copy(lastError = label)
        })
    }

    /** Legacy string form (agent tool). */
    fun recordCheck(id: String, result: String) {
        val kind = GatewayNet.classifyText(result).let { if (result.startsWith("HTTP 2")) GatewayNet.Kind.OK else it }
        recordProbe(id, GatewayNet.Outcome(kind, null, 0, result), result)
    }

    fun setRepair(id: String, r: Repair, note: String? = null) {
        val s = state(id)
        put(id, s.copy(repair = r, repairNote = note, repairAt = System.currentTimeMillis()))
    }

    fun reset(id: String) { map.remove(id); version.value++; persist() }

    // ── fallback / mirror base URL per instance ──────────────────────────
    fun mirror(ctx: Context, id: String): String? =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mirror.$id", null)?.trim()?.ifEmpty { null }

    fun setMirror(ctx: Context, id: String, url: String?) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (url.isNullOrBlank()) remove("mirror.$id") else putString("mirror.$id", url.trim().trimEnd('/'))
        }.apply()
        version.value++
    }
}

/** Probing one provider instance, and repairing it when the network is the problem. */
object GatewayProbe {
    private const val GEMINI = "https://generativelanguage.googleapis.com"

    fun defaultBase(type: ProviderType): String? = when (type) {
        ProviderType.openAI -> "https://api.openai.com/v1"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        else -> null
    }

    private fun isGemini(inst: ProviderInstance) = inst.providerType == ProviderType.gemini && inst.effectiveBaseURL == null

    /** The /models URL and headers for [inst], optionally against [baseOverride]. */
    private fun target(inst: ProviderInstance, key: String?, baseOverride: String? = null): Pair<String, Map<String, String>>? {
        if (isGemini(inst)) {
            val root = (baseOverride ?: GEMINI).trimEnd('/')
            return "$root/v1beta/models" + (key?.let { "?key=$it" } ?: "") to emptyMap()
        }
        val base = baseOverride ?: inst.effectiveBaseURL ?: defaultBase(inst.providerType) ?: return null
        val h = if (key.isNullOrEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $key")
        return base.trimEnd('/') + "/models" to h
    }

    private fun summarize(o: GatewayNet.Outcome, gemini: Boolean): String {
        if (o.kind != GatewayNet.Kind.OK) return o.detail
        val count = runCatching { org.json.JSONObject(o.body).optJSONArray(if (gemini) "models" else "data")?.length() }.getOrNull()
        return "HTTP ${o.code}" + (count?.let { ", $it models" } ?: "") + " · ${o.latencyMs} ms"
    }

    suspend fun probe(repo: com.openminis.app.data.repository.ProviderRepository, inst: ProviderInstance, doh: Boolean = false, baseOverride: String? = null): GatewayNet.Outcome {
        val key = repo.usableApiKey(inst)
        val (url, headers) = target(inst, key, baseOverride)
            ?: return GatewayNet.Outcome(GatewayNet.Kind.UNSUPPORTED, null, 0, "no base URL (OAuth/native provider)")
        val o = GatewayNet.request(inst.id, url, headers = headers, doh = doh)
        val scrubbed = com.openminis.app.mibot.vault.VaultBroker.scrub(summarize(o, isGemini(inst)), listOfNotNull(key))
        return o.copy(detail = scrubbed, body = "")
    }

    /** Health-check one instance; on a network failure, auto-repair (respecting the cooldown). */
    suspend fun check(ctx: Context, repo: com.openminis.app.data.repository.ProviderRepository, inst: ProviderInstance, autoRepair: Boolean = true) {
        GatewayHealth.init(ctx)
        val o = probe(repo, inst)
        if (o.kind == GatewayNet.Kind.UNSUPPORTED) { GatewayHealth.recordProbe(inst.id, o); return }
        GatewayHealth.recordProbe(inst.id, o)
        if (o.kind == GatewayNet.Kind.NETWORK && autoRepair) {
            val s = GatewayHealth.state(inst.id)
            val recent = s.repair == GatewayHealth.Repair.FAILED && System.currentTimeMillis() - s.repairAt < GatewayHealth.REPAIR_COOLDOWN_MS
            if (!recent) repair(ctx, repo, inst)
        }
    }

    /**
     * Network repair ladder: retry with backoff (a flaky link), DNS-over-HTTPS
     * (a poisoned / failing resolver), then the instance's fallback base URL
     * (a mirror / reverse proxy the user configured). A mirror that works is
     * adopted as the instance's base URL so chat traffic follows.
     */
    suspend fun repair(ctx: Context, repo: com.openminis.app.data.repository.ProviderRepository, inst: ProviderInstance): Boolean {
        GatewayHealth.init(ctx)
        fun z(zh: String, en: String) = com.openminis.app.mibot.design.tc(ctx, zh, en)
        GatewayHealth.setRepair(inst.id, GatewayHealth.Repair.RUNNING, z("自动修复中…", "Auto-repairing…"))
        var last: GatewayNet.Outcome? = null
        // 1. retry with backoff
        for (wait in longArrayOf(1_500L, 4_000L)) {
            delay(wait)
            val o = probe(repo, inst)
            last = o
            if (o.kind != GatewayNet.Kind.NETWORK) {
                GatewayHealth.recordProbe(inst.id, o)
                return finish(ctx, inst.id, o, z("已修复（重试成功）", "Fixed (retry worked)"))
            }
        }
        // 2. DNS over HTTPS
        run {
            val o = probe(repo, inst, doh = true)
            last = o
            if (o.kind != GatewayNet.Kind.NETWORK) {
                GatewayHealth.recordProbe(inst.id, o, o.detail + " (DoH)")
                return finish(ctx, inst.id, o, z("已修复（改用 DoH 解析）", "Fixed (DNS-over-HTTPS)"))
            }
        }
        // 3. fallback / mirror base URL
        GatewayHealth.mirror(ctx, inst.id)?.let { m ->
            val o = probe(repo, inst, baseOverride = m)
            last = o
            if (o.kind == GatewayNet.Kind.OK) {
                GatewayHealth.recordProbe(inst.id, o, o.detail + " (mirror)")
                if (isGemini(inst)) {
                    return finish(ctx, inst.id, o, z("备用地址可用（Gemini 需在服务商里手动填写）", "Fallback URL works (set it on the Gemini provider)"))
                }
                runCatching { repo.updateInstance(inst.copy(customBaseURL = m, appendV1Suffix = false)) }
                return finish(ctx, inst.id, o, z("已修复（切换到备用地址）", "Fixed (switched to fallback URL)"))
            }
        }
        val o = last ?: return false
        GatewayHealth.recordProbe(inst.id, o)
        val routed = GatewayNet.isRouted(inst.id)
        val hint = when {
            GatewayHealth.mirror(ctx, inst.id) == null && !routed -> z("；可在 设置›网络 配置代理，或填写备用地址", "; set a proxy in Settings › Network or a fallback URL")
            else -> ""
        }
        GatewayHealth.setRepair(inst.id, GatewayHealth.Repair.FAILED, z("修复失败：", "Repair failed: ") + o.detail.removePrefix("error: ").take(80) + hint)
        return false
    }

    private fun finish(ctx: Context, id: String, o: GatewayNet.Outcome, okNote: String): Boolean {
        fun z(zh: String, en: String) = com.openminis.app.mibot.design.tc(ctx, zh, en)
        // The network is fine now; the account may still have another problem (bad key etc.).
        GatewayHealth.setRepair(id, if (o.kind == GatewayNet.Kind.OK) GatewayHealth.Repair.FIXED else GatewayHealth.Repair.FAILED,
            if (o.kind == GatewayNet.Kind.OK) okNote else z("网络已通，但：", "Network OK, but: ") + o.detail.take(80))
        return o.kind == GatewayNet.Kind.OK
    }
}

/** Label + chip colour for an instance, derived from its latest probe. */
fun gatewayStatus(ctx: Context, inst: ProviderInstance, s: GatewayHealth.State, now: Long): Pair<String, ChipKind> {
    fun z(zh: String, en: String) = com.openminis.app.mibot.design.tc(ctx, zh, en)
    return when {
        !inst.isEnabled -> z("已停用", "Off") to ChipKind.NEUTRAL
        s.repair == GatewayHealth.Repair.RUNNING -> z("修复中…", "Repairing…") to ChipKind.WARN
        s.dead || s.kind == GatewayNet.Kind.AUTH -> z("Key 无效", "Invalid key") to ChipKind.ERR
        s.kind == GatewayNet.Kind.QUOTA -> z("余额不足", "No credit") to ChipKind.ERR
        s.kind == GatewayNet.Kind.RATE && s.cooldownUntil > now -> (z("限流 ", "Rate-limited ") + "${(s.cooldownUntil - now) / 1000}s") to ChipKind.WARN
        s.kind == GatewayNet.Kind.RATE -> z("限流", "Rate-limited") to ChipKind.WARN
        s.kind == GatewayNet.Kind.NETWORK -> z("网络异常", "Network error") to ChipKind.WARN
        s.kind == GatewayNet.Kind.SERVER -> z("服务异常", "Server error") to ChipKind.WARN
        s.kind == GatewayNet.Kind.UNSUPPORTED -> z("无法检测", "Can't check") to ChipKind.NEUTRAL
        s.kind == GatewayNet.Kind.OK && s.cooldownUntil > now -> (z("冷却中 ", "Cooling ") + "${(s.cooldownUntil - now) / 1000}s") to ChipKind.WARN
        s.kind == GatewayNet.Kind.OK -> z("可用", "OK") to ChipKind.OK
        s.kind == GatewayNet.Kind.NOT_FOUND || s.kind == GatewayNet.Kind.OTHER || s.kind == GatewayNet.Kind.REGION -> z("异常", "Error") to ChipKind.WARN
        else -> z("未检测", "Unchecked") to ChipKind.NEUTRAL
    }
}

object GatewayRoutes { const val GATEWAY = "mibot_gateway" }

private fun poolKey(i: ProviderInstance): String {
    val host = i.effectiveBaseURL?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: ""
    return if (host.isNotEmpty()) host else i.providerType.displayName
}

@Composable
fun GatewayScreen(onBack: () -> Unit, onProvider: (String) -> Unit, onModelGroups: () -> Unit, onUsage: () -> Unit, onAddProvider: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { (ctx.applicationContext as MinisApp).providerRepository }
    val config by repo.config.collectAsState()
    remember { GatewayHealth.init(ctx); 0 }
    @Suppress("UNUSED_VARIABLE") val v by GatewayHealth.version.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var mirrorFor by remember { mutableStateOf<ProviderInstance?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

    MibotScaffold(title = t("AI 网关", "AI gateway"), onBack = onBack) {
        MibotFootnote(t(
            "每个服务商实例就是池中的一个账号。状态取自最近一次真实检测：网络不通会自动修复（重试、DoH 解析、备用地址），401/403 标记 Key 无效，429 限流，402 余额不足；模型组按“回退/负载均衡”在可用账号之间自动切换。",
            "Each provider instance is one account in a pool. Status comes from the latest real check: network failures auto-repair (retry, DNS-over-HTTPS, fallback URL); 401/403 = invalid key, 429 = rate-limited, 402 = no credit; model groups fail over across healthy accounts.",
        ))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MibotButton(if (checking) t("体检中…", "Checking…") else t("全部体检", "Health-check all"), enabled = !checking, modifier = Modifier.weight(1f), onClick = {
                checking = true
                scope.launch {
                    try {
                        // Probe everyone first so every row updates quickly, then repair.
                        val insts = config.instances.filter { it.isEnabled }
                        insts.forEach { runCatching { GatewayProbe.check(ctx, repo, it, autoRepair = false) } }
                        insts.filter { GatewayHealth.state(it.id).kind == GatewayNet.Kind.NETWORK }.forEach { inst ->
                            launch { runCatching { GatewayProbe.repair(ctx, repo, inst) } }
                        }
                    } finally { checking = false }
                }
            })
            MibotButton(t("添加账号", "Add account"), tonal = true, modifier = Modifier.weight(1f), onClick = onAddProvider)
        }

        val pools = config.instances.groupBy { poolKey(it) }
        if (pools.isEmpty()) {
            MibotFootnote(t("还没有服务商。先添加一个（免费可用的也行）。", "No providers yet — add one (free ones work)."))
        }
        pools.forEach { (pool, list) ->
            MibotSectionHeader("$pool · ${list.size}")
            MibotCard {
                list.forEachIndexed { i, inst ->
                    val s = GatewayHealth.state(inst.id)
                    val (label, kind) = gatewayStatus(ctx, inst, s, now)
                    val models = config.modelEntries.count { it.providerInstanceId == inst.id && !it.isHidden }
                    val regionsDown = s.regionsDown.filterValues { it > now }.keys
                    val defaultRegion = t("默认", "default")
                    val sub = buildString {
                        append(t("模型 ", "models ")).append(models)
                        append(t(" · 失败 ", " · failures ")).append(s.failures)
                        if (s.kind == GatewayNet.Kind.OK && s.latencyMs > 0) append(" · ").append(s.latencyMs).append(" ms")
                        s.lastCheck?.let { append("\n").append(it.take(90)) }
                        if (regionsDown.isNotEmpty()) append("\n").append(t("区域不可用：", "Region unavailable: "))
                            .append(regionsDown.joinToString { if (it.isEmpty()) defaultRegion else "$it:" })
                        if (s.repair != GatewayHealth.Repair.NONE && !s.repairNote.isNullOrBlank()) append("\n").append(s.repairNote)
                        if (s.dead) append(t("\n需要重新登录或更换 Key", "\nSign in again or replace the key"))
                    }
                    MibotRow(
                        title = inst.label,
                        subtitle = sub,
                        icon = Icons.Outlined.Hub,
                        color = MibotTokens.Brick,
                        onClick = { onProvider(inst.id) },
                        showDivider = false,
                        trailing = { StatusChip(label, kind) },
                    )
                    val repairing = s.repair == GatewayHealth.Repair.RUNNING
                    Row(
                        Modifier.fillMaxWidth().padding(start = 70.dp, end = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SmallAction(if (repairing) t("修复中…", "Repairing…") else t("修复", "Repair"), enabled = inst.isEnabled && !repairing) {
                            scope.launch {
                                val o = GatewayProbe.probe(repo, inst)
                                GatewayHealth.recordProbe(inst.id, o)
                                if (o.kind == GatewayNet.Kind.OK) {
                                    GatewayHealth.setRepair(inst.id, GatewayHealth.Repair.FIXED, com.openminis.app.mibot.design.tc(ctx, "已修复", "Fixed"))
                                } else if (o.kind == GatewayNet.Kind.NETWORK) {
                                    GatewayProbe.repair(ctx, repo, inst)
                                } else {
                                    GatewayHealth.setRepair(inst.id, GatewayHealth.Repair.FAILED,
                                        com.openminis.app.mibot.design.tc(ctx, "修复失败：", "Repair failed: ") + o.detail.take(80))
                                }
                            }
                        }
                        SmallAction(t("检测", "Check"), enabled = inst.isEnabled && !repairing) {
                            scope.launch { GatewayProbe.check(ctx, repo, inst) }
                        }
                        SmallAction(if (GatewayHealth.mirror(ctx, inst.id) != null) t("备用地址 ✓", "Fallback URL ✓") else t("备用地址", "Fallback URL")) {
                            mirrorFor = inst
                        }
                    }
                    if (i < list.lastIndex) com.openminis.app.mibot.design.MibotDivider()
                }
            }
        }

        mirrorFor?.let { inst ->
            var text by remember(inst.id) { mutableStateOf(GatewayHealth.mirror(ctx, inst.id).orEmpty()) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { mirrorFor = null },
                title = { androidx.compose.material3.Text(t("备用地址（镜像 / 反代）", "Fallback URL (mirror / reverse proxy)")) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text(
                            t("网络不通时自动修复会尝试这个地址，成功后切换过去。填写兼容同一接口的基址，例如 https://your-proxy.example.com/v1",
                              "Auto-repair tries this when the network fails and switches to it if it works. Use a base URL serving the same API, e.g. https://your-proxy.example.com/v1"),
                            fontSize = com.openminis.app.mibot.design.MibotType.Secondary,
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                        com.openminis.app.mibot.design.GlassField(value = text, onValueChange = { text = it }, label = "Base URL", placeholder = "https://…")
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { GatewayHealth.setMirror(ctx, inst.id, text); mirrorFor = null }) {
                        androidx.compose.material3.Text(t("保存", "Save"))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { GatewayHealth.setMirror(ctx, inst.id, null); mirrorFor = null }) {
                        androidx.compose.material3.Text(t("清除", "Clear"))
                    }
                },
            )
        }

        MibotSectionHeader(t("路由与用量", "Routing & usage"))
        MibotCard {
            MibotRow(t("模型组", "Model groups"), t("别名：一个逻辑模型 → 多个账号/服务商；回退或负载均衡", "Aliases: one logical model → many accounts/providers; fallback or load-balance"),
                Icons.Outlined.AccountTree, MibotTokens.Slate, onClick = onModelGroups, showDivider = true)
            MibotRow(t("Token 用量", "Token usage"), t("按服务商统计请求与 Token", "Requests and tokens per provider"),
                Icons.Outlined.BarChart, MibotTokens.Green, onClick = onUsage, showDivider = true)
            MibotRow(t("本地 OpenAI 兼容端点", "Local OpenAI-compatible endpoint"), "127.0.0.1 · " + t("即将支持", "coming soon"),
                Icons.Outlined.Dns, MibotTokens.Steel, chevron = false, trailing = { StatusChip(t("即将支持", "Soon"), ChipKind.NEUTRAL) })
        }
    }
}

@Composable
private fun SmallAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = MibotTokens.accent()
    androidx.compose.material3.Text(
        text,
        fontSize = com.openminis.app.mibot.design.MibotType.Caption,
        color = if (enabled) accent else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(accent.copy(alpha = if (enabled) 0.12f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * [fork/mibot-v5] Debug/CI only: seed the gateway with the SAME providers the
 * vault demo shows as connected (OpenRouter, 硅基流动), so 账号 and AI 网关
 * tell one consistent story in screenshots. Keys are fake; the health line
 * says so ("CI 演示数据，未真实检测") instead of pretending a probe ran.
 */
object GatewayDemo {
    fun seed(ctx: Context) {
        val app = ctx.applicationContext as? MinisApp ?: return
        val repo = app.providerRepositoryOrNull ?: return
        runCatching {
            // Re-seed whenever the demo providers are actually absent (not on a
            // one-shot flag): a CI relaunch may restart before the provider
            // table persisted, and a stale "seeded" flag then left the dark
            // theme launch on onboarding with no provider at all.
            val prefs = ctx.getSharedPreferences("mibot_gateway_demo", Context.MODE_PRIVATE)
            repo.ensureConfigLoaded()
            if (repo.config.value.instances.any { it.label == "OpenRouter" || it.label == "硅基流动" }) {
                prefs.edit().putBoolean("seeded", true).commit(); return
            }
            prefs.edit().putBoolean("seeded", true).commit()
            GatewayHealth.init(ctx)
            val demo = listOf(
                ProviderInstance(
                    id = java.util.UUID.randomUUID().toString(), label = "OpenRouter",
                    providerType = ProviderType.openRouter, credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                ) to "sk-or-demo",
                ProviderInstance(
                    id = java.util.UUID.randomUUID().toString(), label = "硅基流动",
                    providerType = ProviderType.openAI, credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                    customBaseURL = "https://api.siliconflow.cn/v1", appendV1Suffix = false,
                ) to "sk-demo",
            )
            demo.forEachIndexed { i, (inst, key) ->
                repo.addInstance(inst)
                repo.saveApiKey(inst.id, key)
                GatewayHealth.recordProbe(
                    inst.id,
                    GatewayNet.Outcome(GatewayNet.Kind.OK, 200, 280L + i * 140L, "demo"),
                    label = "CI 演示数据，未真实检测",
                )
            }
        }
    }
}
