package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.data.db.CertificateEntity

/**
 * Root settings screen: the list of setting categories. Each category is
 * hosted by its own Activity, launched via [onCategoryClick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onCategoryClick: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(SettingsPage.entries) { p ->
                SettingsCategoryRow(
                    title = stringResource(p.titleRes),
                    subtitle = stringResource(p.subtitleRes),
                    icon = p.icon,
                    onClick = { onCategoryClick(p) },
                )
            }
        }
    }
}

enum class SettingsPage(
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
) {
    AUDIO(R.string.settings_audio, R.string.settings_audio_sub, Icons.Filled.Mic),
    NETWORK(R.string.settings_network, R.string.settings_network_sub, Icons.Filled.NetworkCheck),
    IDENTITY(R.string.settings_identity, R.string.settings_identity_sub, Icons.Filled.Badge),
    APPEARANCE(R.string.settings_appearance, R.string.settings_appearance_sub, Icons.Filled.Palette),
    GENERAL(R.string.settings_general, R.string.settings_general_sub, Icons.Filled.Settings),
    ABOUT(R.string.settings_about, R.string.settings_about_sub, Icons.Filled.Info),
}

@Composable
internal fun SettingsCategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

/**
 * Host for a single settings category, used by each category Activity.
 *
 * @param onBack invoked when the top-bar back arrow is pressed; activities
 *   typically finish themselves. System back finishes the activity natively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    settings: AppSettings,
    certificates: List<CertificateEntity>,
    userCertificate: dev.woms.mumdroid.core.model.UserCertificate,
    userCertificates: List<dev.woms.mumdroid.core.model.UserCertificate>,
    userCertificateError: String?,
    onClearUserCertificateError: () -> Unit,
    onChanged: (AppSettings) -> Unit,
    onDeleteCertificate: (CertificateEntity) -> Unit,
    onGenerateUserCertificate: (String) -> Unit,
    onDeleteUserCertificate: (String) -> Unit,
    onSelectUserCertificate: (String) -> Unit,
    onImportUserCertificate: () -> Unit,
    onExportUserCertificate: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    // The certificate picker replaces the whole page (including the top bar),
    // so its back arrow only ever closes it and lands on the identity page.
    if (page == SettingsPage.IDENTITY && showPicker) {
        UserCertificatePicker(
            userCertificate = userCertificate,
            userCertificates = userCertificates,
            onSelect = onSelectUserCertificate,
            onDelete = onDeleteUserCertificate,
            onExport = onExportUserCertificate,
            onBack = { showPicker = false },
        )
        return
    }

    // The About page has its own nested navigation (about -> open source licenses),
    // so it is hosted separately with its own Scaffold rather than inside this one.
    if (page == SettingsPage.ABOUT) {
        AboutPageHost(onBack = onBack)
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when (page) {
            SettingsPage.AUDIO -> AudioSettingsScreen(settings, onChanged, Modifier.padding(padding))
            SettingsPage.NETWORK -> NetworkSettingsScreen(settings, onChanged, Modifier.padding(padding))
            SettingsPage.IDENTITY -> IdentitySettingsScreen(
                settings = settings,
                onSettingsChanged = onChanged,
                certificates = certificates,
                userCertificate = userCertificate,
                userCertificates = userCertificates,
                userCertificateError = userCertificateError,
                onClearUserCertificateError = onClearUserCertificateError,
                onDeleteCertificate = onDeleteCertificate,
                onGenerateUserCertificate = onGenerateUserCertificate,
                onDeleteUserCertificate = onDeleteUserCertificate,
                onSelectUserCertificate = onSelectUserCertificate,
                onImportUserCertificate = onImportUserCertificate,
                onExportUserCertificate = onExportUserCertificate,
                onOpenPicker = { showPicker = true },
                modifier = Modifier.padding(padding),
            )
            SettingsPage.APPEARANCE -> AppearanceSettingsScreen(settings, onChanged, Modifier.padding(padding))
            SettingsPage.GENERAL -> GeneralSettingsScreen(settings, onChanged, Modifier.padding(padding))
            SettingsPage.ABOUT -> Unit
        }
    }
}

