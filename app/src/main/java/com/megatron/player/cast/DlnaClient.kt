package com.megatron.player.cast

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.Closeable
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory

/** Capabilities come from the actual AVTransport SCPD, not the TV brand. */
data class DlnaDevice(
    val id: String,
    val name: String,
    val address: String,
    val controlUrl: String,
    val serviceType: String,
    val seekModes: Set<String> = emptySet(),
    val canPause: Boolean = false,
    val canGetMediaInfo: Boolean = false,
)

data class DlnaStatus(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val state: String = "UNKNOWN",
    val uri: String = "",
)

/** All methods block and belong on IO. Closing cancels only this client's calls/discovery. */
class DlnaClient(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val sockets = ConcurrentHashMap.newKeySet<MulticastSocket>()
    private var mediaDurationUri: String? = null
    private var mediaDurationMs = 0L
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS).build()

    fun discover(): List<DlnaDevice> {
        ensureOpen()
        val locations = linkedMapOf<String, InetAddress>()
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("MegatronPlayer-cast-discovery")?.apply { setReferenceCounted(false); acquire() }
            MulticastSocket(0).use { socket ->
                sockets.add(socket)
                try {
                    if (closed.get()) throw IOException("投屏搜索已取消")
                    socket.soTimeout = 250
                    socket.timeToLive = 2
                    val group = InetAddress.getByName("239.255.255.250")
                    val queries = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")
                    val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                        .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { address -> address is java.net.Inet4Address } }.getOrDefault(false) }
                        .take(4)
                    fun send() {
                        queries.forEach { service ->
                            val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $service\r\nUSER-AGENT: Android/36 UPnP/1.1 NASPlayer\r\n\r\n".toByteArray(Charsets.US_ASCII)
                            socket.send(DatagramPacket(request, request.size, group, 1900))
                        }
                    }
                    if (interfaces.isEmpty()) send() else interfaces.forEach { network -> runCatching { socket.networkInterface = network; send() } }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                    val buffer = ByteArray(8192)
                    while (!closed.get() && System.nanoTime() < deadline && locations.size < 32) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                        val lines = String(packet.data, packet.offset, packet.length, Charsets.ISO_8859_1).split("\r\n")
                        if (lines.firstOrNull()?.startsWith("HTTP/1.1 200") != true) continue
                        val location = lines.drop(1).firstOrNull { it.substringBefore(':').equals("location", true) }?.substringAfter(':')?.trim() ?: continue
                        val parsed = runCatching { DlnaXml.httpUri(location) }.getOrNull() ?: continue
                        // A responder cannot make discovery fetch another machine's URL.
                        if (runCatching { InetAddress.getAllByName(parsed.host).contains(packet.address) }.getOrDefault(false)) locations[location] = packet.address
                    }
                } finally { sockets.remove(socket) }
            }
        } finally { if (lock?.isHeld == true) runCatching { lock.release() } }
        ensureOpen()
        val devices = mutableListOf<DlnaDevice>()
        // Bound response parsing work as well as the multicast window.
        val parseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        locations.keys.take(12).forEach { location ->
            if (System.nanoTime() >= parseDeadline || closed.get()) return@forEach
            runCatching { addByAddress(location) }.getOrNull()?.let { device -> if (devices.none { it.id == device.id }) devices += device }
        }
        ensureOpen()
        return devices
    }

    /** Full device description URL only. No guessed vendor ports or descriptor paths. */
    fun addByAddress(address: String): DlnaDevice {
        ensureOpen()
        val location = DlnaXml.httpUri(address.trim()).toString()
        val info = DlnaXml.description(fetch(Request.Builder().url(location).get().build()), location)
        val descriptionHost = InetAddress.getAllByName(DlnaXml.httpUri(location).host).toSet()
        fun sameDevice(url: String) {
            val actual = InetAddress.getAllByName(DlnaXml.httpUri(url).host).toSet()
            if (actual.intersect(descriptionHost).isEmpty()) throw IOException("电视描述中的服务地址指向其他设备")
        }
        sameDevice(info.controlUrl)
        sameDevice(info.scpdUrl)
        val capabilities = DlnaXml.capabilities(fetch(Request.Builder().url(info.scpdUrl).get().build()))
        val required = setOf("SetAVTransportURI", "Play", "Stop", "GetTransportInfo", "GetPositionInfo")
        if (!capabilities.actions.containsAll(required)) throw IOException("该设备没有完整的视频投放控制服务")
        val host = DlnaXml.httpUri(info.controlUrl).host
        return DlnaDevice(info.id, info.name, InetAddress.getByName(host).hostAddress.orEmpty(), info.controlUrl, info.serviceType,
            if ("Seek" in capabilities.actions) capabilities.seekModes else emptySet(), "Pause" in capabilities.actions, "GetMediaInfo" in capabilities.actions)
    }

    fun load(device: DlnaDevice, media: RelayMedia, title: String, positionMs: Long, durationMs: Long = 0): DlnaStatus {
        require(positionMs >= 0) { "播放时间无效" }
        if (positionMs > 0 && device.seekModes.none { it == "REL_TIME" || it == "ABS_TIME" })
            throw IOException("该电视未声明按时间跳转能力，请从视频开头投放")
        soap(device, "SetAVTransportURI", mapOf("CurrentURI" to media.url, "CurrentURIMetaData" to DlnaXml.metadata(media, title, durationMs)))
        try {
            play(device)
            // Many renderers transition asynchronously after Play. Do not call Seek during TRANSITIONING.
            var latest = awaitPlaying(device, media.url)
            if (positionMs > 0) {
                seek(device, positionMs)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var reached = false
                while (!closed.get() && System.nanoTime() < deadline) {
                    latest = status(device)
                    if (latest.uri == media.url && latest.playing && kotlin.math.abs(latest.positionMs - positionMs) <= 7_000) { reached = true; break }
                    Thread.sleep(350)
                }
                if (!reached) throw IOException("电视未确认跳转到所选时间，手机播放保持不变")
            }
            return latest
        } catch (failure: Throwable) {
            // Failed handoff must not leave the TV playing from a different position.
            runCatching { stop(device) }
            throw failure
        }
    }

    private fun awaitPlaying(device: DlnaDevice, uri: String): DlnaStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
        do {
            ensureOpen()
            val state = status(device)
            if (state.playing && state.uri == uri) return state
            if (state.state == "NO_MEDIA_PRESENT") throw IOException("电视未能读取视频，请检查格式与同一网络连接")
            Thread.sleep(350)
        } while (System.nanoTime() < deadline)
        throw IOException("电视尚未确认播放，手机播放保持不变")
    }

    fun play(device: DlnaDevice) { soap(device, "Play", mapOf("Speed" to "1")) }
    fun pause(device: DlnaDevice) {
        if (!device.canPause) throw IOException("该电视未声明暂停能力")
        soap(device, "Pause")
    }
    fun seek(device: DlnaDevice, positionMs: Long) {
        val mode = when { "REL_TIME" in device.seekModes -> "REL_TIME"; "ABS_TIME" in device.seekModes -> "ABS_TIME"; else -> throw IOException("该电视不支持按时间跳转") }
        soap(device, "Seek", mapOf("Unit" to mode, "Target" to DlnaXml.formatTime(positionMs.coerceAtLeast(0))))
    }
    fun stop(device: DlnaDevice) { soap(device, "Stop") }
    fun status(device: DlnaDevice): DlnaStatus {
        val transport = soap(device, "GetTransportInfo")
        val position = soap(device, "GetPositionInfo")
        val state = DlnaXml.text(transport.documentElement, "CurrentTransportState").ifEmpty { "UNKNOWN" }
        val uri = DlnaXml.text(position.documentElement, "TrackURI")
        var duration = DlnaXml.timeMs(DlnaXml.text(position.documentElement, "TrackDuration"))
        if (duration <= 0 && device.canGetMediaInfo && uri.isNotBlank()) {
            if (mediaDurationUri != uri) {
                mediaDurationUri = uri
                mediaDurationMs = runCatching { DlnaXml.timeMs(DlnaXml.text(soap(device, "GetMediaInfo").documentElement, "MediaDuration")) }.getOrDefault(0)
            }
            duration = mediaDurationMs
        }
        return DlnaStatus(DlnaXml.timeMs(DlnaXml.text(position.documentElement, "RelTime")),
            duration, state == "PLAYING", state, uri)
    }

    private fun soap(device: DlnaDevice, action: String, args: Map<String, String> = emptyMap()): Document {
        ensureOpen()
        val body = DlnaXml.envelope(device.serviceType, action, linkedMapOf("InstanceID" to "0") + args)
        val request = Request.Builder().url(DlnaXml.httpUri(device.controlUrl).toString())
            .header("SOAPACTION", "\"${device.serviceType}#$action\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        return DlnaXml.document(fetch(request, parseFault = true))
    }

    private fun fetch(request: Request, parseFault: Boolean = false): String {
        ensureOpen()
        val call = http.newCall(request)
        calls.add(call)
        try {
            if (closed.get()) { call.cancel(); throw IOException("投屏已关闭") }
            return call.execute().use { response ->
                val body = response.body ?: throw IOException("电视返回空内容")
                if (body.contentLength() > MAX_XML) throw IOException("电视返回的描述过大")
                val data = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_XML) throw IOException("电视返回的描述过大")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                }
                if (parseFault) runCatching { DlnaXml.document(data) }.getOrNull()?.let { document -> DlnaXml.fault(document)?.let { throw it } }
                if (!response.isSuccessful) throw IOException("电视请求失败（HTTP ${response.code}）")
                data
            }
        } finally { calls.remove(call) }
    }

    private fun ensureOpen() { if (closed.get()) throw IOException("投屏已关闭") }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sockets.toList().forEach { runCatching { it.close() } }
        calls.toList().forEach { it.cancel() }
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
    companion object { private const val MAX_XML = 512 * 1024 }
}

