package dev.woms.mumdroid.data

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.model.isPresent
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject

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
 */
/**
 * Thrown when a PKCS#12 file cannot be opened with the supplied password,
 * indicating that the caller should ask the user for the correct password.
 */
class WrongPasswordException(message: String = "Wrong password") : Exception(message)

/**
 * Thrown when a PKCS#12 file is truncated, corrupted or not a PKCS#12 file at
 * all. Re-entering the password cannot help; the caller should tell the user
 * to pick another file instead of prompting for a password again.
 */
class CertificateFileCorruptException(message: String) : Exception(message)

class UserCertificateStore(private val context: Context) {

    companion object {
        private const val TAG = "UserCertificateStore"
        private const val PREFS = "user_cert_prefs"
        private const val PREFS_PASSWORD = "password"
        private const val PREFS_LIST = "certificates"
        private const val PREFS_SELECTED = "selected_fingerprint"
        private const val CERT_FILE_PREFIX = "user_cert_"
        private const val VALIDITY_YEARS = 20L

        private fun subjectFor(username: String): String {
            var cn = username.trim().ifEmpty { "mumdroid-user" }
            // If the caller already passed a full subject, keep its CN.
            if (cn.startsWith("CN=")) {
                cn = cn.removePrefix("CN=").trim().ifEmpty { "mumdroid-user" }
            }
            // X.500 CN cannot contain commas/newlines; sanitise.
            val safe = cn.replace(Regex("[,\\n\\r]"), "_")
            return "CN=$safe"
        }

        private fun certFileName(fingerprint: String): String =
            CERT_FILE_PREFIX + fingerprint.replace(":", "") + ".p12"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Decodes the PKCS#12 keystore stored for the given fingerprint. */
    private fun storeFileFor(fingerprint: String): File =
        File(context.filesDir, certFileName(fingerprint))

    /** Returns all stored user certificates. */
    fun loadAll(): List<UserCertificate> {
        val raw = prefs.getString(PREFS_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(parseCert(arr.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse stored certificate list", e)
            emptyList()
        }
    }

    /**
     * Returns the currently selected (active) user certificate, or
     * [UserCertificate.NONE] if none is stored/selected.
     */
    fun load(): UserCertificate {
        val selected = prefs.getString(PREFS_SELECTED, null)
        return loadAll().firstOrNull { it.fingerprint == selected } ?: UserCertificate.NONE
    }

    /** The SHA-256 fingerprint of the currently selected certificate, if any. */
    fun fingerprint(): String? = load().fingerprint.ifBlank { null }

    /**
     * Returns the [X509Certificate] and [java.security.PrivateKey] of the
     * currently selected certificate for TLS client authentication, or null if
     * no certificate has been generated/imported.
     */
    fun keyStoreMaterial(): Pair<X509Certificate, java.security.PrivateKey>? {
        val cert = load()
        if (!cert.isPresent()) return null
        return try {
            val ks = loadKeyStore(cert.fingerprint, password()) ?: return null
            val entry = ks.getEntry(KEY_ALIAS, KeyStore.PasswordProtection(password())) as? KeyStore.PrivateKeyEntry ?: return null
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
    fun generate(username: String) {
        try {
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + VALIDITY_YEARS * 365L * 24 * 3600 * 1000)
            val serial = BigInteger(160, SecureRandom())

            // Generate an RSA key pair and build a self-signed X.509 certificate
            // (with client-auth usage) using Bouncy Castle, then wrap the private
            // key and certificate into a PKCS#12 keystore so it can be exported
            // like the desktop Mumble client's certificate.
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048, SecureRandom())
            val keyPair = keyGen.generateKeyPair()
            val subject = X500Name(subjectFor(username))

            val builder = JcaX509v3CertificateBuilder(
                subject,
                serial,
                notBefore,
                notAfter,
                subject,
                keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            // ExtendedKeyUsage: clientAuth (1.3.6.1.5.5.7.3.2). Strict TLS
            // stacks verify the purpose chain for client authentication (a
            // leaf with a conflicting EKU is rejected); the official Mumble
            // client's self-signed certificates carry this extension too
            // (SelfSignedCertificate.cpp: ext_key_usage = clientAuth).
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth),
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

            val certMeta = certMetadata(cert, serial)
            val fingerprint = certMeta.fingerprint

            // Persist the private key + certificate as a new PKCS#12 file.
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(KEY_ALIAS, keyPair.private, password().clone(), arrayOf(cert))
            saveKeyStore(fingerprint, ks, password())

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
     * @throws IllegalArgumentException if the file contains no private-key
     *   entry / certificate, or the certificate is expired.
     */
    fun import(p12Bytes: ByteArray, password: CharArray) {
        val ks = KeyStore.getInstance("PKCS12")
        try {
            ByteArrayInputStream(p12Bytes).use { input ->
                ks.load(input, password)
            }
        } catch (e: Exception) {
            // PKCS#12 loading reports a wrong password either through a
            // dedicated exception type (UnrecoverableKeyException, padding
            // errors) or through provider messages ("MAC verification
            // failed... wrong password"). A truncated / corrupted / non-PKCS12
            // file surfaces as a plain IOException or EOFException instead —
            // reporting that as "wrong password" would trap the user in a
            // useless password prompt.
            if (isWrongPasswordFailure(e)) {
                throw WrongPasswordException("证书密码错误或文件受密码保护")
            }
            throw CertificateFileCorruptException("证书文件损坏或格式不支持")
        }

        // Find a private-key entry. The alias is arbitrary in an imported file,
        // so we locate it by iterating the aliases rather than assuming ours.
        var entry: KeyStore.PrivateKeyEntry? = null
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (ks.isKeyEntry(alias)) {
                entry = try {
                    ks.getEntry(alias, KeyStore.PasswordProtection(password)) as? KeyStore.PrivateKeyEntry
                } catch (e: Exception) {
                    null
                }
                if (entry != null) break
            }
        }
        entry ?: throw IllegalArgumentException("证书中未找到私钥")
        val cert = entry.certificate as? X509Certificate
            ?: throw IllegalArgumentException("证书中未找到 X.509 证书")
        if (cert.notAfter.before(Date())) {
            throw IllegalArgumentException("证书已过期")
        }

        val certMeta = certMetadata(cert, cert.serialNumber)
        val fingerprint = certMeta.fingerprint

        // Re-encrypt with our own random password and store in app-private storage.
        val newKs = KeyStore.getInstance("PKCS12")
        newKs.load(null, null)
        newKs.setKeyEntry(
            KEY_ALIAS,
            entry.privateKey,
            password().clone(),
            entry.certificateChain,
        )
        saveKeyStore(fingerprint, newKs, password())

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
     */
    fun exportTo(fingerprint: String, out: OutputStream, password: CharArray) {
        val ks = loadKeyStore(fingerprint, password()) ?: throw IllegalStateException("没有可导出的用户证书")
        val chain = ks.getCertificateChain(KEY_ALIAS) ?: throw IllegalStateException("没有可导出的用户证书")
        val key = ks.getKey(KEY_ALIAS, password())
            ?: throw IllegalStateException("没有可导出的用户证书")

        // Re-encrypt using the caller-provided export password.
        val exportKs = KeyStore.getInstance("PKCS12")
        exportKs.load(null, null)
        exportKs.setKeyEntry(KEY_ALIAS, key, password, chain)
        exportKs.store(out, password)
    }

    /**
     * Marks the certificate with the given fingerprint as the active one.
     */
    fun select(fingerprint: String) {
        val exists = loadAll().any { it.fingerprint == fingerprint }
        if (exists) {
            prefs.edit().putString(PREFS_SELECTED, fingerprint).apply()
        }
    }

    /**
     * Removes the certificate with the given fingerprint (and its private key).
     * If it was the active certificate, another stored certificate is selected,
     * or the selection is cleared if none remain.
     */
    fun delete(fingerprint: String) {
        val list = loadAll().toMutableList()
        list.removeAll { it.fingerprint == fingerprint }
        persistList(list)
        storeFileFor(fingerprint).delete()

        // Re-select an active certificate if the deleted one was active.
        if (prefs.getString(PREFS_SELECTED, null) == fingerprint) {
            val next = list.firstOrNull()
            if (next != null) {
                prefs.edit().putString(PREFS_SELECTED, next.fingerprint).apply()
            } else {
                prefs.edit().remove(PREFS_SELECTED).apply()
            }
        }
    }

    /** Removes all user certificates. */
    fun deleteAll() {
        loadAll().forEach { storeFileFor(it.fingerprint).delete() }
        prefs.edit().clear().apply()
    }

    // ---- internals ----

    /**
     * Whether [e] (walking its cause chain) points to a wrong PKCS#12
     * password rather than a broken file. Android providers signal password
     * failures through dedicated types (UnrecoverableKeyException, padding
     * errors from the wrong decryption key) or well-known messages
     * ("MAC verification failed", "password was incorrect"). Everything else
     * — a plain IOException/EOFException, ASN.1 parse errors, truncated
     * input — is treated as a corrupt file.
     */
    private fun isWrongPasswordFailure(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is UnrecoverableKeyException ||
                cause is BadPaddingException ||
                cause is IllegalBlockSizeException
            ) {
                return true
            }
            val msg = cause.message?.lowercase()
            if (msg != null && (
                    msg.contains("password") ||
                        msg.contains("mac verification")
                    )
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun certMetadata(cert: X509Certificate, serial: BigInteger): UserCertificate {
        val fingerprint = sha256Fingerprint(cert)
        return UserCertificate(
            subject = cert.subjectDN.name,
            fingerprint = fingerprint,
            serial = serial.toString(16),
            notBefore = cert.notBefore.time,
            notAfter = cert.notAfter.time,
            pem = toPem(cert),
        )
    }

    private fun password(): CharArray {
        var p = prefs.getString(PREFS_PASSWORD, null)
        if (p == null) {
            p = generatePassword()
            prefs.edit().putString(PREFS_PASSWORD, p).apply()
        }
        return p.toCharArray()
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun loadKeyStore(fingerprint: String, password: CharArray): KeyStore? {
        val file = storeFileFor(fingerprint)
        if (!file.exists()) return null
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { input ->
            ks.load(input, password)
        }
        return ks
    }

    private fun saveKeyStore(fingerprint: String, ks: KeyStore, password: CharArray) {
        storeFileFor(fingerprint).outputStream().use { output ->
            ks.store(output, password)
        }
    }

    /** Adds a certificate metadata to the store and marks it active. */
    private fun addAndSelect(cert: UserCertificate) {
        val list = loadAll().toMutableList()
        // Replace an existing entry with the same fingerprint if present.
        list.removeAll { it.fingerprint == cert.fingerprint }
        list.add(cert)
        persistList(list)
        prefs.edit().putString(PREFS_SELECTED, cert.fingerprint).apply()
    }

    private fun persistList(list: List<UserCertificate>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(PREFS_LIST, arr.toString()).apply()
    }

    private fun toJson(c: UserCertificate): JSONObject = JSONObject().apply {
        put("subject", c.subject)
        put("fingerprint", c.fingerprint)
        put("serial", c.serial)
        put("not_before", c.notBefore)
        put("not_after", c.notAfter)
        put("pem", c.pem)
    }

    private fun parseCert(o: JSONObject): UserCertificate = UserCertificate(
        subject = o.optString("subject", ""),
        fingerprint = o.optString("fingerprint", ""),
        serial = o.optString("serial", ""),
        notBefore = o.optLong("not_before", 0),
        notAfter = o.optLong("not_after", 0),
        pem = o.optString("pem", ""),
    )

    private fun toPem(cert: X509Certificate): String {
        val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) {
            sb.append(b64, i, minOf(i + 64, b64.length)).append('\n')
            i += 64
        }
        sb.append("-----END CERTIFICATE-----\n")
        return sb.toString()
    }

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    private val KEY_ALIAS = "mumdroid_user_cert"
}
