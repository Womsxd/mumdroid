package dev.woms.mumdroid.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.model.isPresent
import dev.woms.mumdroid.data.UserCertificateStore.Companion.ready
import dev.woms.mumdroid.data.UserCertificateStore.Companion.vaultLock
import dev.woms.mumdroid.data.db.MumdroidDatabase
import dev.woms.mumdroid.data.db.UserCertificateConfigEntity
import dev.woms.mumdroid.data.db.toEntity
import dev.woms.mumdroid.data.db.toModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Manages the app's user (client) certificates.
 *
 * Multiple user certificates can be imported/generated and stored. Each
 * certificate (with its private key) is kept in its own PKCS#12 keystore file
 * in app-private storage, so they can be **imported** from / **exported** to a
 * `.p12`/`.pfx` file — the same format used by the desktop Mumble client and by
 * Mumla. A self-signed certificate can also be generated locally.
 *
 * One of the stored certificates is marked as the **active** one and is
 * presented to the server during the TLS handshake, mirroring the desktop
 * Mumble client's certificate management.
 *
 * Metadata and the active selection live in Room ([MumdroidDatabase]); only the
 * PKCS#12 key material stays on disk. The previous SharedPreferences JSON array
 * is migrated in once, lazily, and then cleared.
 *
 * The keystores carry **no password**: like the official desktop client (which
 * writes `PKCS12_create("", "Mumble Identity", …)`), the app-private sandbox is
 * the boundary. Older installs re-encrypted the files with a random password
 * kept in Room; [convertToPasswordless] re-packs those and clears the stored
 * password only after every file has been verified to open without one.
 *
 * The PKCS#12 files live in a [CertificateVault] subdirectory whose base
 * directory follows [AppSettings.backupUserCertificates]: `filesDir` (included
 * in cloud backup) when on, `noBackupFilesDir` (excluded) when off. Toggling
 * the switch moves the files; a reconcile step on first use also collapses the
 * legacy filesDir-root layout and prunes metadata whose key file is gone (for
 * example after a restore that did not carry the private keys).
 *
 * This class is the storage façade only; the pieces it coordinates are
 * [UserCertificatePkcs12] (crypto), [UserCertificateCodec] (naming/encoding
 * rules) and [UserCertificateLegacyMigration] (the one-time prefs import). The
 * two pieces that belong to the façade itself — the `.p12` file access and the
 * import exceptions this class throws — live at the bottom of this file rather
 * than in files of their own.
 */
class UserCertificateStore(private val context: Context) {

    private val db = MumdroidDatabase.getInstance(context)
    private val dao = db.userCertificateDao()
    private val settingsStore = SettingsStore(context)
    private val vault = CertificateVault(context.filesDir, context.noBackupFilesDir)
    private val legacy = UserCertificateLegacyMigration(context, db, dao)

    /** Returns all stored user certificates. */
    suspend fun loadAll(): List<UserCertificate> {
        vaultLock.withLock { reconcileIfNeeded() }
        return dao.getAll().map { it.toModel() }
    }

    /**
     * Returns the currently selected (active) user certificate, or
     * [UserCertificate.NONE] if none is stored/selected.
     */
    suspend fun load(): UserCertificate {
        vaultLock.withLock { reconcileIfNeeded() }
        val selected = dao.getConfig()?.selectedFingerprint ?: return UserCertificate.NONE
        return dao.findByFingerprint(selected)?.toModel() ?: UserCertificate.NONE
    }

    /** The SHA-256 fingerprint of the currently selected certificate, if any. */
    suspend fun fingerprint(): String? = load().fingerprint.ifBlank { null }

