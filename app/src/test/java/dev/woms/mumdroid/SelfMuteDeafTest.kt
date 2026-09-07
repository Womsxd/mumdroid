package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.SelfMuteDeaf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfMuteDeafTest {

    @Test
    fun muteDoesNotDeafen() {
        val next = SelfMuteDeaf().toggleMute()
        assertTrue(next.muted)
        assertFalse(next.deafened)
    }

    @Test
    fun unmuteWhileDeafenedAlsoUndeafens() {
        val next = SelfMuteDeaf(muted = true, deafened = true).toggleMute()
        assertEquals(SelfMuteDeaf(), next)
    }

    @Test
    fun deafenAlsoMutes() {
        val next = SelfMuteDeaf().toggleDeafen()
        assertTrue(next.muted)
        assertTrue(next.deafened)
        assertTrue(next.unmuteOnUndeaf)
    }

    @Test
    fun undeafenAfterAutoMuteRestoresMicrophone() {
        val next = SelfMuteDeaf().toggleDeafen().toggleDeafen()
        assertEquals(SelfMuteDeaf(), next)
    }

    @Test
    fun undeafenAfterManualMuteKeepsMicrophoneOff() {
        val next = SelfMuteDeaf().toggleMute().toggleDeafen().toggleDeafen()
        assertTrue(next.muted)
        assertFalse(next.deafened)
        assertFalse(next.unmuteOnUndeaf)
    }
}
