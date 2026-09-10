package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.LoopbackMode
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.model.VoiceTargetSpec
import dev.woms.mumdroid.core.net.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Local voice controls: output route, self mute/deafen and push-to-talk. The
 * mute/deafen toggles also echo the new state to the server and roster.
 */
internal class VoiceCommands(
    private val scope: CoroutineScope,
    private val state: SessionState,
    private val roster: SessionRoster,
    private val voice: VoiceSession,
) {
    fun setOutputTarget(target: VoiceOutputTarget) = voice.setOutputTarget(target)

    fun toggleSelfMute() {
        voice.toggleSelfMute()
        sendLocalMuteDeafen()
    }

    fun toggleSelfDeafen() {
        voice.toggleSelfDeafen()
        sendLocalMuteDeafen()
    }

    fun startTalking() = voice.startTalking()
    fun stopTalking() = voice.stopTalking()

    /**
     * Sets the shout/whisper target. Null restores regular speech and revokes
     * every registration on the server.
     */
    fun setVoiceTarget(spec: VoiceTargetSpec?) {
        if (spec == null) voice.setVoiceTarget(null) else voice.setVoiceTarget(spec)
    }

    /** Clears the target, keeping the revocation messages on the wire. */
    fun clearVoiceTarget() = voice.setVoiceTarget(null)

    /**
     * Switches the audio self-test on or off (see [LoopbackMode]). The mode is
     * session-scoped and never persisted: a forgotten self-test would silently
     * stop the user from being heard.
     */
    fun setLoopback(mode: LoopbackMode) = voice.setLoopback(mode)

    private fun sendLocalMuteDeafen() {
        val muted = voice.selfMutedValue()
        val deafened = voice.selfDeafenedValue()
        roster.updateLocalMuteDeafen(muted, deafened)
        val c = state.client ?: return
        scope.launch {
            c.sendMessage(
                MessageType.USER_STATE,
                dev.woms.mumdroid.core.proto.UserState.newBuilder()
                    .setSession(c.currentSession)
                    .setSelfMute(muted)
                    .setSelfDeaf(deafened)
                    .build(),
            )
        }
    }
}
