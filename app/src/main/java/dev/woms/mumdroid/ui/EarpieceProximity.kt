package dev.woms.mumdroid.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.woms.mumdroid.core.audio.VoiceRouteSelection
import dev.woms.mumdroid.core.model.VoiceOutputTarget

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
