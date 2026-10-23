package com.megatron.player.ai

import org.json.JSONObject
import org.json.JSONTokener

data class ParsedClue(val title: String, val artist: String = "", val album: String = "", val version: String = "")
data class MetadataCandidate(val id: String, val title: String, val artist: String = "", val album: String = "", val version: String = "", val durationMs: Long = 0)
data class CandidateChoice(val candidateId: String?, val uncertain: Boolean)

object QwenPolicy {
    const val BEIJING = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val SINGAPORE = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    val endpoints = listOf(BEIJING, SINGAPORE)
    fun validateConfig(config: QwenConfig) {
        require(config.baseUrl.trimEnd('/') in endpoints) { "请选择阿里云官方按量 API 地址" }
        require(config.model.matches(Regex("qwen[a-zA-Z0-9.-]{1,79}"))) { "请输入 Qwen 官方模型 ID，不含上下文后缀" }
        require(config.dailyLimit in 0..1000) { "每日请求上限为 0 至 1000，0 表示不限制" }
    }

    /** A clue is a filename/title only. Full paths, addresses and secrets fail closed. */
    fun validateClue(clue: String): String {
        val value = clue.trim()
        require(value.length in 1..240 && !value.contains(Regex("[\\p{Cntrl}\\p{Cf}]"))) { "歌曲线索过长或包含无效字符" }
        require(!value.contains(Regex("(?i)(https?://|smb://|webdav|x-plex-token|bearer\\s|api[_ -]?key|password|sk-[a-z0-9]|[\\\\/])"))) { "歌曲线索包含地址、路径或敏感内容" }
        val stem = value.replace(Regex("(?i)\\.(flac|mp3|wav|ape|m4a|m4r|ogg|aac|opus)$"), "")
        require(stem.any(Char::isLetter) && !stem.matches(Regex("(?i)((track|audio|cd|disc|曲目|音轨|音频|歌曲|未知|unknown)[ _.-]*[0-9 _.-]*)+"))
            && !stem.matches(Regex("(?i)[a-f0-9-]{16,}"))) { "该文件缺少可识别的歌名线索" }
        return value
    }

    fun validateCandidates(candidates: List<MetadataCandidate>) {
        require(candidates.size in 1..10 && candidates.map { it.id }.toSet().size == candidates.size) { "候选资料数量无效" }
        candidates.forEach {
            require(it.id.matches(Regex("[a-zA-Z0-9:_-]{1,100}"))) { "候选资料标识无效" }
            require(it.title.isNotBlank() && it.durationMs in 0..86_400_000) { "候选资料无效" }
            listOf(it.title, it.artist, it.album, it.version).forEach { text ->
                require(text.length <= 240 && !text.contains(Regex("[\\p{Cntrl}\\p{Cf}]"))) { "候选资料过长" }
                require(!text.contains(Regex("(?i)(https?://|x-plex-token|bearer\\s|api[_ -]?key|password|sk-[a-z0-9])"))) { "候选资料包含敏感内容" }
            }
        }
    }

    fun parseClueResult(text: String): ParsedClue {
        val json = strictObject(text, setOf("title", "artist", "album", "version"))
        return ParsedClue(field(json, "title"), field(json, "artist"), field(json, "album"), field(json, "version"))
    }

    fun parseChoiceResult(text: String, allowedIds: Set<String>): CandidateChoice {
        val json = strictObject(text, setOf("candidateId", "uncertain"))
        require(json.get("uncertain") is Boolean) { "模型返回格式无效" }
        val id = if (json.isNull("candidateId")) null else (json.get("candidateId") as? String
            ?: throw IllegalArgumentException("模型返回格式无效"))
        require(id == null || id in allowedIds) { "模型返回了未授权候选" }
        val uncertain = json.getBoolean("uncertain")
        require(!uncertain || id == null) { "模型返回了矛盾匹配" }
        return CandidateChoice(id, uncertain || id == null)
    }

    private fun strictObject(text: String, keys: Set<String>): JSONObject {
        require(text.length <= 4096) { "模型返回内容过长" }
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("模型返回格式无效")
        require(tokener.nextClean() == '\u0000') { "模型返回格式无效" }
        require(json.keys().asSequence().toSet() == keys) { "模型返回格式无效" }
        return json
    }
    private fun field(json: JSONObject, name: String): String {
        val value = json.get(name) as? String ?: throw IllegalArgumentException("模型返回格式无效")
        require(value.length <= 240 && !value.contains(Regex("[\\p{Cntrl}\\p{Cf}]"))) { "模型返回字段无效" }
        require(!value.contains(Regex("(?i)(https?://|[\\\\/]|x-plex-token|bearer\\s|api[_ -]?key|password|sk-[a-z0-9])"))) { "模型返回了禁止内容" }
        return value.trim()
    }
}