    /**
     * Returns the [X509Certificate] and [PrivateKey] of the currently selected
     * certificate for TLS client authentication, or null if no certificate has
     * been generated/imported.
     */
    suspend fun keyStoreMaterial(): Pair<X509Certificate, PrivateKey>? {
        val cert = load()
        if (!cert.isPresent()) return null
        return try {
            withVault { files ->
                val opened = openStored(files, cert.fingerprint) ?: return@withVault null
                val (ks, storePassword) = opened
                val entry = ks.getEntry(
                    UserCertificatePkcs12.KEY_ALIAS,
                    KeyStore.PasswordProtection(storePassword),
                ) as? KeyStore.PrivateKeyEntry
                val x509 = entry?.certificate as? X509Certificate
                if (entry == null || x509 == null) null else x509 to entry.privateKey
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load keystore material", e)
            null
        }
    }

    /**
     * Generates a new self-signed user certificate (and private key), adds it to
     * the store and marks it as the active certificate.
     */
    suspend fun generate(username: String) {
        try {
            val (key, cert) = UserCertificatePkcs12.createSelfSigned(username)
            val certMeta = UserCertificatePkcs12.metadataOf(cert)
            val fingerprint = certMeta.fingerprint

            // Persist the private key + certificate as a new PKCS#12 file. Like
            // the desktop client's identity file it carries no password: the
            // app-private sandbox is the boundary, and a password stored next to
            // the file would only make the identity unrecoverable if the
            // database holding it were lost.
            withVault { files ->
                val ks = UserCertificatePkcs12.pack(key, arrayOf(cert), EMPTY_PASSWORD)
                files.save(fingerprint, ks, EMPTY_PASSWORD)
            }

            addAndSelect(certMeta)
            Log.i(TAG, "Generated user certificate with fingerprint $fingerprint")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate user certificate", e)
            throw e
        }
    }

    /**
     * Imports a user certificate from a PKCS#12 (.p12/.pfx) file.
     *
     * @param p12Bytes the raw bytes of the PKCS#12 file.
     * @param password the password protecting the PKCS#12 file.
     * @throws WrongPasswordException if the file could not be opened with the
     *   supplied password.
     * @throws CertificateFileCorruptException if the file is truncated,
     *   corrupted or not a PKCS#12 file at all (re-entering the password
     *   cannot help).
     * @throws UnusableCertificateException if the file contains no private-key
     *   entry / certificate, or the certificate is expired.
     */
    suspend fun import(p12Bytes: ByteArray, password: CharArray) {
        val opened = when (val result = UserCertificatePkcs12.open(p12Bytes, password)) {
            is UserCertificatePkcs12.OpenResult.Opened -> result
            UserCertificatePkcs12.OpenResult.WrongPassword ->
                throw WrongPasswordException()
            UserCertificatePkcs12.OpenResult.Corrupt ->
                throw CertificateFileCorruptException()
            is UserCertificatePkcs12.OpenResult.Unusable ->
                throw UnusableCertificateException(result.reason)
        }

        val certMeta = UserCertificatePkcs12.metadataOf(opened.certificate)
        val fingerprint = certMeta.fingerprint

        // Re-pack with the app's (empty) store password and write to app-private storage.
        withVault { files ->
            val newKs = UserCertificatePkcs12.pack(
                opened.entry.privateKey,
                opened.entry.certificateChain,
                EMPTY_PASSWORD,
            )
            files.save(fingerprint, newKs, EMPTY_PASSWORD)
        }

        addAndSelect(certMeta)
        Log.i(TAG, "Imported user certificate with fingerprint $fingerprint")
    }

    /**
     * Exports the user certificate (and private key) with the given fingerprint
     * as a PKCS#12 file.
     *
     * @param fingerprint the SHA-256 fingerprint of the certificate to export.
     * @param out the stream to write the PKCS#12 bytes to. The stream is NOT
     *   closed by this method.
     * @param password the password with which the exported file will be protected.
     * @throws NoExportableCertificateException if no key material is stored for
     *   [fingerprint].
     */
    suspend fun exportTo(fingerprint: String, out: OutputStream, password: CharArray) {
        withVault { files ->
            val opened = openStored(files, fingerprint)
                ?: throw NoExportableCertificateException()
            val (ks, storePassword) = opened
            val chain = ks.getCertificateChain(UserCertificatePkcs12.KEY_ALIAS)
                ?: throw NoExportableCertificateException()
            val key = ks.getKey(UserCertificatePkcs12.KEY_ALIAS, storePassword)
                ?: throw NoExportableCertificateException()

            // The exported copy is protected by the caller-provided password,
            // which is where a user-chosen password belongs.
            val exportKs = UserCertificatePkcs12.pack(key, chain, password)
            files.storeTo(exportKs, out, password)
        }
    }

    /**
     * Marks the certificate with the given fingerprint as the active one.
     */
    suspend fun select(fingerprint: String) {
        legacy.ensureMigrated()
        if (dao.findByFingerprint(fingerprint) != null) {
            setSelected(fingerprint)
        }
    }

    /**
     * Removes the certificate with the given fingerprint (and its private key).
     * If it was the active certificate, another stored certificate is selected,
     * or the selection is cleared if none remain.
     */
    suspend fun delete(fingerprint: String) {
        withVault { files ->
            db.withTransaction {
                val wasSelected = dao.getConfig()?.selectedFingerprint == fingerprint
                dao.deleteByFingerprint(fingerprint)
                files.delete(fingerprint)
                if (wasSelected) {
                    val next = dao.getAll().firstOrNull()?.fingerprint
                    val config = dao.getConfig() ?: UserCertificateConfigEntity()
                    dao.upsertConfig(config.copy(selectedFingerprint = next))
                }
            }
        }
    }

    /** Removes all user certificates. */
    suspend fun deleteAll() {
        withVault { files ->
            db.withTransaction {
                dao.getAll().forEach { files.delete(it.fingerprint) }
                dao.deleteAll()
                dao.upsertConfig(UserCertificateConfigEntity())
            }
        }
    }

    /**
     * Enables or disables cloud backup of the user-certificate private keys.
     *
     * The switch is persisted and the PKCS#12 files are relocated between the
     * backed-up and no-backup areas under one lock, so a concurrent read can
     * never observe the new setting with the files still in the old location.
     * Disabling only affects future backup snapshots; copies already uploaded to
     * a cloud provider are not deleted by this.
     */
    suspend fun setBackupEnabled(enabled: Boolean) {
        vaultLock.withLock {
            settingsStore.setBackupUserCertificates(enabled)
            reconcile(enabled)
            ready = true
        }
    }

    // ---- internals ----

    /** Runs [block] against the vault directory for the current setting. */
    private suspend fun <T> withVault(block: suspend (UserCertificateKeyStoreFiles) -> T): T =
        vaultLock.withLock {
            reconcileIfNeeded()
            block(UserCertificateKeyStoreFiles(vault.dir(currentBackupEnabled())))
        }

    /** One-time-per-process vault reconciliation; must run under [vaultLock]. */
    private suspend fun reconcileIfNeeded() {
        if (ready) return
        reconcile(currentBackupEnabled())
        ready = true
    }

    /**
     * Brings storage in line with [backupEnabled] and with the password-less
     * format: relocates the PKCS#12 files, re-packs any legacy
     * password-protected one, then drops metadata whose key material is gone.
     * Must run under [vaultLock].
     */
    private suspend fun reconcile(backupEnabled: Boolean) {
        legacy.ensureMigrated()
        relocate(backupEnabled)
        convertToPasswordless()
        pruneMissing(backupEnabled)
    }

    private suspend fun currentBackupEnabled(): Boolean =
        settingsStore.settings.first().backupUserCertificates

    /**
     * Moves every PKCS#12 file into the vault matching [enabled], also
     * collapsing the legacy layout where the files sat directly in filesDir.
     */
    private fun relocate(enabled: Boolean) {
        val failed = vault.relocate(
            target = vault.dir(enabled),
            sources = listOf(vault.dir(!enabled), context.filesDir),
        )
        if (failed > 0) {
            Log.w(TAG, "Could not relocate $failed user certificate file(s)")
        }
    }

    /**
     * Drops metadata rows whose PKCS#12 file is nowhere to be found and clears
     * the selection if it pointed at one of them. This is what a restore that
     * did not carry the private keys (backup disabled) leaves behind.
     *
     * A file is only treated as gone when it is absent from the active vault,
     * the other vault and the legacy filesDir root: a relocation that failed
     * must not make a still-present certificate disappear from the store.
     */
    private suspend fun pruneMissing(backupEnabled: Boolean) {
        val vaults = listOf(
            UserCertificateKeyStoreFiles(vault.dir(backupEnabled)),
            UserCertificateKeyStoreFiles(vault.dir(!backupEnabled)),
            UserCertificateKeyStoreFiles(context.filesDir),
        )
        val missing = dao.getAll().filterNot { row ->
            vaults.any { it.fileFor(row.fingerprint).exists() }
        }
        if (missing.isEmpty()) return
        db.withTransaction {
            missing.forEach { dao.deleteByFingerprint(it.fingerprint) }
            val config = dao.getConfig() ?: UserCertificateConfigEntity()
            if (missing.any { it.fingerprint == config.selectedFingerprint }) {
                val next = dao.getAll().firstOrNull()?.fingerprint
                dao.upsertConfig(config.copy(selectedFingerprint = next))
            }
        }
        Log.i(TAG, "Pruned ${missing.size} user certificate(s) without key material")
    }

    /**
     * Re-packs every stored certificate with the app's empty password,
     * replacing the legacy random password the app used to keep in Room.
     *
     * The database value is cleared only once every file has been confirmed to
     * open with the empty password, so a certificate is never left needing a
     * password the database no longer has. A file that cannot be re-packed
     * keeps the value around for a later attempt.
     */
    private suspend fun convertToPasswordless() {
        val legacy = legacyKeystorePassword() ?: return
        val files = UserCertificateKeyStoreFiles(vault.dir(currentBackupEnabled()))
        var allConverted = true
        for (row in dao.getAll()) {
            if (!files.fileFor(row.fingerprint).exists()) continue
            if (files.loadOrNull(row.fingerprint, EMPTY_PASSWORD) != null) continue
            val ks = files.loadOrNull(row.fingerprint, legacy)
            if (ks == null) {
                allConverted = false
                continue
            }
            try {
                files.save(row.fingerprint, ks, EMPTY_PASSWORD)
            } catch (e: Exception) {
                Log.w(TAG, "Could not re-pack certificate ${row.fingerprint}", e)
            }
            if (files.loadOrNull(row.fingerprint, EMPTY_PASSWORD) == null) {
                allConverted = false
            }
        }
        if (!allConverted) {
            Log.w(TAG, "Some certificates still need the legacy keystore password; keeping it")
            return
        }
        val config = dao.getConfig() ?: UserCertificateConfigEntity()
        if (config.keystorePassword.isNotEmpty()) {
            dao.upsertConfig(config.copy(keystorePassword = ""))
            Log.i(TAG, "All certificates are password-less; cleared the legacy keystore password")
        }
    }

    /**
     * Opens the stored PKCS#12 of [fingerprint] with the app's empty password,
     * falling back to the legacy random password for a file that has not been
     * re-packed yet. Returns the keystore together with the password it was
     * actually opened with, since that is the one its key entry needs.
     */
    private suspend fun openStored(
        files: UserCertificateKeyStoreFiles,
        fingerprint: String,
    ): Pair<KeyStore, CharArray>? {
        files.loadOrNull(fingerprint, EMPTY_PASSWORD)?.let { return it to EMPTY_PASSWORD }
        val legacy = legacyKeystorePassword() ?: return null
        return files.loadOrNull(fingerprint, legacy)?.let { it to legacy }
    }

    /**
     * The legacy random password still kept in Room, or null once the
     * certificates have been converted to the password-less format.
     */
    private suspend fun legacyKeystorePassword(): CharArray? =
        dao.getConfig()?.keystorePassword?.takeIf { it.isNotEmpty() }?.toCharArray()

    /** [UserCertificateKeyStoreFiles.load] reporting a wrong password as null. */
    private fun UserCertificateKeyStoreFiles.loadOrNull(
        fingerprint: String,
        password: CharArray,
    ): KeyStore? = try {
        load(fingerprint, password)
    } catch (_: Exception) {
        null
    }

    /** Adds a certificate metadata to the store and marks it active. */
    private suspend fun addAndSelect(cert: UserCertificate) {
        db.withTransaction {
            dao.upsert(cert.toEntity())
            setSelected(cert.fingerprint)
        }
    }

    private suspend fun setSelected(fingerprint: String?) {
        val config = dao.getConfig() ?: UserCertificateConfigEntity()
        dao.upsertConfig(config.copy(selectedFingerprint = fingerprint))
    }

    companion object {
        private const val TAG = "UserCertificateStore"

        /**
         * Password for the app's own PKCS#12 files, empty on purpose.
         *
         * The official Mumble desktop client stores its client identity as a
         * password-less PKCS#12 blob (`PKCS12_create("", "Mumble Identity", …)`
         * in `Cert.cpp`) and relies on the OS sandbox / file permissions. This
         * app does the same: a password kept in the same sandbox as the file
         * adds no real protection, while it would make the identity
         * unrecoverable whenever the database holding it is lost or damaged.
         */
        private val EMPTY_PASSWORD = CharArray(0)

        /**
         * Serialises vault reads/moves. Shared by every store instance (the UI
         * controller and the service each build one) so a relocation triggered
         * from one is visible to the other. [ready] tracks the per-process
         * reconciliation.
         */
        private val vaultLock = Mutex()

        @Volatile
        private var ready = false
    }
}

/**
 * The on-disk half of the user-certificate store: each certificate (with its
 * private key) lives in its own PKCS#12 file in app-private storage. Metadata
 * and the selection live in Room, only the key material stays here.
 */
internal class UserCertificateKeyStoreFiles(private val filesDir: File) {

