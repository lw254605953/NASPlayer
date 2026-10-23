package com.megatron.player.plex

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PlexLibraryQueryTest {
    private val client = PlexClient("library-query-test")
    private fun credentials(address: String = "http://nas.example.test:32400/plex/") =
        PlexCredentials(PlexServerConfig("server", "machine", "NAS", address), "private-token")

    @Test fun defaultQueryHasReleaseDescendingAndNoResidualFilters() {
        val url = client.movieListUrl(credentials(), "1", "", PlexSort.RELEASED, true)
        assertEquals("/plex/library/sections/1/all", url.encodedPath)
        assertEquals("1", url.queryParameter("type"))
        assertEquals("originallyAvailableAt:desc", url.queryParameter("sort"))
        assertEquals(setOf("type", "sort"), url.queryParameterNames)
        assertFalse(url.toString().contains("private-token"))
        assertFalse(PlexLibraryFilter().active)
    }

    @Test fun movieCombinedFiltersAreEncodedAsServerQueryWithoutTokenLeakage() {
        val filters = PlexLibraryFilter(PlexWatchFilter.UNWATCHED, "176", "2026", 7)
        val url = client.movieListUrl(credentials(), "1", "中文 & 100%", PlexSort.RELEASED, true, filters)
        assertEquals("中文 & 100%", url.queryParameter("title"))
        assertEquals("1", url.queryParameter("unwatched"))
        assertEquals("176", url.queryParameter("genre"))
        assertEquals("2026", url.queryParameter("year"))
        assertEquals("7", url.queryParameter("audienceRating>"))
        assertNull(url.queryParameter("rating>"))
        assertTrue(filters.active)
        assertFalse(url.toString().contains("private-token"))
        assertEquals("0", client.movieListUrl(credentials(), "1", "", PlexSort.TITLE, false,
            PlexLibraryFilter(watched = PlexWatchFilter.WATCHED)).queryParameter("unwatched"))
    }

    @Test fun invalidRatingIsRejectedBeforeMakingRequest() {
        assertTrue(runCatching { client.movieListUrl(credentials(), "1", "", PlexSort.RELEASED, true,
            PlexLibraryFilter(minimumRating = 11)) }.isFailure)
        assertTrue(runCatching { client.movieListUrl(credentials(), "1", "", PlexSort.RELEASED, true,
            PlexLibraryFilter(minimumRating = -1)) }.isFailure)
    }

    @Test fun showWatchRulesStayAtWholeShowLevelAndSearchHasBoundedPaging() {
        Pms().use { pms ->
            val saved = credentials(pms.address)
            client.shows(saved, "2", "test", 20, 20, filters = PlexLibraryFilter(PlexWatchFilter.UNWATCHED, "18", "2024", 8))
            val first = pms.requests.single()
            val url = (pms.address.trimEnd('/') + first.first.removePrefix("/plex")).toHttpUrl()
            assertEquals("2", url.queryParameter("type"))
            assertEquals("1", url.queryParameter("show.unviewedLeafCount>"))
            assertEquals("18", url.queryParameter("genre"))
            assertEquals("2024", url.queryParameter("year"))
            assertEquals("8", url.queryParameter("audienceRating>"))
            assertNull(url.queryParameter("unwatched"))
            assertEquals("20", first.second["x-plex-container-start"])
            assertEquals("20", first.second["x-plex-container-size"])
            assertEquals("private-token", first.second["x-plex-token"])
            client.shows(saved, "2", filters = PlexLibraryFilter(watched = PlexWatchFilter.WATCHED))
            val watched = (pms.address.trimEnd('/') + pms.requests.last().first.removePrefix("/plex")).toHttpUrl()
            assertEquals("0", watched.queryParameter("show.unviewedLeafCount"))
            assertNull(watched.queryParameter("show.leafCount>"))
            assertNull(watched.queryParameter("unwatched"))
        }
    }

    @Test fun facetPathsComeFromMetadataAndDoNotScanLibrary() {
        Pms().use { pms ->
            val facets = client.libraryFacets(credentials(pms.address), "1", "movie")
            assertEquals(listOf(PlexFacet("176", "科幻")), facets.genres)
            assertEquals(listOf(PlexFacet("2026", "2026")), facets.years)
            assertEquals(3, pms.requests.size)
            val meta = pms.requests.first()
            val url = (pms.address.trimEnd('/') + meta.first.removePrefix("/plex")).toHttpUrl()
            assertEquals("1", url.queryParameter("includeMeta"))
            assertEquals("1", url.queryParameter("includeAdvanced"))
            assertEquals("0", meta.second["x-plex-container-size"])
            assertTrue(pms.requests[1].first.startsWith("/plex/library/sections/1/choices/genres?"))
            assertTrue(pms.requests[2].first.startsWith("/plex/library/sections/1/choices/years"))
        }
    }

    private class Pms : AutoCloseable {
        private val server = ServerSocket(0)
        val address = "http://127.0.0.1:${server.localPort}/plex/"
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        private val failure = AtomicReference<Throwable?>(null)
        private val worker = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    val first = input.readLine() ?: return@use
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val path = first.split(' ')[1]
                    requests += path to headers
                    val body = when {
                        path.contains("includeMeta=") -> """{"MediaContainer":{"Meta":{"Type":[{"type":"movie","Filter":[{"filter":"genre","key":"/library/sections/1/choices/genres?language=zh"},{"filter":"year","key":"/library/sections/1/choices/years"}]}]}}}"""
                        path.contains("choices/genres") -> """{"MediaContainer":{"Directory":[{"key":"176","title":"科幻"}]}}"""
                        path.contains("choices/years") -> """{"MediaContainer":{"Directory":[{"key":"2026","title":"2026"}]}}"""
                        else -> """{"MediaContainer":{"Metadata":[],"totalSize":0}}"""
                    }.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (e: Throwable) { if (!server.isClosed) failure.set(e) }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); worker.join(2000); failure.get()?.let { throw AssertionError(it) } }
    }
}
