package dev.woms.mumdroid.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.core.content.ContextCompat

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

    /** All View / window handling (scrim, fades, brightness override). */
    private val scrim = EarpieceScreenScrim(activity)

    private var wakeLock: PowerManager.WakeLock? = null
    private var resumed = false
    private var shouldBlank = false
    private var sensorRegistered = false
    private var near = false
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
        scrim.cancelAnimation()
        scrim.hide()
        if (!acquireWakeLock()) {
            scrim.showOpaque()
            scrim.dimScreen()
        }
    }

    /** Snap the panel back on with no scrim animation. */
    private fun applyInstantLight() {
        scrim.cancelAnimation()
        cancelPendingFadeUp()
        releaseWakeLock()
        scrim.restoreBrightness()
        scrim.hide()
    }

    /**
     * Keep a fully black scrim across system screen-off. Only start the
     * fade-up once the panel is on again, so the animation is actually seen.
     */
    private fun fadeToLight() {
        scrim.cancelAnimation()
        val screenOffHeld = wakeLock?.isHeld == true
        scrim.restoreBrightness()
        if (screenOffHeld) {
            scrim.showOpaque()
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
        scrim.postOnAnimation {
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
                scrim.showOpaque()
                acquireWakeLock()
            }
            return
        }
        scrim.showOpaque()
        // One fully black frame on the newly-on panel, then fade.
        scrim.postOnAnimation {
            if (!resumed || near) return@postOnAnimation
            if (scrim.isAnimating) return@postOnAnimation
            animateOverlay(toDark = false)
        }
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
        scrim.fade(dark = toDark) {
            if (toDark) onFullyDark() else onFullyLight()
        }
    }

    private fun onFullyDark() {
        if (!near || !resumed || !shouldBlank) return
        scrim.showOpaque()
        if (!acquireWakeLock()) scrim.dimScreen()
    }

    private fun onFullyLight() {
        scrim.restoreBrightness()
        scrim.hide()
    }

    private fun resetInstant() {
        scrim.cancelAnimation()
        cancelPendingFadeUp()
        releaseWakeLock()
        scrim.restoreBrightness()
        scrim.hide()
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
        const val SCREEN_ON_TIMEOUT_MS = 1_200L
    }
}
