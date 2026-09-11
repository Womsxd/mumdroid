package dev.woms.mumdroid.ui.screen

import androidx.compose.ui.graphics.Color
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserStatusIcon

/** Which palette slot a user-row decoration is drawn with. */
internal enum class UserTintRole { SELF, REMOTE, SUPPRESS, LOCAL, ACCENT }

/**
 * The desktop skin's colour roles, verified against the PC client's
 * `themes/Default` assets (muted_self.svg, muted_server.svg, muted_local.svg,
 * muted_suppressed.svg, priority_speaker.svg, talking_whisper.svg,
 * talking_alt.svg):
 *  - self-controlled mute/deafen   -> red    [#EA4335]
 *  - server-imposed mute/deafen    -> blue   [#44A3F2]
 *  - channel ACL suppress          -> green  [#34A853]
 *  - local mute / ignore messages  -> purple [#9B59B6]
 *  - whispering                    -> purple [#9B59B6]
 *  - shouting                      -> amber  [#FBBC05]
 */
internal object UserRowColors {
    val Self = Color(0xFFEA4335)
    val Remote = Color(0xFF44A3F2)
    val Suppress = Color(0xFF34A853)
    val Local = Color(0xFF9B59B6)
    val Whisper = Color(0xFF9B59B6)
    val Shout = Color(0xFFFBBC05)

    fun of(role: UserTintRole, accent: Color): Color = when (role) {
        UserTintRole.SELF -> Self
        UserTintRole.REMOTE -> Remote
        UserTintRole.SUPPRESS -> Suppress
        UserTintRole.LOCAL -> Local
        UserTintRole.ACCENT -> accent
    }
}

/**
 * How one status badge is drawn: which vector to use, which string describes
 * it and which palette slot tints it. Kept as data so the whole matrix can be
 * asserted without a Compose runtime.
 */
internal data class StatusBadgeStyle(
    val status: UserStatusIcon,
    val descRes: Int,
    val tint: UserTintRole,
)

/**
 * The user row's presentation decisions: badge styling, the dimmed-name rule
 * and the talking-indicator description/colour.
 */
internal object UserRowPresentation {

    fun badge(status: UserStatusIcon): StatusBadgeStyle = when (status) {
        UserStatusIcon.PRIORITY_SPEAKER ->
            StatusBadgeStyle(status, R.string.priority_speaker, UserTintRole.REMOTE)
        UserStatusIcon.SERVER_MUTE ->
            StatusBadgeStyle(status, R.string.muted, UserTintRole.REMOTE)
        UserStatusIcon.SUPPRESS ->
            StatusBadgeStyle(status, R.string.suppressed, UserTintRole.SUPPRESS)
        UserStatusIcon.SELF_MUTE ->
            StatusBadgeStyle(status, R.string.muted, UserTintRole.SELF)
        UserStatusIcon.LOCAL_MUTE ->
            StatusBadgeStyle(status, R.string.blocked, UserTintRole.LOCAL)
        UserStatusIcon.LOCAL_IGNORE ->
            StatusBadgeStyle(status, R.string.messages_ignored, UserTintRole.LOCAL)
        UserStatusIcon.SERVER_DEAF ->
            StatusBadgeStyle(status, R.string.deafened, UserTintRole.REMOTE)
        UserStatusIcon.SELF_DEAF ->
            StatusBadgeStyle(status, R.string.deafened, UserTintRole.SELF)
    }

    /**
     * A silenced name is greyed out, whether the silencing came from the
     * server, from the user themself, or from a local block/ignore.
     */
    fun isNameDimmed(user: User): Boolean =
        user.mute || user.deaf || user.suppress || user.selfMute || user.selfDeaf || user.localBlock

    /** Whether the row draws the "talking" tint at all. */
    fun showsTalking(user: User): Boolean = user.talking && !user.isSpeakBlocked

    /** Accessibility string of the talking/level indicator. */
    fun talkDescription(user: User): Int = when (user.talkState) {
        TalkState.WHISPERING -> R.string.talking_whisper
        TalkState.SHOUTING -> R.string.talking_shout
        else -> if (showsTalking(user)) R.string.talking else R.string.not_talking
    }

    /** Palette slot of the talking indicator; the caller supplies the accent. */
    fun talkTint(user: User): UserTintRole = when (user.talkState) {
        TalkState.WHISPERING -> UserTintRole.LOCAL
        TalkState.SHOUTING -> UserTintRole.ACCENT
        else -> UserTintRole.ACCENT
    }

    /**
     * Raw colour of the talking indicator. Whispering/shouting keep their skin
     * colours regardless of the theme accent.
     */
    fun talkColor(user: User, accent: Color): Color = when (user.talkState) {
        TalkState.WHISPERING -> UserRowColors.Whisper
        TalkState.SHOUTING -> UserRowColors.Shout
        else -> accent
    }
}
