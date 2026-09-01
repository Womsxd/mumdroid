package dev.woms.mumdroid.ui.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.woms.mumdroid.R
import dev.woms.mumdroid.ui.MainViewModel
import dev.woms.mumdroid.ui.screen.settings.SettingsHomeScreen
import dev.woms.mumdroid.ui.screen.settings.SettingsPage
import dev.woms.mumdroid.ui.screen.settings.SettingsPageScreen

/**
 * Settings root screen listing every settings category. Each category is a
 * concrete Activity; tapping an entry launches it.
 */
class SettingsActivity : BaseActivity() {

    @Composable
    override fun Content(vm: MainViewModel) {
        SettingsHomeScreen(
            onCategoryClick = { page ->
                startActivity(Intent(this, page.activityClass()))
            },
            onBack = { finish() },
        )
    }
}

/** Maps a settings category to its hosting activity. */
internal fun SettingsPage.activityClass(): Class<out Activity> = when (this) {
    SettingsPage.AUDIO -> AudioSettingsActivity::class.java
    SettingsPage.NETWORK -> NetworkSettingsActivity::class.java
    SettingsPage.IDENTITY -> IdentitySettingsActivity::class.java
    SettingsPage.APPEARANCE -> AppearanceSettingsActivity::class.java
    SettingsPage.GENERAL -> GeneralSettingsActivity::class.java
    SettingsPage.ABOUT -> AboutActivity::class.java
}

/**
 * Shared wiring of the view-model state into [SettingsPageScreen]. The
 * certificate import/export callbacks default to no-ops and are only wired up
 * by [IdentitySettingsActivity].
 */
@Composable
private fun CategorySettingsContent(
    vm: MainViewModel,
    page: SettingsPage,
    onBack: () -> Unit,
    onImportUserCertificate: () -> Unit = {},
    onExportUserCertificate: (String) -> Unit = {},
) {
    val certificates by vm.certificates.collectAsStateWithLifecycle()
    val userCertificate by vm.userCertificate.collectAsStateWithLifecycle()
    val userCertificates by vm.userCertificates.collectAsStateWithLifecycle()
    val userCertificateError by vm.userCertificateError.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()

    SettingsPageScreen(
        page = page,
        settings = appSettings,
        certificates = certificates,
        userCertificate = userCertificate,
        userCertificates = userCertificates,
        userCertificateError = userCertificateError,
        onClearUserCertificateError = { vm.clearUserCertificateError() },
        onChanged = { newSettings -> vm.updateSettings(newSettings) },
        onDeleteCertificate = { cert -> vm.deleteCertificate(cert) },
        onGenerateUserCertificate = { vm.generateUserCertificate(it) },
        onDeleteUserCertificate = { fp -> vm.deleteUserCertificate(fp) },
        onSelectUserCertificate = { fp -> vm.selectUserCertificate(fp) },
        onImportUserCertificate = onImportUserCertificate,
        onExportUserCertificate = onExportUserCertificate,
        onBack = onBack,
    )
}

/**
 * Base class for a single settings-category activity.
 *
 * Only [IdentitySettingsActivity] overrides [Content] to add its extra flows;
 * every other category uses this default implementation as-is.
 */
abstract class SettingsCategoryActivity : BaseActivity() {

    /** The settings category hosted by this activity. */
    protected abstract val page: SettingsPage

    @Composable
    override fun Content(vm: MainViewModel) {
        CategorySettingsContent(vm = vm, page = page, onBack = { finish() })
    }
}

/**
 * Identity & certificates category. Hosts the user-certificate import /
 * export (PKCS#12) flows: SAF launchers, password dialogs and error feedback.
 */
class IdentitySettingsActivity : SettingsCategoryActivity() {

    override val page get() = SettingsPage.IDENTITY

