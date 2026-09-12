package dev.woms.mumdroid.ui

import android.app.Application
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.data.CertificateFileCorruptException
import dev.woms.mumdroid.data.NoExportableCertificateException
import dev.woms.mumdroid.data.UnusableCertificateException
import dev.woms.mumdroid.data.UserCertificatePkcs12
import dev.woms.mumdroid.data.UserCertificateStore
import dev.woms.mumdroid.data.WrongPasswordException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the user client certificate state (active certificate, installed list,
 * generation/import/export errors) independently of the live session.
 * PKCS#12 SAF pickers and password dialogs stay in IdentitySettingsActivity.
 */
internal class UserCertificateController(
    private val application: Application,
    private val scope: CoroutineScope,
) {
    private val store = UserCertificateStore(application)

    private val _userCertificate = MutableStateFlow(UserCertificate.NONE)
    val userCertificate: StateFlow<UserCertificate> = _userCertificate.asStateFlow()

    private val _userCertificates = MutableStateFlow<List<UserCertificate>>(emptyList())
    val userCertificates: StateFlow<List<UserCertificate>> = _userCertificates.asStateFlow()

    /** Last user-certificate generation failure message (null when none). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /** Maps a data-layer unusable reason to its localised message resource. */
    private fun unusableMessageRes(reason: UserCertificatePkcs12.UnusableReason): Int = when (reason) {
        UserCertificatePkcs12.UnusableReason.NO_PRIVATE_KEY -> R.string.import_cert_no_private_key
        UserCertificatePkcs12.UnusableReason.NO_CERTIFICATE -> R.string.import_cert_no_certificate
        UserCertificatePkcs12.UnusableReason.EXPIRED -> R.string.import_cert_expired
    }

    init {
        scope.launch {
            _userCertificate.value = store.load()
            _userCertificates.value = store.loadAll()
        }
    }

    fun generate(username: String) {
        scope.launch {
            try {
                store.generate(username)
                _userCertificate.value = store.load()
                _userCertificates.value = store.loadAll()
                _error.value = null
            } catch (_: Exception) {
                // Surface the failure instead of silently swallowing it: the
                // user pressed "generate" and deserves to know it did not work.
                _error.value = application.getString(R.string.generate_certificate_failed)
            }
        }
    }

    /** Deletes the user client certificate with the given fingerprint. */
    fun delete(fingerprint: String) {
        scope.launch {
            store.delete(fingerprint)
            _userCertificate.value = store.load()
            _userCertificates.value = store.loadAll()
        }
    }

    /** Selects the user certificate with the given fingerprint as the active one. */
    fun select(fingerprint: String) {
        scope.launch {
            store.select(fingerprint)
            _userCertificate.value = store.load()
            _userCertificates.value = store.loadAll()
        }
    }

    /**
     * Imports a user certificate from a PKCS#12 (.p12/.pfx) file. On success the
     * loaded certificate replaces the current one.
     *
     * @param p12Bytes the raw bytes of the selected .p12/.pfx file.
     * @param password the password protecting the file.
     * @param onNeedPassword invoked when the file cannot be opened with the given
     *   (empty) password, indicating the user should be prompted for it.
     * @param onError invoked with a human-readable message on failure.
     */
    fun import(
        p12Bytes: ByteArray,
        password: CharArray,
        onNeedPassword: () -> Unit = {},
        onError: (String) -> Unit,
    ) {
        scope.launch {
            try {
                store.import(p12Bytes, password)
                _userCertificate.value = store.load()
                _userCertificates.value = store.loadAll()
            } catch (_: WrongPasswordException) {
                onNeedPassword()
            } catch (e: UnusableCertificateException) {
                onError(application.getString(unusableMessageRes(e.reason)))
            } catch (_: CertificateFileCorruptException) {
                onError(application.getString(R.string.import_cert_corrupt))
            } catch (_: Exception) {
                onError(application.getString(R.string.import_cert_failed))
            }
        }
    }

    /**
     * Exports the user certificate (and private key) with the given fingerprint
     * as a PKCS#12 file.
     *
     * @param fingerprint the SHA-256 fingerprint of the certificate to export.
     * @param out the destination stream (e.g. from the SAF file picker).
     * @param password the password to protect the exported file with.
     * @param onError invoked with a human-readable message on failure.
     */
    fun export(fingerprint: String, out: java.io.OutputStream, password: CharArray, onError: (String) -> Unit) {
        scope.launch {
            try {
                store.exportTo(fingerprint, out, password)
            } catch (_: NoExportableCertificateException) {
                onError(application.getString(R.string.export_cert_no_certificate))
            } catch (_: Exception) {
                onError(application.getString(R.string.export_cert_failed))
            }
        }
    }
}
