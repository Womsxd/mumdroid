package dev.woms.mumdroid

import androidx.compose.ui.graphics.Color
import dev.woms.mumdroid.core.model.TalkState
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserStatusIcon
import dev.woms.mumdroid.ui.screen.UserRowColors
import dev.woms.mumdroid.ui.screen.UserRowPresentation
import dev.woms.mumdroid.ui.screen.UserTintRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user row's badge/tint/dim matrix. Previously inlined in the composable,
 * so a wrong palette slot or a missing dim case was invisible to the tests.
 */
class UserRowPresentationTest {

    private val accent = Color(0xFF123456)

    @Test
    fun badge_tintRolesFollowTheDesktopSkin() {
        // self-controlled -> SELF, server-imposed -> REMOTE, ACL -> SUPPRESS,
        // device-local -> LOCAL.
        assertEquals(UserTintRole.REMOTE, UserRowPresentation.badge(UserStatusIcon.PRIORITY_SPEAKER).tint)
        assertEquals(UserTintRole.REMOTE, UserRowPresentation.badge(UserStatusIcon.SERVER_MUTE).tint)
        assertEquals(UserTintRole.SUPPRESS, UserRowPresentation.badge(UserStatusIcon.SUPPRESS).tint)
        assertEquals(UserTintRole.SELF, UserRowPresentation.badge(UserStatusIcon.SELF_MUTE).tint)
        assertEquals(UserTintRole.LOCAL, UserRowPresentation.badge(UserStatusIcon.LOCAL_MUTE).tint)
        assertEquals(UserTintRole.LOCAL, UserRowPresentation.badge(UserStatusIcon.LOCAL_IGNORE).tint)
        assertEquals(UserTintRole.REMOTE, UserRowPresentation.badge(UserStatusIcon.SERVER_DEAF).tint)
        assertEquals(UserTintRole.SELF, UserRowPresentation.badge(UserStatusIcon.SELF_DEAF).tint)
    }

    @Test
    fun badge_everyStatusHasAStyle() {
        // A missing branch would be a compile error, but a duplicated string is
        // not: mutes both say "muted", deafenings both say "deafened".
        val styles = UserStatusIcon.entries.map(UserRowPresentation::badge)
        assertEquals(UserStatusIcon.entries.size, styles.size)
        assertEquals(
            styles.map { it.descRes }.distinct().size,
            6,
        )
        assertEquals(
            UserStatusIcon.entries.toList(),
            styles.map { it.status },
        )
    }

    @Test
    fun colors_supplyTheSkinPalette() {
        assertEquals(Color(0xFFEA4335), UserRowColors.of(UserTintRole.SELF, accent))
        assertEquals(Color(0xFF44A3F2), UserRowColors.of(UserTintRole.REMOTE, accent))
        assertEquals(Color(0xFF34A853), UserRowColors.of(UserTintRole.SUPPRESS, accent))
        assertEquals(Color(0xFF9B59B6), UserRowColors.of(UserTintRole.LOCAL, accent))
        // The plain-talking tint is the theme accent, not a fixed colour.
        assertEquals(accent, UserRowColors.of(UserTintRole.ACCENT, accent))
    }

    @Test
    fun talkColor_whisperAndShoutKeepTheirSkinColours() {
        assertEquals(
            UserRowColors.Whisper,
            UserRowPresentation.talkColor(User(session = 1, name = "a", talkState = TalkState.WHISPERING), accent),
        )
        assertEquals(
            UserRowColors.Shout,
            UserRowPresentation.talkColor(User(session = 1, name = "a", talkState = TalkState.SHOUTING), accent),
        )
        assertEquals(
            accent,
            UserRowPresentation.talkColor(User(session = 1, name = "a", talkState = TalkState.TALKING), accent),
        )
    }

    @Test
    fun isNameDimmed_coversEverySilencingSource() {
        assertFalse(UserRowPresentation.isNameDimmed(User(session = 1, name = "a")))
        assertTrue(UserRowPresentation.isNameDimmed(User(session = 1, name = "a", mute = true)))
        assertTrue(UserRowPresentation.isNameDimmed(User(session = 1, name = "a", deaf = true)))
        assertTrue(UserRowPresentation.isNameDimmed(User(session = 1, name = "a", suppress = true)))
        assertTrue(UserRowPresentation.isNameDimmed(User(session = 1, name = "a", selfMute = true)))
        assertTrue(UserRowPresentation.isNameDimmed(User(session = 1, name = "a", selfDeaf = true)))
        assertTrue(UserRowPresentation.isNameDimmed(User(session = 1, name = "a", localBlock = true)))
    }

    @Test
    fun showsTalking_requiresAudioAndAnUnblockedMic() {
        assertTrue(
            UserRowPresentation.showsTalking(
                User(session = 1, name = "a", talkState = TalkState.TALKING),
            ),
        )
        assertFalse(
            UserRowPresentation.showsTalking(
                User(session = 1, name = "a", talkState = TalkState.TALKING, mute = true),
            ),
        )
        assertFalse(UserRowPresentation.showsTalking(User(session = 1, name = "a")))
    }

    @Test
    fun talkDescription_prefersTheWhisperAndShoutWording() {
        val whisper = User(session = 1, name = "a", talkState = TalkState.WHISPERING)
        val shout = User(session = 1, name = "a", talkState = TalkState.SHOUTING)
        val talking = User(session = 1, name = "a", talkState = TalkState.TALKING)
        val idle = User(session = 1, name = "a")
        assertEquals(
            R.string.talking_whisper,
            UserRowPresentation.talkDescription(whisper),
        )
        assertEquals(R.string.talking_shout, UserRowPresentation.talkDescription(shout))
        assertEquals(R.string.talking, UserRowPresentation.talkDescription(talking))
        assertEquals(R.string.not_talking, UserRowPresentation.talkDescription(idle))
    }
}
