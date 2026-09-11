package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.ui.screen.ConnectionScreenLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection screen's derived values: where the local user is, where an
 * untargeted chat message goes, and whether the voice bar may cover the keys.
 */
class ConnectionScreenLogicTest {

    private val me = User(session = 1, name = "me", channelId = 4, isLocalUser = true)
    private val bob = User(session = 2, name = "bob", channelId = 7)
    private val root = Channel(id = 0, name = "Root")
    private val lobby = Channel(id = 4, name = "Lobby")

    @Test
    fun localChannelId_isTheSelfEntryChannel() {
        assertEquals(4, ConnectionScreenLogic.localChannelId(listOf(bob, me)))
    }

    @Test
    fun localChannelId_isZeroBeforeTheSelfEntryArrives() {
        assertEquals(0, ConnectionScreenLogic.localChannelId(listOf(bob)))
        assertEquals(0, ConnectionScreenLogic.localChannelId(emptyList()))
    }

    @Test
    fun chatChannelId_followsTheLocalUserNotTheFirstChannel() {
        assertEquals(4, ConnectionScreenLogic.chatChannelId(listOf(bob, me), listOf(root, lobby)))
    }

    @Test
    fun chatChannelId_fallsBackToTheFirstChannelBeforeTheRosterArrives() {
        assertEquals(0, ConnectionScreenLogic.chatChannelId(emptyList(), listOf(root, lobby)))
    }

    @Test
    fun chatChannelId_fallsBackToZeroOnAnEmptyTree() {
        assertEquals(0, ConnectionScreenLogic.chatChannelId(emptyList(), emptyList()))
    }

    @Test
    fun voiceControls_areAlwaysShownOnTheChannelTab() {
        assertTrue(ConnectionScreenLogic.showVoiceControls(ConnectionScreenLogic.TAB_CHANNELS, true))
        assertTrue(ConnectionScreenLogic.showVoiceControls(ConnectionScreenLogic.TAB_CHANNELS, false))
    }

    @Test
    fun voiceControls_yieldToTheKeyboardOnTheChatTab() {
        assertTrue(ConnectionScreenLogic.showVoiceControls(ConnectionScreenLogic.TAB_CHAT, false))
        assertFalse(ConnectionScreenLogic.showVoiceControls(ConnectionScreenLogic.TAB_CHAT, true))
    }

    @Test
    fun tabIndexes_areDistinct() {
        assertEquals(0, ConnectionScreenLogic.TAB_CHANNELS)
        assertEquals(1, ConnectionScreenLogic.TAB_CHAT)
    }
}
