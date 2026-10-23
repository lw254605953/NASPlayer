package com.megatron.player.plex

import com.megatron.player.source.BrowserEntry

data class PlexFileLocation(val path: String, val sizeBytes: Long = -1L)
internal data class PlexAutoMatch(val path: String, val mapping: PlexPathMapping)

/** Finds an exact file through actual DAV entries; never probes guessed media URLs. */
internal object PlexWebDavAutoResolver {
    fun resolve(file: PlexFileLocation, binding: PlexWebDavBinding, learned: List<PlexPathMapping>,
        listDirectory: (String) -> List<BrowserEntry>): PlexAutoMatch {
        PlexWebDavPaths.relativePath(file.path, PlexWebDavBinding("validation", listOf(PlexPathMapping("/", ""))))
        val segments = file.path.split('/').filter { it.isNotEmpty() }
        require(segments.size <= 128) { "Plex 文件路径过长" }
        val listings = linkedMapOf<String, List<BrowserEntry>>()
        var differentSize = false
        fun entries(path: String): List<BrowserEntry> = listings.getOrPut(path) {
            if (listings.size >= 64) throw PlexPlaybackFailure("WebDAV 目录层级过多，文件定位未完成")
            listDirectory(path)
        }
        fun name(entry: BrowserEntry) = entry.path.substringAfterLast('/')
        fun accepts(entry: BrowserEntry): Boolean {
            if (entry.isDirectory || name(entry) != segments.last()) return false
            if (file.sizeBytes > 0 && entry.size > 0 && entry.size != file.sizeBytes) {
                differentSize = true
                return false
            }
            return true
        }
        fun follow(start: String, suffix: List<String>): BrowserEntry? {
            var directory = start
            suffix.forEachIndexed { index, segment ->
                val entry = entries(directory).firstOrNull { name(it) == segment } ?: return null
                if (index == suffix.lastIndex) return entry.takeIf(::accepts)
                if (!entry.isDirectory) return null
                directory = entry.path
            }
            return null
        }
        fun result(entry: BrowserEntry): PlexAutoMatch {
            val actual = entry.path.split('/')
            val common = segments.asReversed().zip(actual.asReversed()).takeWhile { it.first == it.second }.size
            return PlexAutoMatch(entry.path, PlexPathMapping("/" + segments.dropLast(common).joinToString("/"), actual.dropLast(common).joinToString("/")))
        }
        entries("")
        // Learned rules and legacy mappings are internal hints, never required settings.
        for (mapping in (learned + binding.mappings).distinct().sortedByDescending { it.plexRoot.length }) {
            val relative = runCatching { PlexWebDavPaths.relativePath(file.path, PlexWebDavBinding(binding.sourceId, listOf(mapping))) }.getOrNull() ?: continue
            follow("", relative.split('/'))?.let { return result(it) }
        }
        fun search(directory: String): List<BrowserEntry> {
            val children = entries(directory)
            val found = children.filter(::accepts).toMutableList()
            children.filter { it.isDirectory }.forEach { anchor ->
                segments.dropLast(1).forEachIndexed { index, segment ->
                    if (name(anchor) == segment) follow(anchor.path, segments.drop(index + 1))?.let(found::add)
                }
            }
            return found.distinctBy { it.path }
        }
        var found = search("")
        // DAV may wrap shares in e.g. files/. Inspect only a bounded number of real
        // wrapper directories; do not recursively index the NAS or media library.
        if (found.isEmpty()) {
            val wrappers = entries("").filter { it.isDirectory && name(it) !in segments.dropLast(1) }.take(16)
            found = wrappers.flatMap { search(it.path) }.distinctBy { it.path }
        }
        if (found.isEmpty()) throw PlexPlaybackFailure(if (differentSize)
            "WebDAV 中找到同名文件，但大小与 Plex 记录不同，请刷新影片资料后重试"
            else "未在 WebDAV 可访问目录中找到 Plex 对应文件，请确认该连接能看到这部影片")
        val ranked = found.map { entry -> result(entry) to segments.asReversed().zip(entry.path.split('/').asReversed()).takeWhile { it.first == it.second }.size }
        val best = ranked.filter { it.second == ranked.maxOf { row -> row.second } }
        if (best.size != 1) throw PlexPlaybackFailure("WebDAV 中有多个目录和文件名均相同的影片，无法自动确定播放文件")
        return best.single().first
    }
}