internal object DlnaXml {
    data class Description(val id: String, val name: String, val serviceType: String, val controlUrl: String, val scpdUrl: String)
    data class Capabilities(val actions: Set<String>, val seekModes: Set<String>)

    fun httpUri(value: String): URI {
        val uri = try { URI(value) } catch (_: Exception) { throw IOException("请填写电视设备描述的完整 http/https 地址") }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.port !in -1..65535)
            throw IOException("请填写电视设备描述的完整 http/https 地址")
        return uri
    }

    fun document(value: String): Document {
        if (value.length > 512 * 1024 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(value)) throw IOException("不支持含外部实体或过大内容的电视 XML")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            // Android XML providers differ; rejecting all DTDs above is mandatory on every provider.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External XML is disabled") }
            setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun error(error: org.xml.sax.SAXParseException) { throw error }
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
            })
        }
        return builder.parse(InputSource(StringReader(value)))
    }

    private fun children(element: Element, name: String): List<Element> = (0 until element.childNodes.length).mapNotNull { index ->
        (element.childNodes.item(index) as? Element)?.takeIf { (it.localName ?: it.nodeName.substringAfter(':')) == name }
    }
    private fun own(element: Element, name: String): String = children(element, name).firstOrNull()?.textContent?.trim().orEmpty()
    fun text(element: Element, name: String): String = element.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()

    fun description(value: String, location: String): Description {
        val root = document(value).documentElement
        val locationUri = httpUri(location)
        val base = own(root, "URLBase").takeIf(String::isNotEmpty)?.let(::httpUri) ?: locationUri
        val devices = root.getElementsByTagNameNS("*", "device")
        for (index in 0 until devices.length) {
            val device = devices.item(index) as? Element ?: continue
            if (!Regex("urn:schemas-upnp-org:device:MediaRenderer:[0-9]+").matches(own(device, "deviceType"))) continue
            val services = children(device, "serviceList").flatMap { children(it, "service") }
            val service = services.firstOrNull { Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+").matches(own(it, "serviceType")) } ?: continue
            val control = own(service, "controlURL").takeIf { it.isNotEmpty() } ?: continue
            val scpd = own(service, "SCPDURL").takeIf { it.isNotEmpty() } ?: continue
            return Description(own(device, "UDN").ifEmpty { location }, own(device, "friendlyName").ifEmpty { locationUri.host },
                own(service, "serviceType"), httpUri(base.resolve(control).toString()).toString(), httpUri(base.resolve(scpd).toString()).toString())
        }
        throw IOException("该设备没有可用的 DLNA 视频投放服务")
    }

    fun capabilities(value: String): Capabilities {
        val root = document(value).documentElement
        val actions = children(root, "actionList").flatMap { children(it, "action") }
        val seek = actions.firstOrNull { own(it, "name") == "Seek" }
        val variableName = seek?.let { children(it, "argumentList").flatMap { item -> children(item, "argument") }.firstOrNull { item -> own(item, "name") == "Unit" } }?.let { own(it, "relatedStateVariable") }
        val variable = children(root, "serviceStateTable").flatMap { children(it, "stateVariable") }.firstOrNull { own(it, "name") == variableName }
        val modes = variable?.let { children(it, "allowedValueList").flatMap { item -> children(item, "allowedValue") }.map { item -> item.textContent.trim() }.filter { it in setOf("REL_TIME", "ABS_TIME") }.toSet() }.orEmpty()
        return Capabilities(actions.map { own(it, "name") }.toSet(), modes)
    }

    fun envelope(serviceType: String, action: String, values: Map<String, String>): String {
        require(Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+").matches(serviceType))
        require(Regex("[A-Za-z][A-Za-z0-9]*").matches(action))
        return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$serviceType">${values.entries.joinToString("") { (key, value) -> require(Regex("[A-Za-z][A-Za-z0-9]*").matches(key)); "<$key>${escape(value)}</$key>" }}</u:$action></s:Body></s:Envelope>"""
    }

    fun metadata(media: RelayMedia, title: String, durationMs: Long = 0): String =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${escape(title.take(300))}</dc:title><upnp:class>object.item.videoItem</upnp:class><res size="${media.length}"${if (durationMs > 0) " duration=\"${formatTime(durationMs)}\"" else ""} protocolInfo="http-get:*:${escape(media.contentType)}:DLNA.ORG_OP=01;DLNA.ORG_CI=0">${escape(media.url)}</res></item></DIDL-Lite>"""

    fun fault(document: Document): IOException? {
        val root = document.documentElement
        if (root.getElementsByTagNameNS("*", "Fault").length == 0) return null
        val code = text(root, "errorCode")
        val description = text(root, "errorDescription").ifEmpty { text(root, "faultstring") }.take(200)
        val message = when (code) {
            "401" -> "电视不支持该操作"; "402" -> "电视不接受此操作参数"; "501" -> "电视执行操作失败"
            "701" -> "电视当前状态不允许此操作"; "702" -> "电视没有可播放内容"; "703" -> "电视读取视频失败"
            "704", "714" -> "电视不支持该视频或音轨格式"; "710" -> "电视不支持此跳转方式"; "711" -> "跳转时间超出电视可播放范围"
            "716" -> "电视无法访问视频地址"; "717" -> "电视不支持此播放速度"; "718" -> "电视不支持该播放实例"
            else -> "电视投屏操作失败"
        }
        return IOException("$message（UPnP ${code.ifEmpty { "未知" }}${if (description.isNotBlank()) "：$description" else ""}）")
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    fun timeMs(value: String): Long {
        val parts = value.split(':')
        if (parts.size != 3) return 0
        val hours = parts[0].toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 3_600_000 } ?: return 0
        val minutes = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return 0
        val seconds = parts[2].toDoubleOrNull()?.takeIf { it >= 0 && it < 60 && it.isFinite() } ?: return 0
        return runCatching { Math.addExact(Math.multiplyExact(hours, 3_600_000), minutes * 60_000L + (seconds * 1000).toLong()) }.getOrDefault(0)
    }
}
