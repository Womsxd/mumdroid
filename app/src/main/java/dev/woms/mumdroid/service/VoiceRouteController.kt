package dev.woms.mumdroid.service

import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.woms.mumdroid.core.audio.VoicePlaybackRouter
import dev.woms.mumdroid.core.audio.VoiceRouteSelection
import dev.woms.mumdroid.core.model.AecMode
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Host side-effects the route controller needs from the owning audio session.
 * The controller owns route *policy*; the host owns the live audio endpoints
 * the policy acts upon.
 */
internal interface VoiceRouteHost {
    fun settings(): AppSettings

    /** Whether an [AudioOutput][dev.woms.mumdroid.core.audio.AudioOutput] is live. */
    fun outputLive(): Boolean

    /** Whether capture is live (gates the AEC-change restart). */
    fun inputLive(): Boolean

    /** The media/communication usage changed: stop, rebuild and re-attach the output. */
    fun onOutputMediaChanged()

    /** The effective AEC mode changed: capture must be restarted to apply it. */
    fun onAecChanged()

    /** Applies the route's preferred capture device to the live input. */
    fun onInputDevice(device: AudioDeviceInfo?)

    /** Applies the route's preferred playback device to the live output. */
    fun onOutputDevice(device: AudioDeviceInfo?)
}

/**
 * Output-route policy for the voice session: picks the wanted target
 * (explicit override → user order over connected devices), drives the
 * [VoicePlaybackRouter] (communication mode / SCO), and rebuilds the output
 * when the media/communication usage changes. The effective AEC mode is tied
 * to the route, so a route change may also require a capture restart.
 *
 * Threading mirrors the original wiring: the router's device callbacks fire
 * on the main thread ([VoiceRouteHost] hops included), while
 * [applyOutputRoute] itself runs on whatever thread the session is driven
 * from — no locks are introduced.
 */
internal class VoiceRouteController(
    audioManager: AudioManager,
    private val host: VoiceRouteHost,
) {
    val playbackRouter = VoicePlaybackRouter(
        audioManager,
        onDevicesChanged = {
            // Only re-apply while an output is live (official behaviour).
            if (host.outputLive()) applyOutputRoute()
        },
        onInputDeviceChanged = { device -> host.onInputDevice(device) },
        onOutputDeviceChanged = { device -> host.onOutputDevice(device) },
    )

    private val _outputTarget = MutableStateFlow<VoiceOutputTarget?>(null)

    /** The currently routed output target (null = system default). */
    val outputTarget: StateFlow<VoiceOutputTarget?> = _outputTarget

    private var sessionOutputOverride: VoiceOutputTarget? = null
    private var routedMedia = false
    private var routedAec: AecMode? = null

    /** The target the route currently resolves to, or null before the first route. */
    fun currentTarget(): VoiceOutputTarget? = _outputTarget.value

    /** Whether the current target plays through media (not communication) usage. */
    fun currentMediaUsage(): Boolean {
        val target = _outputTarget.value ?: return false
        return host.settings().usesMediaPlayback(target)
    }

    /** Sets the user's explicit output target and re-applies the route. */
    fun setOutputTarget(target: VoiceOutputTarget) {
        sessionOutputOverride = target
        applyOutputRoute()
    }

    /** Drops the explicit override (e.g. for a new session). */
    fun resetOverride() {
        sessionOutputOverride = null
    }

    /** Leaves call mode: unroutes and clears override and routing state. */
    fun leaveCall() {
        playbackRouter.leaveCall()
        sessionOutputOverride = null
        _outputTarget.value = null
        routedMedia = false
        routedAec = null
    }

    /**
     * Re-evaluates the output route: picks the wanted target, enters call
     * mode with the right usage, and rebuilds the output when the usage
     * changed. Also restarts capture when the effective AEC mode changed.
     */
    fun applyOutputRoute() {
        val settings = host.settings()
        val types = playbackRouter.availableOutputTypes()
        val connected = VoiceRouteSelection.connectedTargets(types)
        val override = sessionOutputOverride
        if (override != null && override !in connected) {
            sessionOutputOverride = null
        }
        val wanted = sessionOutputOverride?.takeIf { it in connected }
            ?: VoiceRouteSelection.pick(settings.outputDeviceOrder, connected)
        _outputTarget.value = wanted
        if (wanted == null) return
        val media = settings.usesMediaPlayback(wanted)
        val aec = settings.effectiveAecMode(wanted)
        val mediaChanged = media != routedMedia
        playbackRouter.enterCall(wanted, media)
        routedMedia = media
        if (host.outputLive()) {
            if (mediaChanged) {
                host.onOutputMediaChanged()
            } else {
                host.onOutputDevice(playbackRouter.outputDevice)
            }
        }
        if (host.inputLive()) {
            if (routedAec != null && routedAec != aec) {
                host.onAecChanged()
            } else {
                host.onInputDevice(playbackRouter.inputDevice)
            }
        }
        routedAec = aec
    }
}
