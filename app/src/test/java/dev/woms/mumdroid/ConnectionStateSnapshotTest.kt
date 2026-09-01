package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ChatMessage
import dev.woms.mumdroid.core.model.User
import dev.woms.mumdroid.ui.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConnectionStateSnapshotTest {

    @Test
    fun fieldPatch_keepsOtherReferences() {
        val users = listOf(User(session = 1, name = "a"))
        val before = ConnectionState(connected = true, users = users)
        val after = before.copy(
            chatMessages = listOf(ChatMessage(text = "hi", timestamp = 1L)),
        )
        assertSame(users, after.users)
        assertEquals(true, after.connected)
    }

    @Test
    fun identicalPatch_equalsOriginal() {
        val state = ConnectionState(connected = true, status = "ok")
        assertEquals(state, state.copy(status = "ok"))
    }
}
