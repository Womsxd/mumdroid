package dev.woms.mumdroid.data

import android.content.Context
import dev.woms.mumdroid.data.db.CertificateEntity
import dev.woms.mumdroid.data.db.MumdroidDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the certificate store, backed by Room.
 *
 * Captured server certificate fingerprints are recorded here so the user can
 * review, pin and manage the certificates of the servers they connect to
 * (mirroring the desktop client's certificate management).
 */
class CertificateStore(private val context: Context) {

    private val dao = MumdroidDatabase.getInstance(context).certificateDao()

    /** Emits all recorded certificates, newest first. */
    val certificates: Flow<List<CertificateEntity>> = dao.observeAll()

    /**
     * Records a certificate fingerprint for the given host. If the fingerprint
     * is already present, the entry is kept as-is (the store is deduplicated by
     * fingerprint); otherwise a new row is inserted.
     */
    suspend fun record(host: String, port: Int, fingerprint: String, subject: String = "", issuer: String = "") {
        if (fingerprint.isBlank()) return
        if (dao.findByFingerprint(fingerprint) != null) return
        dao.insert(
            CertificateEntity(
                alias = host,
                host = host,
                port = port,
                fingerprint = fingerprint,
                subject = subject,
                issuer = issuer,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * The fingerprint pinned for [host]:[port], if any (the latest entry
     * recorded for that server). Used by the certificate-pinning check.
     */
    suspend fun pinnedFingerprint(host: String, port: Int): String? =
        dao.findLatestByHostPort(host, port)?.fingerprint

    /**
     * Replaces the pinned fingerprint for [host]:[port] ("update certificate"
     * in the certificate-mismatch prompt): the old entries for the server are
     * dropped and the new fingerprint is pinned instead.
     */
    suspend fun replaceForHost(
        host: String,
        port: Int,
        fingerprint: String,
        subject: String = "",
        issuer: String = "",
    ) {
        if (fingerprint.isBlank()) return
        dao.deleteForHostPort(host, port)
        dao.insert(
            CertificateEntity(
                alias = host,
                host = host,
                port = port,
                fingerprint = fingerprint,
                subject = subject,
                issuer = issuer,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(certificate: CertificateEntity) {
        dao.delete(certificate)
    }
}
