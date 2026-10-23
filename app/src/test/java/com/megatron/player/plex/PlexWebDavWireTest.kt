package com.megatron.player.plex

import com.megatron.player.source.*
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexWebDavWireTest {
    private data class Seen(val path: String, val headers: Map<String, String>)
    private class Server(private val reply: (Seen) -> String) : AutoCloseable {
        private val socket = ServerSocket(0)
        val address = "http://127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<Seen>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread {
            try {
                while (!socket.isClosed) socket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val reader = connection.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@use
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val next = reader.readLine() ?: break
                        if (next.isEmpty()) break
                        headers[next.substringBefore(':').lowercase()] = next.substringAfter(':').trim()
                    }
                    val seen = Seen(line.split(' ')[1], headers)
                    requests += seen
                    val body = reply(seen).toByteArray()
                    connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    connection.getOutputStream().write(body)
                    connection.getOutputStream().flush()
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); worker.join(2000); failure.get()?.let { throw AssertionError(it) } }
    }

    @Test fun independentVideoReadsUseDavAuthAndNeverRequestPlexDecisionOrVideo() {
        Server { request ->
            assertEquals("plex-token", request.headers["x-plex-token"])
            assertEquals("/plex/library/metadata/123", request.path)
            """{"MediaContainer":{"Metadata":[{"ratingKey":"123","Media":[{"Part":[{"key":"/library/parts/9/1/file.mkv","file":"/media/中文 空格/100%25.mkv"}]}]}]}}"""
        }.use { pms ->
            Server { request ->
                assertEquals(Credentials.basic("dav-user", "dav-password"), request.headers["authorization"])
                assertFalse(request.headers.keys.any { it.startsWith("x-plex-") })
                assertEquals("/dav/Movies/中文 空格/100%25.mkv", java.net.URLDecoder.decode(request.path, "UTF-8"))
                "video-bytes"
            }.use { dav ->
                val credentials = PlexCredentials(PlexServerConfig("p", "m", "Plex", "${pms.address}/plex/"), "plex-token")
                val source = MediaSourceConfig("dav", "NAS", SourceKind.WEBDAV, "${dav.address.replace("127.0.0.1", "localhost")}/dav/", "dav-user")
                assertNotEquals(credentials.config.address.toHttpUrl().host, source.address.toHttpUrl().host)
                val binding = PlexWebDavBinding("dav", listOf(PlexPathMapping("/media", "Movies")))
                val ref = PlexPlaybackRef("p", "123", "/library/parts/9/1/file.mkv")
                val playback = PlexPlaybackSession("client", directoryReader = { _, path, _ -> when (path) {
                    "" -> listOf(BrowserEntry("Movies", "Movies", true))
                    "Movies" -> listOf(BrowserEntry("中文 空格", "Movies/中文 空格", true))
                    "Movies/中文 空格" -> listOf(BrowserEntry("100%25.mkv", "Movies/中文 空格/100%25.mkv", false))
                    else -> error("Unexpected directory $path")
                } })
                val relative = playback.prepareWebDav(credentials, ref, binding, SourceCredentials(source, "dav-password"), playback::trackCall)
                repeat(2) { index ->
                    assertEquals(relative, playback.prepareWebDav(credentials, ref, binding, SourceCredentials(source, "dav-password"), playback::trackCall))
                    val target = WebDavHttp.urlForPath(source.address.toHttpUrl(), relative)
                    val headers = if (index == 0) Headers.headersOf() else Headers.headersOf("Range", "bytes=2-")
                    WebDavHttp.execute(SourceCredentials(source, "dav-password"), target, "GET", null, headers).use {
                        assertEquals("video-bytes", it.body!!.string())
                    }
                }
                assertEquals(1, pms.requests.size)
                assertEquals(2, dav.requests.size)
                assertEquals("bytes=2-", dav.requests[1].headers["range"])
                assertNull(playback.decision)
                playback.reset()
                playback.prepareWebDav(credentials, ref, binding, SourceCredentials(source, "dav-password"), playback::trackCall)
                assertEquals(2, pms.requests.size)
                playback.release()
            }
        }
    }

    @Test fun exactPartIsResolvedAndAbsentFileIsActionable() {
        val client = PlexClient("client")
        val metadata = JSONObject("""{"MediaContainer":{"Metadata":[{"ratingKey":"123","Media":[
          {"Part":[{"key":"/library/parts/1/1/file.mkv","file":"/movies/old.mkv"}]},
          {"Part":[{"key":"/library/parts/9/1/file.mkv","file":"/movies/right.mkv","size":456}]}]}]}}""")
        assertEquals("/movies/right.mkv", client.selectFilePath(metadata, "123", "/library/parts/9/1/file.mkv"))
        assertEquals(PlexFileLocation("/movies/right.mkv", 456), client.selectFileLocation(metadata, "123", "/library/parts/9/1/file.mkv"))
        val missing = JSONObject("""{"MediaContainer":{"Metadata":[{"ratingKey":"123","Media":[{"Part":[{"key":"/library/parts/9/1/file.mkv"}]}]}]}}""")
        val error = assertThrows(PlexPlaybackFailure::class.java) { client.selectFilePath(missing, "123", "/library/parts/9/1/file.mkv") }
        assertTrue(error.userMessage.contains("文件位置"))
    }
}
