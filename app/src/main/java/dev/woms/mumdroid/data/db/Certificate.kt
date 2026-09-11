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
 * A server certificate the client has seen (and optionally pinned), persisted
 * with Room. This mirrors the desktop client's certificate store so the user
 * can review and manage the servers they have connected to.
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
    indices = [Index(value = ["fingerprint"], unique = true)],
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

    @Query("SELECT * FROM certificates WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): CertificateEntity?

    /** Latest certificate recorded for a host:port pair (the active pin). */
    @Query(
        "SELECT * FROM certificates WHERE host = :host AND port = :port " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun findLatestByHostPort(host: String, port: Int): CertificateEntity?

    @Query("DELETE FROM certificates WHERE host = :host AND port = :port")
    suspend fun deleteForHostPort(host: String, port: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(certificate: CertificateEntity): Long

    @Delete
    suspend fun delete(certificate: CertificateEntity)

    @Query("DELETE FROM certificates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
