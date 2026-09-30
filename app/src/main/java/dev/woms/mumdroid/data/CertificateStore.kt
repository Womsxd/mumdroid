package dev.woms.mumdroid.data

import android.content.Context
import dev.woms.mumdroid.data.db.CertificateDao
import dev.woms.mumdroid.data.db.CertificateEntity
import dev.woms.mumdroid.data.db.MumdroidDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the certificate store, backed by Room.
 *
 * Captured server certificate fingerprints are recorded here so the user can
 * review, pin and manage the certificates of the servers they connect to
 * (mirroring the desktop client's certificate management).
 *
 * Pins are keyed by the server address, not by the fingerprint (see
 * [CertificateEntity]): two servers that present the same certificate each get
 * their own pin, and re-pinning an address is idempotent.
 */
class CertificateStore(private val dao: CertificateDao) : PinnedFingerprintSource {

    constructor(context: Context) : this(MumdroidDatabase.getInstance(context).certificateDao())

    /** Emits all recorded certificates, newest first. */
    val certificates: Flow<List<CertificateEntity>> = dao.observeAll()

    /**
     * Records the certificate presented by [host]:[port] after a connection
     * succeeded. The fingerprint is pinned for that address only when it has no
     * pin yet: a server that shares another server's certificate still gets its
     * own pin, and an existing pin (including one the user kept via "trust
     * once") is never overwritten. The insert is a single atomic statement.
     */
    suspend fun record(host: String, port: Int, fingerprint: String, subject: String = "", issuer: String = "") {
        if (fingerprint.isBlank()) return
        dao.insertIfAbsent(entity(host, port, fingerprint, subject, issuer))
    }

    /**
     * The fingerprint pinned for [host]:[port], if any. Used by the
     * certificate-pinning check.
     */
    override suspend fun pinnedFingerprint(host: String, port: Int): String? =
        dao.findByHostPort(host, port)?.fingerprint

    /**
     * Replaces the pinned fingerprint for [host]:[port] ("update certificate"
     * in the certificate-mismatch prompt). The write is a single atomic upsert
     * keyed by the address, so a fingerprint that another server also uses can
     * neither fail nor leave this address without a pin.
     */
    suspend fun replaceForHost(
        host: String,
        port: Int,
        fingerprint: String,
        subject: String = "",
        issuer: String = "",
    ) {
        if (fingerprint.isBlank()) return
        dao.replace(entity(host, port, fingerprint, subject, issuer))
    }

    suspend fun delete(certificate: CertificateEntity) {
        dao.delete(certificate)
    }

    private fun entity(host: String, port: Int, fingerprint: String, subject: String, issuer: String) =
        CertificateEntity(
            alias = host,
            host = host,
            port = port,
            fingerprint = fingerprint,
            subject = subject,
            issuer = issuer,
            createdAt = System.currentTimeMillis(),
        )
}
