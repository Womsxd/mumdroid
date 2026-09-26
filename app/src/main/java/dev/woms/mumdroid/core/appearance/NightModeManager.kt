package dev.woms.mumdroid.core.appearance

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.core.content.edit
import dev.woms.mumdroid.core.appearance.NightModeManager.current
import dev.woms.mumdroid.core.model.DarkTheme

/**
 * Applies the persisted [DarkTheme] preference to the platform window
 * configuration, so that everything laid out before Compose — the launch-window
 * background, the Android 12+ splash screen and the system-bar icon contrast —
 * follows the user's choice instead of the device's.
 *
 * DataStore stays authoritative, but it cannot be read synchronously from
 * [Context.attachBaseContext], which runs before the first frame. The
 * preference is therefore mirrored into a one-key `SharedPreferences` file that
 * *can* be read there. `BaseActivity` refreshes the mirror from the settings
 * flow on every launch, so a missing copy (an install that predates the mirror)
 * or a stale one repairs itself.
 *
 * `AppCompatDelegate` would normally own this, but every screen here is a plain
 * `ComponentActivity`. Overriding the configuration is sufficient on its own:
 * `enableEdgeToEdge` asks `decorView.resources` for the status/navigation bar
 * icon contrast, i.e. the very configuration this sets, so forcing the night
 * bits here makes the system bars follow the preference too — no
 * `SystemBarStyle` plumbing needed.
 */
object NightModeManager {

    private const val PREFS_NAME = "appearance"
    private const val KEY_DARK_THEME = "dark_theme"

    /**
     * The mirrored preference, or [DarkTheme.SYSTEM] when this install has not
     * mirrored one yet. Reading it is what [Context.attachBaseContext] does
     * before the window is created; the `SharedPreferences` instance caches the
     * parsed file, so only the first call touches disk.
     */
    fun current(context: Context): DarkTheme =
        prefs(context).getString(KEY_DARK_THEME, null)
            ?.let { runCatching { DarkTheme.valueOf(it) }.getOrNull() }
            ?: DarkTheme.SYSTEM

    /**
     * Mirrors [theme] and reports whether it differs from the mirrored value,
     * i.e. whether the caller has to recreate its window to pick it up.
     */
    fun store(context: Context, theme: DarkTheme): Boolean {
        val changed = theme != current(context)
        prefs(context).edit { putString(KEY_DARK_THEME, theme.name) }
        return changed
    }

    /**
     * Returns a context whose configuration carries the night mode [current]
     * implies, or [context] itself while the preference follows the device.
     */
    fun applyIfNeeded(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        val forced = overrideNightMode(configuration.uiMode, current(context))
        if (forced == configuration.uiMode) return context
        configuration.uiMode = forced
        return context.createConfigurationContext(configuration)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * [uiMode] with its night bits replaced by the ones [theme] implies.
 *
 * [DarkTheme.SYSTEM] returns [uiMode] untouched: following the device means
 * leaving whatever the platform reported in place.
 */
internal fun overrideNightMode(uiMode: Int, theme: DarkTheme): Int {
    val nightBits = when (theme) {
        DarkTheme.SYSTEM -> return uiMode
        DarkTheme.ON -> Configuration.UI_MODE_NIGHT_YES
        DarkTheme.OFF -> Configuration.UI_MODE_NIGHT_NO
    }
    return uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or nightBits
}
