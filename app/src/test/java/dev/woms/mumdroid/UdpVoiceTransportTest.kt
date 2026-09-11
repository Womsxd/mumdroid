package dev.woms.mumdroid

import dev.woms.mumdroid.core.net.UdpVoiceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the extracted datagram transport against a real loopback socket:
 * the receive loop, the TCP-peer source filter, and the write path.
 */
class UdpVoiceTransportTest {

    /** Collects transport callbacks. */
    private class Recorder : UdpVoiceTransport.Callbacks {
        val connected = CountDownLatch(1)
        val received = AtomicReference<ByteArray?>()
        val firstDatagram = CountDownLatch(1)
        val error = AtomicReference<String?>()
        var ticks = 0

        override fun onDatagram(data: ByteArray, length: Int) {
            received.set(data.copyOf(length))
            firstDatagram.countDown()
        }

        override fun onTick() {
            ticks++
        }

        override fun onConnected() {
            connected.countDown()
        }

        override fun onError(message: String) {
            error.set(message)
        }
    }

    @Test
    fun start_connectsAndDeliversDatagramsFromPeer() {
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            val recorder = Recorder()
            val transport = UdpVoiceTransport("127.0.0.1", peer.localPort)
            assertTrue(transport.start(bindAddress = null, remoteAddress = null, callbacks = recorder))
            assertTrue(recorder.connected.await(5, TimeUnit.SECONDS))
            assertTrue(transport.isRunning)

            peer.send(
                DatagramPacket(
                    byteArrayOf(9, 8, 7),
                    3,
                    InetAddress.getByName("127.0.0.1"),
                    transport.localPort,
                ),
            )
            assertTrue(recorder.firstDatagram.await(5, TimeUnit.SECONDS))
            assertEquals(listOf<Byte>(9, 8, 7), recorder.received.get()!!.toList())

            transport.close()
            assertFalse(transport.isRunning)
        } finally {
            peer.close()
        }
    }

    @Test
    fun send_writesBackToThePeer() {
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        peer.soTimeout = 5000
        try {
            val recorder = Recorder()
            val transport = UdpVoiceTransport("127.0.0.1", peer.localPort)
            transport.start(bindAddress = null, remoteAddress = null, callbacks = recorder)
            assertTrue(recorder.connected.await(5, TimeUnit.SECONDS))
            try {
                assertTrue(transport.send(byteArrayOf(1, 2, 3, 4), 4))
                val buffer = ByteArray(16)
                val packet = DatagramPacket(buffer, buffer.size)
                peer.receive(packet)
                assertEquals(listOf<Byte>(1, 2, 3, 4), buffer.take(packet.length))
            } finally {
                transport.close()
            }
        } finally {
            peer.close()
        }
    }

    @Test
    fun send_beforeStartReturnsFalse() {
        val transport = UdpVoiceTransport("127.0.0.1", 1)
        assertFalse(transport.send(byteArrayOf(1), 1))
    }

    @Test
    fun start_unresolvablePeerReportsError() {
        val recorder = Recorder()
        val transport = UdpVoiceTransport("invalid.invalid", 64738)
        assertFalse(transport.start(bindAddress = null, remoteAddress = null, callbacks = recorder))
        assertNotNull(recorder.error.get())
        assertFalse(transport.isRunning)
    }
}
