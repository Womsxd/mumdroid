package dev.woms.mumdroid.core.net

import android.os.SystemClock
import android.util.Log
import dev.woms.mumdroid.core.model.MumbleServer
import dev.woms.mumdroid.core.model.ServerPingInfo
import dev.woms.mumdroid.core.model.pingKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Plaintext UDP probe for the server list (no login, no OCB2).
 *
 * A server is always probed once when it first appears. Periodic re-pings
 * are opt-in (`autoServerPing`) so a phone does not keep waking the radio
 * unless the user asks.
 *
 * Packet choice matches official `ConnectDialog::sendPing`: both legacy
 * (v1) and protobuf (v2) while the version is unknown; afterwards only
 * the format that server speaks.
 *
 * DNS is bounded-parallel (8 concurrent) and budgeted so one hung
 * `getAllByName` cannot serialise the list or eat the UDP wait.
 * Each ping's timestamp is the send instant; the 2.5 s reply window starts
 * after the last send, not at probe entry. Replies are still collected on
 * one socket (official ConnectDialog), not a serial per-server wait.
 */
class ServerListPinger(
    private val onUpdate: (Map<String, ServerPingInfo>) -> Unit,
    private val knownInfo: (String) -> ServerPingInfo? = { null },
) {
    private val lock = Any()
    private val queued = LinkedHashMap<String, MumbleServer>()
    private val inFlight = HashSet<String>()
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Enqueue servers that still have no ping result and probe them once. */
    fun pingMissing(scope: CoroutineScope, servers: List<MumbleServer>) {
        enqueue(scope, servers)
    }

    /** Re-probe every server (used by the optional home-list auto ping). */
    fun pingAll(scope: CoroutineScope, servers: List<MumbleServer>) {
        enqueue(scope, servers)
    }

    private fun enqueue(scope: CoroutineScope, servers: List<MumbleServer>) {
        if (servers.isEmpty()) return
        synchronized(lock) {
            this.scope = scope
            for (server in servers) {
                val key = server.pingKey()
                if (key in inFlight || key in queued) continue
                queued[key] = server
            }
            if (queued.isNotEmpty() || inFlight.isNotEmpty() || job?.isActive == true) {
                _busy.value = true
            }
            if (queued.isEmpty() || job?.isActive == true) return
            job = scope.launch(Dispatchers.IO) { drain() }
        }
    }

    fun stop() {
        synchronized(lock) {
            queued.clear()
            inFlight.clear()
            scope = null
            job?.cancel()
            job = null
            _busy.value = false
        }
    }

    private suspend fun drain() {
        try {
            while (coroutineContext.isActive) {
                val batch = synchronized(lock) {
                    val take = queued.values.toList()
                    queued.clear()
                    inFlight.addAll(take.map { it.pingKey() })
                    take
                }
                if (batch.isEmpty()) break
                try {
                    probe(batch)
                } finally {
                    synchronized(lock) {
                        inFlight.removeAll(batch.map { it.pingKey() }.toSet())
                    }
                }
            }
        } finally {
            synchronized(lock) {
                val next = scope
                if (queued.isNotEmpty() && next != null) {
                    job = next.launch(Dispatchers.IO) { drain() }
                } else {
                    job = null
                    _busy.value = false
                }
            }
        }
    }

    private suspend fun probe(servers: List<MumbleServer>) {
        val socket = DatagramSocket()
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val recvBuf = ByteArray(1024)
        val secrets = HashMap<String, Long>()
        val addressToServer = HashMap<String, MutableSet<String>>()
        val pending = servers.map { it.pingKey() }.toMutableSet()
        // Servers that answered at least once (possibly only via the legacy
        // reply). Used so the unreachable fallback below does not clobber a
        // reachable result when the exact-version reply never shows up.
        val answered = HashSet<String>()
        val sent = HashSet<String>()
        try {
            resolveAndSend(servers, socket, secrets, addressToServer, pending, sent)
            if (pending.isEmpty()) return
            val deadline = SystemClock.elapsedRealtime() + REPLY_WAIT_MS
            while (coroutineContext.isActive && pending.isNotEmpty()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    val packet = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(packet)
                    handleReply(packet, secrets, addressToServer, pending, answered)
                } catch (_: SocketTimeoutException) {
                    // keep waiting until [deadline]
                } catch (_: IOException) {
                    delay(50)
                }
            }
            // Only servers that never answered at all are marked unreachable.
            // A server whose legacy reply arrived but whose protobuf reply is
            // still missing keeps its current info instead.
            val silent = pending - answered
            if (silent.isNotEmpty()) {
                val stamp = SystemClock.elapsedRealtime()
                onUpdate(silent.associateWith { unreachable(stamp) })
            }
        } finally {
            socket.close()
        }
    }

    /**
     * Resolves hosts with bounded parallelism and sends each ping as soon as
     * its DNS returns. Blocking `getAllByName` is not cancellable, so lookups
     * are siblings of this coroutine (not children): a [DNS_BUDGET_MS] cap
     * can return without joining hung lookups. Late completions are ignored.
     */
    private suspend fun resolveAndSend(
        servers: List<MumbleServer>,
        socket: DatagramSocket,
        secrets: MutableMap<String, Long>,
        addressToServer: MutableMap<String, MutableSet<String>>,
        pending: MutableSet<String>,
        sent: MutableSet<String>,
    ) {
        val dnsScope = synchronized(lock) { scope } ?: return
        if (servers.isEmpty()) return
        val results = Channel<DnsLookup>(Channel.UNLIMITED)
        val limiter = Semaphore(DNS_CONCURRENCY)
        val outstanding = AtomicInteger(servers.size)
        for (server in servers) {
            dnsScope.launch(Dispatchers.IO) {
                val host = server.host.trim()
                val addrs = if (host.isEmpty()) {
                    null
                } else {
                    limiter.withPermit { lookupHost(host) }
                }
                results.trySend(DnsLookup(server, addrs))
                if (outstanding.decrementAndGet() == 0) results.close()
            }
        }
        val dnsDeadline = SystemClock.elapsedRealtime() + DNS_BUDGET_MS
        while (coroutineContext.isActive) {
            val left = dnsDeadline - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            val lookup = withTimeoutOrNull(left) { results.receiveCatching().getOrNull() } ?: break
            applyLookup(lookup, socket, secrets, addressToServer, pending, sent)
        }
        // Close first so in-flight lookups cannot sneak a send after the drain;
        // already-queued completions are still receivable.
        results.close()
        while (true) {
            val leftover = results.tryReceive().getOrNull() ?: break
            applyLookup(leftover, socket, secrets, addressToServer, pending, sent)
        }
        val unresolved = pending.filter { it !in sent }
        if (unresolved.isNotEmpty()) {
            val stamp = SystemClock.elapsedRealtime()
            for (key in unresolved) pending.remove(key)
            onUpdate(unresolved.associateWith { unreachable(stamp) })
        }
    }

    private fun applyLookup(
        lookup: DnsLookup,
        socket: DatagramSocket,
        secrets: MutableMap<String, Long>,
        addressToServer: MutableMap<String, MutableSet<String>>,
        pending: MutableSet<String>,
        sent: MutableSet<String>,
    ) {
        val key = lookup.server.pingKey()
        if (key !in pending || key in sent) return
        val addrs = lookup.addrs
        if (addrs.isNullOrEmpty()) {
            pending.remove(key)
            onUpdate(mapOf(key to unreachable(SystemClock.elapsedRealtime())))
            return
        }
        sendToAddresses(
            socket, lookup.server, addrs,
            SystemClock.elapsedRealtime(),
            secrets, addressToServer,
        )
        sent.add(key)
    }

    private fun lookupHost(host: String): List<InetAddress>? {
        return try {
            InetAddress.getAllByName(host).toList().takeIf { it.isNotEmpty() }
        } catch (_: UnknownHostException) {
            null
        } catch (e: Exception) {
            Log.d(TAG, "DNS lookup failed for $host: ${e.message}")
            null
        }
    }

    private fun sendToAddresses(
        socket: DatagramSocket,
        server: MumbleServer,
        addrs: List<InetAddress>,
        sentAt: Long,
        secrets: MutableMap<String, Long>,
        addressToServer: MutableMap<String, MutableSet<String>>,
    ) {
        val key = server.pingKey()
        for (addr in addrs) {
            val addrKey = addressKey(addr, server.port)
            addressToServer.getOrPut(addrKey) { mutableSetOf() }.add(key)
            val secret = secrets.getOrPut(addrKey) { secureRandom.nextLong() }
            val token = sentAt xor secret
            val packets = ServerPingCodec.packetsToSend(knownInfo(key))
            for (packet in packets) {
                val payload = when (packet) {
                    ServerPingPacket.LEGACY -> ServerPingCodec.encodeLegacy(token)
                    ServerPingPacket.PROTOBUF -> ServerPingCodec.encodeProtobuf(token)
                }
                sendQuiet(socket, payload, addr, server.port)
            }
        }
    }

    private fun handleReply(
        packet: DatagramPacket,
        secrets: Map<String, Long>,
        addressToServer: Map<String, Set<String>>,
        pending: MutableSet<String>,
        answered: MutableSet<String>,
    ) {
        val decoded = ServerPingCodec.decode(packet.data.copyOf(packet.length)) ?: return
        val addrKey = addressKey(packet.address, packet.port)
        val secret = secrets[addrKey] ?: return
        val sentElapsed = decoded.timestamp xor secret
        val rtt = (SystemClock.elapsedRealtime() - sentElapsed).toInt()
        if (rtt !in 0..MAX_RTT_MS) return
        val keys = addressToServer[addrKey] ?: return
        val stamp = SystemClock.elapsedRealtime()
        val info = ServerPingInfo(
            pingMs = rtt,
            users = decoded.users,
            maxUsers = decoded.maxUsers,
            version = decoded.version,
            versionPrecise = decoded.versionIsV2,
            reachable = true,
            updatedAtMs = stamp,
        )
        val updates = LinkedHashMap<String, ServerPingInfo>(keys.size)
        for (key in keys) {
            answered.add(key)
            // The legacy 24-byte reply carries the saturated 16.8.8 version
            // (e.g. 1.5.857 -> 1.5.255). Only the protobuf reply carries the
            // exact v2 version, so keep listening for it instead of ending
            // the probe when the legacy reply wins the race. If it never
            // arrives, the timeout below leaves the legacy info in place
            // (better than nothing, mirrors one official ping round).
            if (decoded.versionIsV2) {
                pending.remove(key)
            }
            updates[key] = info
        }
        onUpdate(updates)
    }

    private fun sendQuiet(socket: DatagramSocket, payload: ByteArray, addr: InetAddress, port: Int) {
        try {
            socket.send(DatagramPacket(payload, payload.size, addr, port))
        } catch (_: IOException) {
            // IPv6 send on an IPv4-only path (or a firewalled dest) is expected.
        }
    }

    private fun addressKey(addr: InetAddress, port: Int): String {
        val host = addr.hostAddress?.substringBefore('%') ?: addr.hostName
        return "$host:$port"
    }

    private fun unreachable(now: Long) = ServerPingInfo(reachable = false, updatedAtMs = now)

    private data class DnsLookup(val server: MumbleServer, val addrs: List<InetAddress>?)

    companion object {
        private const val TAG = "ServerListPinger"
        /** How long to wait for replies after the last send. Then the socket is closed. */
        private const val REPLY_WAIT_MS = 2_500L
        /** Concurrent blocking DNS lookups. Matches a typical home-list size. */
        private const val DNS_CONCURRENCY = 8
        /**
         * Wall-clock cap for the DNS phase. `getAllByName` cannot be interrupted,
         * so hung lookups are abandoned (treated unreachable) rather than joined.
         */
        private const val DNS_BUDGET_MS = 3_000L
        private const val SOCKET_TIMEOUT_MS = 200
        private const val MAX_RTT_MS = 30_000
        private val secureRandom = SecureRandom()
    }
}
