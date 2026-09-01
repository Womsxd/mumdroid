package dev.woms.mumdroid.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.BuildConfig
import dev.woms.mumdroid.R
import dev.woms.mumdroid.ui.screen.OpenSourceLicensesScreen

// ---- About ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutPageHost(onBack: () -> Unit) {
    var page by remember { mutableStateOf<AboutPage>(AboutPage.ABOUT) }
    // Support the system back gesture / back button to navigate the nested
    // about -> open-source-licenses stack before exiting.
    BackHandler {
        when (page) {
            AboutPage.ABOUT -> onBack()
            AboutPage.LICENSES -> page = AboutPage.ABOUT
        }
    }
    when (page) {
        AboutPage.ABOUT -> AboutScreen(
            onBack = onBack,
            onOpenLicenses = { page = AboutPage.LICENSES },
        )
        AboutPage.LICENSES -> OpenSourceLicensesScreen(
            onBack = { page = AboutPage.ABOUT },
        )
    }
}

private enum class AboutPage { ABOUT, LICENSES }

/** About page: shows app info and an entry to the open source licenses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
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
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(
                            R.string.about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                            BuildConfig.GIT_HASH,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        stringResource(R.string.about_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                SettingsCategoryRow(
                    title = stringResource(R.string.open_source_licenses),
                    subtitle = stringResource(R.string.open_source_licenses_sub),
                    icon = Icons.Filled.Code,
                    onClick = onOpenLicenses,
                )
            }
        }
    }
}

