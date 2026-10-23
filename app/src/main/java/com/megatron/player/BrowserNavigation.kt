package com.megatron.player

import java.io.Serializable

/** A UI location is independent of the feature that implements the destination. */
internal data class BrowserLocation(
    val tab: String = "首页",
    val homePage: String = "main",
    val settingsPage: String = "main",
    val sourceId: String? = null,
    val paths: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val detail: PlexDetailTarget? = null,
    val musicPlayer: Boolean = false,
    val organize: Boolean = false,
    val query: String = "",
    val sortTime: Boolean = false,
) : Serializable {
    val key: String get() = when {
        detail != null -> "detail:${detail.sequence}"
        musicPlayer -> "music-player"
        tab == "设置" -> "settings:$settingsPage"
        tab == "首页" && sourceId != null -> "files:$sourceId:${paths.lastOrNull().orEmpty()}"
        tab == "首页" -> "home:$homePage"
        else -> tab
    }
    val backLabel: String get() = when {
        detail != null -> "返回影片详情"
        musicPlayer -> "返回音乐播放器"
        tab == "设置" && settingsPage == "favorites" -> "返回我的收藏"
        tab == "设置" && settingsPage == "personal" -> "返回个人设置"
        tab == "设置" -> "返回设置"
        tab == "首页" && sourceId != null -> "返回文件目录"
        tab == "首页" && homePage == "continue" -> "返回继续观看"
        tab == "首页" -> "返回首页"
        tab == "音乐" -> "返回音乐"
        else -> "返回媒体库"
    }
}

internal data class BrowserReturn(val destination: String, val origin: BrowserLocation) : Serializable

internal fun pushBrowserReturn(stack: List<BrowserReturn>, origin: BrowserLocation, destination: BrowserLocation): List<BrowserReturn> =
    if (origin.key == destination.key) stack else stack + BrowserReturn(destination.key, origin)

internal fun browserParentSettings(page: String): String = if (page in setOf("history", "progress")) "personal" else "main"

internal fun browserReturn(stack: List<BrowserReturn>, current: BrowserLocation): BrowserReturn? =
    stack.lastOrNull()?.takeIf { it.destination == current.key }
