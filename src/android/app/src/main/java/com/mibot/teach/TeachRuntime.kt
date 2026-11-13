package com.mibot.teach

import com.mibot.core.teach.TeachObservation

/**
 * 示范会话的桩:Mika 的宿主 [com.mibot.MikaHost] 没搬进 Mibot(由 MinisApp 实现),这里只留 cap 代码用到的
 * [wantsEvents]/[offer]/[blocksTasks] 接口,不采集任何事件。完整的 TeachRuntime 在 Mika
 * 的 teach/TeachRuntime.kt,后续如需示范功能再整类搬入。
 */
class TeachRuntime {
    fun wantsEvents(): Boolean = false

    fun blocksTasks(): Boolean = false

    fun offer(event: TeachObservation) {
        // 桩:不处理
    }
}
