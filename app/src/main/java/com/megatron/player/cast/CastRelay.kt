package com.megatron.player.cast

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The receiver sees one temporary URL, never the NAS URL, account or authorization headers. */
data class RelayMedia(val url: String, val contentType: String, val length: Long)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CastRelay(private val factory: DataSource.Factory) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val sources = ConcurrentHashMap.newKeySet<DataSource>()
    @Volatile private var server: RelayHttpServer? = null

    /** Blocking: call from IO. A relay is single use; close it before creating the next one. */
    fun start(uri: Uri, deviceAddress: String, contentType: String): RelayMedia {
        check(started.compareAndSet(false, true)) { "投屏中转已启动" }
        check(!closed.get()) { "投屏中转已关闭" }
        try {
            require(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+").matches(contentType)) { "视频媒体类型无效" }
            val target = InetAddress.getByName(deviceAddress.removeSurrounding("[", "]"))
            require(!target.isAnyLocalAddress && !target.isMulticastAddress) { "电视地址无效" }
            val local = DatagramSocket().use { socket ->
                socket.connect(target, 9)
                socket.localAddress.takeUnless { it.isAnyLocalAddress } ?: throw IOException("找不到连接电视的本机网络")
            }
            // Deliberately do not specify DataSpec.length: it would disguise an unknown source length.
            val probe = newSource()
            val length = try { probe.open(DataSpec.Builder().setUri(uri).build()) } finally { release(probe) }
            if (length <= 0) throw IOException("视频来源未提供有效文件长度，暂不能投屏此资源")
            if (closed.get()) throw IOException("投屏已取消")
            val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val path = "/cast/$token"
            val created = RelayHttpServer(local, target, path, contentType, length) { start, count ->
                val source = newSource()
                try {
                    source.open(DataSpec.Builder().setUri(uri).setPosition(start).setLength(count).build())
                    if (closed.get()) throw IOException("投屏已取消")
                    object : InputStream() {
                        private val released = AtomicBoolean(false)
                        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, length)
                        override fun close() { if (released.compareAndSet(false, true)) release(source) }
                    }
                } catch (failure: Throwable) { release(source); throw failure }
            }
            server = created
            if (closed.get()) { created.close(); throw IOException("投屏已取消") }
            created.start()
            val host = local.hostAddress?.substringBefore('%') ?: throw IOException("本机网络地址不可用")
            val urlHost = if (host.contains(':')) "[$host]" else host
            return RelayMedia("http://$urlHost:${created.port}$path", contentType, length)
        } catch (failure: Throwable) { close(); throw failure }
    }

    private fun newSource(): DataSource {
        if (closed.get()) throw IOException("投屏已关闭")
        val source = factory.createDataSource()
        sources.add(source)
        if (closed.get()) { release(source); throw IOException("投屏已关闭") }
        return source
    }

    private fun release(source: DataSource) { sources.remove(source); runCatching { source.close() } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server?.close()
        sources.toList().forEach(::release)
    }
}

internal data class RelayRange(val start: Long, val end: Long, val partial: Boolean) {
    val length: Long get() = end - start + 1
}

/** One range only. Invalid, multiple and unsatisfiable ranges receive 416. */
internal fun relayRange(header: String?, total: Long): RelayRange {
    require(total > 0)
    if (header == null) return RelayRange(0, total - 1, false)
    val match = Regex("bytes=([0-9]*)-([0-9]*)", RegexOption.IGNORE_CASE).matchEntire(header.trim())
        ?: throw IllegalArgumentException("不支持的 Range")
    val left = match.groupValues[1]
    val right = match.groupValues[2]
    if (left.isEmpty()) {
        val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("无效的后缀范围")
        return RelayRange((total - minOf(total, suffix)).coerceAtLeast(0), total - 1, true)
    }
    val start = left.toLongOrNull()?.takeIf { it < total } ?: throw IllegalArgumentException("范围超出文件")
    val end = if (right.isEmpty()) total - 1 else right.toLongOrNull() ?: throw IllegalArgumentException("无效的范围")
    require(end >= start)
    return RelayRange(start, minOf(end, total - 1), true)
}

