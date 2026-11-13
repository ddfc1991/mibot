package com.openminis.app.mikova.oauth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.GlassField
import com.openminis.app.mibot.design.Glyph
import com.openminis.app.mibot.design.LineIcons
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotIcon
import com.openminis.app.mibot.design.MibotRadius
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotTopBar
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t
import com.openminis.app.mikova.accounts.AccountStore
import com.openminis.app.ui.glass.FrostLevel
import com.openminis.app.ui.glass.frosted
import kotlinx.coroutines.launch

/** 导航路由（在 AppNavigation.kt 注册，见 INTEGRATION.md）。 */
object OAuthRoutes {
    const val CONTAINER = "mikova_oauth/{sid}/{mode}"
    fun container(serviceId: String, mode: OAuthMode) = "mikova_oauth/$serviceId/${mode.name}"
}

/** 容器模式：标准 OAuth（拦截回调换 token） / 浏览器捕获（登录后抓 cookie/localStorage）。 */
enum class OAuthMode { OAUTH, BROWSER }

/**
 * 内置 OAuth 登录容器。
 *
 * - [OAuthMode.OAUTH]：加载 provider 的授权页；[WebViewClient] 拦截回调 URL
 *   （复用 [OAuthBroker.matchCallback] → [com.openminis.app.mibot.vault.Pkce.parseCallback]），
 *   取出 code 后用 [OAuthBroker.exchange] 换 token 并加密存进 [AccountStore]。
 *   顶部「在浏览器打开」可在服务拒绝 WebView（如 Google 的 disallowed_useragent）时
 *   降级到 [OAuthBroker.runLoopbackFlow]（Custom Tab + 本机回调）。
 * - [OAuthMode.BROWSER]：非标准 OAuth 的降级，可编辑地址栏登录任意站点，登录后点
 *   「我已登录，捕获凭据」抓 cookie + localStorage 存为账号。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OAuthContainerScreen(
    serviceId: String,
    mode: OAuthMode,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val broker = remember { OAuthBroker.shared(ctx) }
    val provider = remember(serviceId) { OAuthProviders.get(serviceId) }

    var initialUrl by remember(serviceId, mode) { mutableStateOf<String?>(null) }
    var verifier by remember(serviceId) { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var typedUrl by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf<com.openminis.app.mibot.vault.Pkce.Callback?>(null) }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    // oauth 模式：进入即生成授权 URL + 留存 verifier。
    LaunchedEffect(serviceId, mode) {
        if (mode == OAuthMode.OAUTH) {
            val p = provider
            if (p == null) {
                status = "未配置该服务的 OAuth 接入"
                return@LaunchedEffect
            }
            if (p.clientId.startsWith("<PH:")) {
                status = "缺少 clientId（<PH:${p.id.uppercase()}_CLIENT_ID>），见 INTEGRATION.md"
            }
            val (url, ver) = broker.beginAuthorize(p)
            initialUrl = url
            address = url
            verifier = ver
            status = "已加载授权页，请在页面里登录并点「授权」"
        } else {
            status = "在地址栏打开登录页，登录后点下方「我已登录，捕获凭据」"
        }
    }

    // oauth 模式：拦截到 code → 换 token → 落库 → 返回。
    LaunchedEffect(captured) {
        val cb = captured ?: return@LaunchedEffect
        val p = provider ?: return@LaunchedEffect
        if (cb.error != null && cb.code == null) {
            status = "授权失败：${cb.error}"
            captured = null
            return@LaunchedEffect
        }
        val code = cb.code
        if (code == null) {
            status = "回调里没有 code"
            captured = null
            return@LaunchedEffect
        }
        status = "已拦截到授权回调，正在换取 token…"
        try {
            val token = broker.exchange(p, code, verifier)
            broker.saveToken(p, token, null)
            status = "已连接 ${p.name}"
            Toast.makeText(ctx, t("连接成功", "Connected"), Toast.LENGTH_SHORT).show()
            onBack()
        } catch (e: Exception) {
            status = "换取 token 失败：${e.message ?: e.javaClass.simpleName}"
            captured = null
        }
    }

    Column(Modifier.fillMaxSize().background(MibotTokens.card())) {
        // ------------------------------------------------------- 顶部栏 + 地址栏
        MibotTopBar(
            title = when (mode) {
                OAuthMode.OAUTH -> provider?.name ?: t("OAuth", "OAuth")
                OAuthMode.BROWSER -> t("浏览器登录", "Browser sign-in")
            },
            onBack = onBack,
            actions = {
                if (mode == OAuthMode.OAUTH && provider != null) {
                    Box(
                        Modifier
                            .size(MibotIcon.TopButton)
                            .frosted(CircleShape, FrostLevel.Bar, elevation = 2.dp)
                            .clickable {
                                scope.launch {
                                    try {
                                        status = "已改用浏览器，请在浏览器里完成授权…"
                                        broker.runLoopbackFlow(provider) { s -> status = s }
                                        Toast.makeText(ctx, t("连接成功", "Connected"), Toast.LENGTH_SHORT).show()
                                        onBack()
                                    } catch (e: Exception) {
                                        status = "浏览器流程失败：${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Glyph(LineIcons.ExternalLink, size = MibotIcon.Dense, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            },
        )

        // 地址栏：oauth 只读展示；browser 可编辑 + 前往。
        Box(Modifier.fillMaxWidth().padding(horizontal = MibotTokens.ScreenPadding)) {
            if (mode == OAuthMode.BROWSER) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        GlassField(typedUrl, { typedUrl = it }, t("网址", "URL"), placeholder = "https://…")
                    }
                    Spacer(Modifier.width(8.dp))
                    MibotButton(t("前往", "Go"), onClick = {
                        val u = typedUrl.trim()
                        val full = if (u.startsWith("http")) u else "https://$u"
                        webRef?.loadUrl(full)
                    }, compact = true)
                }
            } else {
                FrostedUrlBar(address)
            }
        }

        // 进度条：细 accent 条，宽度=进度（只用 design token，不引额外组件）。
        Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = MibotTokens.ScreenPadding)) {
            Box(
                Modifier
                    .fillMaxWidth(progress / 100f)
                    .height(3.dp)
                    .clip(MibotRadius.Pill)
                    .background(MibotTokens.accent()),
            )
        }

        // 状态行：「拦截到 token」等。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MibotTokens.ScreenPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (chipLabel, chipKind) = when {
                captured?.code != null -> t("已拦截", "Got code") to ChipKind.OK
                status.startsWith("已拦截") || status.startsWith("已连接") || status.startsWith("已捕获") -> t("进行中", "Working") to ChipKind.OK
                status.contains("失败") || status.contains("缺少") -> t("出错", "Error") to ChipKind.ERR
                else -> t("进行中", "Working") to ChipKind.NEUTRAL
            }
            StatusChip(chipLabel, chipKind)
            Spacer(Modifier.width(8.dp))
            Text(
                status,
                fontSize = MibotType.Secondary,
                lineHeight = MibotType.SecondaryLine,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        // ------------------------------------------------------- WebView
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                if (mode == OAuthMode.OAUTH) {
                                    val cb = provider?.let { broker.matchCallback(it, url) }
                                    if (cb != null) {
                                        captured = cb
                                        view.stopLoading()
                                        return true
                                    }
                                }
                                address = url
                                return false
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                if (mode == OAuthMode.OAUTH) {
                                    val cb = provider?.let { broker.matchCallback(it, url) }
                                    if (cb != null && captured == null) {
                                        captured = cb
                                        view.stopLoading()
                                    }
                                }
                                address = url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                progress = 100
                                address = url
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webRef = this
                        // oauth 模式在 initialUrl 就绪后由下面的 LaunchedEffect 加载。
                    }
                },
                update = { view ->
                    val target = initialUrl
                    if (mode == OAuthMode.OAUTH && target != null && view.url != target) {
                        view.loadUrl(target)
                    }
                },
            )
        }

        // ------------------------------------------------------- browser 捕获底栏
        if (mode == OAuthMode.BROWSER) {
            Box(Modifier.fillMaxWidth().padding(horizontal = MibotTokens.ScreenPadding, vertical = 8.dp)) {
                MibotButton(
                    t("我已登录，捕获凭据", "I'm signed in — capture"),
                    onClick = { captureCurrent(webRef, ctx, serviceId, scope, { status = it }, onBack) },
                    enabled = webRef?.url != null,
                )
            }
        }
    }
}

/** oauth 模式的只读地址栏（锁图标 + URL，展示当前授权/回调地址）。 */
@Composable
private fun FrostedUrlBar(url: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .frosted(MibotRadius.RowShape, elevation = 1.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(LineIcons.Lock, size = MibotIcon.Small, tint = MibotTokens.okColor())
            Spacer(Modifier.width(8.dp))
            Text(
                url.ifBlank { "…" },
                fontSize = MibotType.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** browser 模式：抓当前页 cookie + localStorage，加密存为账号后返回。 */
private fun captureCurrent(
    web: WebView?,
    ctx: android.content.Context,
    serviceId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    setStatus: (String) -> Unit,
    onDone: () -> Unit,
) {
    val url = web?.url
    if (url.isNullOrBlank()) {
        setStatus("还没有可捕获的页面")
        return
    }
    setStatus("正在捕获当前页凭据…")
    val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
    web.evaluateJavascript("(function(){try{return JSON.stringify(window.localStorage)}catch(e){return '{}'}})()") { raw ->
        val ls = runCatching {
            if (raw.length >= 2 && raw.startsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else raw
        }.getOrDefault("{}")
        scope.launch {
            try {
                val store = AccountStore.shared(ctx)
                val payload = org.json.JSONObject()
                    .put("url", url)
                    .put("cookie", cookie)
                    .put("localStorage", ls)
                store.add(
                    com.openminis.app.mikova.accounts.ConnectedAccount(
                        id = store.newId(),
                        serviceId = serviceId,
                        displayName = runCatching { android.net.Uri.parse(url).host ?: serviceId }.getOrDefault(serviceId),
                        connectedAt = System.currentTimeMillis(),
                        secretEnc = store.seal(payload.toString()),
                        kind = "cookie",
                    ),
                )
                setStatus("已捕获并保存（仅本机）")
                Toast.makeText(ctx, com.openminis.app.mibot.design.tc(ctx, "已捕获登录凭据", "Credentials captured"), Toast.LENGTH_SHORT).show()
                onDone()
            } catch (e: Exception) {
                setStatus("保存失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
