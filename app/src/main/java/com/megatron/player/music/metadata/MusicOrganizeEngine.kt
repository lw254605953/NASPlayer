package com.megatron.player.music.metadata

import android.content.Context
import com.megatron.player.ai.*
import com.megatron.player.music.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*

data class OrganizeRow(val track: MusicTrack, val clue: String, val status: String = "待匹配", val matches: List<MusicMatch> = emptyList(), val selected: String? = null) {
    val choice get() = matches.firstOrNull { it.id == selected }
    fun json() = JSONObject().put("track", track.json()).put("clue", clue).put("status", status).put("matches", JSONArray(matches.map { it.json() })).put("selected", selected ?: JSONObject.NULL)
    companion object {
        fun read(j: JSONObject): OrganizeRow { val a = j.getJSONArray("matches"); return OrganizeRow(musicTrack(j.getJSONObject("track")), j.getString("clue").take(240), j.getString("status").take(100), (0 until minOf(a.length(), 12)).map { MusicMatch.read(a.getJSONObject(it)) }, if (j.isNull("selected")) null else j.getString("selected")) }
    }
}
data class OrganizeState(val rows: List<OrganizeRow> = emptyList(), val running: Boolean = false, val finished: Int = 0, val remaining: Int = 0, val error: String? = null)
data class SupplementFields(val title: Boolean = true, val artist: Boolean = true, val album: Boolean = true, val artwork: Boolean = true, val lyrics: Boolean = true) : java.io.Serializable

