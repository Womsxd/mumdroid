package dev.woms.mumdroid.core.model

/** Normalizes a saved-server address the way token lookups compare them. */
object ServerAddress {
    fun normalizeHost(host: String): String = host.trim().lowercase()
}
