package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.CertificateDecision
import dev.woms.mumdroid.core.net.CertificateGate
import org.junit.Assert.assertEquals
import org.junit.Test

class CertificateGateTest {

    @Test
    fun resolve_deliversDecision() {
        val gate = CertificateGate(promptTimeoutSeconds = 5)
        gate.open()
        gate.resolve(CertificateDecision.UPDATE_PIN)
        assertEquals(CertificateDecision.UPDATE_PIN, gate.await())
    }

    @Test
    fun abort_releasesPendingWaitWithReject() {
        val gate = CertificateGate(promptTimeoutSeconds = 60)
        gate.open()
        gate.abort()
        assertEquals(CertificateDecision.REJECT, gate.await())
    }

    @Test
    fun timeout_isTreatedAsReject() {
        val gate = CertificateGate(promptTimeoutSeconds = 0)
        gate.open()
        assertEquals(CertificateDecision.REJECT, gate.await())
    }

    @Test
    fun resolve_withoutOpenPrompt_isIgnored() {
        val gate = CertificateGate(promptTimeoutSeconds = 0)
        gate.resolve(CertificateDecision.TRUST_ONCE)
        assertEquals(CertificateDecision.REJECT, gate.await())
    }

    @Test
    fun secondResolve_isIgnored() {
        val gate = CertificateGate(promptTimeoutSeconds = 5)
        gate.open()
        gate.resolve(CertificateDecision.TRUST_ONCE)
        gate.resolve(CertificateDecision.UPDATE_PIN)
        assertEquals(CertificateDecision.TRUST_ONCE, gate.await())
    }
}
