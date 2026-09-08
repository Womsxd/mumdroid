package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppLanguage
import dev.woms.mumdroid.core.model.AppSettings
import dev.woms.mumdroid.core.model.DarkTheme
import dev.woms.mumdroid.core.model.ThemeColor

// ---- Appearance ----

@Composable
internal fun AppearanceSettingsScreen(settings: AppSettings, onChanged: (AppSettings) -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(R.string.sec_theme))
        // Theme colour currently only follows the system's dynamic palette.
        ThemeColorDropdown(
            themeColor = settings.themeColor,
            onThemeColorChange = { onChanged(settings.copy(themeColor = it)) },
        )
        DarkThemeDropdown(
            darkTheme = settings.darkTheme,
            onDarkThemeChange = { onChanged(settings.copy(darkTheme = it)) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.sec_language))
        LanguageDropdown(
            language = settings.language,
            onLanguageChange = { onChanged(settings.copy(language = it)) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionHeader(stringResource(R.string.sec_channel_list))
        SwitchRow(
            title = stringResource(R.string.show_user_count),
            subtitle = stringResource(R.string.show_user_count_sub),
            checked = settings.showUserCount,
            onCheckedChange = { onChanged(settings.copy(showUserCount = it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeColorDropdown(themeColor: ThemeColor, onThemeColorChange: (ThemeColor) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = themeColor.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.theme_color)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeColor.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.displayName()) },
                    onClick = {
                        onThemeColorChange(t)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeColor.displayName(): String = when (this) {
    ThemeColor.SYSTEM -> stringResource(R.string.theme_color_system)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkThemeDropdown(darkTheme: DarkTheme, onDarkThemeChange: (DarkTheme) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = darkTheme.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.dark_theme)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DarkTheme.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.displayName()) },
                    onClick = {
                        onDarkThemeChange(t)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DarkTheme.displayName(): String = when (this) {
    DarkTheme.SYSTEM -> stringResource(R.string.dark_theme_system)
    DarkTheme.ON -> stringResource(R.string.dark_theme_on)
    DarkTheme.OFF -> stringResource(R.string.dark_theme_off)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = language.displayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.displayName()) },
                    onClick = {
                        onLanguageChange(l)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.CHINESE -> stringResource(R.string.language_chinese)
}
