package com.mibot

import com.mibot.settings.AppAccessStore
import com.mibot.teach.TeachRuntime

/**
 * Mika 宿主接口:cap 代码(无障碍教学、授权、任务)靠 `application as? MikaHost`
 * 拿宿主侧的两条依赖——[appAccess](AppAccessStore) 和 [teach](TeachRuntime)。
 *
 * 旧栈里这是个具体类 [com.mibot.MikaApp];Mibot 的 Application 是
 * [com.openminis.app.MinisApp],所以让 MinisApp 实现 [MikaHost],把 AppAccessStore +
 * TeachRuntime 注入进去,cap 的授权/教学路径就接通了(之前 `as? MikaApp` 在 Mibot
 * 进程里恒为 null,这两条路径自然跳过)。
 *
 * [teachReady] 默认 false:宿主没接 Mika 的完整教学栈时,无障碍事件的教学捕获路径
 * 整条跳过(和旧桩 MikaApp.teachReady=false 的行为一致),等后续把 TeachRuntime 真正
 * 接进 AgentLoop 再打开。
 */
interface MikaHost {
    /** 全局的询问 / 自主授权;宿主没配时为 null,授权检查自然放行。 */
    val appAccess: AppAccessStore?

    /** 教学栈是否就绪;false 时无障碍不捕获教学事件。 */
    val teachReady: Boolean get() = false

    /** 教学 runtime;仅当 [teachReady] 为真时被使用。 */
    val teach: TeachRuntime
}
