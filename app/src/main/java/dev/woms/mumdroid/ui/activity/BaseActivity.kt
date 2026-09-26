package dev.woms.mumdroid.ui.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.woms.mumdroid.core.appearance.NightModeManager
import dev.woms.mumdroid.core.i18n.LocaleManager
import dev.woms.mumdroid.core.model.DarkTheme
import dev.woms.mumdroid.ui.EarpieceProximityEffect
import dev.woms.mumdroid.ui.MainViewModel
import dev.woms.mumdroid.ui.theme.MumdroidTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Resolves whether the app should render in dark mode. */
@Composable
internal fun DarkTheme.effectiveDark(): Boolean = when (this) {
    DarkTheme.ON -> true
    DarkTheme.OFF -> false
    DarkTheme.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Base activity shared by every screen of the app. It applies the persisted
 * language and theme, keeps the screen on while requested, blanks the
 * screen on earpiece proximity, and provides each concrete screen with its
 * own [MainViewModel].
 */
abstract class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Locale first, then night mode: each step derives its configuration
        // from the previous one, so the night override keeps the chosen locale.
        val localized = LocaleManager.applyLocaleIfNeeded(newBase)
        super.attachBaseContext(NightModeManager.applyIfNeeded(localized))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val appSettings by vm.settings.collectAsStateWithLifecycle()
            // Only subscribe to the fields this chrome needs. Collecting the
            // whole ConnectionState would recompose every activity on roster
            // or chat updates.
            val connected by remember(vm) {
                vm.connectionState.map { it.connected }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(false)
            val outputTarget by remember(vm) {
                vm.connectionState.map { it.outputTarget }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(null)

            // Apply the selected language once it is loaded / whenever it changes.
            LaunchedEffect(appSettings.language) {
                val target = LocaleManager.localeFor(appSettings.language)
                if (target != LocaleManager.currentLocale) {
                    LocaleManager.currentLocale = target
                    recreate()
                }
            }

            // The window theme — splash, window background and system-bar icon
            // contrast — is fixed when the window is created, from the
            // configuration NightModeManager wrote in attachBaseContext, so a
            // changed preference needs a new window. Wait for the first
            // DataStore snapshot: until it lands `settings` still holds
            // AppSettings()'s compiled-in defaults, and mirroring a phantom
            // "follow the system" would drop a forced dark/light choice.
            val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
            LaunchedEffect(settingsLoaded, appSettings.darkTheme) {
                if (!settingsLoaded) return@LaunchedEffect
                if (NightModeManager.store(this@BaseActivity, appSettings.darkTheme)) {
                    recreate()
                }
            }

            // Keep the screen on while connected if requested.
            val view = LocalView.current
            if (!view.isInEditMode) {
                view.keepScreenOn = appSettings.stayAwake
                // Earpiece sessions blank the screen when held to the ear.
                EarpieceProximityEffect(
                    connected = connected,
                    outputTarget = outputTarget,
                    fade = appSettings.earpieceProximityFade,
                )
            }

            MumdroidTheme(darkTheme = appSettings.darkTheme.effectiveDark()) {
                Content(vm = vm)
            }
        }
    }

    @Composable
    protected abstract fun Content(vm: MainViewModel)
}
