package dev.woms.mumdroid.core.i18n

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import dev.woms.mumdroid.core.model.AppLanguage
import java.util.Locale

/**
 * Holds the currently selected app locale so it can be applied synchronously
 * from [Context.attachBaseContext] (which runs before DataStore can be read
 * asynchronously). When [currentLocale] is null the app follows the system
 * language.
 */
object LocaleManager {

    @Volatile
    var currentLocale: Locale? = null
        set(value) {
            field = value
            // Channel::lessThan uses the client locale; keep JVM default in
            // sync so channel-name collation matches the UI language.
            if (value != null) Locale.setDefault(value)
        }

    /** Maps an [AppLanguage] preference to a [Locale]; null means "follow system". */
    fun localeFor(language: AppLanguage): Locale? = when (language) {
        AppLanguage.SYSTEM -> null
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
    }

    /**
     * Returns a context whose configuration is overridden with the current app
     * locale, or the original context when the app follows the system language.
     */
    fun applyLocaleIfNeeded(context: Context): Context {
        val locale = currentLocale ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Switches the app language and recreates the activity so the new locale is
     * applied to every screen.
     */
    fun applyLanguage(activity: Activity, language: AppLanguage) {
        currentLocale = localeFor(language)
        activity.recreate()
    }
}
