package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.VoiceSendChannel
import dev.woms.mumdroid.service.VoiceTransmitter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTransmitterTest {

    /** Deterministic VoiceSendChannel fake: encoded bytes are 1-byte counters. */
    private class FakeChannel : VoiceSendChannel {
        var encoderResets = 0
        var silenceEncodes = 0
        var running = false
        var cryptoReady = false
        var udpSendResult = false
        val udpBodies = mutableListOf<ByteArray>()
        private var encodeSeq = 0

        override fun encodeOpus(pcm: ShortArray): ByteArray {
            encodeSeq++
            return byteArrayOf(encodeSeq.toByte())
        }

        override fun resetEncoder() {
            encoderResets++
        }

        override fun encodeSilence(): Pair<ByteArray, Int> {
            silenceEncodes++
            return byteArrayOf(0) to 1
        }

        override fun buildTunnelPacket(
            payload: ByteArray,
            isLastFrame: Boolean,
            frameCount: Int,
        ): ByteArray = payload.copyOf() + byteArrayOf(if (isLastFrame) 1 else 0)

        override val isRunning: Boolean get() = running

        override fun isCryptoReady(): Boolean = cryptoReady

        override fun sendPlaintextUdp(body: ByteArray): Boolean {
            udpBodies.add(body)
            return udpSendResult
        }
    }

    private var channel: FakeChannel? = FakeChannel()
    private var useTcp = false
    private val tunneled = mutableListOf<ByteArray>()

    private fun pcm(tag: Short) = ShortArray(960) { tag }

    private fun transmitter() = VoiceTransmitter(
        channel = { this.channel },
        useTcp = { this.useTcp },
        sendTunneled = { tunneled.add(it) },
    )

    private fun tunnelPayload(body: ByteArray): Byte = body[body.size - 2]
    private fun tunnelIsLast(body: ByteArray): Boolean = body.last() == 1.toByte()

    @Test
    fun firstFrameIsHeldBack() {
        val t = transmitter()
        t.sendVoice(pcm(1))
        assertTrue(tunneled.isEmpty())
        t.sendVoice(pcm(2))
        // The held first frame (encode seq 1, not last) is emitted now.
        assertEquals(1, tunneled.size)
        assertEquals(1.toByte(), tunnelPayload(tunneled[0]))
        assertTrue(!tunnelIsLast(tunneled[0]))
    }

    @Test
    fun terminateFlushesPendingAsLastAndSendsSilenceWhenIdle() {
        val t = transmitter()
        t.sendVoice(pcm(1))
        t.terminate()
        assertEquals(1, tunneled.size)
        assertTrue(tunnelIsLast(tunneled[0]))
        // A second terminate with nothing pending emits digital silence.
        t.terminate()
        assertEquals(2, tunneled.size)
        assertEquals(1, channel!!.silenceEncodes)
        assertTrue(tunnelIsLast(tunneled[1]))
    }

    @Test
    fun encoderResetsOncePerUtterance() {
        val t = transmitter()
        t.sendVoice(pcm(1))
        t.sendVoice(pcm(2))
        t.sendVoice(pcm(3))
        assertEquals(1, channel!!.encoderResets)
        t.terminate()
        t.sendVoice(pcm(4))
        assertEquals(2, channel!!.encoderResets)
    }

    @Test
    fun missingChannelDropsSilently() {
        channel = null
        val t = transmitter()
        t.sendVoice(pcm(1))
        t.sendVoice(pcm(2))
        t.terminate()
        assertTrue(tunneled.isEmpty())
    }

    @Test
    fun forceTcpTunnelsWithoutUdpAttempt() {
        useTcp = true
        channel!!.running = true
        channel!!.cryptoReady = true
        val t = transmitter()
        t.sendVoice(pcm(1))
        t.sendVoice(pcm(2))
        assertEquals(1, tunneled.size)
        assertTrue(channel!!.udpBodies.isEmpty())
    }

    @Test
    fun udpFailureFallsBackToTunnelWithSameBody() {
        channel!!.running = true
        channel!!.cryptoReady = true
        channel!!.udpSendResult = false
        val t = transmitter()
        t.sendVoice(pcm(1))
        t.sendVoice(pcm(2))
        // One stamped body: tried over UDP, then reused for the tunnel —
        // the frame number must not be allocated twice.
        assertEquals(1, channel!!.udpBodies.size)
        assertEquals(1, tunneled.size)
        assertArrayEquals(channel!!.udpBodies[0], tunneled[0])
    }

    @Test
    fun udpSuccessSkipsTunnel() {
        channel!!.running = true
        channel!!.cryptoReady = true
        channel!!.udpSendResult = true
        val t = transmitter()
        t.sendVoice(pcm(1))
        t.sendVoice(pcm(2))
        assertEquals(1, channel!!.udpBodies.size)
        assertTrue(tunneled.isEmpty())
    }
}
