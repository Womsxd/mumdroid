package dev.woms.mumdroid.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.woms.mumdroid.core.audio.VoiceRouteSelection
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import kotlin.math.abs

/**
 * While connected on the earpiece, blanks the screen when the phone is held
 * to the ear so the cheek cannot tap mute / output / PTT. [fade] chooses
 * a slow dim versus an instant phone-call cut.
 */
@Composable
fun EarpieceProximityEffect(
    connected: Boolean,
    outputTarget: VoiceOutputTarget?,
    fade: Boolean = true,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return
    val lock = remember(activity) { EarpieceProximityLock(activity) }
    val audioManager = remember(context) {
        context.getSystemService(AudioManager::class.java)
    }
    var outputTypes by remember {
        mutableStateOf(outputDeviceTypes(audioManager))
    }

    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                outputTypes = outputDeviceTypes(audioManager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                outputTypes = outputDeviceTypes(audioManager)
            }
        }
        outputTypes = outputDeviceTypes(audioManager)
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    val shouldBlank = VoiceRouteSelection.routesToEarpiece(
        connected = connected,
        target = outputTarget,
        availableOutputTypes = outputTypes,
    )

    LifecycleResumeEffect(lock) {
        lock.onResume()
        onPauseOrDispose { lock.onPause() }
    }
    SideEffect {
        lock.setFade(fade)
        lock.setShouldBlank(shouldBlank)
    }
    DisposableEffect(lock) {
        onDispose { lock.onPause() }
    }
}

/**
 * Fades a full-window black scrim from the proximity sensor, then (once
 * fully dark) takes [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK] so the
 * panel can power down like a phone call.
 *
 * Fade-up waits until that system screen-off is actually released: the
 * scrim stays fully black while the panel is off, and only then animates
 * out — otherwise the animator finishes in the dark and the user sees an
 * instant bright frame.
 */
internal class EarpieceProximityLock(private val activity: Activity) : SensorEventListener {

