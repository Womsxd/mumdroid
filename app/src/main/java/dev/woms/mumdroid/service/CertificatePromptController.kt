package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.CertificatePrompt
import dev.woms.mumdroid.core.net.CertificateDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the TLS pin-mismatch prompt while the handshake waits. */
internal class CertificatePromptController {

    private val _prompt = MutableStateFlow<CertificatePrompt?>(null)
    val prompt: StateFlow<CertificatePrompt?> = _prompt

    @Volatile
    private var resolver: ((CertificateDecision) -> Unit)? = null

    fun present(
        fingerprint: String,
        pinnedFingerprint: String,
        host: String,
        port: Int,
        respond: (CertificateDecision) -> Unit,
    ) {
        resolver = respond
        _prompt.value = CertificatePrompt(
            fingerprint = fingerprint,
            pinnedFingerprint = pinnedFingerprint,
            host = host,
            port = port,
        )
    }

    /** Clears the prompt and returns it with the handshake callback. */
    fun consume(): Pair<CertificatePrompt, (CertificateDecision) -> Unit>? {
        val prompt = _prompt.value ?: return null
        val respond = resolver ?: return null
        resolver = null
        _prompt.value = null
        return prompt to respond
    }

    fun clear() {
        resolver = null
        _prompt.value = null
    }
}
