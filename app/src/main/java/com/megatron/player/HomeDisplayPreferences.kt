package com.megatron.player

import android.content.Context

/** Display-only preferences: hiding a section never changes playback history. */
class HomeDisplayPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("home_display_v1", Context.MODE_PRIVATE)

    fun showContinueWatching(): Boolean = preferences.getBoolean("show_continue_watching", true)

    fun setShowContinueWatching(show: Boolean) {
        preferences.edit().putBoolean("show_continue_watching", show).apply()
    }
}
