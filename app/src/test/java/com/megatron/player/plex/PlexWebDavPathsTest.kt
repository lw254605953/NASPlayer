package com.megatron.player.plex

import org.junit.Assert.*
import org.junit.Test

class PlexWebDavPathsTest {
    @Test fun longestWholeDirectoryMappingPreservesRealFileCharacters() {
        val binding = PlexWebDavBinding("dav", listOf(PlexPathMapping("/media", "影视"), PlexPathMapping("/media/Movies/", "电影")))
        assertEquals("电影/中文 空格/100%25.mkv", PlexWebDavPaths.relativePath("/media/Movies/中文 空格/100%25.mkv", binding))
        assertEquals("影视/Series/第1集.mkv", PlexWebDavPaths.relativePath("/media/Series/第1集.mkv", binding))
    }

    @Test fun similarDirectoryNamesDoNotMatch() {
        val binding = PlexWebDavBinding("dav", listOf(PlexPathMapping("/movies", "")))
        assertThrows(PlexPlaybackFailure::class.java) { PlexWebDavPaths.relativePath("/movies-old/a.mkv", binding) }
    }

    @Test fun traversalAndConflictingMappingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PlexWebDavPaths.validateMappings(listOf(PlexPathMapping("/movies/../private", ""))) }
        assertThrows(IllegalArgumentException::class.java) { PlexWebDavPaths.validateMappings(listOf(PlexPathMapping("/movies", "../private"))) }
        assertThrows(IllegalArgumentException::class.java) { PlexWebDavPaths.validateMappings(listOf(PlexPathMapping("/movies", "a"), PlexPathMapping("/movies/", "b"))) }
    }

}
