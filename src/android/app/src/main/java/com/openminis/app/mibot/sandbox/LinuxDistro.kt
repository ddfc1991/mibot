/*
 * 移植自 Harkbox com.hark.multi.Linux（com.harkbox.multi2）
 * 三发行版（Debian 13 / Ubuntu 24.04 / Alpine）一键安装 + PRoot 启动。
 *
 * 还原依据：com.hark.multi.Linux 全部方法 dalvik 字节码（androguard dump）。
 * 功能等价翻译：下载（HTTP+进度）、自包含 POSIX tar 解压（GNU longname/longlink、
 * PAX 扩展头、symlink/hardlink/目录）、发行版初始化（resolv.conf/hosts/group/镜像源/PS1）、
 * 安装编排（busy 标记 + 后台线程 + 多镜像回退）、installed/.ok 状态。
 *
 * 与 Harkbox 的差异（有意的简化/修正）：
 *  - 进度/忙标记与 Harkbox 一致使用进程内 HashMap（Harkbox 并未用 SharedPreferences
 *    持久化 progress/busy；安装完成状态由 <dir>/.ok 文件持久化）。
 *  - Debian rootfs 为 .tar.xz，需 org.tukaani.xz.XZInputStream（Harkbox 用的同款第三方
 *    XZ 解压库）。本文件不引入编译期依赖：用反射加载，缺库时抛出带说明的 IOException。
 *    部署时在 Mibot 的 build.gradle 加入 org.tukaani:xz 即可启用 Debian 安装。
 *  - tar 内 "50 个文件" 下限校验、MB 显示（counting.n >> 20）等与原始逻辑一致。
 */
package com.openminis.app.mibot.sandbox

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import java.util.HashMap
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Linux 沙箱发行版安装器（自包含移植，无第三方编译期依赖）。
 *
 * 用法示例：
 * ```
 * LinuxDistros.install(context, "debian") { progress -> ... }   // 0f..1f
 * LinuxDistros.installWithMessage(context, "ubuntu") { msg -> ... } // Harkbox Cb 语义
 * val ok = LinuxDistros.installed(context, "alpine")
 * val proc = LinuxDistros.start(context, "debian", 80, 40)       // 交互式终端
 * ```
 */
object LinuxDistros {

    /** 发行版描述。id 为稳定标识（debian/ubuntu/alpine）。 */
    data class Distro(
        val id: String,
        val name: String,
        val desc: String,
        val size: String,
        val shell: String
    )

    /** 三发行版配置（与 Harkbox <clinit> 完全一致）。 */
    val ALL: List<Distro> = listOf(
        Distro(id = "debian", name = "Debian 13", desc = "最稳，软件最全", size = "约 90 MB", shell = "/bin/bash"),
        Distro(id = "ubuntu", name = "Ubuntu 24.04", desc = "教程最多，最常用", size = "约 30 MB", shell = "/bin/bash"),
        Distro(id = "alpine", name = "Alpine", desc = "极小极快，apk 装软件", size = "约 4 MB", shell = "/bin/sh")
    )

    /** 安装进度/状态消息（进程内）。 */
    private val progress = HashMap<String, String>()

    /** 该发行版是否正在安装。 */
    private val busy = HashMap<String, Boolean>()

    // ---------------------------------------------------------------------
    // 目录与状态
    // ---------------------------------------------------------------------

    /** 根目录：<filesDir>/linux */
    fun base(ctx: Context): File = File(ctx.filesDir, "linux")

    /** 发行版目录：<filesDir>/linux/<id> */
    fun dir(ctx: Context, id: String): File = File(base(ctx), id)

    /** rootfs 目录：<filesDir>/linux/<id>/rootfs */
    fun rootfs(ctx: Context, id: String): File = File(dir(ctx, id), "rootfs")

    /** 按 id 查找发行版。 */
    fun find(id: String): Distro? = ALL.firstOrNull { it.id == id }

    /** 当前进度状态字符串（progress 映射；空串表示空闲）。 */
    fun state(id: String): String = synchronized(progress) { progress[id] ?: "" }

    /** 该发行版是否正在安装。 */
    fun isBusy(id: String): Boolean = synchronized(busy) { busy[id] == true }