    private val powerManager = activity.getSystemService(PowerManager::class.java)
    private val sensorManager = activity.getSystemService(SensorManager::class.java)
    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val overlay = View(activity).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = OVERLAY_ELEVATION
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var animator: ValueAnimator? = null
    private var resumed = false
    private var shouldBlank = false
    private var sensorRegistered = false
    private var near = false
    private var brightnessOverridden = false
    private var pendingFadeUp = false
    private var screenOnWaitRegistered = false
    private var fade = true

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (pendingFadeUp && isDisplayOn()) startFadeUpAfterScreenOn()
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && pendingFadeUp) {
                startFadeUpAfterScreenOn()
            }
        }
    }

    private val fadeUpTimeout = Runnable {
        if (pendingFadeUp) startFadeUpAfterScreenOn()
    }

    fun onResume() {
        resumed = true
        applyMonitoring()
    }

    fun onPause() {
        resumed = false
        near = false
        applyMonitoring()
        resetInstant()
    }

    fun setFade(enabled: Boolean) {
        if (fade == enabled) return
        fade = enabled
        if (!resumed || !shouldBlank) return
        applyCover(dark = near)
    }

    fun setShouldBlank(blank: Boolean) {
        if (shouldBlank == blank) return
        shouldBlank = blank
        applyMonitoring()
        if (!blank) {
            near = false
            if (resumed) fadeTo(dark = false) else resetInstant()
        }
    }

    private fun applyMonitoring() {
        val want = resumed && shouldBlank
        if (want) registerSensor() else unregisterSensor()
    }

    private fun fadeTo(dark: Boolean) {
        applyCover(dark)
    }

    private fun applyCover(dark: Boolean) {
        if (dark) {
            cancelPendingFadeUp()
            if (fade) animateOverlay(toDark = true) else applyInstantDark()
        } else if (fade) {
            fadeToLight()
        } else {
            applyInstantLight()
        }
    }

    /** Snap to system screen-off with no scrim animation. */
    private fun applyInstantDark() {
        animator?.cancel()
        animator = null
        hideOverlay()
        if (!acquireWakeLock()) {
            ensureOverlayAttached()
            overlay.alpha = 1f
            overlay.visibility = View.VISIBLE
            overlay.isClickable = true
            setBrightnessOff()
        }
    }

    /** Snap the panel back on with no scrim animation. */
    private fun applyInstantLight() {
        animator?.cancel()
        animator = null
        cancelPendingFadeUp()
        releaseWakeLock()
        restoreBrightness()
        hideOverlay()
    }

    private fun hideOverlay() {
        overlay.alpha = 0f
        overlay.isClickable = false
        overlay.visibility = View.GONE
        detachOverlay()
    }

    /**
     * Keep a fully black scrim across system screen-off. Only start the
     * fade-up once the panel is on again, so the animation is actually seen.
     */
    private fun fadeToLight() {
        animator?.cancel()
        animator = null
        val screenOffHeld = wakeLock?.isHeld == true
        restoreBrightness()
        if (screenOffHeld) {
            ensureOverlayAttached()
            overlay.alpha = 1f
            overlay.visibility = View.VISIBLE
            overlay.isClickable = true
            releaseWakeLock()
            waitForScreenOnThenFadeUp()
        } else {
            releaseWakeLock()
            animateOverlay(toDark = false)
        }
    }

    private fun waitForScreenOnThenFadeUp() {
        pendingFadeUp = true
        registerScreenOnWait()
        mainHandler.removeCallbacks(fadeUpTimeout)
        mainHandler.postDelayed(fadeUpTimeout, SCREEN_ON_TIMEOUT_MS)
        // Don't start the fade on this frame: the panel may still be off.
        // The next vsync / SCREEN_ON / display change kicks it off.
        overlay.postOnAnimation {
            if (pendingFadeUp && isDisplayOn()) startFadeUpAfterScreenOn()
        }
    }

    private fun startFadeUpAfterScreenOn() {
        if (!pendingFadeUp) return
        pendingFadeUp = false
        unregisterScreenOnWait()
        if (!resumed) return
        if (near) {
            if (shouldBlank) {
                overlay.alpha = 1f
                overlay.visibility = View.VISIBLE
                overlay.isClickable = true
                acquireWakeLock()
            }
            return
        }
        ensureOverlayAttached()
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.isClickable = true
        val beginFade = Runnable {
            if (!resumed || near) return@Runnable
            if (animator?.isRunning == true) return@Runnable
            animateOverlay(toDark = false)
        }
        // One fully black frame on the newly-on panel, then fade.
        overlay.postOnAnimation(beginFade)
    }

    private fun cancelPendingFadeUp() {
        pendingFadeUp = false
        unregisterScreenOnWait()
    }

    private fun registerScreenOnWait() {
        if (screenOnWaitRegistered) return
        screenOnWaitRegistered = true
        displayManager.registerDisplayListener(displayListener, mainHandler)
        ContextCompat.registerReceiver(
            activity,
            screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterScreenOnWait() {
        mainHandler.removeCallbacks(fadeUpTimeout)
        if (!screenOnWaitRegistered) return
        screenOnWaitRegistered = false
        displayManager.unregisterDisplayListener(displayListener)
        try {
            activity.unregisterReceiver(screenOnReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun isDisplayOn(): Boolean {
        val display = currentDisplay()
        val stateOn = display == null || display.state == Display.STATE_ON
        return stateOn && powerManager.isInteractive
    }

    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

    private fun animateOverlay(toDark: Boolean) {
        ensureOverlayAttached()
        val start = overlay.alpha
        val end = if (toDark) 1f else 0f
        animator?.cancel()
        animator = null
        overlay.visibility = View.VISIBLE
        overlay.isClickable = true
        if (abs(start - end) < 0.01f) {
            overlay.alpha = end
            if (toDark) onFullyDark() else onFullyLight()
            return
        }
        val fullMs = if (toDark) FADE_TO_BLACK_MS else FADE_TO_LIGHT_MS
        val animator = ValueAnimator.ofFloat(start, end).apply {
            duration = (fullMs * abs(end - start)).toLong().coerceAtLeast(1L)
            interpolator = if (toDark) {
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
                    if (toDark) onFullyDark() else onFullyLight()
                }
            })
        }
        this.animator = animator
        animator.start()
    }

    private fun onFullyDark() {
        if (!near || !resumed || !shouldBlank) return
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.isClickable = true
        if (!acquireWakeLock()) setBrightnessOff()
    }

    private fun onFullyLight() {
        restoreBrightness()
        hideOverlay()
    }

    private fun resetInstant() {
        animator?.cancel()
        animator = null
        cancelPendingFadeUp()
        releaseWakeLock()
        restoreBrightness()
        hideOverlay()
    }

    private fun ensureOverlayAttached() {
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

    private fun detachOverlay() {
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(): Boolean {
        if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            return false
        }
        return try {
            val lock = wakeLock ?: powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                WAKE_LOCK_TAG,
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            if (!lock.isHeld) lock.acquire()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
    }

    private fun setBrightnessOff() {
        val window = activity.window ?: return
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        window.attributes = params
        brightnessOverridden = true
    }

    private fun restoreBrightness() {
        if (!brightnessOverridden) return
        val window = activity.window ?: return
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        brightnessOverridden = false
    }

    private fun registerSensor() {
        val sensor = proximity ?: return
        if (sensorRegistered) return
        // The proximity value is only reduced to a near/far boolean in
        // onSensorChanged, so the default rate is plenty. FASTEST would keep
        // the hardware interrupting at its maximum rate for no benefit.
        sensorRegistered = sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            mainHandler,
        )
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(this)
        sensorRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!resumed || !shouldBlank || event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val max = event.sensor.maximumRange
        val nowNear = max > 0f && event.values[0] < max
        if (nowNear == near) return
        near = nowNear
        fadeTo(dark = nowNear)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val WAKE_LOCK_TAG = "mumdroid:earpiece"
        const val FADE_TO_BLACK_MS = 500f
        const val FADE_TO_LIGHT_MS = 1100f
        const val SCREEN_ON_TIMEOUT_MS = 1_200L
        const val OVERLAY_ELEVATION = 100_000f
    }
}

private fun outputDeviceTypes(audioManager: AudioManager): List<Int> =
    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
