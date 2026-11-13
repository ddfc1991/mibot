package com.openminis.app.mikova.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.mibot.design.MibotTokens
import org.json.JSONArray
import org.json.JSONObject

/** 一个「可连接应用」怎么接入：内置 OAuth 容器 / 手填 API Key / 浏览器捕获。 */
enum class AuthType { OAUTH, APIKEY, BROWSER }

/** 应用分类（连接中心下方「可连接应用」的分组）。 */
enum class AppCategory(val zh: String) {
    AI("AI 服务"),
    COMMS("通讯"),
    TOOLS("工具"),
    OTHER("其他"),
}

/**
 * 一个可连接应用的连接模板（预置在 [AppCatalog]，无需联网）。
 * [icon]/[color] 走本体 MibotTokens 调色板，保持 glass 视觉统一。
 */
data class AppTemplate(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val category: AppCategory,
    val authType: AuthType,
    /** 授权前展示给用户的「这个应用能替你做什么 / 会拿到什么」。 */
    val summary: String,
    /** 授权获得的权限范围（与 OAuthBroker 里同名 provider 的 scopes 对齐，展示用）。 */
    val scopes: List<String> = emptyList(),
)

/** 内置应用目录：预置常见服务的连接模板。 */
object AppCatalog {
    val all: List<AppTemplate> = listOf(
        AppTemplate("deepseek", "DeepSeek", Icons.Outlined.SmartToy, MibotTokens.Brick, AppCategory.AI,
            AuthType.APIKEY, "用你的 DeepSeek API Key，作为对话 / 推理模型使用。",
            listOf("按 Key 额度计费", "不读取你的聊天历史")),
        AppTemplate("kimi", "Kimi", Icons.Outlined.AutoAwesome, MibotTokens.Purple, AppCategory.AI,
            AuthType.APIKEY, "用你的 Kimi (Moonshot) API Key，作为对话 / 长文本模型使用。",
            listOf("按 Key 额度计费", "不读取你的聊天历史")),
        AppTemplate("zhipu", "智谱 GLM", Icons.Outlined.Psychology, MibotTokens.Sky, AppCategory.AI,
            AuthType.APIKEY, "用你的智谱开放平台 API Key，调用 GLM 系列模型。",
            listOf("按 Key 额度计费", "不读取你的聊天历史")),
        AppTemplate("qwen", "通义千问", Icons.Outlined.Cloud, MibotTokens.Teal, AppCategory.AI,
            AuthType.APIKEY, "用你的阿里云百炼 / 通义 API Key，调用 Qwen 系列模型。",
            listOf("按 Key 额度计费", "不读取你的聊天历史")),
        AppTemplate("gemini", "Gemini", Icons.Outlined.Language, MibotTokens.Amber, AppCategory.AI,
            AuthType.OAUTH, "用 Google 账号授权 Gemini，让 Mibot 代你调用 Gemini 模型。",
            listOf("访问 Gemini API", "读取你的 Google 账号邮箱（仅用于识别账号）")),
        AppTemplate("openai", "OpenAI", Icons.Outlined.Extension, MibotTokens.Slate, AppCategory.AI,
            AuthType.OAUTH, "用 OpenAI 账号授权，让 Mibot 使用你的 ChatGPT 额度 / API。",
            listOf("访问 OpenAI API", "读取账号标识")),
        AppTemplate("claude", "Claude", Icons.Outlined.Description, MibotTokens.Navy, AppCategory.AI,
            AuthType.OAUTH, "用 Claude 账号授权，让 Mibot 使用你的 Claude 额度 / API。",
            listOf("访问 Claude API", "读取账号标识")),
        AppTemplate("gmail", "Gmail", Icons.Outlined.Email, MibotTokens.Red, AppCategory.COMMS,
            AuthType.OAUTH, "连接 Gmail，让 Mibot 帮你读信、起草与整理邮件。",
            listOf("读取邮件", "发送邮件（每次操作仍会向你确认）")),
        AppTemplate("outlook", "Outlook", Icons.Outlined.AlternateEmail, MibotTokens.Navy, AppCategory.COMMS,
            AuthType.OAUTH, "连接 Microsoft 邮箱，让 Mibot 帮你读信、起草与整理邮件。",
            listOf("读取邮件", "发送邮件（每次操作仍会向你确认）")),
        AppTemplate("notion", "Notion", Icons.Outlined.Build, MibotTokens.Steel, AppCategory.TOOLS,
            AuthType.OAUTH, "连接 Notion 工作区，让 Mibot 帮你写入 / 检索笔记与数据库。",
            listOf("读取你授权的页面与数据库", "写入内容（每次操作仍会向你确认）")),
        AppTemplate("github", "GitHub", Icons.Outlined.Code, MibotTokens.Taupe, AppCategory.TOOLS,
            AuthType.OAUTH, "连接 GitHub，让 Mibot 帮你读写仓库、Issue 与 Pull Request。",
            listOf("读取你授权的仓库", "写入代码 / Issue / PR（每次操作仍会向你确认）")),
        AppTemplate("sesame", "Sesame", Icons.Outlined.Mic, MibotTokens.Green, AppCategory.OTHER,
            AuthType.BROWSER, "用你已登录的浏览器会话接入 Sesame，抓取登录凭据后由 Mibot 代你使用。",
            listOf("仅保存你手动确认的登录凭据", "Mibot 不会自动改你的账号设置")),
    )

