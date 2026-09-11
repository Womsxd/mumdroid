package dev.woms.mumdroid.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.LoopbackMode

/**
 * Audio self-test ("loopback") entries. Both are switches rather than a single
 * item, because the two halves of the feature are independently worth knowing
 * about: the self-test itself (the microphone becomes audible to nobody else)
 * and where the audio is looped back, which starts local — offline, no server
 * round trip — and can be moved to the server to exercise the whole uplink.
 *
 * The self-test is not persisted, so turning it on always means "local, right
 * now", and the row is also the only place it can be switched off again.
 */
@Composable
internal fun LoopbackMenuItems(
    loopback: LoopbackMode,
    enabled: Boolean,
    onSetLoopback: (LoopbackMode) -> Unit,
) {
    val active = loopback.isActive
    DropdownMenuItem(
        text = { Text(stringResource(R.string.loopback_self_test)) },
        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
        trailingIcon = {
            Switch(
                checked = active,
                enabled = enabled,
                onCheckedChange = { on -> onSetLoopback(LoopbackMode.of(enabled = on, server = false)) },
            )
        },
        onClick = { onSetLoopback(LoopbackMode.of(enabled = !active, server = false)) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.loopback_use_server)) },
        leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
        trailingIcon = {
            Switch(
                checked = loopback.isServer,
                enabled = enabled && active,
                onCheckedChange = { server ->
                    onSetLoopback(LoopbackMode.of(enabled = true, server = server))
                },
            )
        },
        // The switch already expresses both states; the row only matters while
        // the self-test runs, so a tap on it is a no-op when nothing loops.
        enabled = enabled && active,
        onClick = {
            onSetLoopback(LoopbackMode.of(enabled = true, server = !loopback.isServer))
        },
    )
}
