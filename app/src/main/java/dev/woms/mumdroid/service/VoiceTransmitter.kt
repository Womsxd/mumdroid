package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.audio.OpusCodec
import dev.woms.mumdroid.core.model.VoiceTargetId
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
 * ## Voice targets
 * Every packet carries the voice-target id in effect when its frame was
 * captured, including the terminator: the pending frame keeps its own id, so
 * ending a whisper (or switching to a different target) mid-sentence still
 * delivers the closing packet to the whisper receivers instead of leaking it to
 * the channel. No global "previous target" has to be maintained for that — the
 * official client only needs one because its target is reset by the same key
 * release that flushes the terminator.
 *
 * A target that resolves to nobody is reported as [VoiceTargetId.NONE] and
 * makes this class drop the audio instead of broadcasting it.
 *
 * ## Local self-test
 * While [withholdAudio] reports true (the local loopback mode) the microphone
 * audio is played back on this device by the caller, so not a single packet may
 * leave it: even the end-of-transmission packet and a stale pending frame are
 * dropped, otherwise ending the self-test would flush previously captured audio
 * to the channel.
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
    /**
     * Voice-target id for a new outgoing frame: 0 = regular speech, 1..30 a
     * registered shout/whisper target, [VoiceTargetId.NONE] = unresolvable.
     */
    private val targetId: () -> Int = { VoiceTargetId.REGULAR_SPEECH },
    /**
     * True while audio must not reach the network at all (local loopback
     * self-test). The caller plays such frames back locally instead.
     */
    private val withholdAudio: () -> Boolean = { false },
) {
    private var pendingVoicePcm: ShortArray? = null
    private var pendingVoiceFrames = 0
    private var pendingVoiceTarget = VoiceTargetId.REGULAR_SPEECH
    private var voiceUtteranceOpen = false

    /**
     * Hands a captured PCM frame to the send pipeline. The first frame of an
     * utterance resets the Opus encoder; the previously held frame is emitted
     * and [pcm] becomes the new pending frame. [isLastFrame] flushes the
     * pipeline as the final packet of the utterance.
     */
    fun sendVoice(pcm: ShortArray, isLastFrame: Boolean = false) {
        if (withholdAudio()) {
            discardPending()
            return
        }
        if (!voiceUtteranceOpen) {
            channel()?.resetEncoder()
            voiceUtteranceOpen = true
        }
        val held = pendingVoicePcm
        val heldFrames = pendingVoiceFrames
        val heldTarget = pendingVoiceTarget
        pendingVoicePcm = pcm.copyOf()
        pendingVoiceFrames = OpusCodec.encodedTenMsFrames(pcm.size).coerceAtLeast(1)
        pendingVoiceTarget = targetId()
        if (held != null) {
            emitVoicePcm(held, isLastFrame = false, heldFrames, heldTarget)
        }
        if (isLastFrame) flushPendingVoice(isLastFrame = true)
    }


    /**
     * Ends the current utterance: flushes the pending frame as the
     * end-of-transmission packet (or encodes one frame of digital silence
     * when nothing is pending). Safe to call while idle.
     */
    fun terminate() {
        if (withholdAudio()) {
            // The local self-test keeps the microphone off the wire, so the
            // end-of-transmission packet must not be sent either; dropping the
            // pending frame also stops it from being flushed later.
            discardPending()
            return
        }
        flushPendingVoice(isLastFrame = true)
    }

    /** Forgets the held frame without emitting it. */
    private fun discardPending() {
        pendingVoicePcm = null
        pendingVoiceFrames = 0
        pendingVoiceTarget = VoiceTargetId.REGULAR_SPEECH
        voiceUtteranceOpen = false
    }

    private fun emitVoicePcm(pcm: ShortArray, isLastFrame: Boolean, frameCount: Int, target: Int) {
        val udpManager = channel() ?: return
        val encoded = udpManager.encodeOpus(pcm) ?: return
        sendEncodedVoice(udpManager, encoded, isLastFrame, frameCount, target)
    }

    private fun flushPendingVoice(isLastFrame: Boolean) {
        val last = pendingVoicePcm
        val frames = pendingVoiceFrames
        val target = pendingVoiceTarget
        pendingVoicePcm = null
        pendingVoiceFrames = 0
        pendingVoiceTarget = VoiceTargetId.REGULAR_SPEECH
        voiceUtteranceOpen = false
        if (last != null) {
            emitVoicePcm(last, isLastFrame, frames, target)
        } else if (isLastFrame) {
            val udpManager = channel() ?: return
            val encoded = udpManager.encodeSilence() ?: return
            sendEncodedVoice(
                udpManager,
                encoded.first,
                isLastFrame = true,
                encoded.second,
                // A terminator with nothing pending is digital silence for the
                // utterance that just ended: it uses the current target, since
                // there is no captured frame to inherit one from.
                targetId(),
            )
        }
    }

    private fun sendEncodedVoice(
        udpManager: VoiceSendChannel,
        encoded: ByteArray,
        isLastFrame: Boolean,
        frameCount: Int,
        target: Int,
    ) {
        if (target == VoiceTargetId.NONE) {
            // Unresolvable target (every receiver left, permissions lost...).
            // Sending nothing is the point: falling back to target 0 here would
            // turn a whisper into a channel-wide broadcast.
            return
        }
        val body = udpManager.buildTunnelPacket(encoded, isLastFrame, frameCount, target) ?: return
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
