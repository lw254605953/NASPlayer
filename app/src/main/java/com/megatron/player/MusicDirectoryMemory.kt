package com.megatron.player

import com.megatron.player.source.BrowserEntry

/** Retain rendered directory data across player presentation, until the user or files change it. */
class MusicDirectoryMemory {
    private data class Page(val entries: List<BrowserEntry>, val files: Int, val sources: Int)
    private val pages = LinkedHashMap<String, Page>(16, .75f, true)
    fun get(key: String, files: Int, sources: Int): List<BrowserEntry>? =
        pages[key]?.takeIf { it.files == files && it.sources == sources }?.entries
    fun put(key: String, files: Int, sources: Int, entries: List<BrowserEntry>) {
        pages[key] = Page(entries, files, sources)
        while (pages.size > 48 || pages.values.sumOf { it.entries.size.toLong() } > 50_000) pages.remove(pages.keys.first())
    }
    fun invalidate(key: String) { pages.remove(key) }
}
