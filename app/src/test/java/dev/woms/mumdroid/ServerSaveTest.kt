package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.ServerAddress
import dev.woms.mumdroid.data.ServerSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSaveTest {

    @Test
    fun add_alwaysInsertsEvenIfAddressAlreadySaved() {
        val plan = ServerSave.plan(editingId = 0)
        assertTrue(plan.isInsert)
    }

    @Test
    fun edit_updatesThatFavoriteInPlace() {
        assertEquals(ServerSave.Plan(updateId = 7), ServerSave.plan(editingId = 7))
    }

    @Test
    fun tokenHost_isCaseInsensitive() {
        assertEquals("voice.example.com", ServerAddress.normalizeHost("  Voice.Example.COM "))
    }
}
