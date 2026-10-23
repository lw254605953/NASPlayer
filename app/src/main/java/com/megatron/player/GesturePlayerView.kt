package com.megatron.player

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/** Video surface only. All visible controls live in Compose, never in PlayerView. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class GesturePlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : PlayerView(context, attrs) {
    var touchLocked = false
        set(value) {
            if (field == value) return
            field = value
            if (value) clearGesture()
        }
    var onTap: () -> Unit = {}
    var onGestureStarted: () -> Unit = {}
    var onGestureFinished: () -> Unit = {}
    var onSeekPreview: (targetMs: Long?, startMs: Long) -> Unit = { _, _ -> }
    var onSeekCommit: (Long) -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var startMs = 0L
    private var candidate = false
    private var tapCandidate = false
    private var dragging = false
    private var cancelled = false

    init {
        useController = false
        isClickable = true
        contentDescription = "显示或隐藏播放控件"
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (touchLocked) true else super.dispatchTouchEvent(event)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) beginTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) beginDrag(event)
        return dragging || cancelled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchLocked) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(event)
            MotionEvent.ACTION_MOVE -> {
                if (cancelled) return true
                if (event.pointerCount != 1) { cancelDrag(); return true }
                beginDrag(event)
                if (dragging) onSeekPreview(gestureSeekPositionMs(startMs, player?.duration ?: 0L, event.x - startX, width), startMs)
            }
            MotionEvent.ACTION_UP -> {
                if (!cancelled && dragging) {
                    gestureSeekPositionMs(startMs, player?.duration ?: 0L, event.x - startX, width)?.let(onSeekCommit)
                    clearGesture()
                    onGestureFinished()
                } else {
                    val tap = !cancelled && tapCandidate && abs(event.x - startX) < slop && abs(event.y - startY) < slop
                    clearGesture()
                    if (tap) performClick()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelDrag()
            MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!touchLocked) onTap()
        return true
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
            return if (touchLocked) false else performClick()
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun beginTouch(event: MotionEvent) {
        clearGesture()
        startX = event.x; startY = event.y
        startMs = player?.currentPosition ?: 0L
        tapCandidate = true
        candidate = player?.isCurrentMediaItemSeekable == true && (player?.duration ?: 0L) > 0 &&
            event.x in (24 * density)..(width - 24 * density) && event.y in (48 * density)..(height - 96 * density)
    }

    private fun beginDrag(event: MotionEvent) {
        if (!candidate || cancelled || event.pointerCount != 1) return
        val dx = event.x - startX; val dy = event.y - startY
        if (abs(dy) > slop && abs(dy) > abs(dx)) { candidate = false; tapCandidate = false }
        else if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
            dragging = true
            tapCandidate = false
            parent?.requestDisallowInterceptTouchEvent(true)
            onGestureStarted()
        }
    }

    private fun cancelDrag() {
        val wasDragging = dragging
        clearGesture()
        cancelled = true
        if (wasDragging) onGestureFinished()
    }

    private fun clearGesture() {
        candidate = false; tapCandidate = false; dragging = false; cancelled = false
        onSeekPreview(null, startMs)
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
