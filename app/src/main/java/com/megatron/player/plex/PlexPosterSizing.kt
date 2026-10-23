package com.megatron.player.plex

internal object PlexPosterSizing {
    fun edge(width: Int, height: Int): Int {
        val desired = maxOf(width.takeIf { it in 1 until Int.MAX_VALUE } ?: 0,
            height.takeIf { it in 1 until Int.MAX_VALUE } ?: 0).takeIf { it > 0 } ?: 768
        return intArrayOf(128, 256, 384, 512, 768, 1024, 1536).firstOrNull { it >= desired } ?: 1536
    }
    fun sample(width: Int, height: Int, edge: Int): Int {
        require(width > 0 && height > 0 && edge > 0)
        var sample = 1
        while (maxOf(width, height).toLong() / (sample.toLong() * 2) >= edge) sample *= 2
        return sample
    }
}
