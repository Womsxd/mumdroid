package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row configuration for the user certificate store, persisted with Room.
 *
 * Replaces the leftover SharedPreferences keys: which certificate is active and
 * the password protecting the PKCS#12 keystore files. There is always at most
 * one row, pinned to [id] = 0 so it can be upserted.
 *
 * @property id fixed primary key (always 0) so the row can be upserted.
 * @property selectedFingerprint fingerprint of the active certificate, if any.
 * @property keystorePassword random password encrypting the on-disk PKCS#12
 *   files; generated lazily on first use.
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
