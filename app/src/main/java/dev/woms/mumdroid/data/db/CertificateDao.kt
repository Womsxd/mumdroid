package dev.woms.mumdroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the certificate store.
 */
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