/** Explicit batches, no autonomous tool loop. Pending results are local and survive screen closure. */
class MusicOrganizeEngine private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("music_organize_tasks_v1", Context.MODE_PRIVATE)
    private val supplements = MusicSupplementStore(app)
    private val model: MetadataModel = QwenClient(app)
    private val catalog = MusicCatalog(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<OrganizeState> = _state
    private var job: Job? = null
    private fun read(): OrganizeState = runCatching { val a = JSONArray(prefs.getString("rows", "[]")); OrganizeState(rows = (0 until minOf(a.length(), 20)).map { OrganizeRow.read(a.getJSONObject(it)) }) }.getOrDefault(OrganizeState())
    @Synchronized private fun publish(state: OrganizeState) { _state.value = state; prefs.edit().putString("rows", JSONArray(state.rows.map { it.json() }).toString()).apply() }
    @Synchronized fun prepare(tracks: List<MusicTrack>) {
        if (_state.value.running || com.megatron.player.backup.BackupRestoreGuard.restoring) return
        val items = tracks.distinctBy { it.id }
        val pending = _state.value.rows
        val requested = items.associateBy { it.id }
        if (pending.isNotEmpty() && pending.any { it.status in setOf("待确认", "请选择版本") && it.matches.isNotEmpty() } &&
            (items.size > 1 || pending.size == 1) && pending.all { row -> requested[row.track.id]?.let { it.sizeBytes == row.track.sizeBytes && it.modifiedAt == row.track.modifiedAt } == true }) return
        val attempted = prefs.getStringSet("attempted", emptySet()).orEmpty()
        val selected = if (items.size == 1) items else items.filterNot { supplements.hasValid(it) || attemptKey(it) in attempted }
        publish(OrganizeState(selected.take(20).map { track ->
            val record = supplements.recordFor(track); val previous = record?.optJSONObject("match")?.let { runCatching { MusicMatch.read(it) }.getOrNull() }
            val clue = musicClue(track.path.substringAfterLast('/'))
            OrganizeRow(track, clue.orEmpty(), if (previous != null) "已保存" else if (clue == null) "待识别" else "待匹配", previous?.let(::listOf).orEmpty(), previous?.id)
        }, remaining = (selected.size - 20).coerceAtLeast(0)))
    }
    @Synchronized fun start(ids: Set<String>? = null) {
        if (_state.value.running || com.megatron.player.backup.BackupRestoreGuard.restoring) return
        val rows = _state.value.rows.filter { ids == null || it.track.id in ids }
        if (rows.isEmpty()) return
        publish(_state.value.copy(running = true, error = null, finished = 0))
        job = scope.launch {
            try {
                QwenConfigStore(app).credentials()
                for (row in rows) {
                    ensureActive()
                    if (row.status == "待识别" || row.status == "已保存" || row.matches.isNotEmpty()) { prefs.edit().putStringSet("attempted", prefs.getStringSet("attempted", emptySet()).orEmpty().toSet() + attemptKey(row.track)).apply(); publish(_state.value.copy(finished = _state.value.finished + 1)); continue }
                    try {
                        val safe = QwenPolicy.validateClue(row.clue)
                        val clue = model.parseClue(safe)
                        require(clue.title.isNotBlank()) { "未能提取歌名" }
                        val matches = catalog.search(clue.title, clue.artist).take(10)
                        var choice: String? = null
                        if (matches.size == 1) choice = matches.first().id
                        else if (matches.size > 1) {
                            val answer = model.choose(safe, matches.map { MetadataCandidate(it.id, it.title.take(240), it.artist.take(240), it.album.take(240), it.version.take(240), it.durationMs.coerceIn(0, 86_400_000)) })
                            if (!answer.uncertain) choice = answer.candidateId
                        }
                        update(row.track.id) { it.copy(matches = matches, selected = choice, status = when { matches.isEmpty() -> "未找到资料"; choice == null -> "请选择版本"; else -> "待确认" }) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { update(row.track.id) { it.copy(status = "匹配失败") }; publish(_state.value.copy(error = safeOrganizeError(e))) }
                    prefs.edit().putStringSet("attempted", prefs.getStringSet("attempted", emptySet()).orEmpty().toSet() + attemptKey(row.track)).apply()
                    publish(_state.value.copy(finished = _state.value.finished + 1))
                }
            } catch (e: CancellationException) { /* Completed candidates remain resumable. */ }
            catch (e: Exception) { publish(_state.value.copy(error = safeOrganizeError(e))) }
            finally { finishTask(currentCoroutineContext().job) }
        }
    }
    private fun attemptKey(track: MusicTrack) = "${track.id}:${track.sizeBytes}:${track.modifiedAt}"
    @Synchronized private fun update(id: String, transform: (OrganizeRow) -> OrganizeRow) = publish(_state.value.copy(rows = _state.value.rows.map { if (it.track.id == id) transform(it) else it }))
    fun editClue(id: String, clue: String) {
        if (_state.value.running || com.megatron.player.backup.BackupRestoreGuard.restoring) return
        val safe = QwenPolicy.validateClue(clue)
        update(id) { it.copy(clue = safe, status = "待匹配", matches = emptyList(), selected = null) }
    }
    fun select(id: String, matchId: String) {
        if (_state.value.running || com.megatron.player.backup.BackupRestoreGuard.restoring) return
        update(id) { row -> if (row.matches.any { it.id == matchId }) row.copy(selected = matchId, status = "待确认") else row }
    }
    fun pause() { job?.cancel(); catalog.cancel() }
    @Synchronized fun save(ids: Set<String>, fields: SupplementFields, replace: Boolean = false) {
        if (_state.value.running || com.megatron.player.backup.BackupRestoreGuard.restoring) return
        val rows = _state.value.rows.filter { it.track.id in ids && it.choice != null }
        publish(_state.value.copy(running = true, error = null))
        job = scope.launch {
            try { for (row in rows) {
                ensureActive()
                val current = MusicRepository.shared(app).rawTrack(row.track.id) ?: throw IllegalStateException("歌曲已移动或删除，请重新选择")
                require(current.sizeBytes == row.track.sizeBytes && current.modifiedAt == row.track.modifiedAt) { "歌曲已变更，请重新匹配" }
                val selected = row.choice ?: continue
                val effective = supplements.decorate(current)
                val record = supplements.recordFor(current)?.let { JSONObject(it.toString()) } ?: JSONObject()
                val sources = record.optJSONObject("fieldSources") ?: JSONObject()
                var changed = false
                fun text(field: String, value: String, allowed: Boolean) { if (allowed && value.isNotBlank()) { record.put(field, value); sources.put(field, selected.source); changed = true } }
                val oldMatch = record.optJSONObject("match")
                val compatible = replace || (placeholderMusic(effective.album) || effective.album.equals(selected.album, true)) && (placeholderMusic(effective.artist) || effective.artist.equals(selected.artist, true)) && (oldMatch == null || oldMatch.optString("recording") == selected.recordingId && oldMatch.optString("release") == selected.releaseId)
                text("title", selected.title, fields.title && (replace || record.optString("title").isBlank() && missingMusicTitle(effective)))
                text("artist", selected.artist, fields.artist && (replace || record.optString("artist").isBlank() && placeholderMusic(effective.artist)))
                text("album", selected.album, fields.album && (replace || record.optString("album").isBlank() && placeholderMusic(effective.album)))
                if (compatible && fields.artwork && (replace || effective.artworkPath?.let { java.io.File(it).isFile } != true)) catalog.artwork(selected)?.let { record.put("artPath", it); sources.put("artwork", "https://coverartarchive.org/release/${selected.releaseId}"); changed = true }
                if (compatible && fields.lyrics && (replace || supplements.lyrics(current) == null)) {
                    // Reuse actual NAS/embedded lyrics before a new public lyrics query.
                    val local = MusicLyricsReader(app).load(current)
                    if (local == null) catalog.lyrics(selected, current.durationMs.takeIf { it > 0 } ?: selected.durationMs)?.let {
                        record.put("lyrics", it); record.put("lyricsSource", "https://lrclib.net"); sources.put("lyrics", "https://lrclib.net"); changed = true
                    }
                }
                ensureActive()
                check(!com.megatron.player.backup.BackupRestoreGuard.restoring) { "正在恢复备份" }
                if (changed) {
                    record.put("match", selected.json()).put("source", selected.source).put("fieldSources", sources)
                    MusicRepository.shared(app).saveSupplement(current, record)
                }
                update(row.track.id) { it.copy(status = "已保存") }
            } } catch (e: CancellationException) { }
            catch (e: Exception) { publish(_state.value.copy(error = safeOrganizeError(e))) }
            finally { finishTask(currentCoroutineContext().job) }
        }
    }
    @Synchronized fun undo(id: String) {
        if (_state.value.running || com.megatron.player.backup.BackupRestoreGuard.restoring) return
        publish(_state.value.copy(running = true, error = null))
        job = scope.launch {
            try { ensureActive(); check(!com.megatron.player.backup.BackupRestoreGuard.restoring) { "正在恢复备份" }
                MusicRepository.shared(app).removeSupplement(id); update(id) { it.copy(status = "已撤销") }
            } catch (e: CancellationException) { }
            catch (_: Exception) { publish(_state.value.copy(error = "撤销失败，请重试")) }
            finally { finishTask(currentCoroutineContext().job) }
        }
    }
    @Synchronized private fun finishTask(finished: Job) { if (job === finished) { publish(_state.value.copy(running = false)); job = null } }
    suspend fun cancelAndJoin() { val current = job; pause(); current?.join() }
    companion object {
        @Volatile private var instance: MusicOrganizeEngine? = null
        fun shared(context: Context) = instance ?: synchronized(this) { instance ?: MusicOrganizeEngine(context).also { instance = it } }
    }
}
private fun safeOrganizeError(e: Exception): String = when {
    e is java.io.IOException -> e.message?.takeIf { it.length <= 100 && !it.contains(Regex("(?i)(https?://|sk-|token|bearer)")) } ?: "资料服务暂不可用"
    e is IllegalStateException || e is IllegalArgumentException -> e.message?.takeIf { it.length <= 100 && !it.contains(Regex("(?i)(https?://|sk-|token|bearer)")) } ?: "资料无法确认，请重新选择"
    else -> "资料整理失败，请重试"
}
