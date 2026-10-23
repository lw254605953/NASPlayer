package com.megatron.player.plex

import com.megatron.player.source.BrowserEntry
import org.junit.Assert.*
import org.junit.Test

class PlexWebDavAutoTest {
    private val binding = PlexWebDavBinding("dav", autoMatch = true)
    private fun directory(path: String) = BrowserEntry(path.substringAfterLast('/'), path, true)
    private fun video(path: String, size: Long = 123) = BrowserEntry(path.substringAfterLast('/'), path, false, size)

    @Test fun actualSharesLocateFileWithoutTouchingInaccessibleGuessedVolume() {
        val seen = mutableListOf<String>()
        val tree = mapOf("" to listOf(directory("电影")), "电影" to listOf(directory("电影/中文 空格")),
            "电影/中文 空格" to listOf(video("电影/中文 空格/100%25.mkv")))
        val match = PlexWebDavAutoResolver.resolve(PlexFileLocation("/volume1/电影/中文 空格/100%25.mkv", 123), binding, emptyList()) {
            seen += it
            tree[it] ?: throw java.io.IOException("HTTP 403: inaccessible guessed path")
        }
        assertEquals("电影/中文 空格/100%25.mkv", match.path)
        assertEquals(PlexPathMapping("/volume1", ""), match.mapping)
        assertEquals(listOf("", "电影", "电影/中文 空格"), seen)
    }

    @Test fun existingRuleForOneShareAlsoWorksForAnotherAndNoHeadIsNeeded() {
        val tree = mapOf("" to listOf(directory("剧集40")), "剧集40" to listOf(directory("剧集40/第一季")),
            "剧集40/第一季" to listOf(video("剧集40/第一季/第1集.mkv", 0)))
        val match = PlexWebDavAutoResolver.resolve(PlexFileLocation("/volume1/剧集40/第一季/第1集.mkv", 456), binding,
            listOf(PlexPathMapping("/volume1", ""))) { tree[it] ?: error(it) }
        assertEquals("剧集40/第一季/第1集.mkv", match.path)
    }

    @Test fun wrappersAndShareScopedConnectionDoNotNeedRootSettings() {
        val wrapped = mapOf("" to listOf(directory("files")), "files" to listOf(directory("files/Movies")),
            "files/Movies" to listOf(video("files/Movies/A.mkv")))
        assertEquals("files/Movies/A.mkv", PlexWebDavAutoResolver.resolve(PlexFileLocation("/volume1/Movies/A.mkv", 123), binding, emptyList()) {
            wrapped[it] ?: error(it)
        }.path)
        assertEquals("A.mkv", PlexWebDavAutoResolver.resolve(PlexFileLocation("/volume1/Movies/A.mkv", 123), binding, emptyList()) {
            if (it.isEmpty()) listOf(video("A.mkv")) else error(it)
        }.path)
    }

    @Test fun mismatchingSizeAndDuplicateFullDirectorySuffixAreRejected() {
        val file = PlexFileLocation("/volume1/Movies/A.mkv", 123)
        val wrongSize = assertThrows(PlexPlaybackFailure::class.java) {
            PlexWebDavAutoResolver.resolve(file, binding, emptyList()) { path ->
                when (path) { "" -> listOf(directory("Movies")); "Movies" -> listOf(video("Movies/A.mkv", 999)); else -> error(path) }
            }
        }
        assertTrue(wrongSize.userMessage.contains("大小"))
        val duplicated = mapOf("" to listOf(directory("one"), directory("two")),
            "one" to listOf(directory("one/Movies")), "two" to listOf(directory("two/Movies")),
            "one/Movies" to listOf(video("one/Movies/A.mkv")), "two/Movies" to listOf(video("two/Movies/A.mkv")))
        assertThrows(PlexPlaybackFailure::class.java) { PlexWebDavAutoResolver.resolve(file, binding, emptyList()) { duplicated[it] ?: error(it) } }
    }

    @Test fun realPermissionErrorAndTraversalAreNotHidden() {
        assertThrows(java.io.IOException::class.java) {
            PlexWebDavAutoResolver.resolve(PlexFileLocation("/volume1/Movies/A.mkv", 123), binding, emptyList()) {
                throw java.io.IOException("WebDAV 请求失败（HTTP 403）")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlexWebDavAutoResolver.resolve(PlexFileLocation("/media/../private/a.mkv", 123), binding, emptyList()) { error("must not read") }
        }
    }
}
