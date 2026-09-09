package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.net.VoiceSendChannel

/**
 * The outgoing voice path: the "hold one frame back" utterance state machine
 * plus the UDP → TCP-tunnel send fallback.
 *
 * ## Why one frame is held back
 * Opus needs the *next* frame to know whether the current one is the last of
 * a talk spurt (the terminator flag rides the same packet), so the newest
 * captured frame is always kept pending until its successor arrives. The
 * previously held frame is emitted on every [sendVoice]; [terminate] flushes
 * the pending frame (or encodes a frame of digital silence for an empty
 * utterance) as the end-of-transmission packet.
 *
 * ## Send path
 * Each encoded frame is stamped into a plaintext body via
 * [VoiceSendChannel.buildTunnelPacket] (allocating the frame number), then
 * sent as an OCB2-encrypted UDP datagram. If that write fails (socket closed,
 * network switch, force-TCP) the SAME body is tunneled over TCP — mirroring
 * official `ServerHandler::sendMessage` (`TcpModeEnabled || !bUdp` →
 * UDPTunnel) so the sequence number is never allocated twice.
 */
internal class VoiceTransmitter(
    private val channel: () -> VoiceSendChannel?,
    private val useTcp: () -> Boolean,
    private val sendTunneled: (ByteArray) -> Unit,
) {
    private var pendingVoicePcm: ShortArray? = null
    private var pendingVoiceFrames = 0
    private var voiceUtteranceOpen = false

    /**
     * Hands a captured PCM frame to the send pipeline. The first frame of an
     * utterance resets the Opus encoder; the previously held frame is emitted
     * and [pcm] becomes the new pending frame. [isLastFrame] flushes the
     * pipeline as the final packet of the utterance.
     */
    fun sendVoice(pcm: ShortArray, isLastFrame: Boolean = false) {
        if (!voiceUtteranceOpen) {
            channel()?.resetEncoder()
            voiceUtteranceOpen = true
        }
        val held = pendingVoicePcm
        val heldFrames = pendingVoiceFrames
        pendingVoicePcm = pcm.copyOf()
        pendingVoiceFrames = OpusCodec.encodedTenMsFrames(pcm.size).coerceAtLeast(1)
        if (held != null) {
            emitVoicePcm(held, isLastFrame = false, heldFrames)
        }
        if (isLastFrame) flushPendingVoice(isLastFrame = true)
    }

    /**
     * Ends the current utterance: flushes the pending frame as the
     * end-of-transmission packet (or encodes one frame of digital silence
     * when nothing is pending). Safe to call while idle.
     */
    fun terminate() {
        flushPendingVoice(isLastFrame = true)
    }

    private fun emitVoicePcm(pcm: ShortArray, isLastFrame: Boolean, frameCount: Int) {
        val udpManager = channel() ?: return
        val encoded = udpManager.encodeOpus(pcm) ?: return
        sendEncodedVoice(udpManager, encoded, isLastFrame, frameCount)
    }

    private fun flushPendingVoice(isLastFrame: Boolean) {
        val last = pendingVoicePcm
        val frames = pendingVoiceFrames
        pendingVoicePcm = null
        pendingVoiceFrames = 0
        voiceUtteranceOpen = false
        if (last != null) {
            emitVoicePcm(last, isLastFrame, frames)
        } else if (isLastFrame) {
            val udpManager = channel() ?: return
            val encoded = udpManager.encodeSilence() ?: return
            sendEncodedVoice(udpManager, encoded.first, isLastFrame = true, encoded.second)
        }
    }

    private fun sendEncodedVoice(
        udpManager: VoiceSendChannel,
        encoded: ByteArray,
        isLastFrame: Boolean,
        frameCount: Int,
    ) {
        val body = udpManager.buildTunnelPacket(encoded, isLastFrame, frameCount)
        // Official ServerHandler::sendMessage: TcpModeEnabled || !bUdp →
        // UDPTunnel. A failed datagram write (socket closed, network switch)
        // is the same as !bUdp for this packet — reuse the already-stamped
        // body so the sequence number is not allocated twice.
        val sentUdp = !useTcp() &&
            udpManager.isRunning &&
            udpManager.isCryptoReady() &&
            udpManager.sendPlaintextUdp(body)
        if (!sentUdp) {
            sendTunneled(body)
        }
    }
}
