package com.openminis.app.mikova.voice

/**
 * [mikova/voice] 语音识别可用性诊断 —— 纯字符串/组件解析,不依赖 android API,
 * 可以在 JVM 单测里覆盖。平台引擎在这里把 Settings.Secure.voice_recognition_service
 * 的值喂进来,得到一个面向用户的中文诊断。
 *
 * 三个失败档:
 *  1. 系统连识别服务都没装(极少数出厂机)
 *  2. 装了服务但 Settings.Secure.voice_recognition_service 为空 —— 最常见的真机坑,
 *     表现为 SpeechRecognizer 一点就 ERROR_CLIENT
 *  3. 配置串存在但组件已失效(包被卸载/停用),解析与查询两步都能被兜住
 */
sealed class DefaultRecognitionService {
    /** Settings.Secure.voice_recognition_service 为空或纯空白。 */
    object NotConfigured : DefaultRecognitionService()

    /** 配置串格式非法(连包名都提取不出来)。 */
    data class Malformed(val raw: String) : DefaultRecognitionService()

    /**
     * 可解析的组件串。
     * @param packageName 包名,如 com.google.android.googlequicksearchbox
     * @param className   完整类名;包相对类(如 .service.Foo)已展开成全限定名;可能为 null
     * @param raw         原始配置串
     */
    data class Configured(val packageName: String, val className: String?, val raw: String) : DefaultRecognitionService()
}

object VoiceDiagnostics {

    /** 解析 Settings.Secure.voice_recognition_service 的值。 */
    fun parseDefaultService(raw: String?): DefaultRecognitionService {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return DefaultRecognitionService.NotConfigured
        val slash = s.lastIndexOf('/')
        val pkg = (if (slash >= 0) s.substring(0, slash) else s).trim()
        val cls = (if (slash >= 0) s.substring(slash + 1) else "").trim()
        if (pkg.isEmpty()) return DefaultRecognitionService.Malformed(s)
        val full = when {
            cls.isEmpty() -> null
            cls.startsWith(".") -> pkg + cls
            else -> cls
        }
        return DefaultRecognitionService.Configured(pkg, full, s)
    }

    /** 把诊断结论收敛成用户能看懂的一句话;null 表示配置本身没问题(可能仍需服务可用性检查)。 */
    fun reason(state: DefaultRecognitionService): String? = when (state) {
        DefaultRecognitionService.NotConfigured -> "系统未设置默认语音识别服务，去系统语音输入设置选一个即可"
        is DefaultRecognitionService.Malformed -> "系统默认语音识别服务配置无效，请到系统语音输入设置重新选择"
        is DefaultRecognitionService.Configured -> null
    }
}