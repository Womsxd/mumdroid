package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.ui.screen.UserMenuVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user long-press menu is driven entirely by permission predicates, so the
 * visibility flags (and the dividers derived from them) are what decides whether
 * an action is offered at all.
 */
class UserMenuVisibilityTest {

    private val localChannelId = 1
    private val dests = listOf(ChannelPick(2, "Lobby", 0))

    private fun visibility(
        user: User,
        moveDests: List<ChannelPick> = dests,
        showWhisper: Boolean = false,
        showLoopback: Boolean = false,
        canAdministerChannel: (Int) -> Boolean = { true },
        canMuteUser: (User) -> Boolean = { true },
        canPrioritySpeaker: (User) -> Boolean = { true },
        canMoveInChannel: (Int) -> Boolean = { true },
        canKickUser: () -> Boolean = { true },
        canBanUser: () -> Boolean = { true },
        canRegisterUser: (User) -> Boolean = { true },
        canTextMessage: (Int) -> Boolean = { true },
    ) = UserMenuVisibility.of(
        user = user,
        localChannelId = localChannelId,
        moveDests = moveDests,
        showWhisper = showWhisper,
        showLoopback = showLoopback,
        canAdministerChannel = canAdministerChannel,
        canMuteUser = canMuteUser,
        canPrioritySpeaker = canPrioritySpeaker,
        canMoveInChannel = canMoveInChannel,
        canKickUser = canKickUser,
        canBanUser = canBanUser,
        canRegisterUser = canRegisterUser,
        canTextMessage = canTextMessage,
    )

    @Test
    fun remoteUserInAnotherChannel_offersJoinAndMove() {
        val v = visibility(User(session = 4, name = "bob", channelId = 2))
        assertTrue(v.inOtherChannel)
        assertTrue(v.showMoveMenu)
        assertTrue(v.showMoveHere)
        assertTrue(v.showMoveTo)
        assertTrue(v.showAdminMenu)
        assertTrue(v.showBlockActions)
        assertTrue(v.showSendMessage)
    }

    @Test
    fun remoteUserInOurChannel_hasNoJoinOrMove() {
        // The row filters move destinations to other channels, so a user in our
        // channel is handed an empty list.
        val v = visibility(
            User(session = 4, name = "bob", channelId = localChannelId),
            moveDests = emptyList(),
        )
        assertFalse(v.inOtherChannel)
        assertFalse(v.showMoveMenu)
    }

    @Test
    fun ownRow_offersVoiceTargetAndSelfTest() {
        val v = visibility(
            User(session = 1, name = "me", channelId = localChannelId, isLocalUser = true),
            showWhisper = false,
            showLoopback = true,
        )
        assertFalse(v.showBlockActions)
        assertFalse(v.showSendMessage)
        assertFalse(v.showKick)
        assertFalse(v.showBan)
        assertFalse(v.showMoveMenu)
        assertTrue(v.showLoopback)
        assertTrue(v.showAdminMenu)
    }

    @Test
    fun listenerProxy_onlyOffersStopListening() {
        val v = visibility(
            User(
                session = 9,
                name = "bob",
                channelId = 3,
                isLocalUser = true,
                isChannelListener = true,
                listenerChannelId = 3,
            ),
        )
        assertTrue(v.isListener)
        assertTrue(v.showStopListening)
        assertFalse(v.showAdminMenu)
        assertFalse(v.showKick)
        assertFalse(v.showBan)
        assertFalse(v.showMute)
        assertFalse(v.showDeafen)
        assertFalse(v.showBlockActions)
        assertFalse(v.showSendMessage)
        assertFalse(v.showRegister)
    }

    @Test
    fun listenerProxy_asksForWhisperPermissionOnTheListenerChannel() {
        var asked = -1
        val v = visibility(
            User(
                session = 9,
                name = "bob",
                channelId = 3,
                isChannelListener = true,
                listenerChannelId = 7,
            ),
            canTextMessage = { asked = it; true },
        )
        assertEquals(7, asked)
        assertTrue(v.showSendMessage)
    }

    @Test
    fun mutedOrSuppressed_readsUnmute() {
        assertTrue(visibility(User(session = 2, mute = true)).silencedByServer)
        assertTrue(visibility(User(session = 2, suppress = true)).silencedByServer)
        assertFalse(visibility(User(session = 2, selfMute = true)).silencedByServer)
    }

    @Test
    fun noMoveDestination_hidesTheMoveToEntry() {
        // The only place left to move the user is our own channel.
        val v = visibility(
            User(session = 4, channelId = 2),
            moveDests = emptyList(),
        )
        assertFalse(v.showMoveTo)
        assertTrue(v.showMoveHere)
        assertTrue(v.showMoveMenu)
    }

    @Test
    fun moveHereRequiresPermissionOnOurChannel() {
        val v = visibility(
            User(session = 4, channelId = 2),
            canMoveInChannel = { it == 2 },
        )
        assertTrue(v.showMoveTo)
        assertFalse(v.showMoveHere)
        assertTrue(v.showMoveMenu)
    }

    @Test
    fun deniedModerationPermission_hidesTheWholeAdminMenu() {
        val v = visibility(
            User(session = 4, channelId = localChannelId),
            canMuteUser = { false },
            canPrioritySpeaker = { false },
            canAdministerChannel = { false },
            canKickUser = { false },
            canBanUser = { false },
        )
        assertFalse(v.showAdminMenu)
        assertFalse(v.showDeafen)
    }

    @Test
    fun dividersFollowTheGroupsThatActuallyRender() {
        // Own row with no voice-target entries: only the administration group
        // and the footer render, so no divider precedes them.
        val bare = visibility(
            User(session = 1, channelId = localChannelId, isLocalUser = true),
            canMuteUser = { false },
            canPrioritySpeaker = { false },
        )
        assertFalse(bare.dividerBeforeAdmin)
        assertFalse(bare.dividerBeforeLocalActions)
        assertFalse(bare.dividerBeforeFooter)

        // A full remote user keeps every group divider.
        val full = visibility(User(session = 4, channelId = 2), showWhisper = true)
        assertTrue(full.dividerBeforeAdmin)
        assertTrue(full.dividerBeforeLocalActions)
        assertTrue(full.dividerBeforeVoiceTarget)
        assertTrue(full.dividerBeforeFooter)
    }

    @Test
    fun dividerBeforeAdmin_onlyDependsOnTheGroupsAboveIt() {
        // Own row: no join / move entry above the administration group, so the
        // rule would hang under nothing.
        val ownRow = visibility(
            User(session = 1, channelId = localChannelId, isLocalUser = true),
            showLoopback = true,
        )
        assertFalse(ownRow.dividerBeforeAdmin)
        // The administration group does render (server mute / priority speaker).
        assertTrue(ownRow.showAdminMenu)
        assertTrue(ownRow.dividerBeforeLocalActions)
        assertTrue(ownRow.dividerBeforeFooter)
    }
}
