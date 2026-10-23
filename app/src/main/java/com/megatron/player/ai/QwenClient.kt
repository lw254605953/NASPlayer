package com.megatron.player.ai

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface MetadataModel {
    suspend fun parseClue(clue: String): ParsedClue
    suspend fun choose(clue: String, candidates: List<MetadataCandidate>): CandidateChoice
}

/** Application-owned, bounded requests. No tools, search permission or Agent SDK. */
class QwenClient(context: Context, private val store: QwenConfigStore = QwenConfigStore(context)) : MetadataModel {
    private val app = context.applicationContext
    private val skill by lazy { QwenSkill.read(app) }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    override suspend fun parseClue(clue: String): ParsedClue {
        val safe = QwenPolicy.validateClue(clue)
        val output = complete(JSONObject().put("task", "parse").put("clue", safe))
        return validated { QwenPolicy.parseClueResult(output) }
    }

    override suspend fun choose(clue: String, candidates: List<MetadataCandidate>): CandidateChoice {
        val safe = QwenPolicy.validateClue(clue)
        QwenPolicy.validateCandidates(candidates)
        val list = JSONArray(candidates.map { item -> JSONObject().put("id", item.id).put("title", item.title)
            .put("artist", item.artist).put("album", item.album).put("version", item.version)
            .put("durationMs", item.durationMs) })
        val output = complete(JSONObject().put("task", "choose").put("clue", safe).put("candidates", list))
        return validated { QwenPolicy.parseChoiceResult(output, candidates.map { it.id }.toSet()) }
    }

    suspend fun testConnection() {
        val output = complete(JSONObject().put("task", "ping"))
        validated {
            val json = JSONObject(output)
            require(json.keys().asSequence().toSet() == setOf("ok") && json.get("ok") == true)
        }
    }

    private inline fun <T> validated(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IOException("模型返回格式无效，请重试或更换 Qwen 模型") }

    private suspend fun complete(input: JSONObject): String {
        val credentials = store.credentials()
        val payload = JSONObject().put("model", credentials.config.model).put("stream", false)
            .put("enable_thinking", false).put("max_tokens", 512)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", skill))
                .put(JSONObject().put("role", "user").put("content", input.toString())))
        val request = Request.Builder().url(credentials.config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + credentials.key)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try { store.reserveRequest(credentials.config.dailyLimit) }
            catch (error: Exception) { continuation.resumeWithException(error); return@suspendCancellableCoroutine }
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("模型连接失败，请检查网络与 API 配置"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException(when (it.code) {
                                401, 403 -> "API Key 无效或没有当前模型的调用权限"
                                429 -> "模型调用受限，请稍后再试"
                                in 300..399 -> "模型地址发生重定向，已停止发送"
                                else -> "模型请求失败（HTTP ${it.code}）"
                            })
                            val body = it.body ?: throw IOException("模型未返回内容")
                            val source = body.source()
                            source.request(64 * 1024L + 1)
                            if (source.buffer.size > 64 * 1024) throw IOException("模型返回内容过长")
                            val bytes = source.readByteArray()
                            try {
                                val choice = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0)
                                if (choice.optString("finish_reason") != "stop") throw IllegalArgumentException()
                                val content = choice.getJSONObject("message").get("content") as? String
                                    ?: throw IllegalArgumentException()
                                if (content.length > 4096) throw IllegalArgumentException()
                                content
                            } catch (_: Exception) { throw IOException("模型返回格式无效") }
                        }
                    }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }) { error ->
                        continuation.resumeWithException(if (error is IOException && error.message in SAFE_ERRORS) error
                            else IOException("模型响应读取失败"))
                    }
                }
            })
        }
    }

    private companion object {
        val SAFE_ERRORS = setOf("API Key 无效或没有当前模型的调用权限", "模型调用受限，请稍后再试", "模型地址发生重定向，已停止发送",
            "模型未返回内容", "模型返回内容过长", "模型返回格式无效") + (100..599).map { "模型请求失败（HTTP $it）" }
    }
}
