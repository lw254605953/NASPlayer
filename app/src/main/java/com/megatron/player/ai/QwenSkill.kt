package com.megatron.player.ai

import android.content.Context

object QwenSkill {
    const val PATH = "skills/music-metadata/SKILL.md"
    fun read(context: Context): String = context.applicationContext.assets.open(PATH).bufferedReader().use { it.readText() }
}
