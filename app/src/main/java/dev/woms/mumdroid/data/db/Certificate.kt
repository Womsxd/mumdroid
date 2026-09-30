package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The certificate pinned for one server address ([host]:[port]), persisted with
 * Room. This mirrors the desktop client's certificate store so the user can
 * review and manage the servers they have connected to.
 *
 * The pin is keyed by the *server address*, never by the fingerprint: several
 * servers may legitimately present the same certificate (shared/self-signed
 * certs, one cert for a host and several of its ports), and each must keep its
 * own pin. Uniqueness is therefore on (host, port) — exactly the key the
 * pinning check looks up — so a shared certificate can never shadow or drop
 * another server's pin.
 *
 * @property id auto-generated primary key.
 * @property alias user-facing label shown in the certificate list.
 * @property host the host the certificate was captured from.
 * @property port the port the certificate was captured from.
 * @property fingerprint colon-separated uppercase SHA-256 fingerprint, used to
 *   identify / pin the certificate.
 * @property subject the X.509 subject CN/DN if available.
 * @property issuer the X.509 issuer if available.
 * @property createdAt epoch millis when this entry was recorded.
 */
@Entity(
    tableName = "certificates",
    indices = [Index(value = ["host", "port"], unique = true)],
)
data class CertificateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val alias: String = "",
    val host: String = "",
    val port: Int = 64738,
    val fingerprint: String = "",
    val subject: String = "",
    val issuer: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
)

/** DAO for the certificate store. */
@Dao
interface CertificateDao {

    /** Emits all stored certificates, newest first. */
    @Query("SELECT * FROM certificates ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CertificateEntity>>

    @Query("SELECT * FROM certificates WHERE id = :id")
    suspend fun getById(id: Long): CertificateEntity?

    /**
     * The certificate pinned for a host:port pair, if any. At most one row can
     * exist per address (the unique index is on `(host, port)`), so this is the
     * address's active pin.
     */
    @Query("SELECT * FROM certificates WHERE host = :host AND port = :port LIMIT 1")
    suspend fun findByHostPort(host: String, port: Int): CertificateEntity?

    /**
     * Records a pin without ever replacing an existing one: a conflict on
     * `(host, port)` is ignored. Used for first-connection pinning, so a later
     * connection can never silently overwrite the user's pin.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(certificate: CertificateEntity): Long

    /**
     * Sets the pin for `(host, port)` to the given certificate, replacing any
     * previous pin for that address (the explicit "update certificate" action).
     * A single `INSERT OR REPLACE` is atomic, so the address always ends up
     * with either the new pin or (on failure) its previous one — never none.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(certificate: CertificateEntity): Long

    @Delete
    suspend fun delete(certificate: CertificateEntity)

    @Query("DELETE FROM certificates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
