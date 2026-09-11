package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.woms.mumdroid.R

/**
 * The connection screen's app bar: back, disconnect, server information, and the
 * server menu with the administration screens (registered users, ban list) that
 * are only enabled for users the server actually lets administer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionTopBar(
    serverName: String,
    connected: Boolean,
    canEditRegisteredUsers: Boolean,
    canBan: Boolean,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onShowServerInfo: () -> Unit,
    onShowAccessTokens: () -> Unit,
    onOpenRegisteredUsers: () -> Unit,
    onOpenBanList: () -> Unit,
) {
    var showServerMenu by remember { mutableStateOf(false) }
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
                }
            }
        },
    )
}