/** JVM-only streaming server, separated from Media3 so Range/cancellation can be checked without a device. */
internal class RelayHttpServer(
    private val local: InetAddress,
    private val target: InetAddress,
    private val path: String,
    private val mime: String,
    private val length: Long,
    private val open: (Long, Long) -> InputStream,
) : Closeable {
    private val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(local, 0), 6) }
    val port: Int = listener.localPort
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<Socket, AtomicLong>()
    private val streams = ConcurrentHashMap.newKeySet<InputStream>()
    private val workers = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, ArrayBlockingQueue(6), { task ->
        Thread(task, "cast-http").apply { isDaemon = true }
    })
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "cast-http-timeout").apply { isDaemon = true } }
    private var accepting: Thread? = null

    fun start() {
        check(!closed.get()) { "投屏中转已关闭" }
        watchdog.scheduleAtFixedRate({
            val now = System.nanoTime()
            connections.forEach { (socket, activity) -> if (now - activity.get() > TimeUnit.SECONDS.toNanos(35)) runCatching { socket.close() } }
        }, 5, 5, TimeUnit.SECONDS)
        accepting = Thread({
            while (!closed.get()) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                socket.soTimeout = 10_000
                socket.tcpNoDelay = true
                if (closed.get() || (socket.inetAddress != target && socket.inetAddress != local && !socket.inetAddress.isLoopbackAddress)) {
                    runCatching { socket.close() }; continue
                }
                connections[socket] = AtomicLong(System.nanoTime())
                try { workers.execute { handle(socket) } }
                catch (_: RejectedExecutionException) { connections.remove(socket); runCatching { socket.close() } }
            }
        }, "cast-http-accept").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        var body: InputStream? = null
        var committed = false
        try {
            run {
                val input = BufferedInputStream(socket.getInputStream(), 8192)
                val first = line(input, 2048)?.split(' ') ?: return
                if (first.size != 3 || first[2] !in setOf("HTTP/1.0", "HTTP/1.1")) { respond(socket, 400); return }
                val headers = linkedMapOf<String, String>()
                var bytes = 0
                while (true) {
                    val next = line(input, 4096) ?: throw IOException("Incomplete HTTP headers")
                    bytes += next.length + 2
                    if (bytes > 16_384 || headers.size > 64) { respond(socket, 431); return }
                    if (next.isEmpty()) break
                    val colon = next.indexOf(':')
                    if (colon <= 0) { respond(socket, 400); return }
                    val key = next.substring(0, colon).lowercase(Locale.ROOT)
                    if (!Regex("[a-z0-9!#$%&'*+.^_`|~-]+").matches(key) || headers.containsKey(key)) { respond(socket, 400); return }
                    headers[key] = next.substring(colon + 1).trim()
                }
                if (first[1] != path) { respond(socket, 404); return }
                val cors = cors(headers["origin"])
                if (first[0] == "OPTIONS") { respond(socket, 204, cors + mapOf("Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS", "Access-Control-Allow-Headers" to "Range, Content-Type, Accept-Encoding, getcontentFeatures.dlna.org, transferMode.dlna.org", "Access-Control-Max-Age" to "600")); return }
                if (first[0] !in setOf("GET", "HEAD")) { respond(socket, 405, cors + mapOf("Allow" to "GET, HEAD, OPTIONS")); return }
                val range = try { relayRange(headers["range"], length) } catch (_: IllegalArgumentException) {
                    respond(socket, 416, cors + mapOf("Content-Range" to "bytes */$length", "Accept-Ranges" to "bytes")); return
                }
                val reply = linkedMapOf(
                    "Content-Type" to mime, "Accept-Ranges" to "bytes", "Cache-Control" to "no-store",
                    "transferMode.dlna.org" to "Streaming",
                    // Byte-range seek, no server-side conversion, streaming/background/stall support.
                    "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                ).apply { putAll(cors); if (range.partial) put("Content-Range", "bytes ${range.start}-${range.end}/$length") }
                if (first[0] == "GET") {
                    body = open(range.start, range.length)
                    streams.add(body!!)
                    if (closed.get()) throw IOException("Relay closed")
                }
                respond(socket, if (range.partial) 206 else 200, reply, range.length)
                committed = true
                if (first[0] == "HEAD") return
                val output = socket.getOutputStream()
                val buffer = ByteArray(64 * 1024)
                var remaining = range.length
                while (remaining > 0 && !closed.get()) {
                    val count = body!!.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw EOFException("Source ended early")
                    if (count == 0) throw IOException("Source made no progress")
                    output.write(buffer, 0, count)
                    remaining -= count
                    connections[socket]?.set(System.nanoTime())
                }
                output.flush()
            }
        } catch (_: Exception) {
            // Keep credentials and source exception URLs off the receiver and logs.
            if (!committed && !socket.isClosed) runCatching { respond(socket, 502) }
        } finally {
            body?.let { streams.remove(it); runCatching { it.close() } }
            connections.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun cors(origin: String?): Map<String, String> {
        if (origin == null) return emptyMap()
        val parsed = runCatching { URI(origin) }.getOrNull() ?: return emptyMap()
        if (parsed.scheme !in setOf("https", "http") || parsed.host == null || parsed.userInfo != null || !parsed.rawPath.isNullOrEmpty() || parsed.query != null || parsed.fragment != null) return emptyMap()
        return mapOf("Access-Control-Allow-Origin" to origin, "Vary" to "Origin", "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges, Content-Type, contentFeatures.dlna.org")
    }

    private fun respond(socket: Socket, code: Int, headers: Map<String, String> = emptyMap(), bodyLength: Long = 0) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; 431 -> "Request Header Fields Too Large"; else -> "Bad Gateway" }
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\nConnection: close\r\n")
            if (code != 204) append("Content-Length: $bodyLength\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply { write(text.toByteArray(Charsets.ISO_8859_1)); flush() }
    }

    private fun line(input: InputStream, limit: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= limit) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else throw IOException("Incomplete HTTP line")
            if (value == 10) {
                val result = bytes.toByteArray()
                if (result.isEmpty() || result.last() != 13.toByte()) throw IOException("Invalid HTTP line")
                return String(result, 0, result.size - 1, Charsets.ISO_8859_1)
            }
            if (value < 32 && value != 13 && value != 9) throw IOException("Invalid HTTP character")
            bytes.write(value)
        }
        throw IOException("HTTP line too long")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        connections.keys.toList().forEach { runCatching { it.close() } }
        streams.toList().forEach { runCatching { it.close() } }
        workers.shutdownNow()
        watchdog.shutdownNow()
    }
}