    /** The PKCS#12 file backing the given fingerprint. */
    fun fileFor(fingerprint: String): File =
        File(filesDir, UserCertificateCodec.certFileName(fingerprint))

    /** Opens the PKCS#12 file of [fingerprint], or null if it does not exist. */
    fun load(fingerprint: String, password: CharArray): KeyStore? {
        val file = fileFor(fingerprint)
        if (!file.exists()) return null
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { input ->
            ks.load(input, password)
        }
        return ks
    }

    /** Writes [ks] to the PKCS#12 file of [fingerprint]. */
    fun save(fingerprint: String, ks: KeyStore, password: CharArray) {
        val file = fileFor(fingerprint)
        // Stage in a sibling temp file and rename it into place: a cloud-backup
        // pass that walks this directory concurrently must never capture a
        // half-written keystore (which would restore as an unusable key).
        val staging = File(file.parentFile, file.name + ".tmp")
        try {
            staging.outputStream().use { output -> ks.store(output, password) }
            if (!staging.renameTo(file) && !(file.delete() && staging.renameTo(file))) {
                throw IOException("Could not store ${file.name}")
            }
        } catch (e: Exception) {
            staging.delete()
            throw e
        }
    }

    /** Writes [ks] to an arbitrary [out] stream (export). */
    fun storeTo(ks: KeyStore, out: OutputStream, password: CharArray) {
        ks.store(out, password)
    }

    /** Removes the PKCS#12 file of [fingerprint] if present. */
    fun delete(fingerprint: String) {
        fileFor(fingerprint).delete()
    }
}

/**
 * Thrown when a PKCS#12 file cannot be opened with the supplied password,
 * indicating that the caller should ask the user for the correct password.
 *
 * Carries no message: the data layer holds no user-facing text, so the caller
 * picks the string from the exception type.
 */
class WrongPasswordException : Exception()

/**
 * Thrown when a PKCS#12 file is truncated, corrupted or not a PKCS#12 file at
 * all. Re-entering the password cannot help; the caller should tell the user
 * to pick another file instead of prompting for a password again.
 */
class CertificateFileCorruptException : Exception()

/**
 * Thrown when a PKCS#12 file opens but its content cannot be used as a client
 * certificate; [reason] tells the caller which message to show.
 *
 * Internal because [reason] is [UserCertificatePkcs12]'s own reason type,
 * which does not leave the module.
 */
internal class UnusableCertificateException(val reason: UserCertificatePkcs12.UnusableReason) : Exception()

/**
 * Thrown when the certificate to export has no PKCS#12 key material on disk
 * (its file is gone or the fingerprint is unknown).
 */
class NoExportableCertificateException : Exception()
