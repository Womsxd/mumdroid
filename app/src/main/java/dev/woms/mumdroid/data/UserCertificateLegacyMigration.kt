package dev.woms.mumdroid.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.data.db.MumdroidDatabase
import dev.woms.mumdroid.data.db.UserCertificateConfigEntity
import dev.woms.mumdroid.data.db.UserCertificateDao
import dev.woms.mumdroid.data.db.toEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-time migration of the certificates that were once stored as a JSON array
 * in SharedPreferences. The legacy row is copied into Room once (across all
 * store instances), then the prefs are cleared. New installs never see this.
 */
internal class UserCertificateLegacyMigration(
    private val context: Context,
    private val db: MumdroidDatabase,
    private val dao: UserCertificateDao,
) {

    companion object {
        private const val TAG = "UserCertificateStore"

        // Legacy SharedPreferences removed after the one-time migration to Room.
        private const val LEGACY_PREFS = "user_cert_prefs"
        private const val LEGACY_PASSWORD = "password"
        private const val LEGACY_LIST = "certificates"
        private const val LEGACY_SELECTED = "selected_fingerprint"

        /** Guards [ensureMigrated] across all store instances. */
        @Volatile
        private var migrated = false
        private val migrationLock = Mutex()
    }

    /**
     * Copies the legacy SharedPreferences JSON certificate list, keystore
     * password and selection into Room exactly once, then clears the prefs.
     */
    suspend fun ensureMigrated() {
        if (migrated) return
        migrationLock.withLock {
            if (migrated) return
            migrate()
            migrated = true
        }
    }

    private suspend fun migrate() {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val rawList = prefs.getString(LEGACY_LIST, null)
        val password = prefs.getString(LEGACY_PASSWORD, null)
        val selected = prefs.getString(LEGACY_SELECTED, null)
        if (rawList == null && password == null && selected == null) return

        val certs = rawList?.let(::parseList).orEmpty()
        db.withTransaction {
            certs.forEach { dao.upsert(it.toEntity()) }
            val config = dao.getConfig() ?: UserCertificateConfigEntity()
            val selectedValid = selected?.takeIf { fp -> certs.any { it.fingerprint == fp } }
            dao.upsertConfig(
                config.copy(
                    selectedFingerprint = selectedValid ?: config.selectedFingerprint,
                    keystorePassword = password ?: config.keystorePassword,
                ),
            )
        }
        prefs.edit().clear().apply()
        Log.i(TAG, "Migrated ${certs.size} user certificate(s) from SharedPreferences to Room")
    }

    private fun parseList(raw: String): List<UserCertificate> = try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                add(parseCert(arr.getJSONObject(i)))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not parse legacy certificate list", e)
        emptyList()
    }

    private fun parseCert(o: JSONObject): UserCertificate = UserCertificate(
        subject = o.optString("subject", ""),
        fingerprint = o.optString("fingerprint", ""),
        serial = o.optString("serial", ""),
        notBefore = o.optLong("not_before", 0),
        notAfter = o.optLong("not_after", 0),
        pem = o.optString("pem", ""),
    )
}
