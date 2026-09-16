package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.LoopbackMode

/**
 * The connection screen's app bar: back, disconnect, server information, and the
 * server menu with the administration screens (registered users, ban list) that
 * are only enabled for users the server actually lets administer.
 *
 * The audio self-test is that menu's second level ([ServerMenuPage.SelfTest]).
 * The app's other submenus ([NestedDropdownMenu]) open beside their entry, which
 * a menu anchored in the top-right corner has no room for, so this one swaps the
 * menu's own content instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionTopBar(
    serverName: String,
    connected: Boolean,
    canEditRegisteredUsers: Boolean,
    canBan: Boolean,
    loopback: LoopbackMode,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onShowServerInfo: () -> Unit,
    onShowAccessTokens: () -> Unit,
    onOpenRegisteredUsers: () -> Unit,
    onOpenBanList: () -> Unit,
    onSetLoopback: (LoopbackMode) -> Unit,
) {
    var showServerMenu by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf(ServerMenuPage.Main) }
    LaunchedEffect(showServerMenu) {
        if (!showServerMenu) menuPage = ServerMenuPage.Main
    }
    TopAppBar(
        title = { Text(serverName.ifEmpty { stringResource(R.string.connection) }) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            IconButton(
                onClick = onShowServerInfo,
                enabled = connected,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.server_information),
                )
            }
            IconButton(onClick = onDisconnect) {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = stringResource(R.string.disconnect),
                )
            }
            Box {
                IconButton(
                    onClick = { showServerMenu = true },
                    enabled = connected,
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.server_menu),
                    )
                }
                DropdownMenu(
                    expanded = showServerMenu,
                    onDismissRequest = { showServerMenu = false },
                ) {
                    when (menuPage) {
                        ServerMenuPage.Main -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.access_tokens)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Key, contentDescription = null)
                                },
                                onClick = {
                                    showServerMenu = false
                                    onShowAccessTokens()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.registered_users)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.HowToReg, contentDescription = null)
                                },
                                enabled = canEditRegisteredUsers,
                                onClick = {
                                    showServerMenu = false
                                    onOpenRegisteredUsers()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ban_list)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Block, contentDescription = null)
                                },
                                enabled = canBan,
                                onClick = {
                                    showServerMenu = false
                                    onOpenBanList()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.loopback_self_test)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Hearing, contentDescription = null)
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { menuPage = ServerMenuPage.SelfTest },
                            )
                        }

                        ServerMenuPage.SelfTest -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.back)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { menuPage = ServerMenuPage.Main },
                            )
                            HorizontalDivider()
                            LoopbackMenuItems(
                                loopback = loopback,
                                enabled = connected,
                                onSetLoopback = onSetLoopback,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Which level of the three-dot menu is showing. */
private enum class ServerMenuPage { Main, SelfTest }

/**
 * Audio self-test ("loopback") entries: the app bar menu's second level. Both
 * are switches rather than a single item, because the two halves of the feature
 * are independently worth knowing about: the self-test itself (the microphone
 * becomes audible to nobody else) and where the audio is looped back, which
 * starts local — offline, no server round trip — and can be moved to the server
 * to exercise the whole uplink.
 *
 * The self-test is not persisted, so turning it on always means "local, right
 * now". It is switched off again from here or from the warning strip above the
 * voice bar, whichever the user reaches first.
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
