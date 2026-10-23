package com.megatron.player.source

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.IOException
import java.io.InputStream
import java.io.EOFException
import java.util.regex.Pattern
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

class AppDataSourceFactory(
    private val context: Context,
    private val credentials: (String) -> SourceCredentials?,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(context, credentials)
}

private class RoutingDataSource(
    context: Context,
    private val credentials: (String) -> SourceCredentials?,
) : BaseDataSource(/* isNetwork = */ true) {
    private val defaultFactory = DefaultDataSource.Factory(context)
    private var delegate: DataSource? = null
    private var remote: RemoteStream? = null
    private var currentSpec: DataSpec? = null
    @Volatile private var activeCall: okhttp3.Call? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        currentSpec = dataSpec
        val parsed = SourceUris.parse(dataSpec.uri)
        if (parsed == null) {
            val local = defaultFactory.createDataSource()
            delegate = local
            transferInitializing(dataSpec)
            return try {
                val length = local.open(dataSpec)
                transferStarted(dataSpec)
                opened = true
                remaining = length
                length
            } catch (error: IOException) {
                delegate = null
                throw error
            }
        }
        val (sourceId, path) = parsed
        val supplied = credentials(sourceId) ?: throw IOException("找不到 WebDAV 片源配置")
        if (supplied.config.kind != SourceKind.WEBDAV || supplied.config.id != sourceId) throw IOException("WebDAV 片源配置无效")
        val base = try { WebDavHttp.validateBase(supplied.config.address) } catch (error: IllegalArgumentException) { throw IOException("WebDAV 地址无效") }
        val relative = try { WebDavHttp.cleanRelativePath(path) } catch (error: IllegalArgumentException) { throw IOException("WebDAV 播放路径无效") }
        val target = WebDavHttp.urlForPath(base, relative)
        transferInitializing(dataSpec)
        val rangeStart = dataSpec.position
        if (rangeStart < 0L) throw IOException("WebDAV 读取位置无效")
        if (dataSpec.length == 0L) {
            remaining = 0L
            transferStarted(dataSpec)
            opened = true
            return 0L
        }
        val rangeEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) null else {
            if (dataSpec.length < 0L || dataSpec.length > Long.MAX_VALUE - rangeStart) throw IOException("WebDAV 读取范围过大")
            rangeStart + dataSpec.length - 1
        }
        val range = if (rangeStart > 0 || rangeEnd != null) "bytes=$rangeStart-${rangeEnd ?: ""}" else null
        val headers = if (range == null) Headers.headersOf("Accept-Encoding", "identity") else Headers.headersOf("Range", range, "Accept-Encoding", "identity")
        val response = try { WebDavHttp.execute(supplied, target, "GET", null, headers) { activeCall = it } } catch (error: IOException) { throw error }
        try {
            if (!response.isSuccessful) throw WebDavHttp.httpFailure(response.code)
            val expectedPartial = rangeStart > 0
            if (expectedPartial && response.code != 206) throw IOException("WebDAV 服务不支持从指定位置读取")
            val responseBody = response.body ?: throw IOException("WebDAV 返回空内容")
            if (response.code == 206) validateContentRange(response.header("Content-Range"), rangeStart)
            val sourceLength = responseBody.contentLength()
            val logicalLength = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                sourceLength >= 0 -> sourceLength
                else -> C.LENGTH_UNSET.toLong()
            }
            remote = RemoteStream(response, responseBody, responseBody.byteStream(), logicalLength)
            remaining = logicalLength
            transferStarted(dataSpec)
            opened = true
            return logicalLength
        } catch (error: IOException) {
            response.close()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val activeRemote = remote
        if (activeRemote != null) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val requested = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
            val count = activeRemote.stream.read(buffer, offset, requested)
            if (count == -1) {
                if (remaining != C.LENGTH_UNSET.toLong()) throw EOFException("WebDAV 内容提前结束")
                remaining = 0L
                return C.RESULT_END_OF_INPUT
            }
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
            bytesTransferred(count)
            return count
        }
        val local = delegate ?: throw IOException("数据源未打开")
        val count = local.read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = delegate?.uri ?: currentSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        remote?.response?.headers?.toMultimap() ?: delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        activeCall?.cancel()
        activeCall = null
        var failure: IOException? = null
        try { remote?.close() } catch (error: IOException) { failure = error }
        remote = null
        try { delegate?.close() } catch (error: IOException) { if (failure == null) failure = error }
        delegate = null
        if (opened) transferEnded()
        opened = false
        remaining = C.LENGTH_UNSET.toLong()
        currentSpec = null
        failure?.let { throw it }
    }

    private fun validateContentRange(value: String?, expectedStart: Long) {
        val match = value?.let { CONTENT_RANGE.matcher(it).matches() } == true
        val start = value?.let { CONTENT_RANGE.matcher(it).takeIf { matcher -> matcher.matches() }?.group(1)?.toLongOrNull() }
        if (!match || start != expectedStart) throw IOException("WebDAV 返回的范围无效")
    }

    private data class RemoteStream(
        val response: Response,
        val body: ResponseBody,
        val stream: InputStream,
        val length: Long,
    ) {
        fun close() { response.close() }
    }

    companion object { private val CONTENT_RANGE = Pattern.compile("bytes (\\d+)-\\d+/(?:\\d+|\\*)", Pattern.CASE_INSENSITIVE) }
}
