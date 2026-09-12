package dev.woms.mumdroid.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.model.isPresent
import dev.woms.mumdroid.data.db.MumdroidDatabase
import dev.woms.mumdroid.data.db.UserCertificateConfigEntity
import dev.woms.mumdroid.data.db.toEntity
import dev.woms.mumdroid.data.db.toModel
import java.io.File
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
    private val files = UserCertificateKeyStoreFiles(context.filesDir)
    private val legacy = UserCertificateLegacyMigration(context, db, dao)

    /** Returns all stored user certificates. */
    suspend fun loadAll(): List<UserCertificate> {
        legacy.ensureMigrated()
        return dao.getAll().map { it.toModel() }
    }

    /**
     * Returns the currently selected (active) user certificate, or
     * [UserCertificate.NONE] if none is stored/selected.
     */
    suspend fun load(): UserCertificate {
        legacy.ensureMigrated()
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
            val password = keystorePassword()
            val ks = files.load(cert.fingerprint, password) ?: return null
            val entry = ks.getEntry(
                UserCertificatePkcs12.KEY_ALIAS,
                KeyStore.PasswordProtection(password),
            ) as? KeyStore.PrivateKeyEntry ?: return null
            val x509 = entry.certificate as? X509Certificate ?: return null
            x509 to entry.privateKey
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
        legacy.ensureMigrated()
        try {
            val (key, cert) = UserCertificatePkcs12.createSelfSigned(username)
            val certMeta = UserCertificatePkcs12.metadataOf(cert)
            val fingerprint = certMeta.fingerprint

            // Persist the private key + certificate as a new PKCS#12 file.
            val password = keystorePassword()
            val ks = UserCertificatePkcs12.pack(key, arrayOf(cert), password)
            files.save(fingerprint, ks, password)

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
        legacy.ensureMigrated()
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

        // Re-encrypt with our own random password and store in app-private storage.
        val storePassword = keystorePassword()
        val newKs = UserCertificatePkcs12.pack(
            opened.entry.privateKey,
            opened.entry.certificateChain,
            storePassword,
        )
        files.save(fingerprint, newKs, storePassword)

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
        legacy.ensureMigrated()
        val storePassword = keystorePassword()
        val ks = files.load(fingerprint, storePassword)
            ?: throw NoExportableCertificateException()
        val chain = ks.getCertificateChain(UserCertificatePkcs12.KEY_ALIAS)
            ?: throw NoExportableCertificateException()
        val key = ks.getKey(UserCertificatePkcs12.KEY_ALIAS, storePassword)
            ?: throw NoExportableCertificateException()

        // Re-encrypt using the caller-provided export password.
        val exportKs = UserCertificatePkcs12.pack(key, chain, password)
        files.storeTo(exportKs, out, password)
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
        legacy.ensureMigrated()
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

    /** Removes all user certificates. */
    suspend fun deleteAll() {
        legacy.ensureMigrated()
        db.withTransaction {
            dao.getAll().forEach { files.delete(it.fingerprint) }
            dao.deleteAll()
            dao.upsertConfig(UserCertificateConfigEntity())
        }
    }

    // ---- internals ----

    /**
     * The password protecting the on-disk PKCS#12 files. Generated once and
     * kept in Room, so the files stay encrypted at rest even though the app
     * never asks the user for a key-store password.
     */
    private suspend fun keystorePassword(): CharArray {
        val config = dao.getConfig()
        val existing = config?.keystorePassword
        if (!existing.isNullOrEmpty()) return existing.toCharArray()
        val generated = UserCertificateCodec.generatePassword()
        dao.upsertConfig((config ?: UserCertificateConfigEntity()).copy(keystorePassword = generated))
        return generated.toCharArray()
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
        fileFor(fingerprint).outputStream().use { output ->
            ks.store(output, password)
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
