package dev.woms.mumdroid

import android.content.res.Configuration
import dev.woms.mumdroid.core.appearance.overrideNightMode
import dev.woms.mumdroid.core.model.DarkTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies how the in-app dark-theme preference is folded into a platform
 * `uiMode`: a forced choice must replace the device's night bits without
 * disturbing the mode-type bits, and "follow the system" must leave the
 * reported value alone.
 */
class NightModeOverrideTest {

    @Test
    fun system_keepsWhateverTheDeviceReported() {
        val darkDevice = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        assertEquals(darkDevice, overrideNightMode(darkDevice, DarkTheme.SYSTEM))

        val lightDevice = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
        assertEquals(lightDevice, overrideNightMode(lightDevice, DarkTheme.SYSTEM))
    }

    @Test
    fun forcedDark_overridesALightDeviceAndKeepsTheModeType() {
        assertEquals(
            Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES,
            overrideNightMode(
                Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_NO,
                DarkTheme.ON,
            ),
        )
    }

    @Test
    fun forcedLight_overridesADarkDeviceAndKeepsTheModeType() {
        assertEquals(
            Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_NO,
            overrideNightMode(
                Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES,
                DarkTheme.OFF,
            ),
        )
    }

    @Test
    fun forcedMode_replacesTheNightBitsInsteadOfAddingThem() {
        val forced = overrideNightMode(
            Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES,
            DarkTheme.OFF,
        )
        assertEquals(
            Configuration.UI_MODE_NIGHT_NO,
            forced and Configuration.UI_MODE_NIGHT_MASK,
        )
    }
}
