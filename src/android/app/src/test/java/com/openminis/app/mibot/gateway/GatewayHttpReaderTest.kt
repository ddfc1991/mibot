package com.openminis.app.mibot.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地 HTTP 请求读取（GatewayHttpReader）在真实 socket 上的测试。
 * 重点是 Content-Length 按**字节**读：多字节 UTF-8（中文）请求体不得卡住，
 * 分块送达的 body 也要读满。
 */
class GatewayHttpReaderTest {

    /** 起一个真实服务器 socket，客户端按 [write] 写请求，服务端按 [serverRead] 解析，回一个 ACK。 */
    private fun roundTrip(write: (OutputStream) -> Unit, serverRead: (InputStream) -> Unit): String? {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0), 32)
        val error = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                server.accept().use { s ->
                    serverRead(s.getInputStream())
                    s.getOutputStream().write("ACK".toByteArray())
                    s.getOutputStream().flush()
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }.apply { isDaemon = true; start() }
        val ack = try {
            Socket("127.0.0.1", server.localPort).use { s ->
                s.soTimeout = 3000
                write(s.getOutputStream())
                val buf = ByteArray(3)
                var r = 0
                while (r < 3) { val n = s.getInputStream().read(buf, r, 3 - r); if (n < 0) break; r += n }
                String(buf, 0, r)
            }
        } finally {
            server.close()
        }
        worker.join(3000)
        error.get()?.let { throw AssertionError("服务端读取失败", it) }
        return ack
    }

    @Test
    fun `parses request line headers and ascii body`() {
        val body = """{"model":"gpt-4o","messages":[]}"""
        val raw = buildString {
            append("POST /v1/chat/completions HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("\r\n")
            append(body)
        }.toByteArray()
        var head: GatewayHttpReader.Head? = null
        var gotBody: String? = null
        val ack = roundTrip({ out -> out.write(raw); out.flush() }) { input ->
            head = GatewayHttpReader.readHead(input)
            gotBody = GatewayHttpReader.readBody(input, head!!.contentLength)
        }
        assertEquals("ACK", ack)
        assertEquals("POST", head!!.method)
        assertEquals("/v1/chat/completions", head!!.path)
        assertEquals(body, gotBody)
    }

    @Test
    fun `cjk body is read by bytes without stalling`() {
        // 中文请求体：字节数 > 字符数。旧实现按字符读会读不满并卡到 soTimeout。
        val body = """{"model":"glm-4","messages":[{"role":"user","content":"你好，世界！中文请求体。"}]}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val raw = buildString {
            append("POST /v1/chat/completions HTTP/1.1\r\n")
            append("Host: x\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }.toByteArray() + bytes
        var gotBody: String? = null
        val ack = roundTrip({ out -> out.write(raw); out.flush() }) { input ->
            val h = GatewayHttpReader.readHead(input)
            gotBody = GatewayHttpReader.readBody(input, h!!.contentLength)
        }
        assertEquals("ACK", ack)
        assertEquals(body, gotBody)
    }

    @Test
    fun `body is read correctly when delivered in chunks`() {
        val body = "0123456789"
        val headBytes = "POST /v1/chat/completions HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n".toByteArray()
        var got: String? = null
        val ack = roundTrip(
            write = { out ->
                out.write(headBytes); out.flush()
                out.write(body.toByteArray(), 0, 3); out.flush()
                Thread.sleep(30)
                out.write(body.toByteArray(), 3, 4); out.flush()
                Thread.sleep(30)
                out.write(body.toByteArray(), 7, 3); out.flush()
            },
            serverRead = { input ->
                val h = GatewayHttpReader.readHead(input)
                got = GatewayHttpReader.readBody(input, h!!.contentLength)
            },
        )
        assertEquals("ACK", ack)
        assertEquals(body, got)
    }

    @Test
    fun `empty stream yields null head`() {
        assertNull(GatewayHttpReader.readHead(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = IOException::class)
    fun `malformed request line is rejected`() {
        GatewayHttpReader.readHead(ByteArrayInputStream("GARBAGE\r\n\r\n".toByteArray()))
    }

    @Test(expected = IOException::class)
    fun `oversized request head is rejected`() {
        val big = "X: " + "a".repeat(64 * 1024)
        GatewayHttpReader.readHead(ByteArrayInputStream(("GET / HTTP/1.1\r\n" + big + "\r\n\r\n").toByteArray()))
    }
}
