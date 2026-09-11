package dev.woms.mumdroid.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * The full-window black scrim used by the earpiece proximity lock, plus the
 * brightness override that stands in for the system screen-off when the
 * proximity wake lock is unavailable.
 *
 * Split out of [EarpieceProximityLock] so the lock keeps the sensor / screen-off
 * state machine while all View and window handling lives here.
 */
internal class EarpieceScreenScrim(private val activity: Activity) {

    private val overlay = View(activity).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = OVERLAY_ELEVATION
    }

    private var animator: ValueAnimator? = null
    private var brightnessOverridden = false

    /** Whether a fade is currently running. */
    val isAnimating: Boolean get() = animator?.isRunning == true

    /** Drops the animation without invoking its completion callback. */
    fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    /**
     * Shows a fully opaque, tap-blocking scrim immediately. Used before the
     * system screen-off is taken and while waiting for the panel to come back.
     */
    fun showOpaque() {
        ensureAttached()
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.isClickable = true
    }

    /** Makes the scrim invisible and detaches it from the window. */
    fun hide() {
        overlay.alpha = 0f
        overlay.isClickable = false
        overlay.visibility = View.GONE
        detach()
    }

    /** Runs [block] after the next frame of the overlay. */
    fun postOnAnimation(block: () -> Unit) {
        overlay.postOnAnimation(block)
    }

    /**
     * Fades the scrim to fully dark or fully clear, invoking [onFadeEnd] once it
     * reaches the target (or immediately when it is already there).
     */
    fun fade(dark: Boolean, onFadeEnd: () -> Unit) {
        cancelAnimation()
        val start = overlay.alpha
        val end = if (dark) 1f else 0f
        overlay.visibility = View.VISIBLE
        overlay.isClickable = true
        if (abs(start - end) < EPSILON) {
            overlay.alpha = end
            onFadeEnd()
            return
        }
        val fullMs = if (dark) FADE_TO_BLACK_MS else FADE_TO_LIGHT_MS
        val started = ValueAnimator.ofFloat(start, end).apply {
            duration = (fullMs * abs(end - start)).toLong().coerceAtLeast(1L)
            interpolator = if (dark) {
                PathInterpolator(0.4f, 0f, 0.2f, 1f)
            } else {
                // Stay dark longer, then ease into full brightness.
                PathInterpolator(0.72f, 0f, 0.28f, 1f)
            }
            addUpdateListener { overlay.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    onFadeEnd()
                }
            })
        }
        animator = started
        started.start()
    }

    /** Overrides the window brightness to off. */
    fun dimScreen() {
        val window = activity.window ?: return
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        window.attributes = params
        brightnessOverridden = true
    }

    /** Restores the window brightness to the system value. */
    fun restoreBrightness() {
        if (!brightnessOverridden) return
        val window = activity.window ?: return
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        brightnessOverridden = false
    }

    private fun ensureAttached() {
        if (overlay.parent != null) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun detach() {
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    private companion object {
        const val FADE_TO_BLACK_MS = 500f
        const val FADE_TO_LIGHT_MS = 1100f
        const val OVERLAY_ELEVATION = 100_000f
        const val EPSILON = 0.01f
    }
}
