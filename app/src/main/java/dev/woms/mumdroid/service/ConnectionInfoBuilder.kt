package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.net.MumbleClient

/** Assembles the desktop-style server information snapshot. */
internal fun buildServerConnectionInfo(
    live: Boolean,
    host: String,
    port: Int,
    userCount: Int,
    maxUsers: Int,
    client: MumbleClient?,
    voice: VoiceSession,
    tcp: TcpPingStats,
    forceTcp: Boolean,
): ServerConnectionInfo {
    val c = if (live) client else null
    val udp = if (live) voice.udp else null
    val local = udp?.packetStats()
    val tcpAvg = if (live) tcp.averageMs else 0f
    val tcpVar = if (live) tcp.variance else 0f
    val tls = ServerConnectionInfo.formatTlsProtocol(c?.tlsProtocol.orEmpty())
    val cipher = c?.tlsCipherSuite.orEmpty()
    return ServerConnectionInfo(
        host = host,
        port = port,
        userCount = if (live) userCount else 0,
        maxUsers = if (live) maxUsers else 0,
        protocol = ServerConnectionInfo.formatProtocol(
            c?.serverVersionV2 ?: 0L,
            c?.serverVersionLegacy ?: 0,
        ),
        release = c?.serverRelease.orEmpty(),
        os = c?.serverOs.orEmpty(),
        osVersion = c?.serverOsVersion.orEmpty(),
        currentBandwidthBps = voice.currentBandwidthBps(),
        allowedBandwidthBps = if (live) voice.serverMaxBandwidthBps else 0,
        forceTcp = forceTcp,
        udpFallback = voice.udpFallback(forceTcp, live),
        hasUdpLatency = (udp?.udpPingCount ?: 0) > 0,
        udpLatencyMs = (udp?.averageUdpPing ?: 0L).toFloat(),
        udpLatencyVariance = udp?.udpPingVariance ?: 0f,
        udpGoodIn = local?.good ?: 0,
        udpLateIn = local?.late ?: 0,
        udpLostIn = local?.lost ?: 0,
        udpResyncIn = local?.resync ?: 0,
        udpGoodOut = c?.remoteCryptGood ?: 0,
        udpLateOut = c?.remoteCryptLate ?: 0,
        udpLostOut = c?.remoteCryptLost ?: 0,
        udpResyncOut = c?.remoteCryptResync ?: 0,
        tlsVersion = tls,
        cipherSuite = cipher,
        hasTcpLatency = live && tcp.sampleCount > 0,
        tcpLatencyMs = tcpAvg,
        tcpLatencyVariance = tcpVar,
        perfectForwardSecrecy = c?.let {
            if (it.tlsProtocol.isEmpty() && it.tlsCipherSuite.isEmpty()) {
                null
            } else {
                ServerConnectionInfo.usesPerfectForwardSecrecy(it.tlsProtocol, it.tlsCipherSuite)
            }
        },
        certificateFingerprint = c?.serverFingerprint.orEmpty(),
    )
}
