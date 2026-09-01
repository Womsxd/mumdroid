package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.core.model.UserStatusIcon
import org.junit.Assert.assertEquals
import org.junit.Test

class UserStatusIconTest {

    @Test
    fun order_matchesDesktopUserModel() {
        val user = User(
            session = 1,
            name = "a",
            prioritySpeaker = true,
            mute = true,
            suppress = true,
            selfMute = true,
            localBlock = true,
            localIgnore = true,
            deaf = true,
            selfDeaf = true,
        )
        assertEquals(
            listOf(
                UserStatusIcon.PRIORITY_SPEAKER,
                UserStatusIcon.SERVER_MUTE,
                UserStatusIcon.SUPPRESS,
                UserStatusIcon.SELF_MUTE,
                UserStatusIcon.LOCAL_MUTE,
                UserStatusIcon.LOCAL_IGNORE,
                UserStatusIcon.SERVER_DEAF,
                UserStatusIcon.SELF_DEAF,
            ),
            user.visibleStatusIcons(),
        )
    }

    @Test
    fun idleUser_hasNoStatusIcons() {
        assertEquals(emptyList<UserStatusIcon>(), User(session = 1, name = "a").visibleStatusIcons())
    }

    @Test
    fun listenerProxy_matchesDesktopIcons() {
        val listener = User(
            session = 1,
            name = "a",
            isChannelListener = true,
            prioritySpeaker = true,
            mute = false,
            suppress = true,
            selfMute = true,
            localBlock = true,
            localIgnore = true,
            deaf = true,
            selfDeaf = true,
        )
        assertEquals(
            listOf(
                UserStatusIcon.SERVER_MUTE,
                UserStatusIcon.SERVER_DEAF,
                UserStatusIcon.SELF_DEAF,
            ),
            listener.visibleStatusIcons(),
        )
    }
}
