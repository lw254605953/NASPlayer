package com.megatron.player

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class BrowserNavigationTest {
    private fun details(from: BrowserLocation) = from.copy(detail = PlexDetailTarget("server", "123", 2, 99))

    @Test fun homeDetailKeepsChannelAndReturnsHome() {
        val home = BrowserLocation()
        val detail = details(home)
        val stack = pushBrowserReturn(emptyList(), home, detail)
        assertEquals("首页", detail.tab)
        assertEquals(home, browserReturn(stack, detail)?.origin)
        assertEquals("返回首页", home.backLabel)
    }
    @Test fun fullContinueListReturnsToThatListAndThenHome() {
        val home = BrowserLocation(); val list = home.copy(homePage = "continue"); val detail = details(list)
        val stack = pushBrowserReturn(pushBrowserReturn(emptyList(), home, list), list, detail)
        assertEquals(list, browserReturn(stack, detail)?.origin)
        assertEquals(home, browserReturn(stack.dropLast(1), list)?.origin)
    }
    @Test fun sharedSettingsCanReturnThroughDetailToOriginalFavorites() {
        val favorites = BrowserLocation(tab = "设置", settingsPage = "favorites")
        val detail = details(favorites)
        val settings = detail.copy(detail = null, settingsPage = "plex")
        val stack = pushBrowserReturn(pushBrowserReturn(emptyList(), favorites, detail), detail, settings)
        assertEquals(detail, browserReturn(stack, settings)?.origin)
        assertEquals(favorites, browserReturn(stack.dropLast(1), detail)?.origin)
        assertEquals("返回我的收藏", favorites.backLabel)
    }
    @Test fun favoriteDirectoryAndNestedDirectoryRestoreRealOriginAndQuery() {
        val favorites = BrowserLocation(tab = "设置", settingsPage = "favorites")
        val root = favorites.copy(tab = "首页", sourceId = "nas", paths = listOf("", "Movies"), labels = listOf("NAS", "电影"), query = "科幻", sortTime = true)
        val child = root.copy(paths = root.paths + "Movies/科幻", labels = root.labels + "科幻", query = "")
        val stack = pushBrowserReturn(pushBrowserReturn(emptyList(), favorites, root), root, child)
        assertEquals(root, browserReturn(stack, child)?.origin)
        assertEquals(favorites, browserReturn(stack.dropLast(1), root)?.origin)
        assertEquals("科幻", browserReturn(stack, child)?.origin?.query)
    }
    @Test fun modelSettingsRestoreMusicPlayerAndOrganizeEntry() {
        val source = BrowserLocation(tab = "音乐", musicPlayer = true, organize = true)
        val settings = source.copy(tab = "设置", settingsPage = "ai", musicPlayer = false, organize = false)
        val stack = pushBrowserReturn(emptyList(), source, settings)
        assertEquals(source, browserReturn(stack, settings)?.origin)
    }
    @Test fun staleReturnCannotHijackExplicitChannelSelectionOrParentDirectory() {
        val home = BrowserLocation(); val detail = details(home)
        val stack = pushBrowserReturn(emptyList(), home, detail)
        assertNull(browserReturn(stack, BrowserLocation(tab = "音乐")))
        assertEquals(emptyList<BrowserReturn>(), pushBrowserReturn(emptyList(), home, home))
        assertEquals("personal", browserParentSettings("history"))
        assertEquals("main", browserParentSettings("ai"))
    }
    @Test fun locationsAndReturnFramesRoundTripThroughSavedStateSerialization() {
        val origin = BrowserLocation(tab = "首页", homePage = "continue")
        val value = arrayListOf(BrowserReturn("settings:ai", details(origin)))
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(value) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(value, restored)
    }
}
