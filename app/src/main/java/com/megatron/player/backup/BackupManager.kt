package com.megatron.player.backup

import android.content.Context
import com.megatron.player.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class BackupTarget(val sourceId: String, val path: String)
data class BackupStatus(val busy: Boolean = false, val lastSuccess: Long = 0, val error: String? = null)

class BackupManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nas_backup_v1", 0)
    private val secrets = DeviceSecrets("MegatronPlayer.BackupSecrets.v1")
    private val sources = SourceStore(app)
    private val dav = WebDavClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var startupRequested = false
    private val _status = MutableStateFlow(BackupStatus(lastSuccess = prefs.getLong("last_success", 0), error = prefs.getString("last_error", null)))
    val status: StateFlow<BackupStatus> = _status
    fun target(): BackupTarget? = prefs.getString("source", null)?.let { BackupTarget(it, prefs.getString("path", "").orEmpty()) }
    fun configured() = target() != null && prefs.contains("password")
    fun passwordProtected() = configured() && prefs.getBoolean("password_protected", true)
    fun sources() = sources.list().filter { it.kind == SourceKind.WEBDAV }
    private fun password(): CharArray = secrets.decrypt(prefs.getString("password", null) ?: error("请设置备份密码")).toCharArray()
    private fun owner(): String = synchronized(this) {
        prefs.getString("owner", null) ?: UUID.randomUUID().toString().replace("-", "").also { check(prefs.edit().putString("owner", it).commit()) }
    }
    suspend fun configure(target: BackupTarget, password: String?) = mutex.withLock {
        withContext(Dispatchers.IO) { saveConfiguration(target, password) }
    }
    private fun saveConfiguration(target: BackupTarget, password: String?) {
        val actualPassword = password ?: if (configured()) String(this.password()) else ""
        require(actualPassword.isEmpty() || actualPassword.length in 8..128) { "备份密码需为8至128位" }
        require(sources.find(target.sourceId)?.kind == SourceKind.WEBDAV) { "请先连接WebDAV" }
        val path = WebDavHttp.cleanRelativePath(target.path)
        check(prefs.edit().putString("source", target.sourceId).putString("path", path)
            .putString("password", secrets.encrypt(actualPassword)).putBoolean("password_protected", actualPassword.isNotEmpty())
            .putLong("last_success", 0).remove("last_error").commit())
        _status.value = BackupStatus()
    }
    /** Once per process, not on each Activity recreation or foreground transition. */
    @Synchronized fun onAppStart() {
        if (startupRequested) return
        startupRequested = true
        if (configured()) scope.launch { delay(2500); upload() }
    }
    fun backupNow() { scope.launch { upload() } }
    suspend fun directories(sourceId: String, path: String): List<BrowserEntry> = withContext(Dispatchers.IO) {
        dav.list(credentials(sourceId), WebDavHttp.cleanRelativePath(path)).filter { it.isDirectory }
    }
    suspend fun createDirectory(sourceId: String, parent: String, name: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 100 && '/' !in name && '\\' !in name && name !in setOf(".", "..")) { "目录名称无效" }
        val credential = credentials(sourceId)
        val path = listOf(parent.trim('/'), name).filter(String::isNotEmpty).joinToString("/")
        WebDavHttp.execute(credential, url(credential, path, true), "MKCOL", null).use {
            if (!it.isSuccessful) throw WebDavHttp.httpFailure(it.code)
        }
    }
    suspend fun versions(target: BackupTarget = target() ?: error("请先设置备份目录")): List<BrowserEntry> = withContext(Dispatchers.IO) {
        dav.list(credentials(target.sourceId), target.path).filter { !it.isDirectory && BackupNames.valid(it.name) && it.size in 1..BackupArchive.MAX_BYTES.toLong() }
            .sortedByDescending { it.name.substring(43, 56) }
    }
    private fun credentials(id: String): SourceCredentials = sources.credentials(id)?.takeIf { it.config.kind == SourceKind.WEBDAV }
        ?: throw IOException("备份的WebDAV连接已移除，请重新设置")
    private fun url(credentials: SourceCredentials, path: String, directory: Boolean = false) = WebDavHttp.urlForPath(WebDavHttp.validateBase(credentials.config.address), WebDavHttp.cleanRelativePath(path), directory)
    private fun path(target: BackupTarget, name: String) = listOf(target.path.trim('/'), name).filter(String::isNotEmpty).joinToString("/")
    private fun read(credentials: SourceCredentials, path: String): ByteArray {
        WebDavHttp.execute(credentials, url(credentials, path), "GET", null, Headers.headersOf("Accept-Encoding", "identity")) {
            it.timeout().timeout(60, TimeUnit.SECONDS)
        }.use { response ->
            if (!response.isSuccessful) throw WebDavHttp.httpFailure(response.code)
            val body = response.body ?: throw IOException("备份文件为空")
            require(body.contentLength() <= BackupArchive.MAX_BYTES) { "备份文件过大" }
            val result = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(result.size() + count <= BackupArchive.MAX_BYTES) { "备份文件过大" }
                    result.write(buffer, 0, count)
                }
            }
            return result.toByteArray()
        }
    }
    private suspend fun upload() = mutex.withLock {
        if (!configured() || BackupRestoreGuard.restoring) return@withLock
        _status.value = _status.value.copy(busy = true, error = null)
        var uploadedPath: String? = null
        var credential: SourceCredentials? = null
        var verified = false
        var putAccepted = false
        try {
            val target = target() ?: error("请设置备份目录")
            credential = credentials(target.sourceId)
            val pass = password()
            val bytes = try { BackupArchive.encode(BackupSnapshot(app).capture(target), pass) } finally { pass.fill('\u0000') }
            val name = "mp-backup-${owner()}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.mpbackup"
            uploadedPath = path(target, name)
            WebDavHttp.execute(credential, url(credential, uploadedPath), "PUT", bytes.toRequestBody("application/octet-stream".toMediaType()), Headers.headersOf("If-None-Match", "*")).use {
                if (!it.isSuccessful) throw WebDavHttp.httpFailure(it.code)
            }
            putAccepted = true
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), MessageDigest.getInstance("SHA-256").digest(read(credential, uploadedPath)))) { "备份完整性校验失败" }
            verified = true
            val now = System.currentTimeMillis()
            check(prefs.edit().putLong("last_success", now).remove("last_error").commit())
            _status.value = BackupStatus(busy = true, lastSuccess = now)
            // Cleanup only this installation's verified archive family, never other files/devices.
            runCatching {
                BackupNames.obsolete(versions(target).map { it.name }, owner()).forEach { old ->
                    WebDavHttp.execute(credential, url(credential, path(target, old)), "DELETE", null).use {
                        if (!it.isSuccessful && it.code != 404) throw WebDavHttp.httpFailure(it.code)
                    }
                }
            }.onFailure { _status.value = _status.value.copy(error = "备份成功，旧版本清理失败") }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = when {
                error.message?.contains("HTTP 401") == true || error.message?.contains("HTTP 403") == true -> "备份失败，请检查WebDAV账号和目录写入权限"
                error.message?.contains("凭据") == true -> "备份失败，请重新保存连接凭据"
                else -> "备份失败，请检查WebDAV连接和备份目录"
            }
            prefs.edit().putString("last_error", message).apply()
            _status.value = _status.value.copy(error = message)
            if (!verified && putAccepted && uploadedPath != null && credential != null) runCatching {
                WebDavHttp.execute(credential, url(credential, uploadedPath), "DELETE", null).close()
            }
        } finally { _status.value = _status.value.copy(busy = false) }
    }
    internal suspend fun prepareRestore(entry: BrowserEntry, enteredPassword: String): PreparedBackup = mutex.withLock {
        val target = target() ?: error("请先选择备份目录")
        require(BackupNames.valid(entry.name) && entry.path == path(target, entry.name)) { "备份文件路径无效" }
        val pass = enteredPassword.toCharArray()
        val contents = try { withContext(Dispatchers.IO) { BackupArchive.decodeContents(read(credentials(target.sourceId), entry.path), pass) } }
            finally { pass.fill('\u0000') }
        withContext(Dispatchers.IO) { BackupSnapshot(app).prepare(contents.payload).copy(passwordProtected = contents.passwordProtected) }
    }
    internal suspend fun applyRestore(prepared: PreparedBackup, password: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            BackupSnapshot(app).apply(prepared) { saveConfiguration(prepared.target, if (prepared.passwordProtected) password else "") }
        }
    }
    companion object {
        @Volatile private var instance: BackupManager? = null
        fun shared(context: Context) = instance ?: synchronized(this) { instance ?: BackupManager(context).also { instance = it } }
    }
}
