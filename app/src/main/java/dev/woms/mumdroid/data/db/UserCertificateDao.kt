package dev.woms.mumdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
