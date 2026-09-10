package dev.woms.mumdroid

import dev.woms.mumdroid.core.crypto.CryptState
import dev.woms.mumdroid.core.crypto.UdpVoiceCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpVoiceCryptoTest {

    // Monotonic fake clock; starts away from 0 so the armed-time sentinel
    // (cryptoReadyMs == 0 means "not armed") does not swallow the baseline.
    private var nowMs = 100_000L

    private val key = ByteArray(16) { it.toByte() }

    /**
     * Two *distinct* nonces, like a real CryptSetup handshake. They must not be
     * equal: `setup` arms the encrypt nonce to `clientNonce` and the decrypt
     * nonce to `serverNonce`, and a state can never decrypt what it produced
     * itself (its own encrypt nonce is not its decrypt nonce).
     */
    private val clientNonce = ByteArray(16) { 0x11 }
    private val serverNonce = ByteArray(16) { 0x42 }

    private fun armedCrypto(): UdpVoiceCrypto {
        val crypto = UdpVoiceCrypto(clock = { nowMs })
        crypto.setup(key = key, clientNonce = clientNonce, serverNonce = serverNonce)
        return crypto
    }

    /**
     * Builds a datagram that the test's [UdpVoiceCrypto] will accept on its
     * decrypt path: a peer state encrypts using *our* decrypt nonce
     * (`serverNonce`) as its encrypt nonce, which is exactly the CryptSetup
     * pairing. Encrypting with [UdpVoiceCrypto] itself cannot work — see the
     * note on [clientNonce]/[serverNonce] above.
     */
    private fun peerPacket(payload: ByteArray): ByteArray {
        val peer = CryptState().apply { assertTrue(setKey(key, serverNonce, clientNonce)) }
        return peer.encrypt(payload)!!
    }

    @Test
    fun setup_armsCryptoAndSnapshotsEncryptIv() {
        val crypto = armedCrypto()
        assertTrue(crypto.isReady)
        val iv = crypto.encryptIV()
        assertNotNull(iv)
        assertEquals(16, iv!!.size)
    }

    @Test
    fun setup_rejectsInvalidKeyWithoutSideEffects() {
        val crypto = UdpVoiceCrypto(clock = { nowMs })
        crypto.setup(ByteArray(15), ByteArray(16), ByteArray(16))
        assertFalse(crypto.isReady)
        assertNull(crypto.encryptIV())
    }

    @Test
    fun decrypt_roundTripsAndRejectsTampering() {
        val crypto = armedCrypto()
        val payload = ByteArray(40) { it.toByte() }
        val packet = peerPacket(payload)
        val plain = crypto.decrypt(packet, 0, packet.size)
        assertNotNull(plain)
        assertArrayEquals(payload, plain)

        val bad = packet.copyOf()
        bad[1] = (bad[1].toInt() xor 1).toByte()
        assertNull(crypto.decrypt(bad, 0, bad.size))
    }

    @Test
    fun resyncDecryptIV_countsNonceResyncsOnly() {
        val crypto = armedCrypto()
        assertEquals(0, crypto.packetStats().resync)
        assertTrue(crypto.resyncDecryptIV(ByteArray(16) { 6 }))
        assertEquals(1, crypto.packetStats().resync)
        assertFalse(crypto.resyncDecryptIV(ByteArray(8)))
        assertEquals(1, crypto.packetStats().resync)
    }

    @Test
    fun decrypt_failuresForFiveSecondsRequestResyncOnce() {
        val crypto = armedCrypto()
        var resyncRequests = 0
        crypto.onRequestCryptResync = { resyncRequests++ }

        val packet = peerPacket(ByteArray(40))
        packet[1] = (packet[1].toInt() xor 1).toByte() // break the OCB2 tag

        // Within the official 5-second window: failures stay silent.
        nowMs += 4_999
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(0, resyncRequests)

        // Past the window (baseline = armed time, official tLastGood): one request.
        nowMs += 2
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(1, resyncRequests)

        // Repeat failures inside the suppression window: no further requests.
        nowMs += 1_000
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(1, resyncRequests)

        // A successful decrypt re-arms the rule.
        val goodPacket = peerPacket(ByteArray(40))
        nowMs += 1_000
        assertNotNull(crypto.decrypt(goodPacket, 0, goodPacket.size))
        nowMs += 5_001
        packet[2] = (packet[2].toInt() xor 1).toByte()
        assertNull(crypto.decrypt(packet, 0, packet.size))
        assertEquals(2, resyncRequests)
    }

    @Test
    fun reset_wipesCryptoState() {
        val crypto = armedCrypto()
        assertTrue(crypto.isReady)
        crypto.reset()
        assertFalse(crypto.isReady)
        assertEquals(0, crypto.packetStats().good)
    }
}
