package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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

/**
 * Single-row configuration for the user certificate store, persisted with Room.
 *
 * Replaces the leftover SharedPreferences keys: which certificate is active and
 * the password the PKCS#12 keystore files used to be encrypted with. There is
 * always at most one row, pinned to [id] = 0 so it can be upserted.
 *
 * @property id fixed primary key (always 0) so the row can be upserted.
 * @property selectedFingerprint fingerprint of the active certificate, if any.
 * @property keystorePassword legacy random password the on-disk PKCS#12 files
 *   were once encrypted with. No new value is ever written: the files are now
 *   stored password-less (mirroring the desktop client) and this field only
 *   survives until [dev.woms.mumdroid.data.UserCertificateStore] has re-packed
 *   every existing file and verified each one opens without a password, after
 *   which it is cleared. The column itself can be dropped once no install
 *   still needs it.
 */
@Entity(tableName = "user_certificate_config")
data class UserCertificateConfigEntity(
    @PrimaryKey
    val id: Int = 0,
    @ColumnInfo(name = "selected_fingerprint")
    val selectedFingerprint: String? = null,
    @ColumnInfo(name = "keystore_password")
    val keystorePassword: String = "",
)

/**
 * DAO for the user (client) certificate store: the certificate metadata rows
 * plus the single-row [UserCertificateConfigEntity].
 */
@Dao
interface UserCertificateDao {

    /** All stored user certificates, newest first. */
    @Query("SELECT * FROM user_certificates ORDER BY created_at DESC")
    suspend fun getAll(): List<UserCertificateEntity>

    @Query("SELECT * FROM user_certificates WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): UserCertificateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(certificate: UserCertificateEntity)

    @Query("DELETE FROM user_certificates WHERE fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String)

    @Query("DELETE FROM user_certificates")
    suspend fun deleteAll()

    @Query("SELECT * FROM user_certificate_config WHERE id = 0 LIMIT 1")
    suspend fun getConfig(): UserCertificateConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: UserCertificateConfigEntity)
}
