package com.openminis.app.mikova.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mikova/voice] 语音识别默认服务串的解析与诊断文案。
 * 真机最常见的“没设置默认识别服务”必须被这一层精确捕获,界面才能给出一键跳转。
 */
class VoiceDiagnosticsTest {

    @Test
    fun `null raw means not configured`() {
        assertEquals(DefaultRecognitionService.NotConfigured, VoiceDiagnostics.parseDefaultService(null))
    }

    @Test
    fun `blank raw means not configured`() {
        assertEquals(DefaultRecognitionService.NotConfigured, VoiceDiagnostics.parseDefaultService(""))
        assertEquals(DefaultRecognitionService.NotConfigured, VoiceDiagnostics.parseDefaultService("   "))
    }

    @Test
    fun `not configured reason guides the user to voice input settings`() {
        val reason = VoiceDiagnostics.reason(DefaultRecognitionService.NotConfigured)
        assertNotNull(reason)
        assertTrue(reason!!.contains("默认"))
        assertTrue(reason.contains("设置"))
    }

    @Test
    fun `package relative class is expanded to fully qualified`() {
        val state = VoiceDiagnostics.parseDefaultService("com.google.android.googlequicksearchbox/.RecognitionService")
        assertTrue(state is DefaultRecognitionService.Configured)
        state as DefaultRecognitionService.Configured
        assertEquals("com.google.android.googlequicksearchbox", state.packageName)
        assertEquals("com.google.android.googlequicksearchbox.RecognitionService", state.className)
        assertNull(VoiceDiagnostics.reason(state))
    }

    @Test
    fun `absolute class is preserved`() {
        val state = VoiceDiagnostics.parseDefaultService("com.example.voice/com.example.voice.engine.Engine")
        assertTrue(state is DefaultRecognitionService.Configured)
        state as DefaultRecognitionService.Configured
        assertEquals("com.example.voice", state.packageName)
        assertEquals("com.example.voice.engine.Engine", state.className)
    }

    @Test
    fun `malformed raw yields a fixable reason`() {
        val state = VoiceDiagnostics.parseDefaultService("/only")
        assertTrue(state is DefaultRecognitionService.Malformed)
        val reason = VoiceDiagnostics.reason(state)
        assertNotNull(reason)
        assertTrue(reason!!.contains("无效"))
    }

    @Test
    fun `configured service has no blocker reason by itself`() {
        val state = VoiceDiagnostics.parseDefaultService("com.a.b/.R")
        assertNull(VoiceDiagnostics.reason(state))
    }
}