    fun byId(id: String): AppTemplate? = all.firstOrNull { it.id == id }

    /** 按 [AppCategory] 分组，保持目录声明顺序。 */
    fun grouped(): List<Pair<AppCategory, List<AppTemplate>>> =
        AppCategory.entries.map { cat -> cat to all.filter { it.category == cat } }.filter { it.second.isNotEmpty() }
}

/**
 * 一条「已连接账号」。秘密（token / api key / cookie）只以加密形态存在
 * [secretEnc]（复用本体 Android Keystore AES-GCM，见 [com.openminis.app.mibot.vault.VaultCrypto]）；
 * 其余字段是明文元数据，可安全展示。
 */
data class ConnectedAccount(
    val id: String,
    /** 对应 [AppTemplate.id]。 */
    val serviceId: String,
    /** 账号显示名：邮箱 / 用户名 / 自定义标签。 */
    val displayName: String,
    val connectedAt: Long,
    val scopes: List<String> = emptyList(),
    val secretEnc: String? = null,
    /** token | apikey | cookie —— 标明秘密是什么，便于展示与后续调用。 */
    val kind: String = "token",
    /** access_token 过期时间（epoch ms）；0 = 未知 / 不过期。 */
    val expiresAt: Long = 0L,
) {
    val hasSecret: Boolean get() = !secretEnc.isNullOrEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("serviceId", serviceId)
        put("displayName", displayName)
        put("connectedAt", connectedAt)
        put("scopes", JSONArray().apply { scopes.forEach { put(it) } })
        secretEnc?.let { put("secretEnc", it) }
        put("kind", kind)
        put("expiresAt", expiresAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ConnectedAccount = ConnectedAccount(
            id = o.getString("id"),
            serviceId = o.optString("serviceId"),
            displayName = o.optString("displayName"),
            connectedAt = o.optLong("connectedAt", System.currentTimeMillis()),
            scopes = o.optJSONArray("scopes")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { s -> s.isNotEmpty() } } }
                ?: emptyList(),
            secretEnc = if (o.has("secretEnc") && !o.isNull("secretEnc")) o.optString("secretEnc").takeIf { it.isNotEmpty() } else null,
            kind = o.optString("kind", "token").ifBlank { "token" },
            expiresAt = o.optLong("expiresAt", 0L),
        )
    }
}
