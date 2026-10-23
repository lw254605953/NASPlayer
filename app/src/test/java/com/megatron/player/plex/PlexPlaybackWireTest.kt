package com.megatron.player.plex

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PlexPlaybackWireTest {
    private class Pms(private val deny: Boolean) : AutoCloseable {
        private val server = ServerSocket(0)
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val failure = AtomicReference<Throwable?>(null)
        val address = "http://127.0.0.1:${server.localPort}/plex/"
        private val worker = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val first = reader.readLine() ?: return@use
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val path = first.split(' ')[1]
                    requests += path to headers
                    val decision = path.startsWith("/plex/video/:/transcode/universal/decision?")
                    val authorized = headers["x-plex-token"] == "wire-token" && headers["x-plex-pms-api-version"] == "1.0"
                    val body = if (decision) {
                        val code = if (deny) 2000 else 1000
                        """{"MediaContainer":{"generalDecisionCode":$code,"directPlayDecisionCode":$code,"generalDecisionText":"${if (deny) "User not authorized to play." else "Direct play OK."}"}}"""
                    } else """{"MediaContainer":{"Metadata":[{"ratingKey":"123","duration":120000,"Media":[{"Part":[{"key":"/library/parts/1/1/file.mp4"}]}]}]}}"""
                    val bytes = body.toByteArray()
                    val status = if (authorized) "200 OK" else "403 Forbidden"
                    socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); worker.join(2000); failure.get()?.let { throw AssertionError(it) } }
    }

    @Test fun negotiatesWithAuthenticatedSessionReusesForSeekAndRenegotiatesOnRetry() {
        Pms(false).use { pms ->
            val credentials = PlexCredentials(PlexServerConfig("server", "machine", "PMS", pms.address), "wire-token")
            val ref = PlexPlaybackRef("server", "123", "/library/parts/1/1/file.mp4")
            val playback = PlexPlaybackSession("wire-client")
            assertTrue(playback.prepare(credentials, ref, playback::trackCall).allowsDirectPlay)
            playback.prepare(credentials, ref, playback::trackCall)
            assertEquals(2, pms.requests.size)
            val decision = pms.requests[1]
            assertTrue(decision.first.contains("directPlay=1"))
            assertFalse(decision.first.contains("location="))
            assertFalse(decision.first.contains("wire-token"))
            assertEquals(playback.sessionIdentifier, decision.second["x-plex-session-identifier"])
            assertEquals("NASPlayer", decision.second["x-plex-product"])
            playback.reset()
            playback.prepare(credentials, ref, playback::trackCall)
            assertEquals(4, pms.requests.size)
            playback.release()
        }
    }

    @Test fun deniedDecisionIsPreservedAndDoesNotReadAnyMedia() {
        Pms(true).use { pms ->
            val credentials = PlexCredentials(PlexServerConfig("server", "machine", "PMS", pms.address), "wire-token")
            val playback = PlexPlaybackSession("wire-client")
            val ref = PlexPlaybackRef("server", "123", "/library/parts/1/1/file.mp4")
            val error = runCatching { playback.prepare(credentials, ref, playback::trackCall) }.exceptionOrNull()
            assertTrue(error is PlexPlaybackFailure)
            assertTrue((error as PlexPlaybackFailure).description().contains("User not authorized"))
            assertEquals(2000, playback.decision?.generalCode)
            runCatching { playback.prepare(credentials, ref, playback::trackCall) }
            assertEquals(2, pms.requests.size)
            assertTrue(pms.requests.none { it.first.startsWith("/library/parts/") })
            playback.release()
        }
    }
}
