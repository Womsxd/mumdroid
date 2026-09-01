package dev.woms.mumdroid.ui.screen.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AppSettings

// ---- General ----

@Composable
internal fun GeneralSettingsScreen(settings: AppSettings, onChanged: (AppSettings) -> Unit, modifier: Modifier) {
    val context = LocalContext.current

    // POST_NOTIFICATIONS is a runtime permission on Android 13+: turning chat
    // notifications on first asks the system. If the user declines, the switch
    // stays off — enabling a setting that can never deliver a notification
    // would only mislead. The launcher callback is kept fresh by Compose, so
    // it always applies the latest `settings` snapshot on grant.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onChanged(settings.copy(chatNotifications = true))
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(R.string.sec_behaviour))
        SwitchRow(
            title = stringResource(R.string.stay_awake),
            subtitle = stringResource(R.string.stay_awake_sub),
            checked = settings.stayAwake,
            onCheckedChange = { onChanged(settings.copy(stayAwake = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.earpiece_proximity_fade),
            subtitle = stringResource(R.string.earpiece_proximity_fade_sub),
            checked = settings.earpieceProximityFade,
            onCheckedChange = { onChanged(settings.copy(earpieceProximityFade = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.chat_notifications),
            subtitle = stringResource(R.string.chat_notifications_sub),
            checked = settings.chatNotifications,
            onCheckedChange = { enable ->
                if (!enable || hasNotificationPermission(context)) {
                    // Turning off needs no permission; turning on with the
                    // permission already granted lands immediately.
                    onChanged(settings.copy(chatNotifications = enable))
                    return@SwitchRow
                }
                // Ask first; the setting only flips on grant.
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }
}

/** Whether the app may post notifications (pre-13 platforms grant implicitly). */
private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