    /** 是否已安装完成（由 <dir>/.ok 标记文件判定，持久化）。 */
    fun installed(ctx: Context, id: String): Boolean = File(dir(ctx, id), ".ok").exists()

    private fun set(id: String, msg: String, cb: (String) -> Unit) {
        synchronized(progress) { progress[id] = msg }
        cb(msg)
    }

    // ---------------------------------------------------------------------
    // 安装编排
    // ---------------------------------------------------------------------

    /**
     * 一键安装（任务要求的签名）。progress 为 0f..1f 进度；消息内部转换为进度。
     */
    fun install(ctx: Context, id: String, progress: (Float) -> Unit = {}) {
        installInternal(ctx, id) { msg -> progress(messageToProgress(msg)) }
    }

    /**
     * 一键安装（Harkbox Cb 等价）。onMessage 收到与 Harkbox 一致的进度文案，
     * 例如 "下载并解压中 45% · 12 个文件 · mirrors.tuna.tsinghua.edu.cn"。
     */
    fun installWithMessage(ctx: Context, id: String, onMessage: (String) -> Unit = {}) {
        installInternal(ctx, id, onMessage)
    }

    /** 消息 → 0f..1f 进度（粗粒度映射）。 */
    private fun messageToProgress(msg: String): Float = when {
        msg.contains('%') -> (msg.substringBefore('%').trim().substringAfterLast(' ').toFloatOrNull() ?: 0f) / 100f
        msg.contains(" MB") -> 0.5f
        msg.startsWith("安装失败") -> 0f
        msg.contains("最后设置") -> 0.99f
        msg.isEmpty() -> 1f
        else -> 0f
    }

    private fun installInternal(ctx: Context, id: String, onMessage: (String) -> Unit) {
        synchronized(busy) {
            if (busy[id] == true) return
            busy[id] = true
        }
        Thread {
            try {
                val app = ctx.applicationContext
                val d = dir(app, id)
                set(id, "正在找下载地址…", onMessage)
                val urls = urls(id)
                if (urls.isEmpty()) throw IOException("所有镜像都连不上，检查一下网络")
                if (d.exists()) deleteTree(d)
                val root = rootfs(app, id)
                root.mkdirs()
                var ok = false
                for (url in urls) {
                    try {
                        download(url, root, id, onMessage)
                        set(id, "正在做最后设置…", onMessage)
                        setup(app, id, root)
                        FileOutputStream(File(d, ".ok")).use { }
                        set(id, "", onMessage)
                        ok = true
                        break
                    } catch (e: Exception) {
                        // 单个镜像失败：清掉半成品，尝试下一个镜像
                        deleteTree(root)
                        root.mkdirs()
                    }
                }
                if (!ok) throw IOException("所有镜像都连不上，检查一下网络")
            } catch (e: Exception) {
                set(id, "安装失败：" + (e.message ?: ""), onMessage)
            } finally {
                synchronized(busy) { busy[id] = false }
            }
        }.start()
    }

    // ---------------------------------------------------------------------
    // 下载地址解析（镜像源前缀 + 最新版本匹配）
    // ---------------------------------------------------------------------

