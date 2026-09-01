package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.LastChannelRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastChannelRestoreTest {

    private val root = Channel(id = 0, name = "Root")
    private val lobby = Channel(id = 3, name = "Lobby")
    private val games = Channel(id = 7, name = "Games")
    private val channels = listOf(root, lobby, games)

    @Test
    fun missingMemory_staysOnServerAssignment() {
        assertNull(LastChannelRestore.resolve(null, 0, channels))
    }

    @Test
    fun knownId_rejoinsWhenServerPutUsElsewhere() {
        val remembered = LastChannelRestore.Target(id = 7, name = "Games")
        assertEquals(7, LastChannelRestore.resolve(remembered, 0, channels))
    }

    @Test
    fun alreadyThere_doesNotJoinAgain() {
        val remembered = LastChannelRestore.Target(id = 7, name = "Games")
        assertNull(LastChannelRestore.resolve(remembered, 7, channels))
    }

    @Test
    fun deletedChannel_fallsBackToUniqueName() {
        val remembered = LastChannelRestore.Target(id = 99, name = "Lobby")
        assertEquals(3, LastChannelRestore.resolve(remembered, 0, channels))
    }

    @Test
    fun deletedChannel_ambiguousName_staysPut() {
        val twins = channels + Channel(id = 8, name = "Lobby")
        val remembered = LastChannelRestore.Target(id = 99, name = "Lobby")
        assertNull(LastChannelRestore.resolve(remembered, 0, twins))
    }

    @Test
    fun deletedChannel_unknownName_staysPut() {
        val remembered = LastChannelRestore.Target(id = 99, name = "Gone")
        assertNull(LastChannelRestore.resolve(remembered, 0, channels))
    }

    @Test
    fun emptyTree_staysPut() {
        val remembered = LastChannelRestore.Target(id = 7, name = "Games")
        assertNull(LastChannelRestore.resolve(remembered, 0, emptyList()))
    }
}
