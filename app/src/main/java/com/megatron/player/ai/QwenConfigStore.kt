package com.megatron.player.ai

import android.content.Context
import com.megatron.player.source.DeviceSecrets
import java.time.LocalDate

data class QwenConfig(
    val baseUrl: String = QwenPolicy.BEIJING,
    val model: String = "qwen-plus",
    val enabled: Boolean = false,
    val dailyLimit: Int = 100,
)

/** Deliberately not a data class: a diagnostic toString must never reveal the key. */
class QwenCredentials internal constructor(val config: QwenConfig, internal val key: String) {
    override fun toString() = "QwenCredentials(redacted)"
}

class QwenConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nasplayer_ai_v1", Context.MODE_PRIVATE)
    private val secrets = DeviceSecrets("NASPlayer.QwenSecrets.v1")

    fun read() = QwenConfig(
        baseUrl = prefs.getString("base_url", QwenPolicy.BEIJING) ?: QwenPolicy.BEIJING,
        model = prefs.getString("model", "qwen-plus") ?: "qwen-plus",
        enabled = prefs.getBoolean("enabled", false),
        dailyLimit = prefs.getInt("daily_limit", 100).coerceIn(0, 1000),
    )

    fun save(config: QwenConfig, key: String? = null) = synchronized(QwenConfigStore::class.java) {
        QwenPolicy.validateConfig(config)
        val encrypted = key?.let {
            require(it.isNotBlank() && it.length <= 512 && it.none(Char::isWhitespace)) { "请输入有效 API Key" }
            secrets.encrypt(it)
        }
        val edit = prefs.edit().putString("base_url", config.baseUrl.trimEnd('/'))
            .putString("model", config.model).putBoolean("enabled", config.enabled)
            .putInt("daily_limit", config.dailyLimit)
        if (encrypted != null) edit.putString("api_key", encrypted)
        check(edit.commit()) { "模型设置保存失败" }
    }

    fun hasKey(): Boolean = runCatching { key().isNotBlank() }.getOrDefault(false)
    fun clearKey() { check(prefs.edit().remove("api_key").putBoolean("enabled", false).commit()) { "模型设置保存失败" } }
    private fun key(): String = prefs.getString("api_key", null)?.let(secrets::decrypt).orEmpty()
    fun credentials(): QwenCredentials {
        val config = read()
        QwenPolicy.validateConfig(config)
        check(config.enabled) { "请先启用音乐资料整理" }
        val value = runCatching { key() }.getOrElse { throw IllegalStateException("API Key 无法读取，请重新填写") }
        check(value.isNotBlank()) { "请先填写 API Key" }
        return QwenCredentials(config, value)
    }

    /** Counts attempts, including failures; no automatic retry can spend beyond the local cap. */
    internal fun reserveRequest(limit: Int) = synchronized(QwenConfigStore::class.java) {
        val today = LocalDate.now().toString()
        val previous = if (prefs.getString("usage_day", "") == today) prefs.getInt("usage_count", 0) else 0
        check(limit == 0 || previous < limit) { "已达到今日模型请求上限" }
        check(prefs.edit().putString("usage_day", today).putInt("usage_count", (previous.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).commit()) { "无法保存请求计数" }
    }

    fun requestsToday(): Int = synchronized(QwenConfigStore::class.java) {
        if (prefs.getString("usage_day", "") == LocalDate.now().toString()) prefs.getInt("usage_count", 0) else 0
    }
}
