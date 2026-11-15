package com.openminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Diagnostics breadcrumbs stay bounded and never carry secrets or messages. */
class BugJournalFileTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `encode drops non-allowlisted fields and secret-like values`() {
        val line = BugJournalFile.encode(
            "gateway.start_failed",
            mapOf(
                "port" to "8788",
                "preferredPort" to "8788",
                "outcome" to "bind_error",
                // Must never survive: keys, urls, auth headers.
                "apiKey" to "sk-secret-abc",
                "baseUrl" to "https://api.example.com/v1",
                "authorization" to "Bearer sk-xyz",
                "body" to "{\"messages\":[{\"content\":\"hi\"}]}",
            ),
            null,
        )
        assertTrue(line.contains("gateway.start_failed"))
        assertTrue(line.contains("port=8788"))
        assertTrue(line.contains("outcome=bind_error"))
        assertFalse(line.contains("sk-secret"))
        assertFalse(line.contains("api.example.com"))
        assertFalse(line.contains("Bearer"))
        assertFalse(line.contains("messages"))
    }

    @Test
    fun `encode records only exception class and project frames - never message`() {
        class LocalCrash(message: String) : RuntimeException(message)
        val error = LocalCrash("secret token abc123 in this message")
        val line = BugJournalFile.encode("crash.uncaught", emptyMap(), error)
        assertTrue(line.contains("error=" + LocalCrash::class.java.name))
        assertTrue(line.contains("at=com.openminis.app.diagnostics.BugJournalFileTest"))
        assertFalse(line.contains("secret token"))
    }

    @Test
    fun `append rotates to previous file past the cap and keeps both readable`() {
        // Each encoded line is ~45-60 bytes: with a 60-byte cap, the second
        // write must exceed it and rotate the first into *-previous.log.
        val file = BugJournalFile(tmp.root, maxBytes = 60L)
        val first = BugJournalFile.encode("screen.entered", mapOf("screen" to "settings"), null)
        val second = BugJournalFile.encode("screen.entered", mapOf("screen" to "accounts"), null)
        file.append(first)
        file.append(second)
        val current = tmp.root.resolve("bug-events.log").readText()
        val previous = tmp.root.resolve("bug-events-previous.log").readText()
        assertTrue("current should carry the newest line", current.contains("accounts"))
        assertTrue("previous should carry the rotated line", previous.contains("settings"))
    }

    @Test
    fun `append remains append-only across many small lines`() {
        val file = BugJournalFile(tmp.root)
        for (i in 0 until 50) {
            file.append(BugJournalFile.encode("screen.entered", mapOf("screen" to "s$i"), null))
        }
        val lines = tmp.root.resolve("bug-events.log").readLines()
        assertEquals(50, lines.size)
        assertTrue(lines.first().contains("s0"))
        assertTrue(lines.last().contains("s49"))
    }

    @Test
    fun `routeName keeps only a stable template without query or ids`() {
        assertEquals("mibot_app_access", BugJournal.routeName("mibot_app_access"))
        assertEquals("settings", BugJournal.routeName("settings"))
        assertEquals("unknown", BugJournal.routeName(""))
        assertEquals("unknown", BugJournal.routeName("https://example.com/path"))
        assertEquals("chat", BugJournal.routeName("chat/session-abc-123"))
        assertEquals("unknown", BugJournal.routeName("/mib/chat/abc"))
    }

    @Test
    fun `screenLabel keeps CJK titles and drops control characters`() {
        assertEquals("账号连接中心", BugJournal.screenLabel("账号连接中心"))
        assertEquals("AI gateway", BugJournal.screenLabel("AI gateway"))
        assertEquals("unknown", BugJournal.screenLabel(""))
        assertEquals("unknown", BugJournal.screenLabel(null))
        assertEquals("a b", BugJournal.screenLabel("a\nb"))
        val long = "x".repeat(200)
        assertEquals(80, BugJournal.screenLabel(long).length)
    }
}