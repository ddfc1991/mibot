package com.openminis.app.mibot.gateway

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 本地 HTTP 请求的字节级读取。
 *
 * 此前 LocalGateway 用 `CharArray(contentLength)` + `reader.read(charArray)` 读 body，
 * 而 Content-Length 是**字节**数：对多字节 UTF-8（中文）请求体，字符数小于字节数，
 * 循环按字节上限去读字符，在 keep-alive 连接上会一直阻塞到 soTimeout 超时
 * （约 60s）才返回 500。这里改为按字节精确读取，读满 contentLength 字节为止；
 * 行解析也逐字节进行，避免 BufferedReader 预读把 body 吞进自己的缓冲区。
 */
object GatewayHttpReader {

    const val MAX_HEAD_BYTES = 32 * 1024

    data class Head(
        val method: String,
        val path: String,
        val headers: LinkedHashMap<String, String>,
        val contentLength: Int,
    )

    /** 逐字节读一行（\r\n 或 \n 结尾），去掉行尾；EOF 且无内容时返回 null。 */
    fun readLine(input: InputStream, limit: Int = MAX_HEAD_BYTES): String? {
        val buf = ByteArrayOutputStream(128)
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (total == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            total++
            if (total > limit) throw IOException("请求头过大（>${limit} 字节）")
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 读取请求行 + 请求头。返回解析后的 [Head]；EOF 返回 null。
     * @throws IOException 请求行非法或请求头过大。
     */
    fun readHead(input: InputStream): Head? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) throw IOException("请求行非法：$requestLine")
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val headers = LinkedHashMap<String, String>()
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim().lowercase()
                val v = line.substring(idx + 1).trim()
                headers[k] = v
                if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
            }
        }
        return Head(method, path, headers, contentLength)
    }

    /**
     * 精确读取 [contentLength] 字节的 body（Content-Length 是字节数，按字节读，
     * 不是字符数）。读不满（连接提前关闭）则返回已读内容。
     */
    fun readBody(input: InputStream, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = input.read(buf, read, contentLength - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }
}
