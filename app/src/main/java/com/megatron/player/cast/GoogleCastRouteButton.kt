package com.megatron.player.cast

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.megatron.player.R

/** Keep MediaRouter's AppCompat theme local to the optional Cast widget. */
fun themedGoogleCastButton(context: Context): MediaRouteButton =
    MediaRouteButton(ContextThemeWrapper(context, R.style.Theme_MegatronCast))

/** Both view inflation and SDK setup can throw; neither may take down the DLNA panel. */
fun safeGoogleCastButton(context: Context, onFailure: (Throwable) -> Unit): View = FrameLayout(context).apply {
    runCatching {
        val button = themedGoogleCastButton(context).apply { contentDescription = "选择 Google Cast 电视" }
        CastButtonFactory.setUpMediaRouteButton(button.context, button)
        addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }.onFailure { error -> post { onFailure(error) } }
}
