package dev.woms.mumdroid.data

import java.io.File
import java.io.OutputStream
import java.security.KeyStore

/**
 * The on-disk half of the user-certificate store: each certificate (with its
 * private key) lives in its own PKCS#12 file in app-private storage. Metadata
 * and the selection live in Room, only the key material stays here.
 */
internal class UserCertificateKeyStoreFiles(private val filesDir: File) {

    /** The PKCS#12 file backing the given fingerprint. */
    fun fileFor(fingerprint: String): File =
        File(filesDir, UserCertificateCodec.certFileName(fingerprint))

    /** Opens the PKCS#12 file of [fingerprint], or null if it does not exist. */
    fun load(fingerprint: String, password: CharArray): KeyStore? {
        val file = fileFor(fingerprint)
        if (!file.exists()) return null
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { input ->
            ks.load(input, password)
        }
        return ks
    }

    /** Writes [ks] to the PKCS#12 file of [fingerprint]. */
    fun save(fingerprint: String, ks: KeyStore, password: CharArray) {
        fileFor(fingerprint).outputStream().use { output ->
            ks.store(output, password)
        }
    }

    /** Writes [ks] to an arbitrary [out] stream (export). */
    fun storeTo(ks: KeyStore, out: OutputStream, password: CharArray) {
        ks.store(out, password)
    }

    /** Removes the PKCS#12 file of [fingerprint] if present. */
    fun delete(fingerprint: String) {
        fileFor(fingerprint).delete()
    }
}
