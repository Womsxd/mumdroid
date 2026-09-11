package dev.woms.mumdroid.core.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The datagram transport of the UDP voice channel: socket lifecycle, the
 * receive loop with TCP-peer source filtering, and the datagram write path.
 *
 * Split out of [UdpVoiceManager] so the manager keeps only the protocol policy
 * (Opus codec, OCB2 crypto, framing, ping statistics) while all socket handling
 * lives here. The manager drives the loop through [Callbacks].
 */
internal class UdpVoiceTransport(
    private val host: String,
    private val port: Int,
) {
    companion object {
        private const val TAG = "UdpVoiceManager"

        /** Matches the official `MAX_UDP_PACKET_SIZE` (murmur/MumbleProtocol.h):
         *  1024. A larger bound would let a spoofed oversized datagram be read
         *  in full and pushed through OCB2, which costs one AES block op per
         *  16-byte block (256 AES for 4096 B vs. 64 for 1024 B). Aligning with
         *  the server also keeps behaviour identical: murmur drops any packet
         *  with `len > MAX_UDP_PACKET_SIZE`. */
        internal const val MAX_PACKET = 1024
        private const val RECEIVE_POLL_MS = 250

        /**
         * Official `udpReady` drops datagrams whose source is not the TCP
         * peer (`HostAddress` equality, which treats IPv4-mapped IPv6 as IPv4).
         */
        internal fun peerMatches(
            packetAddr: InetAddress,
            packetPort: Int,
            peerAddr: InetAddress,
            peerPort: Int,
        ): Boolean {
            if (packetPort != peerPort) return false
            if (packetAddr == peerAddr) return true
            val a = ipv4Bytes(packetAddr) ?: return false
            val b = ipv4Bytes(peerAddr) ?: return false
            return a.contentEquals(b)
        }

        private fun ipv4Bytes(addr: InetAddress): ByteArray? {
            when (addr) {
                is Inet4Address -> return addr.address
                is Inet6Address -> {
                    val bytes = addr.address
                    if (bytes.size != 16) return null
                    for (i in 0..9) if (bytes[i] != 0.toByte()) return null
                    if (bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()) return null
                    return bytes.copyOfRange(12, 16)
                }
            }
            return null
        }
    }

    /** Hooks the transport invokes from its receive loop. */
    interface Callbacks {
        /** A datagram that passed the peer filter. [length] bytes of [data] are valid. */
        fun onDatagram(data: ByteArray, length: Int)

        /** Called on the loop thread before each blocking receive. */
        fun onTick()

        /** The socket is up. */
        fun onConnected()

        /** The socket failed to open, or the loop ended with an error. */
        fun onError(message: String)
    }

    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null

    /** TCP peer used for `sendto` / source filtering. Official does not `connect()`. */
    @Volatile
    private var peerAddress: InetAddress? = null

    @Volatile
    private var peerPort: Int = 0

    /** Sets the QoS traffic class on the socket. Read once, at [start]. */
    @Volatile
    var qualityOfService: Boolean = false

    /** Whether the receive loop is active. */
    val isRunning: Boolean get() = running.get()

    /**
     * The bound local port, or 0 while the socket is not open. Reported to the
     * server's admin view as the client's UDP source port.
     */
    val localPort: Int get() = socket?.localPort ?: 0

    /**
     * Opens the datagram socket and starts the receive loop. When [bindAddress]
     * is set (the TCP socket's local address) the socket is bound to the same
     * interface, matching the official `bUdpForceTcpAddr` default.
     *
     * @return false when the socket could not be opened; [Callbacks.onError]
     *   has then been invoked with the reason.
     */
    fun start(
        bindAddress: InetAddress?,
        remoteAddress: InetAddress?,
        callbacks: Callbacks,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return true
        try {
            val sock = DatagramSocket(null)
            if (bindAddress != null && !bindAddress.isAnyLocalAddress) {
                sock.bind(InetSocketAddress(bindAddress, 0))
            } else {
                sock.bind(InetSocketAddress(0))
            }
            // Official `QUdpSocket::writeDatagram` — never connect(). A
            // connected DatagramSocket turns ICMP errors into receive()
            // exceptions and drops replies that are IPv4-mapped.
            val dest = if (remoteAddress != null) {
                InetSocketAddress(remoteAddress, port)
            } else {
                InetSocketAddress(host, port)
            }
            val resolved = dest.address ?: throw IllegalStateException("UDP peer unresolved")
            peerAddress = resolved
            peerPort = dest.port
            socket = sock
            if (qualityOfService) {
                try {
                    sock.trafficClass = 0xE0
                } catch (_: Exception) {
                    try {
                        sock.trafficClass = 0x80
                    } catch (_: Exception) {
                    }
                }
            }
            receiveThread = Thread({ receiveLoop(callbacks) }, "udp-voice").apply { start() }
            callbacks.onConnected()
            return true
        } catch (e: Exception) {
            running.set(false)
            peerAddress = null
            peerPort = 0
            closeSocket()
            callbacks.onError(e.message ?: "UDP connect failed")
            return false
        }
    }

    private fun receiveLoop(callbacks: Callbacks) {
        val sock = socket ?: return
        try {
            sock.soTimeout = RECEIVE_POLL_MS
        } catch (_: Exception) {
        }
        val buffer = ByteArray(MAX_PACKET)
        // Do not ping until `receive()` has run: a reply that lands before
        // that is dropped by the kernel and desyncs OCB2.
        var receivePrimed = false
        while (running.get()) {
            if (receivePrimed) callbacks.onTick()
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                receivePrimed = true
                val from = packet.address ?: continue
                val expected = peerAddress
                if (expected == null || !peerMatches(from, packet.port, expected, peerPort)) {
                    continue
                }
                try {
                    callbacks.onDatagram(packet.data, packet.length)
                } catch (e: Exception) {
                    Log.e(TAG, "UDP packet processing error", e)
                }
            } catch (_: SocketTimeoutException) {
                receivePrimed = true
            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(TAG, "UDP receive error", e)
                if (sock.isClosed) {
                    callbacks.onError(e.message ?: "UDP socket closed")
                    break
                }
            }
        }
    }

    /** Writes an already-encrypted datagram to the peer. */
    fun send(packetData: ByteArray, length: Int = packetData.size): Boolean {
        val sock = socket ?: return false
        val dest = peerAddress ?: return false
        return try {
            sock.send(DatagramPacket(packetData, length, dest, peerPort))
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP send error", e)
            false
        }
    }

    /**
     * Stops the loop and closes the socket, joining the receive thread so a
     * caller may immediately reopen the transport (UDP→TCP fallback and back).
     */
    fun close() {
        running.set(false)
        peerAddress = null
        peerPort = 0
        val thread = receiveThread
        closeSocket()
        receiveThread = null
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (_: Exception) {
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
