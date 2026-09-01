package dev.woms.mumdroid.core.model

import dev.woms.mumdroid.core.proto.UserRemove
import dev.woms.mumdroid.core.proto.UserState

/**
 * Builds user-moderation payloads the way the desktop client does
 * (`ServerHandler::kickUser` / `banUser` / `registerUser`, `BanDialog`).
 */
object UserModeration {
    /** Official `Version::fromComponents(1, 6, 0)` in the v2 packing. */
    val SELECTIVE_BAN_VERSION_V2: Long = (1L shl 48) or (6L shl 32)

    /** Official `Version::fromComponents(1, 4, 0)` — channel listening. */
    val CHANNEL_LISTEN_VERSION_V2: Long = (1L shl 48) or (4L shl 32)

    fun kick(session: Int, reason: String): UserRemove =
        UserRemove.newBuilder()
            .setSession(session)
            .setReason(reason)
            .setBan(false)
            .build()

    fun ban(
        session: Int,
        reason: String,
        banCertificate: Boolean,
        banIp: Boolean,
    ): UserRemove =
        UserRemove.newBuilder()
            .setSession(session)
            .setReason(reason)
            .setBan(true)
            .setBanCertificate(banCertificate)
            .setBanIp(banIp)
            .build()

    /**
     * Desktop `ServerHandler::registerUser`: `UserState` with `user_id = 0`
     * asks the server to assign a registered id.
     */
    fun register(session: Int): UserState =
        UserState.newBuilder()
            .setSession(session)
            .setUserId(0)
            .build()

    /**
     * Desktop `MainWindow::on_qaUserPrioritySpeaker_triggered`:
     * `UserState` with `priority_speaker` toggled.
     */
    fun prioritySpeaker(session: Int, enabled: Boolean): UserState =
        UserState.newBuilder()
            .setSession(session)
            .setPrioritySpeaker(enabled)
            .build()

    /** Desktop `ServerHandler::joinChannel` for any session. */
    fun moveToChannel(session: Int, channelId: Int): UserState =
        UserState.newBuilder()
            .setSession(session)
            .setChannelId(channelId)
            .build()

    /**
     * Desktop `ServerHandler::startListeningToChannel` /
     * `stopListeningToChannel`.
     */
    fun setChannelListening(session: Int, channelId: Int, listen: Boolean): UserState {
        val builder = UserState.newBuilder().setSession(session)
        if (listen) builder.addListeningChannelAdd(channelId)
        else builder.addListeningChannelRemove(channelId)
        return builder.build()
    }

    /**
     * Desktop `MainWindow::on_qaUserMute_triggered`.
     *
     * The Mute menu item covers both server mute and ACL suppress
     * (`qaUserMute->setChecked(bMute || bSuppress)`). Unmuting clears whichever
     * of those is set; muting only sets `mute` (admins cannot impose suppress —
     * the server rejects `suppress=true`).
     */
    fun remoteMute(
        session: Int,
        currentlyMuted: Boolean,
        currentlySuppressed: Boolean,
        wantMuted: Boolean,
    ): UserState {
        val builder = UserState.newBuilder().setSession(session)
        if (wantMuted) {
            builder.setMute(true)
        } else {
            // Mirror the desktop unmute branch: only include flags that are on.
            if (currentlyMuted) builder.setMute(false)
            if (currentlySuppressed) builder.setSuppress(false)
            if (!currentlyMuted && !currentlySuppressed) builder.setMute(false)
        }
        return builder.build()
    }

    /**
     * Servers >= 1.6.0 accept `ban_certificate` / `ban_ip`. Older servers treat
     * a ban as both, so the desktop hides those checkboxes.
     */
    fun supportsSelectiveBan(versionV2: Long, legacyVersion: Int = 0): Boolean {
        val v2 = if (versionV2 != 0L) versionV2 else legacyToV2(legacyVersion)
        return v2 >= SELECTIVE_BAN_VERSION_V2
    }

    /**
     * Desktop hides Listen unless the server is >= 1.4.0.
     */
    fun supportsChannelListen(versionV2: Long, legacyVersion: Int = 0): Boolean {
        val v2 = if (versionV2 != 0L) versionV2 else legacyToV2(legacyVersion)
        return v2 >= CHANNEL_LISTEN_VERSION_V2
    }

    /**
     * Initial Ban-dialog checkbox state from desktop `BanDialog`:
     *  - pre-1.6: both on (and hidden)
     *  - 1.6+ with a certificate: certificate on, IP off, both editable
     *  - 1.6+ without a certificate: IP on, certificate off, both locked
     */
    fun initialBanOptions(showBanOptions: Boolean, hasCertificate: Boolean): BanOptions =
        when {
            !showBanOptions -> BanOptions(
                banCertificate = true,
                banIp = true,
                optionsEnabled = false,
            )
            hasCertificate -> BanOptions(
                banCertificate = true,
                banIp = false,
                optionsEnabled = true,
            )
            else -> BanOptions(
                banCertificate = false,
                banIp = true,
                optionsEnabled = false,
            )
        }

    data class BanOptions(
        val banCertificate: Boolean,
        val banIp: Boolean,
        val optionsEnabled: Boolean,
    )

    internal fun legacyToV2(legacy: Int): Long {
        val major = (legacy shr 16) and 0xffff
        val minor = (legacy shr 8) and 0xff
        val patch = legacy and 0xff
        return (major.toLong() shl 48) or (minor.toLong() shl 32) or (patch.toLong() shl 16)
    }
}
