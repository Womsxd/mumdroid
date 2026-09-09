package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.woms.mumdroid.core.model.UserCertificate

/**
 * A user (client) X.509 certificate, persisted with Room.
 *
 * Unlike [CertificateEntity], which records the certificates *servers* present,
 * this holds the certificates *we* present during the TLS handshake. The
 * private key stays in its own PKCS#12 file; only the display metadata lives
 * here (previously a JSON array in SharedPreferences).
 *
 * @property id auto-generated primary key.
 * @property subject the X.509 subject (the user's identity).
 * @property fingerprint colon-separated uppercase SHA-256 fingerprint, used to
 *   identify the certificate and locate its PKCS#12 file.
 * @property serial the certificate serial number as a hex string.
 * @property notBefore epoch millis when the certificate becomes valid.
 * @property notAfter epoch millis when the certificate expires.
 * @property pem the PEM-encoded certificate (public part), for display/export.
 * @property createdAt epoch millis when the certificate was stored.
 */
@Entity(
    tableName = "user_certificates",
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class UserCertificateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subject: String = "",
    val fingerprint: String = "",
    val serial: String = "",
    @ColumnInfo(name = "not_before")
    val notBefore: Long = 0,
    @ColumnInfo(name = "not_after")
    val notAfter: Long = 0,
    val pem: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
)

/** Converts to the UI model used throughout the app. */
fun UserCertificateEntity.toModel(): UserCertificate = UserCertificate(
    subject = subject,
    fingerprint = fingerprint,
    serial = serial,
    notBefore = notBefore,
    notAfter = notAfter,
    pem = pem,
)

/** Maps a [UserCertificate] into a [UserCertificateEntity]. */
fun UserCertificate.toEntity(createdAt: Long = System.currentTimeMillis()): UserCertificateEntity =
    UserCertificateEntity(
        subject = subject,
        fingerprint = fingerprint,
        serial = serial,
        notBefore = notBefore,
        notAfter = notAfter,
        pem = pem,
        createdAt = createdAt,
    )
