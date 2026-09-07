package dev.woms.mumdroid.core.model

/**
 * Local self-mute / self-deafen pairing, matching desktop Mumble
 * (`MainWindow::on_qaAudioMute_triggered` / `on_qaAudioDeaf_triggered`).
 *
 *  - Mute only mutes.
 *  - Unmute also undeafens.
 *  - Deafen also mutes. If the user was not already muted, undeafen later
 *    restores the microphone ([unmuteOnUndeaf]); otherwise undeafen leaves
 *    the microphone muted so it can be turned back on independently.
 */
data class SelfMuteDeaf(
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val unmuteOnUndeaf: Boolean = false,
) {
    fun toggleMute(): SelfMuteDeaf {
        val newMute = !muted
        return if (newMute) {
            copy(muted = true)
        } else {
            SelfMuteDeaf(muted = false, deafened = false)
        }
    }

    fun toggleDeafen(): SelfMuteDeaf {
        if (deafened && unmuteOnUndeaf) {
            return toggleMute()
        }
        return if (!deafened) {
            SelfMuteDeaf(
                muted = true,
                deafened = true,
                unmuteOnUndeaf = !muted,
            )
        } else {
            copy(deafened = false)
        }
    }
}
