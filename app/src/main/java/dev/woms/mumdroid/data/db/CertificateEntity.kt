package dev.woms.mumdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
