package com.megatron.player

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import com.megatron.player.plex.*

/** Activity-local browsing data; never put a whole library into Android saved-state Bundles. */
class PlexLibraryPageMemory {
    private var identity: List<String>? = null
    private var page = PlexLibraryPageState()
    internal fun forConnection(value: List<String>?): PlexLibraryPageState {
        if (identity != value) { identity = value; page = PlexLibraryPageState() }
        return page
    }
}

internal class PlexLibraryPageState {
    val grid = LazyGridState()
    val list = LazyListState()
    val globalSearch = PlexGlobalSearchState()
    val globalList = LazyListState()
    var offset by mutableIntStateOf(0)
    var movies by mutableStateOf<List<PlexMovie>>(emptyList())
    var total by mutableIntStateOf(0)
    var nextPageOffset by mutableStateOf<Int?>(null)
    var lastAppliedOffset by mutableIntStateOf(-1)
    var displayedPageKey by mutableStateOf<String?>(null)
    var detail by mutableStateOf<PlexMovie?>(null)
    var libraryRefresh by mutableIntStateOf(0)
    var forceLibraryRefresh by mutableStateOf(false)
    var completedRequest: String? = null
}
