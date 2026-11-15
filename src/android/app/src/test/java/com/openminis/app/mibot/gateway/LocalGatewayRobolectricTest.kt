package com.openminis.app.mibot.gateway

import com.openminis.app.MinisApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * LocalGateway 单例在真实端口占用下的回退与 stop/restart 竞态测试。
 *
 * 依赖 MinisApp 的 provider 仓库初始化（Robolectric 环境）：若环境未把仓库准备好
 * （providerRepositoryOrNull == null），跳过而非当成逻辑失败——逻辑本身由
 * [GatewayListenerTest] 的纯 JVM 测试覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalGatewayRobolectricTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        LocalGateway.stop()
    }

    private fun repoReady(): Boolean = (ctx as? MinisApp)?.providerRepositoryOrNull != null

    private fun freePort(): Int = ServerSocket().use { s ->
        s.reuseAddress = true
        s.bind(InetSocketAddress("127.0.0.1", 0), 32)
        s.localPort
    }

    @Test
    fun `starting on an occupied port falls back and keeps the other service alive`() {
        assumeTrue("需要 provider 仓库已初始化", repoReady())
        // 占用一个端口模拟“其它服务”（对应截图里 *:8788 被占的情形）
        val occupier = ServerSocket()
        occupier.reuseAddress = true
        occupier.bind(InetSocketAddress("127.0.0.1", 0), 32)
        try {
            val occupied = occupier.localPort
            val ok = LocalGateway.start(ctx, occupied)
            assertTrue("应能启动（回退绑定）", ok)
            assertTrue(LocalGateway.isRunning)
            assertTrue("回退端口不等于被占用端口", LocalGateway.currentPort != occupied)
            assertNotNull(LocalGateway.bindNote)
            assertTrue("说明文案应提到被占用端口", LocalGateway.bindNote!!.contains(occupied.toString()))
            // “其它服务”原样活着：还能 accept 新连接
            val probe = Socket("127.0.0.1", occupied)
            probe.soTimeout = 2000
            try {
                occupier.accept().close()
            } finally {
                probe.close()
            }
            // 实际端点能响应
            val resp = rawGet(LocalGateway.boundHost, LocalGateway.currentPort, "/health")
            assertTrue(resp.startsWith("HTTP/1.1 200"))
            assertTrue(resp.contains("mibot-local-gateway"))
        } finally {
            LocalGateway.stop()
            occupier.close()
        }
    }

    @Test
    fun `stop then restart leaves a working instance and no stale error`() {
        assumeTrue("需要 provider 仓库已初始化", repoReady())
        LocalGateway.stop()
        val port = freePort()
        assertTrue(LocalGateway.start(ctx, port))
        Thread.sleep(150)
        assertTrue(LocalGateway.isRunning)
        LocalGateway.stop()
        assertTrue(!LocalGateway.isRunning)
        // 立刻重启（旧 accept 线程可能仍在退出中）
        assertTrue(LocalGateway.start(ctx, port))
        Thread.sleep(250)
        assertTrue("重启后必须仍在运行（旧循环不得误杀新实例）", LocalGateway.isRunning)
        assertEquals(null, LocalGateway.lastError)
        val resp = rawGet(LocalGateway.boundHost, LocalGateway.currentPort, "/health")
        assertTrue(resp.startsWith("HTTP/1.1 200"))
        LocalGateway.stop()
    }

    private fun rawGet(host: String, port: Int, path: String): String {
        return Socket(host, port).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArrayOutputStream()
            val b = ByteArray(4096)
            while (true) {
                val n = s.getInputStream().read(b)
                if (n < 0) break
                buf.write(b, 0, n)
            }
            String(buf.toByteArray(), Charsets.UTF_8)
        }
    }
}
