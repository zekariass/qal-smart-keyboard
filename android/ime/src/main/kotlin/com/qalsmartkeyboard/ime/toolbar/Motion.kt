package com.qalsmartkeyboard.ime.toolbar

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The toolbar's motion (ADR 0035): short reveals for the AI actions row and the tool panels, skipped entirely when the
 * user turned animations off ("Remove animations" / animator duration scale 0). Nothing here changes a view's size, so
 * the keyboard's height never moves while something animates.
 */
object Motion {
    const val DURATION_MS = 180L

    /** Animations are on (the system's animator duration scale is not 0). */
    fun enabled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }
    } catch (e: RuntimeException) {
        true
    }

    /** Fades [view] in while it slides [fromXPx], [fromYPx] into place; with animations off it simply appears. */
    fun reveal(view: View, fromXPx: Float = 0f, fromYPx: Float = 0f) {
        view.animate().cancel()
        if (!enabled(view.context)) {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationX = fromXPx
        view.translationY = fromYPx
        view.animate().alpha(1f).translationX(0f).translationY(0f)
            .setDuration(DURATION_MS).setInterpolator(DecelerateInterpolator()).start()
    }
}
