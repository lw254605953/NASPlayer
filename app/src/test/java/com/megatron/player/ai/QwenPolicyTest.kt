package com.megatron.player.ai

import org.junit.Assert.*
import org.junit.Test

class QwenPolicyTest {
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun onlyOfficialPayAsYouGoEndpointsAndQwenModelIds() {
        QwenPolicy.validateConfig(QwenConfig())
        QwenPolicy.validateConfig(QwenConfig(baseUrl = QwenPolicy.SINGAPORE, model = "qwen3.8-max"))
        listOf("https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic", "https://dashscope.aliyuncs.com/apps/anthropic",
            "http://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com.evil.test/compatible-mode/v1",
            QwenPolicy.BEIJING + "?api_key=example", "https://user@dashscope.aliyuncs.com/compatible-mode/v1").forEach { endpoint ->
            rejected { QwenPolicy.validateConfig(QwenConfig(baseUrl = endpoint)) }
        }
        listOf("qwen3.8-max[1m]", "deepseek-v3.2", "glm-5", "qwen+bad").forEach { model -> rejected { QwenPolicy.validateConfig(QwenConfig(model = model)) } }
    }

    @Test fun dailyLimitAcceptsUnlimitedAndRejectsInvalidBounds() {
        listOf(0, 1, 100, 1000).forEach { QwenPolicy.validateConfig(QwenConfig(dailyLimit = it)) }
        listOf(-1, 1001, Int.MAX_VALUE).forEach { rejected { QwenPolicy.validateConfig(QwenConfig(dailyLimit = it)) } }
    }

    @Test fun privatePathsCredentialsAndNumberOnlyCluesNeverPass() {
        assertEquals("周杰伦 - 晴天.flac", QwenPolicy.validateClue("周杰伦 - 晴天.flac"))
        listOf("001.flac", "track 01.mp3", "CD01 Track01.flac", "歌曲001.mp3", "cf8855dd33ff6611.flac", "123456", "/private/NAS/歌曲.flac", "D:\\Music\\晴天.flac",
            "https://nas.invalid/晴天", "晴天 X-Plex-Token=example", "artist sk-examplekey", "晴天\u0000", "晴天\u202E").forEach { clue ->
            rejected { QwenPolicy.validateClue(clue) }
        }
    }

    @Test fun outputIsStrictAndUnknownFieldsLinksAndForeignCandidatesAreRejected() {
        val parsed = QwenPolicy.parseClueResult("{\"title\":\"晴天\",\"artist\":\"周杰伦\",\"album\":\"\",\"version\":\"\"}")
        assertEquals("周杰伦", parsed.artist)
        assertEquals("id-1", QwenPolicy.parseChoiceResult("{\"candidateId\":\"id-1\",\"uncertain\":false}", setOf("id-1")).candidateId)
        assertTrue(QwenPolicy.parseChoiceResult("{\"candidateId\":null,\"uncertain\":true}", setOf("id-1")).uncertain)
        listOf("{\"candidateId\":\"other\",\"uncertain\":false}", "{\"candidateId\":\"id-1\",\"uncertain\":true}",
            "{\"candidateId\":null,\"uncertain\":true,\"tool\":\"delete\"}", "{\"candidateId\":null,\"uncertain\":true} extra").forEach {
            rejected { QwenPolicy.parseChoiceResult(it, setOf("id-1")) }
        }
        rejected { QwenPolicy.parseClueResult("{\"title\":\"https://evil.invalid\",\"artist\":\"\",\"album\":\"\",\"version\":\"\"}") }
    }

    @Test fun candidatesAreBoundedAndCannotContainSecrets() {
        QwenPolicy.validateCandidates(listOf(MetadataCandidate("mb:1", "晴天", "周杰伦", durationMs = 240000)))
        rejected { QwenPolicy.validateCandidates(List(11) { MetadataCandidate("$it", "晴天") }) }
        rejected { QwenPolicy.validateCandidates(listOf(MetadataCandidate("same", "晴天"), MetadataCandidate("same", "其他"))) }
        rejected { QwenPolicy.validateCandidates(listOf(MetadataCandidate("ok", "晴天", album = "api_key=secret"))) }
        rejected { QwenPolicy.validateCandidates(listOf(MetadataCandidate("https://evil.invalid", "晴天"))) }
    }
}
