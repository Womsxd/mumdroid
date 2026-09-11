package dev.woms.mumdroid

import dev.woms.mumdroid.ui.screen.settings.CertificateFormatting
import dev.woms.mumdroid.ui.screen.settings.DeleteConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The certificate screens keep two rules outside the composables: how a
 * certificate's subject/validity is rendered, and the two-step delete
 * confirmation. Both are exercised here.
 */
class CertificateDeletionTest {

    // ---- CertificateFormatting ----

    @Test
    fun displaySubject_stripsCnPrefix() {
        assertEquals("Alice", CertificateFormatting.displaySubject("CN=Alice"))
    }

    @Test
    fun displaySubject_stripsOnlyTheLeadingCnPrefix() {
        assertEquals("Bob,OU=Team", CertificateFormatting.displaySubject("CN=Bob,OU=Team"))
        assertEquals("Alice", CertificateFormatting.displaySubject("Alice"))
    }

    @Test
    fun formatEpoch_rendersIsoDate() {
        // 1714979289000 = 2024-05-06 UTC; the formatter uses the device zone,
        // so only the shape is asserted.
        val rendered = CertificateFormatting.formatEpoch(1714979289000)
        assertTrue(rendered.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun formatEpoch_blankForMissingMetadata() {
        assertEquals("-", CertificateFormatting.formatEpoch(0))
        assertEquals("-", CertificateFormatting.formatEpoch(-1))
    }

    // ---- DeleteConfirmation ----

    private val certA = "fp-a"
    private val certB = "fp-b"

    @Test
    fun startsIdle() {
        val state = DeleteConfirmation<String>()
        assertNull(state.candidate)
        assertFalse(state.requiresSecondStep)
    }

    @Test
    fun request_armsFirstStep() {
        val state = DeleteConfirmation<String>().request(certA)
        assertEquals(certA, state.candidate)
        assertFalse("first tap must not already be the second step", state.requiresSecondStep)
    }

    @Test
    fun advance_firstStep_keepsCandidateAndAsksAgain() {
        val (next, deleted) = DeleteConfirmation<String>().request(certA).advance()
        assertEquals(certA, next.candidate)
        assertTrue(next.requiresSecondStep)
        assertNull("must not delete before the second confirmation", deleted)
    }

    @Test
    fun advance_secondStep_yieldsItemAndResets() {
        val armed = DeleteConfirmation<String>().request(certA).advance().first
        val (next, deleted) = armed.advance()
        assertEquals(certA, deleted)
        assertNull("state must reset after a deletion", next.candidate)
        assertFalse(next.requiresSecondStep)
    }

    @Test
    fun advance_withoutCandidate_isNoop() {
        val (next, deleted) = DeleteConfirmation<String>().advance()
        assertNull(next.candidate)
        assertNull(deleted)
    }

    @Test
    fun dismiss_clearsPendingDeletion() {
        val state = DeleteConfirmation<String>().request(certB).dismiss()
        assertNull(state.candidate)
        assertFalse(state.requiresSecondStep)
    }

    @Test
    fun request_afterSecondStep_rearmsFromFirstStep() {
        // Confirming cert A once, then asking for cert B, must start B's
        // confirmation from the first step again.
        val armed = DeleteConfirmation<String>().request(certA).advance().first
        val restarted = armed.request(certB)
        assertEquals(certB, restarted.candidate)
        assertFalse(restarted.requiresSecondStep)
    }
}
