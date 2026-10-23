package com.megatron.player

data class RecentVideo(
    val uri: String,
    val title: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val subtitleUri: String? = null,
    val subtitleName: String? = null,
    val updatedAt: Long = 0L,
)

fun historyIdentity(uri: String): String = if (uri.startsWith("mpplex://")) uri.substringBefore('?') else uri

fun updatedHistory(existing: List<RecentVideo>, latest: RecentVideo, limit: Int = 500): List<RecentVideo> =
    (listOf(latest) + existing.filterNot { historyIdentity(it.uri) == historyIdentity(latest.uri) }).take(limit)
