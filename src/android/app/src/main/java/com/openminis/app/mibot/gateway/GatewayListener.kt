package com.openminis.app.mibot.gateway

import java.io.Closeable
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地监听器（纯 JVM，可单测）。
 *
 * 把 LocalGateway 的“绑定 + accept 循环 + 生命周期”拆出来，原因有二：
 *  - 端口冲突处理：优先绑定 [desiredPort]，被占则依次尝试后续端口，最后交给系统
 *    分配端口（0）。每次失败的 bind 都会关闭对应 socket，绝不触碰已占用端口的
 *    其它服务（例如设备上另一个 app 正在监听的 *:8788）。
 *  - stop/restart 竞态：accept 循环按“代际”守卫，旧实例被 close 后，它遗留的
 *    accept 线程不会再影响之后新建的实例（不会误把新实例标记成停止）。
 *
 * 只负责监听与分发，HTTP 解析由调用方在 [onAccept] 里完成。
 */
class GatewayListener private constructor(
    /** 绑定地址（回退只在同一 host 上进行，默认仅回环 127.0.0.1）。 */
    val host: String,
    /** 实际绑定端口；发生回退时与请求的 desiredPort 不同。 */
    val port: Int,
    /** 是否发生了端口回退（desiredPort 被占用）。 */
    val fallbackUsed: Boolean,
    /** 给人看的说明文案；无冲突时为空串。 */
    val note: String,
    private val socket: ServerSocket,
    private val acceptExecutor: Executor,
    private val onAccept: (Socket) -> Unit,
) : Closeable {

    companion object {
        const val DEFAULT_MAX_TRIES = 10

        /**
         * 绑定 host:desiredPort；端口被占时依次尝试 [desiredPort+1] 起共 [maxTries]
         * 个端口，最后用系统分配端口（0）。
         * @throws BindException 全部尝试失败（含系统分配端口本身）。
         */
        fun bind(
            host: String = "127.0.0.1",
            desiredPort: Int,
            maxTries: Int = DEFAULT_MAX_TRIES,
            acceptExecutor: Executor = java.util.concurrent.Executors.newCachedThreadPool { r ->
                Thread(r, "GatewayListener").apply { isDaemon = true }
            },
            onAccept: (Socket) -> Unit = {},
        ): GatewayListener {
            val end = (desiredPort + maxTries - 1).coerceAtMost(65535)
            val ports = (desiredPort..end).toList() + 0
            var last: Exception? = null
            for (p in ports) {
                val s = ServerSocket()
                try {
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(host, p), 32)
                    val fallback = p != desiredPort
                    val note = when {
                        !fallback -> ""
                        p == 0 -> "默认端口 $desiredPort 被占用，已改用系统分配端口 ${s.localPort}"
                        else -> "默认端口 $desiredPort 被占用，已改用 $p"
                    }
                    return GatewayListener(
                        host, if (p == 0) s.localPort else p, fallback, note,
                        s, acceptExecutor, onAccept,
                    )
                } catch (e: Exception) {
                    last = e
                    // 这次 bind 未成功，也必须关掉对应 socket，避免句柄泄漏。
                    runCatching { s.close() }
                }
            }
            throw BindException(
                "无法在 $host:$desiredPort 绑定（尝试了 $desiredPort-$end 与系统分配端口）：${last?.message ?: "未知原因"}",
            )
        }
    }

    /** 代际计数：每次 start/close 递增，旧 accept 线程据此判断自己是否还是“当前”。 */
    private val generation = AtomicInteger(0)
    @Volatile private var closed = false
    @Volatile private var acceptThread: Thread? = null

    val isClosed: Boolean get() = closed

    /**
     * 启动 accept 循环。客户端连接经 [acceptExecutor] 派发给 [onAccept]。
     * 监听器被 close() 后，旧循环只会静静退出（其 socket 已关闭），不会回调。
     * @param onFatalClose 仅在本实例仍“当前”且非 close 导致的异常退出时调用一次。
     */
    fun start(onFatalClose: (String) -> Unit = {}) {
        if (closed) return
        val gen = generation.incrementAndGet()
        val local = socket
        acceptThread = Thread {
            try {
                while (!closed && generation.get() == gen) {
                    val client = try { local.accept() } catch (e: Exception) { break }
                    acceptExecutor.execute { runCatching { onAccept(client) } }
                }
            } catch (e: Exception) {
                if (!closed && generation.get() == gen) {
                    onFatalClose(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                runCatching { local.close() }
            }
        }.apply { isDaemon = true; name = "GatewayListener-Accept" }
        acceptThread?.start()
    }

    /** 关闭监听。幂等；可从任意线程调用。旧 accept 线程不会影响新实例。 */
    override fun close() {
        closed = true
        generation.incrementAndGet()
        runCatching { socket.close() }
        acceptThread = null
    }
}
