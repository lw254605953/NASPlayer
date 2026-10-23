package com.megatron.player.music

import com.megatron.player.source.*
import okhttp3.Headers
import java.io.IOException

internal fun musicMovePath(sourcePath: String, directory: String): String {
    val source = WebDavHttp.cleanRelativePath(sourcePath)
    require(source.isNotBlank() && isMusicFile(source.substringAfterLast('/'))) { "请选择音乐文件" }
    val target = WebDavHttp.cleanRelativePath(directory)
    require(target != source.substringBeforeLast('/', "")) { "请选择其他目录" }
    return listOf(target, source.substringAfterLast('/')).filter(String::isNotEmpty).joinToString("/")
}

/** A single exact file, never a directory. Mutations are neither redirected nor retried. */
internal class MusicFileOperations(private val client: WebDavClient = WebDavClient()) {
    fun execute(credentials: SourceCredentials, track: MusicTrack, destinationDirectory: String?): MusicTrack? {
        val path = WebDavHttp.cleanRelativePath(track.path)
        require(path.isNotEmpty() && isMusicFile(path.substringAfterLast('/'))) { "请选择音乐文件" }
        val parent = path.substringBeforeLast('/', "")
        val source = client.list(credentials, parent).firstOrNull { it.path == path }
            ?: throw IOException("文件已不存在，请刷新目录")
        if (source.isDirectory) throw IOException("不能对目录执行歌曲操作")
        if (track.sizeBytes > 0 && source.size != track.sizeBytes || track.modifiedAt > 0 && source.modifiedAt > 0 && track.modifiedAt != source.modifiedAt)
            throw IOException("文件已变更，请刷新目录后重试")
        val destination = destinationDirectory?.let { musicMovePath(path, it) }
        if (destination != null && client.list(credentials, destinationDirectory).any { it.path == destination })
            throw IOException("目标目录已有同名文件")
        val base = WebDavHttp.validateBase(credentials.config.address)
        val headers = Headers.Builder().apply {
            source.etag?.takeIf { it.startsWith('"') && it.endsWith('"') }?.let { add("If-Match", it) }
            if (destination != null) {
                add("Destination", WebDavHttp.urlForPath(base, destination).toString())
                add("Overwrite", "F")
            }
        }.build()
        try {
            WebDavHttp.execute(credentials, WebDavHttp.urlForPath(base, path), if (destination == null) "DELETE" else "MOVE", null, headers).use { response ->
                when (response.code) {
                    200, 201, 204 -> Unit
                    401, 403 -> throw IOException("没有文件写入权限，请检查 WebDAV 账号权限")
                    404 -> throw IOException("文件或目标目录已不存在，请刷新后重试")
                    405, 501 -> throw IOException("此 WebDAV 服务不支持该文件操作")
                    409, 412 -> throw IOException("目标已存在或文件已变更，请刷新后重试")
                    423 -> throw IOException("文件已被锁定，请稍后重试")
                    else -> throw IOException("文件操作未确认（HTTP ${response.code}），请刷新目录核实")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("连接超时，操作结果未确认，请刷新目录核实", e)
        } catch (e: IOException) {
            if (e.message?.startsWith("文件") == true || e.message?.startsWith("目标") == true || e.message?.startsWith("没有") == true || e.message?.startsWith("此 ") == true) throw e
            throw IOException("连接中断，操作结果未确认，请刷新目录核实", e)
        }
        return destination?.let { track.copy(id = musicId(track.sourceId, it), path = it) }
    }
}
