package com.megatron.player.plex

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Application-scoped connection, library and first-page cache for the Plex browser. */
class PlexBrowseSession(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val store = PlexStore(appContext)
    private val preferences = PlexLibraryPreferences(appContext)
    private val diskCache = PlexDiskCache(appContext)
    private val lifecycleLock = Any()
    private val cacheLock = Any()

    /** The identifier is persisted by PlexStore and is stable across session instances. */
    val client: PlexClient = PlexClient(store.clientIdentifier)

    var credentials: PlexCredentials? by mutableStateOf<PlexCredentials?>(null)
        private set
    var config: PlexServerConfig? by mutableStateOf<PlexServerConfig?>(null)
        private set
    var libraries: List<PlexLibrary> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var failure: String? by mutableStateOf<String?>(null)
        private set
    var revision: Int by mutableIntStateOf(0)
        private set

    private var started = false
    @Volatile private var epoch = 0L
    private var operationJob: Job = SupervisorJob(scope.coroutineContext[Job])
    private var operationScope = CoroutineScope(scope.coroutineContext + operationJob)
    private data class LibraryKey(val serverId: String, val libraryKey: String)
    private data class CachedPage(val page: PlexMoviePage, val storedAtMs: Long)
    private data class PageKey(
        val serverId: String,
        val libraryKey: String,
        val sort: PlexSort,
        val descending: Boolean,
    )

    private val firstPageCache = LinkedHashMap<PageKey, CachedPage>(16, 0.75f, true)
    private val libraryVersions = mutableMapOf<LibraryKey, Long>()
    private val inFlight = mutableMapOf<PageKey, Deferred<PlexMoviePage>>()
    private val maxCachedPages = 12

    /** Starts one background load. A failed start can be retried with reload(). */
    fun start() {
        synchronized(lifecycleLock) {
            if (started) return
            started = true
        }
        beginLoad(clearState = false)
    }

    /** Cancels old work, reloads the saved connection and clears all first-page data. */
    fun reload() {
        synchronized(lifecycleLock) { started = true }
        beginLoad(clearState = false)
    }

    /** Deletes the locally stored Plex connection and invalidates all old work. */
    fun disconnect() {
        val reset = resetOperations(clearCache = true)
        val currentEpoch = reset.first
        val runScope = reset.second
        synchronized(lifecycleLock) { started = false }
        runScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.disconnect()
                    runCatching { diskCache.clearAll() }
                    runCatching { java.io.File(appContext.cacheDir, "plex-posters").deleteRecursively() }
                }
                if (isCurrent(currentEpoch)) clearStateOnMain()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(currentEpoch)) setFailureOnMain(plexErrorMessage(error))
            }
        }
    }

    /** Loads a page. Only an empty query at offset zero participates in the bounded cache. */
    private val extraPages = com.megatron.player.source.CoalescingCache<String, PlexMoviePage>(5 * 60_000, 48)

    suspend fun loadPage(
        library: PlexLibrary,
        query: String = "",
        offset: Int = 0,
        sort: PlexSort = PlexSort.RELEASED,
        descending: Boolean = true,
        force: Boolean = false,
        filters: PlexLibraryFilter = PlexLibraryFilter(),
    ): PlexMoviePage {
        require(offset >= 0) { "Plex 分页偏移无效" }
        val snapshot = synchronized(lifecycleLock) {
            val current = credentials ?: throw IllegalStateException("尚未连接 Plex 服务器")
            Triple(epoch, current, operationScope)
        }
        val normalizedQuery = query.trim()
        val cacheable = normalizedQuery.isEmpty() && offset == 0 && !filters.active
        val key = PageKey(snapshot.second.config.id, library.key, sort, descending)

        if (cacheable && force) invalidateLibrary(key)
        if (cacheable) {
            synchronized(cacheLock) {
                firstPageCache[key]?.takeIf { !isExpired(it) }?.page?.let { return it }
                firstPageCache.remove(key)
            }
        }

        val libraryVersion = synchronized(cacheLock) { libraryVersions[LibraryKey(snapshot.second.config.id, library.key)] ?: 0L }
        if (!cacheable) return extraPages.get("${snapshot.first}|$revision|$libraryVersion|${snapshot.second.config.id}|${library.key}|$normalizedQuery|$offset|$sort|$descending|$filters", snapshot.third, force) {
            fetchPage(snapshot.second, library, normalizedQuery, offset, sort, descending, snapshot.first, filters)
        }
        val deferred = sharedFirstPage(key, snapshot.first to snapshot.second, library, sort, descending, snapshot.third)
        return deferred.await()
    }

    suspend fun loadFacets(library: PlexLibrary): PlexLibraryFacets {
        val snapshot = currentSnapshot()
        val result = withContext(Dispatchers.IO) { client.libraryFacets(snapshot.second, library.key, library.type) }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        return result
    }

    /** One bounded query per library, never a full-library scan. */
    suspend fun searchLibrary(library: PlexLibrary, query: String, offset: Int = 0): PlexMoviePage {
        require(query.isNotBlank() && offset >= 0) { "请输入搜索词" }
        val snapshot = currentSnapshot()
        val page = withContext(Dispatchers.IO) {
            if (library.type == "show") client.shows(snapshot.second, library.key, query.trim(), offset, 20)
            else client.movies(snapshot.second, library.key, query.trim(), offset, 20)
        }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        if (page.offset != offset) throw IllegalStateException("Plex 搜索分页偏移异常")
        return page
    }

    private fun beginLoad(clearState: Boolean) {
        val reset = resetOperations(clearCache = true)
        val currentEpoch = reset.first
        val runScope = reset.second
        runScope.launch {
            if (clearState) clearStateOnMain() else failure = null
            setLoadingOnMain(true)
            try {
                val savedConfig = withContext(Dispatchers.IO) { store.config() }
                if (!isCurrent(currentEpoch)) return@launch
                config = savedConfig
                if (savedConfig == null) {
                    clearStateOnMain()
                    return@launch
                }
                val previous = credentials
                if (previous != null && (previous.config.id != savedConfig.id || previous.config.machineIdentifier != savedConfig.machineIdentifier || previous.config.address != savedConfig.address)) {
                    credentials = null
                    libraries = emptyList()
                    continueWatching = emptyList()
                }
                val loaded = try {
                    withContext(Dispatchers.IO) { store.credentials(savedConfig.id) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isCurrent(currentEpoch)) {
                        credentials = null
                        libraries = emptyList()
                        setFailureOnMain(plexErrorMessage(error))
                    }
                    return@launch
                }
                if (loaded == null) {
                    if (isCurrent(currentEpoch)) setFailureOnMain("Plex 凭据不可用")
                    return@launch
                }
                val identity = diskCache.identity(loaded)
                val previousIdentity = previous?.let { diskCache.identity(it) }
                if (previousIdentity != null && previousIdentity != identity) {
                    credentials = null
                    libraries = emptyList()
                    continueWatching = emptyList()
                    withContext(Dispatchers.IO) { diskCache.clearIdentity(previousIdentity) }
                    revision += 1
                }
                credentials = loaded
                config = loaded.config
                val cachedLibraries = withContext(Dispatchers.IO) { readLibraries(identity) }
                if (cachedLibraries.isNotEmpty() && isCurrent(currentEpoch)) {
                    libraries = cachedLibraries
                    setLoadingOnMain(false)
                }
                withContext(Dispatchers.IO) { readMovieListCache(identity, "continue", "home") }?.let { continueWatching = it }
                refreshContinueWatching()
                val loadedLibraries = withContext(Dispatchers.IO) { client.libraries(loaded) }
                if (!isCurrent(currentEpoch)) return@launch
                config = loaded.config
                val supportedLibraries = loadedLibraries.filter {
                    it.type.equals("movie", ignoreCase = true) || it.type.equals("show", ignoreCase = true)
                }
                libraries = supportedLibraries
                withContext(Dispatchers.IO) { writeIfCurrent(currentEpoch) { writeLibraries(identity, supportedLibraries) } }
                failure = null
                revision += 1
                setLoadingOnMain(false)
                prefetch(currentEpoch, loaded, supportedLibraries, runScope)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(currentEpoch)) {
                    setFailureOnMain(plexErrorMessage(error))
                }
            } finally {
                if (isCurrent(currentEpoch)) setLoadingOnMain(false)
            }
        }
    }

    suspend fun cachedPage(library: PlexLibrary, query: String = "", offset: Int = 0, sort: PlexSort = PlexSort.RELEASED, descending: Boolean = true): PlexMoviePage? {
        require(offset >= 0) { "Plex 分页偏移无效" }
        val snapshot = synchronized(lifecycleLock) { epoch to (credentials ?: return null) }
        val identity = diskCache.identity(snapshot.second)
        val result = withContext(Dispatchers.IO) {
            diskCache.read(identity, "page", pageKey(library, query.trim(), offset, sort, descending))?.let { pageFromJson(it) }
        }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        return result
    }

    suspend fun cachedDetail(key: String): PlexMovie? = readMovieCache("detail", key)

    suspend fun loadDetail(key: String): PlexMovie {
        val snapshot = currentSnapshot()
        val current = snapshot.second
        val movie = withContext(Dispatchers.IO) { client.detail(current, key) }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        withContext(Dispatchers.IO) { writeIfCurrent(snapshot.first) { writeMovieCache(diskCache.identity(current), "detail", key, movie) } }
        return movie
    }

    suspend fun cachedEpisodes(showKey: String): List<PlexMovie>? = readMovieListCache("episodes", showKey)

    suspend fun loadEpisodes(showKey: String): List<PlexMovie> {
        val snapshot = currentSnapshot()
        val current = snapshot.second
        val episodes = withContext(Dispatchers.IO) { client.allEpisodes(current, showKey) }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        withContext(Dispatchers.IO) { writeIfCurrent(snapshot.first) { writeMovieListCache(diskCache.identity(current), "episodes", showKey, episodes) } }
        return episodes
    }

    suspend fun cachedSeasons(showKey: String): List<PlexMovie>? = readMovieListCache("seasons", showKey)

    suspend fun loadSeasons(showKey: String): List<PlexMovie> {
        val snapshot = currentSnapshot()
        val current = snapshot.second
        val seasons = withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, PlexMovie>()
            val seen = HashSet<String>()
            var requestOffset = 0
            var previousNext = -1
            val limit = 100
            while (true) {
                val page = client.children(current, showKey, requestOffset, limit)
                if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
                if (page.offset != requestOffset) throw IllegalStateException("Plex 季列表分页偏移异常")
                if (page.movies.isEmpty()) {
                    if (page.total > requestOffset) throw IllegalStateException("Plex 季列表分页提前结束")
                    break
                }
                val raw = page.movies.map { it.ratingKey }
                if (raw.any { !seen.add(it) }) throw IllegalStateException("Plex 季列表分页重复")
                page.movies.filter { it.type.equals("season", true) && (it.parentRatingKey.isBlank() || it.parentRatingKey == showKey) }.forEach { found.putIfAbsent(it.ratingKey, it) }
                val next = requestOffset + page.movies.size
                if (next <= requestOffset || next <= previousNext) throw IllegalStateException("Plex 季列表分页未前进")
                previousNext = next
                if ((page.total >= 0 && next >= page.total) || (page.total < 0 && page.movies.size < limit)) break
                requestOffset = next
            }
            found.values.sortedWith(compareBy<PlexMovie> { if (it.index < 0) Int.MAX_VALUE else it.index }.thenBy { it.ratingKey })
        }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        withContext(Dispatchers.IO) { writeIfCurrent(snapshot.first) { writeMovieListCache(diskCache.identity(current), "seasons", showKey, seasons) } }
        return seasons
    }

    var continueWatching: List<PlexMovie> by mutableStateOf(emptyList())
        private set
    var continueLoading: Boolean by mutableStateOf(false)
        private set
    var continueFailure: String? by mutableStateOf<String?>(null)
        private set
    @Volatile private var continueRequestToken = 0L
    private var continueJob: Job? = null

    private var continueSuccessAt = 0L
    private var continueSuccessEpoch = -1L
    fun refreshContinueWatching(force: Boolean = true) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && (continueJob?.isActive == true || (continueSuccessEpoch == epoch && now - continueSuccessAt in 0..30_000))) return
        val reset = synchronized(lifecycleLock) { epoch to operationScope }
        val request = ++continueRequestToken
        continueJob?.cancel()
        continueJob = reset.second.launch {
            val current = synchronized(lifecycleLock) { credentials } ?: return@launch
            continueLoading = true
            try {
                val items = withContext(Dispatchers.IO) { aggregateContinue(client.continueWatching(current)) }
                if (isCurrent(reset.first) && request == continueRequestToken) {
                    continueWatching = items
                    continueFailure = null
                    continueSuccessAt = android.os.SystemClock.elapsedRealtime(); continueSuccessEpoch = reset.first
                    withContext(Dispatchers.IO) { writeIfCurrent(reset.first) { if (request == continueRequestToken) writeMovieListCache(diskCache.identity(current), "continue", "home", items) } }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(reset.first) && request == continueRequestToken) continueFailure = plexErrorMessage(error)
            } finally {
                if (isCurrent(reset.first) && request == continueRequestToken) continueLoading = false
            }
        }
    }

    suspend fun setWatched(movie: PlexMovie, watched: Boolean) {
        require(movie.type.equals("movie", true) || movie.type.equals("episode", true)) { "只支持电影和单集标记观看状态" }
        val snapshot = currentSnapshot()
        val current = snapshot.second
        withContext(Dispatchers.IO) {
            if (watched) client.markWatched(current, movie.ratingKey) else client.markUnwatched(current, movie.ratingKey)
            store.discardPendingProgress(movie.ratingKey)
        }
        val reset = resetIfCurrent(snapshot.first, diskCache.identity(current))
        val postEpoch = reset.first
        val currentLibraries = libraries
        withContext(Dispatchers.IO) {
            writeIfCurrent(postEpoch) {
                diskCache.invalidatePlayback(diskCache.identity(current), movie.ratingKey, movie.grandparentRatingKey, movie.parentRatingKey)
                writeLibraries(diskCache.identity(current), currentLibraries)
            }
        }
        if (!isCurrent(postEpoch)) throw CancellationException("Plex 连接已更新")
        if (watched) continueWatching = continueWatching.filterNot { it.ratingKey == movie.ratingKey }
        revision += 1
        refreshContinueWatching()
    }

    fun rename(name: String) {
        val renamed = store.rename(name) ?: return
        if (config?.id == renamed.id) {
            config = renamed
            credentials?.let { credentials = PlexCredentials(renamed, it.token) }
        }
    }

    /** Invalidates playback related metadata after the player leaves, without blocking the caller. */
    fun refreshAfterPlayback(ratingKey: String, showKey: String? = null) {
        require(ratingKey.isNotBlank()) { "Plex 媒体编号不能为空" }
        val reset = synchronized(lifecycleLock) { epoch to operationScope }
        reset.second.launch {
            val current = synchronized(lifecycleLock) { credentials } ?: return@launch
            val cached = withContext(Dispatchers.IO) { diskCache.read(diskCache.identity(current), "detail", ratingKey)?.let(PlexMovieCodec::fromJson) }
            val resolvedShowKey = showKey?.takeIf { it.isNotBlank() } ?: cached?.grandparentRatingKey
            val resolvedSeasonKey = cached?.parentRatingKey
            synchronized(cacheLock) {
                invalidateFirstPagesLocked()
            }
            withContext(Dispatchers.IO) { writeIfCurrent(reset.first) { diskCache.invalidatePlayback(diskCache.identity(current), ratingKey, resolvedShowKey, resolvedSeasonKey) } }
            if (isCurrent(reset.first)) {
                revision += 1
                refreshContinueWatching()
            }
        }
    }

    private suspend fun currentCredentials(): PlexCredentials = synchronized(lifecycleLock) { credentials } ?: throw IllegalStateException("尚未连接 Plex 服务器")
    private fun currentSnapshot(): Pair<Long, PlexCredentials> = synchronized(lifecycleLock) { epoch to (credentials ?: throw IllegalStateException("尚未连接 Plex 服务器")) }

    private suspend fun readMovieCache(kind: String, key: String): PlexMovie? {
        val snapshot = synchronized(lifecycleLock) { epoch to (credentials ?: return null) }
        val result = withContext(Dispatchers.IO) { diskCache.read(diskCache.identity(snapshot.second), kind, key)?.let(PlexMovieCodec::fromJson) }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        return result
    }

    private suspend fun readMovieListCache(kind: String, key: String): List<PlexMovie>? {
        val snapshot = synchronized(lifecycleLock) { epoch to (credentials ?: return null) }
        val result = withContext(Dispatchers.IO) { diskCache.read(diskCache.identity(snapshot.second), kind, key)?.optJSONArray("movies")?.let { PlexDiskCache.movies(it) } }
        if (!isCurrent(snapshot.first)) throw CancellationException("Plex 浏览会话已更新")
        return result
    }

    private fun writeMovieCache(identity: String, kind: String, key: String, movie: PlexMovie) = diskCache.write(identity, kind, key, PlexMovieCodec.toJson(movie))
    private fun writeMovieListCache(identity: String, kind: String, key: String, movies: List<PlexMovie>) = diskCache.write(identity, kind, key, JSONObject().put("movies", PlexDiskCache.movies(movies)))
    private fun writePageCache(identity: String, library: PlexLibrary, query: String, offset: Int, sort: PlexSort, descending: Boolean, page: PlexMoviePage) = diskCache.write(identity, "page", pageKey(library, query, offset, sort, descending), JSONObject().put("movies", PlexDiskCache.movies(page.movies)).put("total", page.total).put("offset", page.offset))
    private fun readMovieListCache(identity: String, kind: String, key: String): List<PlexMovie>? = diskCache.read(identity, kind, key)?.optJSONArray("movies")?.let { PlexDiskCache.movies(it) }
    private fun writeIfCurrent(expectedEpoch: Long, action: () -> Unit) {
        synchronized(lifecycleLock) {
            if (epoch != expectedEpoch) return
            runCatching { action() }
        }
    }
    private fun aggregateContinue(items: List<PlexMovie>): List<PlexMovie> {
        val grouped = LinkedHashMap<String, PlexMovie>()
        items.forEach { movie ->
            val group = if (movie.type.equals("episode", true) && movie.grandparentRatingKey.isNotBlank()) {
                "show:${movie.grandparentRatingKey}"
            } else "item:${movie.ratingKey}"
            grouped.putIfAbsent(group, movie)
        }
        return grouped.values.toList()
    }
    private fun pageKey(library: PlexLibrary, query: String, offset: Int, sort: PlexSort, descending: Boolean) = "${library.key}|$query|$offset|${sort.name}|$descending"
    private fun pageFromJson(json: JSONObject): PlexMoviePage? = json.optJSONArray("movies")?.let { PlexMoviePage(PlexDiskCache.movies(it), json.optInt("total"), json.optInt("offset")) }
    private fun readLibraries(identity: String): List<PlexLibrary> = diskCache.read(identity, "libraries", "all")?.optJSONArray("libraries")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { item ->
        val locations = item.optJSONArray("locations")
        PlexLibrary(item.optString("key"), item.optString("title"), item.optString("type"), if (locations == null) emptyList() else (0 until locations.length()).map { locations.optString(it) }.filter(String::isNotBlank))
    } } } ?: emptyList()
    private fun writeLibraries(identity: String, values: List<PlexLibrary>) = diskCache.write(identity, "libraries", "all", JSONObject().put("libraries", JSONArray().also { a -> values.forEach { a.put(JSONObject().put("key", it.key).put("title", it.title).put("type", it.type).put("locations", JSONArray(it.locations))) } }))

    private suspend fun prefetch(currentEpoch: Long, loaded: PlexCredentials, loadedLibraries: List<PlexLibrary>, runScope: CoroutineScope) {
        val semaphore = Semaphore(2)
        coroutineScope {
            loadedLibraries.map { library ->
                async {
                    semaphore.withPermit {
                        if (!isCurrent(currentEpoch)) return@withPermit
                        runCatching {
                            val options = withContext(Dispatchers.IO) {
                                preferences.options(loaded.config.id, library.key)
                            }
                            loadPageInternal(currentEpoch, loaded, library, options.sort, options.descending, runScope)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun loadPageInternal(
        currentEpoch: Long,
        loaded: PlexCredentials,
        library: PlexLibrary,
        sort: PlexSort,
        descending: Boolean,
        runScope: CoroutineScope,
    ): PlexMoviePage {
        val key = PageKey(loaded.config.id, library.key, sort, descending)
        synchronized(cacheLock) {
            firstPageCache[key]?.takeIf { !isExpired(it) }?.page?.let { return it }
            firstPageCache.remove(key)
        }
        return sharedFirstPage(key, currentEpoch to loaded, library, sort, descending, runScope).await()
    }

    private fun sharedFirstPage(
        key: PageKey,
        snapshot: Pair<Long, PlexCredentials>,
        library: PlexLibrary,
        sort: PlexSort,
        descending: Boolean,
        runScope: CoroutineScope,
    ): Deferred<PlexMoviePage> {
        synchronized(cacheLock) {
            inFlight[key]?.let { return it }
            val libraryKey = LibraryKey(key.serverId, key.libraryKey)
            val version = libraryVersions[libraryKey] ?: 0L
            val deferred = runScope.async(Dispatchers.IO) {
                val page = fetchPage(snapshot.second, library, "", 0, sort, descending, snapshot.first)
                synchronized(lifecycleLock) {
                    if (epoch == snapshot.first) {
                        synchronized(cacheLock) {
                            if ((libraryVersions[libraryKey] ?: 0L) == version) {
                                firstPageCache[key] = CachedPage(page, System.currentTimeMillis())
                                runCatching { writePageCache(diskCache.identity(snapshot.second), library, "", 0, sort, descending, page) }
                                while (firstPageCache.size > maxCachedPages) {
                                    val iterator = firstPageCache.entries.iterator()
                                    iterator.next()
                                    iterator.remove()
                                }
                            }
                        }
                    }
                }
                page
            }
            inFlight[key] = deferred
            deferred.invokeOnCompletion { synchronized(cacheLock) { if (inFlight[key] === deferred) inFlight.remove(key) } }
            return deferred
        }
    }

    private suspend fun fetchPage(
        loaded: PlexCredentials,
        library: PlexLibrary,
        query: String,
        offset: Int,
        sort: PlexSort,
        descending: Boolean,
        expectedEpoch: Long,
        filters: PlexLibraryFilter = PlexLibraryFilter(),
    ): PlexMoviePage {
        val page = withContext(Dispatchers.IO) {
            if (library.type.equals("show", ignoreCase = true)) {
                client.shows(loaded, library.key, query, offset, PAGE_SIZE, sort, descending, filters)
            } else {
                client.movies(loaded, library.key, query, offset, PAGE_SIZE, sort, descending, filters)
            }
        }
        if (!isCurrent(expectedEpoch)) throw CancellationException("Plex 浏览会话已更新")
        return page
    }

    private fun invalidateLibrary(key: PageKey) {
        val toCancel = mutableListOf<Deferred<PlexMoviePage>>()
        synchronized(cacheLock) {
            val libraryKey = LibraryKey(key.serverId, key.libraryKey)
            libraryVersions[libraryKey] = (libraryVersions[libraryKey] ?: 0L) + 1L
            val cacheIterator = firstPageCache.keys.iterator()
            while (cacheIterator.hasNext()) {
                val item = cacheIterator.next()
                if (item.serverId == key.serverId && item.libraryKey == key.libraryKey) cacheIterator.remove()
            }
            val flightIterator = inFlight.entries.iterator()
            while (flightIterator.hasNext()) {
                val entry = flightIterator.next()
                if (entry.key.serverId == key.serverId && entry.key.libraryKey == key.libraryKey) {
                    toCancel += entry.value
                    flightIterator.remove()
                }
            }
        }
        toCancel.forEach { it.cancel() }
    }

    private fun resetOperations(clearCache: Boolean): Pair<Long, CoroutineScope> {
        synchronized(lifecycleLock) {
            return resetOperationsLocked(clearCache)
        }
    }

    private fun resetIfCurrent(expectedEpoch: Long, expectedIdentity: String): Pair<Long, CoroutineScope> {
        synchronized(lifecycleLock) {
            val current = credentials ?: throw CancellationException("Plex 连接已更新")
            if (epoch != expectedEpoch || diskCache.identity(current) != expectedIdentity) throw CancellationException("Plex 连接已更新")
            return resetOperationsLocked(clearCache = true)
        }
    }

    private fun resetOperationsLocked(clearCache: Boolean): Pair<Long, CoroutineScope> {
        epoch += 1
        operationJob.cancel()
        operationJob = SupervisorJob(scope.coroutineContext[Job])
        operationScope = CoroutineScope(scope.coroutineContext + operationJob)
        if (clearCache) synchronized(cacheLock) {
            firstPageCache.clear()
            inFlight.clear()
            libraryVersions.clear()
        }
        return epoch to operationScope
    }

    private fun invalidateFirstPagesLocked() {
        val keys = (firstPageCache.keys + inFlight.keys + libraryVersions.keys.map { PageKey(it.serverId, it.libraryKey, PlexSort.RELEASED, true) })
            .distinct()
        val toCancel = inFlight.values.toList()
        firstPageCache.clear()
        inFlight.clear()
        keys.forEach { key ->
            val library = LibraryKey(key.serverId, key.libraryKey)
            libraryVersions[library] = (libraryVersions[library] ?: 0L) + 1L
        }
        toCancel.forEach { it.cancel() }
    }

    private fun isCurrent(candidate: Long): Boolean = synchronized(lifecycleLock) { epoch == candidate }

    private suspend fun clearStateOnMain() = withContext(Dispatchers.Main.immediate) {
        credentials = null
        config = null
        libraries = emptyList()
        loading = false
        failure = null
        continueWatching = emptyList()
        continueLoading = false
        continueFailure = null
        revision += 1
    }

    private suspend fun setFailureOnMain(message: String) = withContext(Dispatchers.Main.immediate) { failure = message }

    private suspend fun setLoadingOnMain(value: Boolean) = withContext(Dispatchers.Main.immediate) { loading = value }

    private fun isExpired(cached: CachedPage): Boolean =
        System.currentTimeMillis() - cached.storedAtMs >= CACHE_TTL_MS

    private companion object {
        const val PAGE_SIZE = 60
        const val CACHE_TTL_MS = 120_000L
    }
}
