package com.megatron.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

internal const val MAX_SLEEP_MS = 24 * 60 * 60_000L
internal fun sleepDurationMs(hours: Int, minutes: Int): Long? =
    if (hours in 0..23 && minutes in 0..59) (hours * 60L + minutes).times(60_000).takeIf { it > 0 } else null
internal fun sleepRemainingMs(deadline: Long, now: Long): Long = if (deadline > 0) (deadline - now).coerceAtLeast(0) else 0

data class SleepTimerState(val remainingMs: Long = 0, val active: Boolean = false)

/** One app-wide timer. Existing video/music/cast owners register their pause operation. */
class SleepTimer private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sleep_timer_v1", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state
    var pauseGeneration: Long = 0
        private set
    private var deadline = 0L
    private var foreground: WeakReference<Activity>? = null
    private val pauses = linkedMapOf<Any, () -> Unit>()
    private val tick = Runnable { update() }
    init {
        val remaining = (prefs.getLong("wall_deadline", 0) - System.currentTimeMillis()).coerceIn(0, MAX_SLEEP_MS)
        if (remaining > 0) { deadline = SystemClock.elapsedRealtime() + remaining; update() }
        else prefs.edit().remove("wall_deadline").apply()
    }
    fun start(durationMs: Long) {
        checkMain(); require(durationMs in 1..MAX_SLEEP_MS)
        deadline = SystemClock.elapsedRealtime() + durationMs
        prefs.edit().putLong("wall_deadline", System.currentTimeMillis() + durationMs).apply()
        update()
    }
    fun cancel() { checkMain(); deadline = 0; handler.removeCallbacks(tick); prefs.edit().remove("wall_deadline").apply(); _state.value = SleepTimerState() }
    fun register(owner: Any, pause: () -> Unit) { checkMain(); pauses[owner] = pause }
    fun unregister(owner: Any) { checkMain(); pauses.remove(owner) }
    fun foreground(activity: Activity?) { checkMain(); foreground = activity?.let(::WeakReference); if (deadline > 0) update() }
    private fun update() {
        handler.removeCallbacks(tick)
        if (deadline == 0L) return
        val left = sleepRemainingMs(deadline, SystemClock.elapsedRealtime())
        if (left == 0L) {
            pauseGeneration++
            cancel()
            pauses.values.toList().forEach { runCatching(it) }
            foreground?.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { activity ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } else {
            _state.value = SleepTimerState(left, true)
            handler.postDelayed(tick, minOf(left, 1000))
        }
    }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    companion object {
        @Volatile private var instance: SleepTimer? = null
        fun shared(context: Context): SleepTimer = instance ?: synchronized(this) { instance ?: SleepTimer(context).also { instance = it } }
    }
}
