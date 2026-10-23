package com.megatron.player.music

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.megatron.player.source.BrowserEntry
import com.megatron.player.source.SourceKind
import com.megatron.player.source.SourceStore
import com.megatron.player.source.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque

/** The music index is local and separate from video/Plex state. No credentials are copied here. */
class MusicRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("music_library_v1", Context.MODE_PRIVATE)
    private val db = MusicDatabase(app)
    private val supplements = com.megatron.player.music.metadata.MusicSupplementStore(app)
    private val sourceStore = SourceStore(app)
    private val webDav = WebDavClient()
    private val tagReader = MusicTagReader(app, sourceStore)
    private val playbackTagReader = MusicTagReader(app, sourceStore)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationLock = Any()
    private val fileMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var fileMutationActive = false
    private val removedPaths = HashSet<String>()
    private val _fileRevision = MutableStateFlow(0)
    val fileRevision: StateFlow<Int> = _fileRevision
    private val _supplementRevision = MutableStateFlow(0)
    val supplementRevision: StateFlow<Int> = _supplementRevision
    private val trackMap = db.read().associateBy { it.id }.toMutableMap()
    private val _tracks = MutableStateFlow(trackMap.values.map(supplements::decorate))
    private val _roots = MutableStateFlow(readRoots())
    private val _favoriteIds = MutableStateFlow(readFavorites())
    private val _scanning = MutableStateFlow(false)
    private val _status = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)
    val tracks: StateFlow<List<MusicTrack>> = _tracks
    val roots: StateFlow<List<MusicRoot>> = _roots
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds
    val scanning: StateFlow<Boolean> = _scanning
    val status: StateFlow<String?> = _status
    val error: StateFlow<String?> = _error
    @Volatile private var scanJob: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null

    init {
        // One incremental repair for legacy/incomplete indices; completed files need no tag requests.
        val selected = trackMap.values.filter { track -> _roots.value.any { root ->
            track.sourceId == root.sourceId && (root.path.isBlank() || track.path.startsWith(root.path.trimEnd('/') + "/"))
        } }
        if (_roots.value.isNotEmpty() && (selected.isEmpty() || selected.any(::musicNeedsMetadata))) {
            scope.launch { scan() }
        }
    }


    private fun readRoots(): List<MusicRoot> = runCatching {
        val a = JSONArray(prefs.getString("roots", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { MusicRoot(it.getString("source"), it.getString("path"), it.getString("name")) }
    }.getOrDefault(emptyList())
    private fun readFavorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    fun saveRoots(items: List<MusicRoot>) {
        val clean = items.map { it.copy(path = it.path.trim('/')) }.distinctBy { it.sourceId to it.path }
        val old = _roots.value
        if (clean == old) return
        val added = clean.any { root -> old.none { it.sourceId == root.sourceId && it.path == root.path } }
        val previousScan = scanJob
        cancelScan()
        prefs.edit().putString("roots", JSONArray(clean.map { JSONObject().apply {
            put("source", it.sourceId); put("path", it.path); put("name", it.name)
        } }).toString()).apply()
        _roots.value = clean
        _status.value = when {
            clean.isEmpty() -> "请选择 NAS 音乐目录"
            added -> "目录已保存，正在准备扫描…"
            else -> "音乐目录已更新"
        }
        if (added) scope.launch {
            // Finish cancellation before starting the new snapshot, so the old scan's
            // network cleanup cannot cancel the new directory scan.
            previousScan?.join()
            if (_roots.value == clean) scan()
        }
    }
    fun toggleFavorite(id: String) {
        val old = _favoriteIds.value
        val changed = if (id in old) old - id else old + id
        prefs.edit().putStringSet("favorites", changed).apply()
        _favoriteIds.value = changed
    }
    fun dismissError() { _error.value = null }

    fun trackFor(sourceId: String, entry: BrowserEntry): MusicTrack = supplements.decorate(trackForInternal(sourceId, entry, true))
    private fun trackForInternal(sourceId: String, entry: BrowserEntry, publish: Boolean): MusicTrack = synchronized(mutationLock) {
        val id = musicId(sourceId, entry.path)
        val old = trackMap[id]
        val track = old?.takeIf { it.sizeBytes == entry.size && it.modifiedAt == entry.modifiedAt } ?: MusicTrack(
            id, sourceId, entry.path, entry.name.substringBeforeLast('.', entry.name),
            format = entry.name.substringAfterLast('.', "").uppercase(), sizeBytes = entry.size, modifiedAt = entry.modifiedAt,
        )
        if (id in removedPaths) return@synchronized track
        if (old != track) { db.upsert(track); trackMap[id] = track; if (publish) _tracks.value = trackMap.values.map(supplements::decorate) }
        track
    }

    private fun storeTrack(track: MusicTrack, publish: Boolean = true): MusicTrack = synchronized(mutationLock) {
        val current = trackMap[track.id]
        if (track.id in removedPaths) return@synchronized track
        if (current == track) return@synchronized current
        // A metadata request started before a file changed must never put the old file back.
        if (current != null && (current.modifiedAt > track.modifiedAt || current.modifiedAt == track.modifiedAt && (current.sizeBytes != track.sizeBytes || current.indexedAt > track.indexedAt))) return@synchronized current
        db.upsert(track); trackMap[track.id] = track
        if (publish) _tracks.value = trackMap.values.map(supplements::decorate)
        track
    }
    private fun publishTracks() = synchronized(mutationLock) { _tracks.value = trackMap.values.map(supplements::decorate) }
    private fun removeTracks(ids: List<String>) = synchronized(mutationLock) {
        ids.forEach { db.remove(it); trackMap.remove(it) }
        _tracks.value = trackMap.values.map(supplements::decorate)
    }

    /** Persist fallback labels decoded during playback, not just the transient now-playing display. */
    suspend fun updatePlaybackInfo(track: MusicTrack, durationMs: Long, sampleRate: Int, bitDepth: Int,
        title: String? = null, artist: String? = null, album: String? = null, artworkBytes: ByteArray? = null): MusicTrack = withContext(Dispatchers.IO) {
        val art = artworkBytes?.takeIf { track.artworkPath?.let { java.io.File(it).isFile } != true }
            ?.let { playbackTagReader.cacheArtwork(it) }
        synchronized(mutationLock) {
            val current = trackMap[track.id] ?: track
            val supplemented = supplements.hasValid(track)
            if (current.sizeBytes != track.sizeBytes || current.modifiedAt != track.modifiedAt) return@synchronized current
            supplements.decorate(storeTrack(current.copy(
                title = current.title.takeUnless { it.isBlank() || it == current.path.substringAfterLast('/').substringBeforeLast('.') }
                    ?: title?.takeIf { !supplemented && it.isNotBlank() } ?: current.title,
                artist = current.artist.ifBlank { if (supplemented) "" else artist.orEmpty() },
                album = current.album.ifBlank { if (supplemented) "" else album.orEmpty() },
                artworkPath = art ?: current.artworkPath,
                durationMs = durationMs.takeIf { it > 0 } ?: current.durationMs,
                sampleRate = sampleRate.takeIf { it > 0 } ?: current.sampleRate,
                bitDepth = current.bitDepth.takeIf { it > 0 } ?: bitDepth.coerceAtLeast(0))))
        }
    }

    suspend fun listDirectory(sourceId: String, path: String): List<BrowserEntry> = withContext(Dispatchers.IO) {
        val credentials = sourceStore.credentials(sourceId)?.takeIf { it.config.kind == SourceKind.WEBDAV }
            ?: throw IOException("音乐来源已删除，请重新选择 NAS 连接")
        val before = _fileRevision.value
        val entries = com.megatron.player.source.DirectoryBrowserCache.list(credentials, path).filter { it.isDirectory || isMusicFile(it.name) }
        synchronized(mutationLock) { if (before == _fileRevision.value && !fileMutationActive) entries.forEach { removedPaths.remove(musicId(sourceId, it.path)) } }
        entries
    }

    fun invalidateDirectory(sourceId: String, path: String) {
        runCatching { sourceStore.credentials(sourceId)?.let { com.megatron.player.source.DirectoryBrowserCache.invalidate(it, path) } }
    }

    /** Header/tail reads are bounded; a failed tag never prevents filename-based playback. */
    suspend fun enrich(track: MusicTrack): MusicTrack = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val current = synchronized(mutationLock) { trackMap[track.id] } ?: track
        if (!musicNeedsMetadata(current)) return@withContext current
        val enriched = playbackTagReader.read(current)
        currentCoroutineContext().ensureActive()
        supplements.decorate(storeTrack(enriched))
    }

    suspend fun scan() {
        val task = synchronized(this) {
            if (_scanning.value || fileMutationActive) return
            _scanning.value = true
            scope.launch(start = CoroutineStart.LAZY) { scanNow() }.also { scanJob = it }
        }
        task.invokeOnCompletion { cause ->
            synchronized(this) {
                if (scanJob === task) {
                    scanJob = null; _scanning.value = false
                    if (cause is CancellationException && _status.value?.startsWith("正在索引") == true)
                        _status.value = "索引已取消，已读取的歌曲保留；音乐库尚不完整"
                }
            }
        }
        task.start()
        task.join()
    }

    private suspend fun scanNow() = withContext(Dispatchers.IO) {
        _error.value = null
        val snapshot = _roots.value
        var files = 0
        var directories = 0
        var failures = 0
        var boundary = false
        var metadataFailures = 0
        var lastPublishedAt = 0L
        tagReader.beginScan()
        val old = db.read().associateBy { it.id }
        val found = LinkedHashMap<String, MusicTrack>()
        val seen = HashSet<Pair<String, String>>()
        try {
            if (snapshot.isEmpty()) { _status.value = "请先选择 NAS 音乐目录"; return@withContext }
            for (root in snapshot) {
                val credentials = sourceStore.credentials(root.sourceId)?.takeIf { it.config.kind == SourceKind.WEBDAV }
                if (credentials == null) { failures++; _error.value = "音乐来源已删除，请在目录管理中重新选择"; continue }
                val pending = ArrayDeque<Pair<String, Int>>()
                pending.add(root.path to 0)
                var rootComplete = true
                val rootIds = HashSet<String>()
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val (path, depth) = pending.removeFirst()
                    if (!seen.add(root.sourceId to path)) { rootComplete = false; continue }
                    if (++directories > 10_000 || depth > 64) { boundary = true; rootComplete = false; break }
                    _status.value = "正在索引 · $files 首歌曲 · $directories 个目录"
                    val entries = try { webDav.list(credentials, path) { activeCall = it } }
                    catch (e: IOException) { currentCoroutineContext().ensureActive(); failures++; rootComplete = false; continue }
                    finally { activeCall = null }
                    for (entry in entries) {
                        currentCoroutineContext().ensureActive()
                        if (entry.isDirectory) { pending.add(entry.path to depth + 1); continue }
                        if (!isMusicFile(entry.name)) continue
                        if (++files > 20_000) { boundary = true; rootComplete = false; break }
                        val id = musicId(root.sourceId, entry.path)
                        rootIds += id
                        synchronized(mutationLock) { removedPaths.remove(id) }
                        val base = trackForInternal(root.sourceId, entry, false)
                        val track = if (!musicNeedsMetadata(base)) base
                            else tagReader.read(base, entries) { activeCall = it }
                        currentCoroutineContext().ensureActive()
                        if (track.indexedAt == 0L) metadataFailures++
                        activeCall = null
                        storeTrack(track, false)
                        found[id] = track
                        val now = System.currentTimeMillis()
                        if (files % 10 == 0 || now - lastPublishedAt >= 1000) { publishTracks(); lastPublishedAt = now }
                    }
                    if (boundary) break
                }
                // Only a complete directory walk may remove missing records. Cancellation/failures retain old data.
                currentCoroutineContext().ensureActive()
                if (rootComplete) {
                    val prefix = root.path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
                    removeTracks(old.values.filter { it.sourceId == root.sourceId && it.path.startsWith(prefix) && it.id !in rootIds && it.id !in found }.map { it.id })
                }
                if (boundary) break
            }
            publishTracks()
            _status.value = when {
                boundary -> "本次已索引 ${minOf(files, 20_000)} 首；达到单次 20,000 首 / 10,000 目录 / 64 层边界，音乐库尚不完整，请缩小选择范围"
                failures > 0 -> "索引结束，$files 首；$failures 个目录读取失败，保留已有资料，可重试"
                metadataFailures > 0 -> "索引完成 · $files 首歌曲 · $metadataFailures 首资料待重试"
                else -> "索引完成 · $files 首歌曲"
            }
            if (metadataFailures > 0) _error.value = "$metadataFailures 首歌曲资料读取失败"
            if (failures > 0 && _error.value == null) _error.value = "部分目录无法读取，请检查 NAS 连接和目录权限后重试"
        } catch (e: CancellationException) {
            publishTracks()
            _status.value = "索引已取消，已读取的歌曲保留；音乐库尚不完整"
            throw e
        } catch (e: Exception) {
            publishTracks()
            _error.value = "音乐索引失败，请检查 NAS 连接后重试"
            _status.value = "索引未完成，已读取的歌曲保留"
        } finally {
            activeCall?.cancel(); activeCall = null
        }
    }

    fun displayTrack(track: MusicTrack): MusicTrack = supplements.decorate(track)
    fun rawTrack(id: String): MusicTrack? = synchronized(mutationLock) { trackMap[id] }
    fun saveSupplement(track: MusicTrack, row: JSONObject) = synchronized(mutationLock) {
        check(!com.megatron.player.backup.BackupRestoreGuard.restoring) { "正在恢复备份" }
        val current = trackMap[track.id] ?: error("歌曲已移动或删除")
        check(current.sizeBytes == track.sizeBytes && current.modifiedAt == track.modifiedAt && track.id !in removedPaths) { "歌曲已变更" }
        supplements.put(current, row)
        _supplementRevision.value++
        _tracks.value = trackMap.values.map(supplements::decorate)
    }
    fun removeSupplement(id: String) = synchronized(mutationLock) {
        check(!com.megatron.player.backup.BackupRestoreGuard.restoring) { "正在恢复备份" }
        supplements.remove(id)
        _supplementRevision.value++
        _tracks.value = trackMap.values.map(supplements::decorate)
    }

    fun fileVisible(sourceId: String, path: String): Boolean = synchronized(mutationLock) { musicId(sourceId, path) !in removedPaths }

    /** Returns the new track for MOVE, null for DELETE. Local state changes only after server success. */
    suspend fun mutateFile(track: MusicTrack, destinationDirectory: String?): MusicTrack? = withContext(Dispatchers.IO) {
        fileMutex.lock()
        try {
            val previousScan = synchronized(this@MusicRepository) { fileMutationActive = true; scanJob }
            cancelScan()
            previousScan?.join()
            val credentials = sourceStore.credentials(track.sourceId)?.takeIf { it.config.kind == SourceKind.WEBDAV }
                ?: throw IOException("音乐连接已移除")
            val result = MusicFileOperations().execute(credentials, track, destinationDirectory)
            // Do not let UI cancellation after a successful remote write skip local reconciliation.
            synchronized(mutationLock) {
                removedPaths += track.id
                db.replaceFile(track.id, result)
                supplements.move(track, result)
                trackMap.remove(track.id)
                if (result != null) { removedPaths.remove(result.id); trackMap[result.id] = result }
                val favorites = _favoriteIds.value
                val changed = (favorites - track.id).let { if (result != null && track.id in favorites) it + result.id else it }
                prefs.edit().putStringSet("favorites", changed).apply()
                _favoriteIds.value = changed
                invalidateDirectory(track.sourceId, track.path.substringBeforeLast('/', ""))
                destinationDirectory?.let { invalidateDirectory(track.sourceId, it) }
                _fileRevision.value++
                _tracks.value = trackMap.values.map(supplements::decorate)
            }
            result
        } finally {
            fileMutationActive = false
            fileMutex.unlock()
        }
    }

    fun cancelScan() { activeCall?.cancel(); tagReader.cancel(); scanJob?.cancel() }
    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun shared(context: Context): MusicRepository = instance ?: synchronized(this) {
            instance ?: MusicRepository(context.applicationContext).also { instance = it }
        }
    }
}

internal class MusicDatabase(context: Context) : SQLiteOpenHelper(context, "music_library_v1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE music_tracks (id TEXT PRIMARY KEY NOT NULL, record TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun read(): List<MusicTrack> = readableDatabase.rawQuery("SELECT record FROM music_tracks ORDER BY id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) runCatching { musicTrack(JSONObject(cursor.getString(0))) }.getOrNull()?.let(::add) }
    }
    @Synchronized fun upsert(track: MusicTrack) { writableDatabase.execSQL("INSERT OR REPLACE INTO music_tracks (id, record) VALUES (?, ?)", arrayOf(track.id, track.json().toString())) }
    @Synchronized fun replaceFile(id: String, replacement: MusicTrack?) {
        writableDatabase.beginTransaction()
        try { remove(id); if (replacement != null) upsert(replacement); writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun remove(id: String) { writableDatabase.delete("music_tracks", "id = ?", arrayOf(id)) }
}
