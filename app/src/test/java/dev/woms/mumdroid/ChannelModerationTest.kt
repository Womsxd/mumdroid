package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelModeration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelModerationTest {

    @Test
    fun create_omitsChannelIdAndSetsParent() {
        val msg = ChannelModeration.create(
            parentId = 3,
            name = "Lobby",
            description = "hello",
            position = -10,
            temporary = true,
            maxUsers = 8,
        )
        assertFalse(msg.hasChannelId())
        assertEquals(3, msg.parent)
        assertEquals("Lobby", msg.name)
        assertEquals("hello", msg.description)
        assertEquals(-10, msg.position)
        assertTrue(msg.temporary)
        assertEquals(8, msg.maxUsers)
    }

    @Test
    fun update_sendsOnlyChangedFields() {
        val current = Channel(
            id = 4,
            name = "Games",
            description = "old",
            position = 0,
            maxUsers = 0,
        )
        val none = ChannelModeration.update(
            current, name = "Games", description = "old", position = 0, maxUsers = 0,
        )
        assertNull(none)

        val renamed = ChannelModeration.update(
            current, name = "Arcade", description = "old", position = 0, maxUsers = 0,
        )!!
        assertEquals(4, renamed.channelId)
        assertTrue(renamed.hasName())
        assertEquals("Arcade", renamed.name)
        assertFalse(renamed.hasDescription())
        assertFalse(renamed.hasPosition())
        assertFalse(renamed.hasMaxUsers())

        val moved = ChannelModeration.update(
            current, name = "Games", description = "old", position = -3, maxUsers = 12,
        )!!
        assertFalse(moved.hasName())
        assertTrue(moved.hasPosition())
        assertEquals(-3, moved.position)
        assertTrue(moved.hasMaxUsers())
        assertEquals(12, moved.maxUsers)
    }

    @Test
    fun update_rootCannotRename() {
        val root = Channel(id = 0, name = "Root", description = "", position = 1)
        val msg = ChannelModeration.update(
            root, name = "Nope", description = "hi", position = 1, maxUsers = 0,
        )!!
        assertFalse(msg.hasName())
        assertEquals("hi", msg.description)
    }

    @Test
    fun remove_setsChannelId() {
        val msg = ChannelModeration.remove(9)
        assertEquals(9, msg.channelId)
    }

    @Test
    fun addLink_setsChannelIdAndLinksAdd() {
        val msg = ChannelModeration.addLink(1, 4)
        assertEquals(1, msg.channelId)
        assertEquals(listOf(4), msg.linksAddList)
        assertEquals(0, msg.linksRemoveCount)
    }

    @Test
    fun unlinkAll_omitsEmpty() {
        assertNull(ChannelModeration.unlinkAll(1, emptyList()))
        val msg = ChannelModeration.unlinkAll(1, listOf(2, 3))!!
        assertEquals(1, msg.channelId)
        assertEquals(listOf(2, 3), msg.linksRemoveList)
    }
}
