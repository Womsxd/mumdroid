package dev.woms.mumdroid

import dev.woms.mumdroid.service.SelfMuteDeafController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfMuteDeafControllerTest {

    private val muteEvents = mutableListOf<Boolean>()
    private val deafenEvents = mutableListOf<Boolean>()

    private fun controller() = SelfMuteDeafController(
        onMuteChanged = { muteEvents.add(it) },
        onDeafenChanged = { deafenEvents.add(it) },
    )

    @Test
    fun muteNotifiesOnlyMute() {
        val c = controller()
        assertTrue(c.toggleMute())
        assertTrue(c.isBlocked)
        assertEquals(listOf(true), muteEvents)
        assertTrue(deafenEvents.isEmpty())
    }

    @Test
    fun deafenNotifiesMuteThenDeafen() {
        val c = controller()
        assertTrue(c.toggleDeafen())
        assertEquals(listOf(true), muteEvents)
        assertEquals(listOf(true), deafenEvents)
        assertTrue(c.deafenedValue)
    }

    @Test
    fun undeafenRestoresMicrophoneAndNotifiesBoth() {
        val c = controller()
        c.toggleDeafen()
        muteEvents.clear()
        deafenEvents.clear()
        assertFalse(c.toggleDeafen())
        assertEquals(listOf(false), muteEvents)
        assertEquals(listOf(false), deafenEvents)
        assertFalse(c.isBlocked)
    }

    @Test
    fun clearResetsWithoutNotifying() {
        val c = controller()
        c.toggleDeafen()
        muteEvents.clear()
        deafenEvents.clear()
        c.clear()
        assertFalse(c.isBlocked)
        assertTrue(muteEvents.isEmpty())
        assertTrue(deafenEvents.isEmpty())
    }
}