    /**
     * 返回该发行版按优先级排列的 rootfs 下载地址（每个镜像源一个候选）。
     * 与 Harkbox urls() 一致：先 GET 目录/清单页，用 last() 取"最大"匹配项。
     */
    fun urls(id: String): List<String> {
        val result = ArrayList<String>()
        when (id) {
            "alpine" -> {
                val prefixes = arrayOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/alpine",
                    "https://dl-cdn.alpinelinux.org/alpine",
                    "https://mirrors.aliyun.com/alpine"
                )
                for (prefix in prefixes) {
                    // 单个镜像失败(如 403/超时)不拖垮整个安装:跳过继续下一个镜像
                    val content = runCatching { get("$prefix/latest-stable/releases/aarch64/latest-releases.yaml") }.getOrNull() ?: continue
                    val file = last(content, "alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz") ?: continue
                    result.add("$prefix/latest-stable/releases/aarch64/$file")
                }
            }
            "ubuntu" -> {
                val prefixes = arrayOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage",
                    "https://cdimage.ubuntu.com",
                    "https://mirrors.aliyun.com/ubuntu-cdimage"
                )
                for (prefix in prefixes) {
                    // 单个镜像失败(如 403/超时)不拖垮整个安装:跳过继续下一个镜像
                    val content = runCatching { get("$prefix/ubuntu-base/releases/24.04/release/") }.getOrNull() ?: continue
                    val file = last(content, "ubuntu-base-24\\.04(\\.[0-9]+)?-base-arm64\\.tar\\.gz") ?: continue
                    result.add("$prefix/ubuntu-base/releases/24.04/release/$file")
                }
            }
            "debian" -> {
                val prefixes = arrayOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/lxc-images",
                    "https://images.linuxcontainers.org",
                    "https://mirrors.cernet.edu.cn/lxc-images"
                )
                for (prefix in prefixes) {
                    // 单个镜像失败(如 403/超时)不拖垮整个安装:跳过继续下一个镜像
                    val content = runCatching { get("$prefix/images/debian/trixie/arm64/default/") }.getOrNull() ?: continue
                    val stamp = last(content, "20[0-9]{6}_[0-9]{2}:[0-9]{2}") ?: continue
                    result.add("$prefix/images/debian/trixie/arm64/default/$stamp/rootfs.tar.xz")
                }
            }
        }
        return result
    }

    /**
     * 取 text 中正则 regex 的"最大"匹配（字典序最大 = 最新版本/时间戳）。
     * 等价 Harkbox Linux.last()。
     */
    private fun last(text: String, regex: String): String? {
        val m = Pattern.compile(regex).matcher(text)
        var best: String? = null
        while (m.find()) {
            val g = m.group()
            if (best == null || g.compareTo(best) > 0) best = g
        }
        return best
    }

    /** GET 并返回 UTF-8 文本（跟随重定向；非 200 抛 IOException("HTTP x")）。 */
    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HarkMulti")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        val baos = ByteArrayOutputStream()
        conn.inputStream.use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                baos.write(buf, 0, r)
            }
        }
        return baos.toString("UTF-8")
    }

    // ---------------------------------------------------------------------
    // 下载 + 解压
    // ---------------------------------------------------------------------

    /**
     * HTTP 下载并直接解压到 file（rootfs 目录）。
     * .xz（Debian）走 org.tukaani.xz.XZInputStream（反射加载），.gz 走 GZIPInputStream。
     */
    fun download(url: String, file: File, id: String, cb: (String) -> Unit = {}) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HarkMulti")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        val total = conn.contentLength.toLong()
        val counting = Counting(BufferedInputStream(conn.inputStream, 65536))
        val stream = openDecompressor(counting, url)
        val host = URL(url).host
        untar(BufferedInputStream(stream, 65536), file, id, cb, counting, total, host)
        stream.close()
        conn.disconnect()
    }

    /**
     * 便捷下载：解压到 file，进度 0f..1f（不绑定发行版 id/状态）。
     * 完整安装请用 [install]。
     */
    fun download(url: String, file: File, progress: (Float) -> Unit = {}) {
        download(url, file, "", { msg -> progress(messageToProgress(msg)) })
    }

    /**
     * 按扩展名选择解压流。.xz 需 org.tukaani:xz（Harkbox 同款）；通过反射避免编译期依赖，
     * 缺库时抛出带部署说明的 IOException。
     */
    private fun openDecompressor(raw: InputStream, url: String): InputStream {
        if (!url.endsWith(".xz")) return GZIPInputStream(raw, 65536)
        return try {
            val cls = Class.forName("org.tukaani.xz.XZInputStream")
            cls.getConstructor(InputStream::class.java).newInstance(raw) as InputStream
        } catch (e: Exception) {
            throw IOException("需要 XZ 解压库 org.tukaani.xz（Debian rootfs 为 .tar.xz）。" +
                    "请在 Mibot build.gradle 添加 org.tukaani:xz 依赖。原因：${e.message}")
        }
    }

    /** 自包含 POSIX tar 解压（等价 Harkbox untar()，含 GNU 'L'/'K'、PAX 'x'、硬/软链接、目录延迟 chmod）。 */
    private fun untar(
        input: InputStream,
        dir: File,
        id: String,
        cb: (String) -> Unit,
        counting: Counting,
        total: Long,
        host: String
    ) {
        val header = ByteArray(512)
        val links = ArrayList<Array<String>>()   // 硬链接 [安全目标, 安全路径]
        val dirs = ArrayList<Array<Any>>()        // 目录 [File, mode]
        var count = 0
        var lastUpdate = 0L
        var linkname = ""    // 头 linkname
        var longname = ""    // GNU 'L'
        var longlink = ""    // GNU 'K'
        var paxPath: String? = null
        var paxLink: String? = null

        while (true) {
            readFully(input, header, 512)
            if (header[0].toInt() == 0) break   // 归档结束（连续零块）

            var hName = str(header, 0, 100)
            val mode = octal(header, 100, 8).toInt()
            val size = octal(header, 124, 12)
            val typeflag = header[156].toInt().toChar()
            linkname = str(header, 157, 100)
            if (str(header, 257, 5) == "ustar") {
                val prefix = str(header, 345, 155)
                if (prefix.isNotEmpty()) hName = "$prefix/$hName"
            }

            when (typeflag) {
                'L' -> { // GNU long name
                    longname = str(readData(input, size), 0, size.toInt())
                    continue
                }
                'K' -> { // GNU long link
                    longlink = str(readData(input, size), 0, size.toInt())
                    continue
                }
                'x' -> { // PAX 扩展头：解析 path/linkpath
                    parsePax(readData(input, size)) { k, v ->
                        if (k == "path") paxPath = v
                        if (k == "linkpath") paxLink = v
                    }
                    continue
                }
                'g' -> { // PAX 全局头：跳过
                    skip(input, roundUp(size))
                    continue
                }
            }

            val entryName = paxPath ?: if (longname.isNotEmpty()) longname else hName
            val entryLink = paxLink ?: if (longlink.isNotEmpty()) longlink else linkname
            paxPath = null; paxLink = null; longname = ""; longlink = ""

            val safePath = safe(entryName)
            val f = if (safePath == null) null else File(dir, safePath)
            if (f != null) f.parentFile?.mkdirs()

            when (typeflag) {
                '5' -> { // 目录
                    if (f != null) {
                        f.mkdirs()
                        dirs.add(arrayOf(f, mode))
                    }
                    skip(input, roundUp(size))
                }
                '2' -> { // 符号链接
                    if (f != null) {
                        rm(f)
                        Os.symlink(entryLink, f.path)
                    }
                    skip(input, roundUp(size))
                }
                '1' -> { // 硬链接：延迟到末尾统一处理
                    val target = safe(entryLink)
                    if (safePath != null && target != null) links.add(arrayOf(target, safePath))
                    skip(input, roundUp(size))
                }
                '0', '\u0000', '7' -> { // 普通文件
                    if (f == null) {
                        skip(input, roundUp(size))
                    } else {
                        rm(f)
                        val out = FileOutputStream(f)
                        var remaining = size
                        val buf = ByteArray(65536)
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(65536L, remaining).toInt())
                            if (n < 0) throw IOException("文件不完整")
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                        out.close()
                        Os.chmod(f.path, (mode and 0x1FF) or 0x180) // (mode & 0o777) | 0o600
                        skip(input, pad(size))
                    }
                }
                else -> skip(input, roundUp(size)) // 其他类型（char/block/fifo 等）跳过
            }

            count++
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 400) {
                val prog = if (total > 0) "${counting.n * 100 / total}%" else "${counting.n shr 20} MB"
                set(id, "下载并解压中 $prog · $count 个文件 · $host", cb)
                lastUpdate = now
            }
        }

        // 处理延迟的硬链接：优先 link，失败则复制内容并继承源模式
        for (link in links) {
            val src = File(dir, link[0])
            val dst = File(dir, link[1])
            try {
                rm(dst)
                Os.link(src.path, dst.path)
            } catch (e: Exception) {
                rm(dst)
                FileInputStream(src).use { ins ->
                    FileOutputStream(dst).use { outs ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                        }
                    }
                }
                Os.chmod(dst.path, Os.stat(src.path).st_mode and 0x1FF)
            }
        }
        // 目录权限延迟设置：mode & 0o777 | 0o700
        for (d in dirs) {
            val f = d[0] as File
            val m = d[1] as Int
            Os.chmod(f.path, (m and 0x1FF) or 0x1C0)
        }
        if (count < 50) throw IOException("下载的文件不对（只有 $count 个）")
    }

    /**
     * 便捷解压：把 tar 流解压到 dir（无进度上报，无 id/状态绑定）。
     * 完整安装请用 [install]。
     */
    fun untar(input: InputStream, dir: File) {
        untar(input, dir, "", {}, Counting(input), -1L, "")
    }

    /** PAX 记录解析："LEN key=value\n" 序列。 */
    private fun parsePax(data: ByteArray, on: (String, String) -> Unit) {
        val s = String(data, Charsets.UTF_8)
        var i = 0
        while (i < s.length) {
            var j = i
            while (j < s.length && s[j] != ' ') j++
            if (j >= s.length) break
            val len = s.substring(i, j).toIntOrNull() ?: break
            val end = minOf(i + len, s.length)
            val record = s.substring(j + 1, end).trimEnd('\n', '\r')
            val eq = record.indexOf('=')
            if (eq > 0) on(record.substring(0, eq), record.substring(eq + 1))
            if (end <= i) break
            i = end
        }
    }

    private fun roundUp(size: Long): Long = (size + 511) / 512 * 512

    private fun pad(size: Long): Long = (512 - size % 512) % 512

    /** 过滤/规范化归档路径；不安全（..、空、.）返回 null。 */
    private fun safe(name: String): String? {
        var s = name
        if (s.startsWith("./")) s = s.substring(2)
        if (s.startsWith("/")) s = s.substring(1)
        if (s == ".") return null
        if (s.isEmpty()) return null
        if (s.split("/").any { it == ".." }) return null
        if (s.endsWith("/")) s = s.substring(0, s.length - 1)
        return s
    }

    // ---------------------------------------------------------------------
    // 发行版初始化（等价 Harkbox setup()）
    // ---------------------------------------------------------------------

    /** 解压完成后初始化 rootfs：DNS、hosts、tmp/root 权限、group 补充、镜像源、PS1 欢迎。 */
    private fun setup(ctx: Context, id: String, dir: File) {
        write(File(dir, "etc/resolv.conf"), "nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
        write(File(dir, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        val tmp = File(dir, "tmp")
        tmp.mkdirs()
        Os.chmod(tmp.path, 0x1FF) // 0o777
        File(dir, "root").mkdirs()

        // 把 app 的 supplementary groups / uid 写进 etc/group，避免 rootfs 内权限问题
        val status = read(File("/proc/self/status"))
        val groupFile = File(dir, "etc/group")
        val group = read(groupFile)
        if (status != null && group != null) {
            val m = Pattern.compile("Groups:\\s*([0-9 ]*)").matcher(status)
            if (m.find()) {
                val sb = StringBuilder()
                for (gid in m.group(1).trim().split(Regex("\\s+"))) {
                    if (gid.isEmpty()) continue
                    if (!group.contains(":$gid:")) {
                        sb.append("aid_").append(gid).append(":x:").append(gid).append(":root\n")
                    }
                }
                val uid = Process.myUid().toString()
                if (!group.contains(":$uid:")) {
                    sb.append("aid_app:x:").append(uid).append(":root\n")
                }
                if (sb.isNotEmpty()) {
                    val sep = if (group.endsWith("\n")) "" else "\n"
                    write(groupFile, group + sep + sb.toString())
                }
            }
        }

        if (id == "alpine") {
            replaceIn(File(dir, "etc/apk/repositories"), "dl-cdn.alpinelinux.org", "mirrors.aliyun.com")
        } else {
            write(File(dir, "etc/apt/apt.conf.d/01-proot"), "APT::Sandbox::User \"root\";\n")
            for (f in arrayOf(
                "etc/apt/sources.list.d/debian.sources",
                "etc/apt/sources.list.d/ubuntu.sources",
                "etc/apt/sources.list"
            )) {
                val file = File(dir, f)
                replaceIn(file, "deb.debian.org", "mirrors.aliyun.com")
                replaceIn(file, "security.debian.org", "mirrors.aliyun.com")
                replaceIn(file, "ports.ubuntu.com", "mirrors.aliyun.com")
            }
        }

        val distroName = find(id)?.name ?: id
        val hark = buildString {
            append("export TERM=dumb\n")
            append("export LANG=C.UTF-8\n")
            append("export PS1='\\u@").append(id).append(":\\w\\$ '\n")
            append("[ -f /root/.hark_welcome ] || { echo '欢迎！这是装在 HarkMulti 里的 ")
            append(distroName).append("（PRoot）。'; echo '手机存储在 /sdcard。退出输入 exit。'; touch /root/.hark_welcome; }\n")
        }
        write(File(dir, "etc/profile.d/hark.sh"), hark)
    }

    // ---------------------------------------------------------------------
    // PRoot 启动（等价 Harkbox builder()/start()）
    // ---------------------------------------------------------------------

    /**
     * 构造 PRoot 启动命令。
     * @param rows 终端行数（仅 cmd==null 交互式使用）
     * @param cols 终端列数
     * @param cmd  非空表示执行该命令（/bin/sh -lc cmd）；null 表示启动交互式登录 shell。
     */
    fun builder(ctx: Context, id: String, rows: Int, cols: Int, cmd: String?): ProcessBuilder {
        val distro = find(id) ?: throw IllegalArgumentException("未知发行版：$id")
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        val tmp = File(base(ctx), "tmp")
        tmp.mkdirs()
        val root = rootfs(ctx, id)
        val cmdList = ArrayList<String>()

        if (cmd == null) {
            // 交互式终端：需要 pty + proot
            cmdList.add("$nativeLibDir/libpty.so")
            cmdList.add(rows.toString())
            cmdList.add(cols.toString())
            cmdList.add("$nativeLibDir/libproot.so")
        }
        cmdList.add("--kill-on-exit")
        cmdList.add("--link2symlink")
        cmdList.add("-0")
        cmdList.add("-r")
        cmdList.add(root.path)
        cmdList.add("-b"); cmdList.add("/dev")
        cmdList.add("-b"); cmdList.add("/proc")
        cmdList.add("-b"); cmdList.add("/sys")
        cmdList.add("-b"); cmdList.add("/dev/urandom:/dev/random")
        cmdList.add("-b"); cmdList.add("/proc/self/fd:/dev/fd")
        cmdList.add("-b"); cmdList.add("${tmp.path}:/dev/shm")
        if (File("/storage/emulated/0").exists()) {
            cmdList.add("-b"); cmdList.add("/storage/emulated/0:/sdcard")
        }
        cmdList.add("-w"); cmdList.add("/root")
        cmdList.add("/usr/bin/env")
        cmdList.add("-i")
        cmdList.add("HOME=/root")
        cmdList.add("USER=root")
        cmdList.add("TERM=dumb")
        cmdList.add("LANG=C.UTF-8")
        cmdList.add("TMPDIR=/tmp")
        cmdList.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        if (cmd == null) {
            cmdList.add(distro.shell)
            cmdList.add("-l")
        } else {
            cmdList.add("/bin/sh")
            cmdList.add("-lc")
            cmdList.add(cmd)
        }

        val pb = ProcessBuilder(cmdList)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_LOADER"] = "$nativeLibDir/libproot-loader.so"
        env["PROOT_LOADER_32"] = "$nativeLibDir/libproot-loader32.so"
        env["PROOT_TMP_DIR"] = tmp.path
        env["LD_LIBRARY_PATH"] = nativeLibDir
        pb.directory(dir(ctx, id))
        return pb
    }

    /** 启动交互式终端（cmd=null → 登录 shell）。 */
    fun start(ctx: Context, id: String, rows: Int, cols: Int): java.lang.Process =
        builder(ctx, id, rows, cols, null).start()

    // ---------------------------------------------------------------------
    // tar 自包含辅助（等价 Harkbox octal/str/readFully/skip/readData）
    // ---------------------------------------------------------------------

    /** 解析 tar 数字字段：优先 base-256，其次 8 进制字符串（跳过前导空格/NUL）。 */
    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (off < 0 || off >= b.size || len <= 0) return 0L
        if ((b[off].toInt() and 0x80) != 0) {
            // base-256 编码（GNU 大文件）：忽略首字节符号位，剩余按大端读取
            var r = 0L
            var i = 1
            while (i < len && off + i < b.size) {
                r = (r shl 8) or (b[off + i].toLong() and 0xff)
                i++
            }
            return r
        }
        val end = minOf(off + len, b.size)
        var i = off
        while (i < end && ((b[i].toInt() and 0xff) == 0x20 || (b[i].toInt() and 0xff) == 0)) i++
        var r = 0L
        while (i < end) {
            val c = b[i].toInt() and 0xff
            if (c < 0x30 || c > 0x37) break
            r = r * 8 + (c - 0x30)
            i++
        }
        return r
    }

    /** 从 off 起读 C 字符串（到 NUL 或 end 为止）。 */
    private fun str(b: ByteArray, off: Int, len: Int): String {
        if (off < 0 || off >= b.size) return ""
        val end = minOf(off + len, b.size)
        var i = off
        while (i < end && b[i].toInt() != 0) i++
        return String(b, off, i - off, Charsets.UTF_8)
    }

    /** 完整读取 len 字节，不足抛 IOException("文件不完整")。 */
    private fun readFully(input: InputStream, b: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val r = input.read(b, off, len - off)
            if (r < 0) throw IOException("文件不完整")
            off += r
        }
    }

    /** 精确跳过 n 字节，不足抛 IOException("文件不完整")。 */
    private fun skip(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(8192L, left).toInt())
            if (r < 0) throw IOException("文件不完整")
            left -= r
        }
    }

    /** 读取 size 字节的数据块 + 512 对齐填充。 */
    private fun readData(input: InputStream, size: Long): ByteArray {
        val b = ByteArray(size.toInt())
        readFully(input, b, b.size)
        skip(input, pad(size))
        return b
    }

    /** 统计读取字节数的流（等价 Harkbox Counting）。 */
    private class Counting(input: InputStream) : FilterInputStream(input) {
        var n: Long = 0
            private set
        override fun read(): Int {
            val r = super.read()
            if (r > 0) n += 1
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) n += r
            return r
        }
    }

    // ---------------------------------------------------------------------
    // 文件操作（等价 Harkbox rm/deleteTree/write/read/replaceIn）
    // ---------------------------------------------------------------------

    /** 删除文件/目录；不存在或失败回退 file.delete()。 */
    private fun rm(file: File) {
        try {
            val st = Os.lstat(file.path)
            if (OsConstants.S_ISDIR(st.st_mode)) deleteTree(file)
            else Os.remove(file.path)
        } catch (e: Exception) {
            file.delete()
        }
    }

    /** 递归删除（先 chmod 0o700 防只读目录）。 */
    private fun deleteTree(file: File) {
        try {
            val st = Os.lstat(file.path)
            if (OsConstants.S_ISDIR(st.st_mode)) {
                Os.chmod(file.path, 0x1C0) // 0o700 防只读目录
                file.list()?.forEach { deleteTree(File(file, it)) }
                Os.remove(file.path)
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            file.delete()
        }
    }

    /** 覆盖写文本（先 rm，确保父目录存在，UTF-8）。 */
    private fun write(file: File, text: String) {
        rm(file)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** 读文件为 UTF-8；失败返回 null。 */
    private fun read(file: File): String? {
        return try {
            FileInputStream(file).use { ins ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val r = ins.read(buf)
                    if (r <= 0) break
                    baos.write(buf, 0, r)
                }
                baos.toString("UTF-8")
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 若文件存在且包含 from，则把 from 替换为 to 后写回。 */
    private fun replaceIn(file: File, from: String, to: String) {
        val s = read(file) ?: return
        if (!s.contains(from)) return
        write(file, s.replace(from, to))
    }
}
