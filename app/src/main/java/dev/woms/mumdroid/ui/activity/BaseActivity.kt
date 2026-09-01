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
import dev.woms.mumdroid.core.i18n.LocaleManager
import dev.woms.mumdroid.core.model.AppTheme
import dev.woms.mumdroid.ui.EarpieceProximityEffect
import dev.woms.mumdroid.ui.MainViewModel
import dev.woms.mumdroid.ui.theme.MumdroidTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Resolves the user-selected theme (system / light / dark). */
@Composable
internal fun AppTheme.darkMode(): Boolean = when (this) {
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
    AppTheme.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Base activity shared by every screen of the app. It applies the persisted
 * language and theme, keeps the screen on while requested, blanks the
 * screen on earpiece proximity, and provides each concrete screen with its
 * own [MainViewModel].
 */
abstract class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocaleIfNeeded(newBase))
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
            }.collectAsStateWithLifecycle(vm.connectionState.value.connected)
            val outputTarget by remember(vm) {
                vm.connectionState.map { it.outputTarget }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(vm.connectionState.value.outputTarget)

            // Apply the selected language once it is loaded / whenever it changes.
            LaunchedEffect(appSettings.language) {
                val target = LocaleManager.localeFor(appSettings.language)
                if (target != LocaleManager.currentLocale) {
                    LocaleManager.currentLocale = target
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

            MumdroidTheme(darkTheme = appSettings.theme.darkMode()) {
                Content(vm = vm)
            }
        }
    }

    @Composable
    protected abstract fun Content(vm: MainViewModel)
}
