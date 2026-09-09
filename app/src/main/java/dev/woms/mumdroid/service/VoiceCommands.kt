package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.VoiceOutputTarget
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
