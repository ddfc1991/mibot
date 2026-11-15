package com.openminis.app.mibot.gateway

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 真实端口占用下的绑定回退 + 生命周期（stop/restart 不串扰）测试。
 * 全部走真实 ServerSocket / Socket，不用 mock、不做源码字符串镜像。
 */
class GatewayListenerTest {

    private val opened = mutableListOf<ServerSocket>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    /** 返回一个当前空闲的端口（拿到后立即释放，供被测代码去绑定）。 */
    private fun freePort(): Int = ServerSocket().use { s ->
        s.reuseAddress = true
        s.bind(InetSocketAddress("127.0.0.1", 0), 32)
        s.localPort
    }

    private fun bindAt(port: Int): ServerSocket {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress("127.0.0.1", port), 32)
        opened += s
        return s
    }

    @Test
    fun `binds the exact requested port when it is free`() {
        val free = freePort()
        val l = GatewayListener.bind("127.0.0.1", free, maxTries = 3)
        try {
            assertEquals(free, l.port)
            assertFalse(l.fallbackUsed)
            assertEquals("", l.note)
        } finally { l.close() }
    }

    @Test
    fun `falls back to a later port when requested is occupied and leaves the occupier running`() {
        val occupied = freePort()
        val occupier = bindAt(occupied)
        val l = GatewayListener.bind("127.0.0.1", occupied, maxTries = 5)
        try {
            assertTrue("应回退到其它端口", l.fallbackUsed)
            assertTrue("回退端口不得等于被占用端口", l.port != occupied)
            assertTrue("应落在 desiredPort 起的连续回退范围内", l.port in occupied + 1..occupied + 4)
            assertTrue("说明文案应提到被占用端口", l.note.contains(occupied.toString()))
            // 关键：被占用的“其它服务”必须原样活着，还能 accept 新连接
            val probe = Socket("127.0.0.1", occupied)
            probe.soTimeout = 2000
            try {
                occupier.accept().close()
            } finally {
                probe.close()
            }
        } finally { l.close() }
    }

    @Test
    fun `uses an OS-assigned port when every preferred port is occupied`() {
        val base = freePort()
        val occ = (0 until 3).map { bindAt(base + it) }
        val l = GatewayListener.bind("127.0.0.1", base, maxTries = 3)
        try {
            assertTrue(l.fallbackUsed)
            assertTrue("应使用系统分配端口（不等于任何被占端口）", l.port !in base until base + 3)
            assertTrue("说明文案应提到系统分配", l.note.contains("系统分配"))
        } finally { l.close() }
        occ.forEach { assertFalse("被占端口仍应可继续 accept", it.isClosed) }
    }

    @Test(expected = BindException::class)
    fun `bind throws when nothing can bind`() {
        // 203.0.113.7 是 TEST-NET-3，非本机地址，全部尝试必失败
        GatewayListener.bind("203.0.113.7", 0, 3)
    }

    @Test
    fun `dispatches accepted connections to the handler`() {
        val free = freePort()
        val got = CountDownLatch(1)
        val l = GatewayListener.bind("127.0.0.1", free, maxTries = 2, onAccept = { got.countDown() })
        l.start()
        try {
            Socket("127.0.0.1", free).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
            }
            assertTrue("应把连接交给 onAccept", got.await(3, TimeUnit.SECONDS))
        } finally { l.close() }
    }

    @Test
    fun `close is idempotent and a stopped listener refuses new connections`() {
        val free = freePort()
        val l = GatewayListener.bind("127.0.0.1", free, maxTries = 2)
        l.start()
        l.close()
        l.close() // 幂等
        assertTrue(l.isClosed)
        val refused = try {
            Socket("127.0.0.1", free).use { false }
        } catch (e: ConnectException) {
            true
        }
        assertTrue("关闭后应拒绝新连接", refused)
    }

    @Test
    fun `restart does not let a stale accept loop reset a newer listener`() {
        val free = freePort()
        val fatal = AtomicReference<String?>(null)
        val l1 = GatewayListener.bind("127.0.0.1", free, maxTries = 2, onAccept = {})
        l1.start(onFatalClose = { fatal.set(it) })
        Thread.sleep(100) // 让第一个 accept 线程真正进入 accept()
        l1.close()
        // 立刻在同一端口起新实例（旧线程可能还没完全退出）
        val accepted = CountDownLatch(1)
        val l2 = GatewayListener.bind("127.0.0.1", free, maxTries = 2, onAccept = { accepted.countDown() })
        l2.start(onFatalClose = { fatal.set(it) })
        try {
            Socket("127.0.0.1", free).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
            }
            assertTrue("新实例必须还能接受连接", accepted.await(3, TimeUnit.SECONDS))
            assertTrue("旧循环不得在新实例上报致命错误", fatal.get() == null)
        } finally { l2.close() }
    }
}
