package com.openminis.app.mibot.gateway

import com.openminis.app.diagnostics.BugJournalFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 BugJournal 日志契约（用真实 encode 逻辑，非镜像）：
 * 只记录允许键、值被清洗、异常只记类名不记 message——保证网关事件不会泄漏
 * 上游 body / credential。
 */
class GatewayJournalContractTest {

    @Test
    fun `gateway event fields are kept and value sanitized`() {
        val line = BugJournalFile.encode(
            "gateway.started",
            mapOf("port" to "8789", "preferredPort" to "8788", "models" to "0", "outcome" to "fallback"),
            null,
        )
        assertTrue(line.contains("gateway.started"))
        assertTrue(line.contains("port=8789"))
        assertTrue(line.contains("preferredPort=8788"))
        assertTrue(line.contains("models=0"))
        assertTrue(line.contains("outcome=fallback"))
    }

    @Test
    fun `disallowed fields such as body or credential are dropped`() {
        val line = BugJournalFile.encode(
            "gateway.started",
            mapOf("port" to "8789", "body" to "{\"secret\":\"sk-xxx\"}", "credential" to "sk-123456"),
            null,
        )
        assertTrue(line.contains("port=8789"))
        assertFalse("body 键不得记录", line.contains("body="))
        assertFalse("credential 键不得记录", line.contains("credential="))
        assertFalse("上游 body 内容不得泄漏", line.contains("sk-xxx"))
    }

    @Test
    fun `error records class only never the message`() {
        val line = BugJournalFile.encode(
            "gateway.start_failed",
            mapOf("reason" to "port_unavailable", "outcome" to "error"),
            RuntimeException("上游凭据 sk-super-secret 泄漏测试"),
        )
        assertTrue(line.contains("reason=port_unavailable"))
        assertTrue(line.contains("outcome=error"))
        assertTrue(line.contains("error=java.lang.RuntimeException"))
        assertFalse("异常 message 不得记录", line.contains("sk-super-secret"))
    }
}
