package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.ui.screen.ChannelNodeVisibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel row's show/hide matrix — previously inlined in the composable,
 * so a wrong permission gate could only be caught by looking at the screen.
 */
class ChannelNodeVisibilityTest {

    private val me = User(session = 1, name = "me", channelId = 40, isLocalUser = true)

    private fun channel(
        id: Int = 4,
        temporary: Boolean = false,
        withUsers: Boolean = false,
        withChildren: Boolean = false,
    ) = Channel(
        id = id,
        parentId = 0,
        name = "c$id",
        temporary = temporary,
        users = if (withUsers) mutableListOf(me) else mutableListOf(),
        children = if (withChildren) mutableListOf(Channel(id = 9, name = "child")) else mutableListOf(),
    )

    private fun flags(
        channel: Channel = channel(),
        collapsedIds: Set<Int> = emptySet(),
        localChannelId: Int = 0,
        homeAllLinks: Set<Int> = emptySet(),
        listeningChannels: Set<Int> = emptySet(),
        activeShoutChannelId: Int? = null,
        supportsChannelListen: Boolean = true,
        mayWhisper: (Int) -> Boolean = { true },
        canListen: (Int) -> Boolean = { true },
        canTextMessage: (Int) -> Boolean = { true },
        canAddChannel: (Int) -> Boolean = { true },
        canMakePermanentChannel: (Int) -> Boolean = { true },
        canWriteChannel: (Int) -> Boolean = { true },
        canLinkChannel: (Int) -> Boolean = { true },
    ) = ChannelNodeVisibility.of(
        channel = channel,
        collapsedIds = collapsedIds,
        localChannelId = localChannelId,
        homeAllLinks = homeAllLinks,
        homeDirectLinks = emptySet(),
        listeningChannels = listeningChannels,
        activeShoutChannelId = activeShoutChannelId,
        supportsChannelListen = supportsChannelListen,
        mayWhisper = mayWhisper,
        canListen = canListen,
        canTextMessage = canTextMessage,
        canAddChannel = canAddChannel,
        canMakePermanentChannel = canMakePermanentChannel,
        canWriteChannel = canWriteChannel,
        canLinkChannel = canLinkChannel,
    )

    @Test
    fun showJoin_hiddenOnlyInTheLocalChannel() {
        assertFalse(flags(channel = channel(4), localChannelId = 4).showJoin)
        assertTrue(flags(channel = channel(4), localChannelId = 7).showJoin)
    }

    @Test
    fun showAdd_neverOfferedForTemporaryChannels() {
        assertTrue(flags(channel = channel(temporary = false)).showAdd)
        assertFalse(flags(channel = channel(temporary = true)).showAdd)
    }

    @Test
    fun forceTemporary_followsThePermanentPermission() {
        assertTrue(flags(canMakePermanentChannel = { false }).forceTemporary)
        assertFalse(flags(canMakePermanentChannel = { true }).forceTemporary)
        // A temporary channel offers no add at all, so nothing to force.
        assertFalse(flags(channel = channel(temporary = true), canMakePermanentChannel = { false }).forceTemporary)
    }

    @Test
    fun showRemove_neverOfferedForTheRootChannel() {
        assertTrue(flags(channel = channel(4)).showRemove)
        assertFalse(flags(channel = channel(0)).showRemove)
        assertFalse(flags(channel = channel(4), canWriteChannel = { false }).showRemove)
    }

    @Test
    fun showListen_needsTheServerFeatureAndTheAcl() {
        assertTrue(flags().showListen)
        assertFalse(flags(canListen = { false }).showListen)
        assertFalse(flags(supportsChannelListen = false).showListen)
    }

    @Test
    fun showListen_survivesLosingTheAclWhileAlreadyListening() {
        val listening = flags(
            listeningChannels = setOf(4),
            canListen = { false },
        )
        // Still listening, so the entry must stay reachable to stop it.
        assertTrue(listening.listening)
        assertTrue(listening.showListen)
    }

    @Test
    fun showShout_needsWhisperUnlessItsTargetIsActive() {
        assertTrue(flags().showShout)
        assertFalse(flags(mayWhisper = { false }).showShout)
        val active = flags(activeShoutChannelId = 4, mayWhisper = { false })
        assertTrue(active.shoutActive)
        assertTrue(active.showShout)
        assertFalse(flags(activeShoutChannelId = 9, mayWhisper = { false }).shoutActive)
    }

    @Test
    fun collapse_needsChildrenOrUsers() {
        assertFalse(flags(channel = channel(withUsers = false, withChildren = false)).canCollapse)
        assertTrue(flags(channel = channel(withUsers = true)).canCollapse)
        assertTrue(flags(channel = channel(withChildren = true)).canCollapse)
        // The root channel never folds.
        assertFalse(flags(channel = channel(id = 0, withUsers = true)).canCollapse)
    }

    @Test
    fun collapsed_isClearedWhenTheNodeCannotFold() {
        assertTrue(flags(channel = channel(withUsers = true), collapsedIds = setOf(4)).collapsed)
        assertFalse(flags(channel = channel(), collapsedIds = setOf(4)).collapsed)
    }

    @Test
    fun linkIcon_hiddenInTheHomeChannelAndWithoutPartners() {
        val linked = flags(channel = channel(4), localChannelId = 4, homeAllLinks = setOf(4, 9))
        assertTrue(linked.isLinked)
        assertFalse(linked.showLinkIcon)
        val away = flags(channel = channel(4), localChannelId = 7, homeAllLinks = setOf(4, 7))
        assertTrue(away.showLinkIcon)
        assertFalse(flags(channel = channel(4), localChannelId = 7, homeAllLinks = setOf(4)).isLinked)
    }

    @Test
    fun linkMenu_isBuiltFromTheLivePermissions() {
        val menu = ChannelNodeVisibility.linkMenu(
            channel = channel(4),
            localChannelId = 7,
            homeAllLinks = setOf(7),
            homeDirectLinks = emptySet(),
            canLinkChannel = { true },
        )
        assertTrue(menu.showLink)
        assertFalse(menu.showUnlink)
        assertFalse(menu.showUnlinkAll)
    }
}
