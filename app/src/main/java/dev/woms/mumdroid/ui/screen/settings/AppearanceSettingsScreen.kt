package dev.woms.mumdroid.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
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

// Thin wrappers over EnumDropdown, as in AudioDropdowns.kt.

@Composable
private fun ThemeColorDropdown(themeColor: ThemeColor, onThemeColorChange: (ThemeColor) -> Unit) {
    EnumDropdown(
        value = themeColor,
        entries = ThemeColor.entries,
        label = stringResource(R.string.theme_color),
        onValueChange = onThemeColorChange,
        labelOf = { it.displayName() },
    )
}

@Composable
private fun ThemeColor.displayName(): String = when (this) {
    ThemeColor.SYSTEM -> stringResource(R.string.theme_color_system)
}

@Composable
private fun DarkThemeDropdown(darkTheme: DarkTheme, onDarkThemeChange: (DarkTheme) -> Unit) {
    EnumDropdown(
        value = darkTheme,
        entries = DarkTheme.entries,
        label = stringResource(R.string.dark_theme),
        onValueChange = onDarkThemeChange,
        labelOf = { it.displayName() },
    )
}

@Composable
private fun DarkTheme.displayName(): String = when (this) {
    DarkTheme.SYSTEM -> stringResource(R.string.dark_theme_system)
    DarkTheme.ON -> stringResource(R.string.dark_theme_on)
    DarkTheme.OFF -> stringResource(R.string.dark_theme_off)
}

@Composable
private fun LanguageDropdown(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    EnumDropdown(
        value = language,
        entries = AppLanguage.entries,
        label = stringResource(R.string.language),
        onValueChange = onLanguageChange,
        labelOf = { it.displayName() },
    )
}

@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.CHINESE -> stringResource(R.string.language_chinese)
}