    @Composable
    override fun Content(vm: MainViewModel) {
        val context = LocalContext.current
        val contentResolver = context.contentResolver

        // ---- User certificate import / export (PKCS#12) flow ----
        var importUri by remember { mutableStateOf<Uri?>(null) }
        var importBytes by remember { mutableStateOf<ByteArray?>(null) }
        var importNeedsPassword by remember { mutableStateOf(false) }
        var importPassword by remember { mutableStateOf("") }
        var showExportDialog by remember { mutableStateOf(false) }
        var exportPassword by remember { mutableStateOf("") }
        // The fingerprint of the certificate pending export, chosen in the
        // certificate picker before the password dialog is shown.
        var exportFingerprint by remember { mutableStateOf<String?>(null) }
        // UI feedback (error message), shown as a dialog and cleared afterwards.
        var certError by remember { mutableStateOf<String?>(null) }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                // Read the file and try to import it without a password first.
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
                if (bytes == null) {
                    certError = context.getString(R.string.import_cert_failed)
                } else {
                    importNeedsPassword = false
                    vm.importUserCertificate(
                        bytes,
                        CharArray(0),
                        onNeedPassword = {
                            // The file is password-protected: ask for the password.
                            importBytes = bytes
                            importUri = uri
                            importNeedsPassword = true
                            importPassword = ""
                        },
                    ) { msg -> certError = msg }
                }
            }
        }
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-pkcs12"),
        ) { uri ->
            val fingerprint = exportFingerprint
            if (uri != null && fingerprint != null) {
                // Write the PKCS#12 file using the password entered beforehand.
                val password = exportPassword.toCharArray()
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        vm.exportUserCertificate(fingerprint, out, password) { msg -> certError = msg }
                    } ?: run { certError = context.getString(R.string.export_cert_failed) }
                } catch (e: Exception) {
                    certError = e.message ?: context.getString(R.string.export_cert_failed)
                }
                exportPassword = ""
                exportFingerprint = null
            }
        }

        CategorySettingsContent(
            vm = vm,
            page = page,
            onBack = { finish() },
            onImportUserCertificate = {
                importLauncher.launch(
                    arrayOf("application/x-pkcs12", "application/x-pfx", "application/octet-stream", "*/*"),
                )
            },
            onExportUserCertificate = { fingerprint ->
                exportFingerprint = fingerprint
                exportPassword = ""
                showExportDialog = true
            },
        )

        // Password dialog shown only when the selected .p12/.pfx file requires a
        // password (opening it with an empty password failed).
        if (importUri != null && importNeedsPassword) {
            AlertDialog(
                onDismissRequest = { importUri = null; importNeedsPassword = false; importBytes = null },
                title = { Text(context.getString(R.string.import_certificate)) },
                text = {
                    Column {
                        Text(context.getString(R.string.import_cert_password_hint))
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            label = { Text(context.getString(R.string.cert_password)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val bytes = importBytes
                        importUri = null
                        importNeedsPassword = false
                        importBytes = null
                        if (bytes != null) {
                            vm.importUserCertificate(bytes, importPassword.toCharArray()) { msg -> certError = msg }
                        }
                        importPassword = ""
                    }) {
                        Text(context.getString(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { importUri = null; importNeedsPassword = false; importBytes = null; importPassword = "" }) {
                        Text(context.getString(R.string.cancel))
                    }
                },
            )
        }

        // Password dialog shown before exporting a user certificate as PKCS#12.
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false; exportPassword = ""; exportFingerprint = null },
                title = { Text(context.getString(R.string.export_certificate)) },
                text = {
                    Column {
                        Text(context.getString(R.string.export_cert_password_hint))
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text(context.getString(R.string.cert_password)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showExportDialog = false
                        exportLauncher.launch("mumdroid-user-cert.p12")
                    }) {
                        Text(context.getString(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false; exportPassword = ""; exportFingerprint = null }) {
                        Text(context.getString(R.string.cancel))
                    }
                },
            )
        }

        // Error / feedback dialog.
        val error = certError
        if (error != null) {
            AlertDialog(
                onDismissRequest = { certError = null },
                title = { Text(context.getString(R.string.certificate_operation)) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { certError = null }) {
                        Text(context.getString(R.string.confirm))
                    }
                },
            )
        }
    }
}

class AudioSettingsActivity : SettingsCategoryActivity() {
    override val page get() = SettingsPage.AUDIO
}

class NetworkSettingsActivity : SettingsCategoryActivity() {
    override val page get() = SettingsPage.NETWORK
}

class AppearanceSettingsActivity : SettingsCategoryActivity() {
    override val page get() = SettingsPage.APPEARANCE
}

class GeneralSettingsActivity : SettingsCategoryActivity() {
    override val page get() = SettingsPage.GENERAL
}

class AboutActivity : SettingsCategoryActivity() {
    override val page get() = SettingsPage.ABOUT
}
