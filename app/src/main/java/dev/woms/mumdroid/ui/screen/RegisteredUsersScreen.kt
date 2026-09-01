package dev.woms.mumdroid.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelTree
import dev.woms.mumdroid.core.net.RegisteredUser
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Desktop `UserEdit`: registered accounts on this server. Rename and
 * unregister persist immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisteredUsersScreen(
    users: List<RegisteredUser>?,
    channels: List<Channel>,
    isRefreshing: Boolean = false,
    onBack: () -> Unit,
    onRename: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onRefresh: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<RegisteredUser?>(null) }
    var removing by remember { mutableStateOf<RegisteredUser?>(null) }

    val filtered = remember(users, query) {
        val list = users ?: return@remember emptyList()
        val q = query.trim()
        if (q.isEmpty()) list
        else list.filter { it.name.contains(q, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (users == null) {
                            stringResource(R.string.registered_users)
                        } else {
                            stringResource(R.string.registered_users_count, users.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.search_users)) },
            )
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    users == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    filtered.isEmpty() -> {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                stringResource(R.string.no_registered_users),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filtered, key = { it.userId }) { user ->
                                RegisteredUserRow(
                                    user = user,
                                    lastChannel = ChannelTree.find(channels, user.lastChannel)?.name.orEmpty(),
                                    onRename = { renaming = user },
                                    onRemove = { removing = user },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    val current = renaming
    if (current != null) {
        RenameRegisteredUserDialog(
            original = current.name,
            onSave = { name ->
                onRename(current.userId, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    val pendingRemove = removing
    if (pendingRemove != null) {
        UnregisterUserDialog(
            userName = pendingRemove.name.ifEmpty { "#${pendingRemove.userId}" },
            onConfirm = {
                onRemove(pendingRemove.userId)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun RegisteredUserRow(
    user: RegisteredUser,
    lastChannel: String,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onRename)
                .padding(vertical = 12.dp),
        ) {
            Text(user.name.ifEmpty { "#${user.userId}" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                lastSeenLabel(user.lastSeen, lastChannel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.rename_user))
        }
        if (user.userId != 0) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.unregister_user),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun lastSeenLabel(lastSeen: String, lastChannel: String): String {
    val seen = if (lastSeen.isBlank()) {
        stringResource(R.string.last_seen_never)
    } else {
        val days = inactiveDays(lastSeen)
        if (days != null) stringResource(R.string.inactive_days, days)
        else lastSeen
    }
    val channel = lastChannel.ifEmpty { "—" }
    return stringResource(R.string.registered_user_detail, seen, channel)
}

private fun inactiveDays(iso: String): Int? {
    val seen = parseInstant(iso) ?: return null
    return ChronoUnit.DAYS.between(seen, Instant.now()).toInt().coerceAtLeast(0)
}

private fun parseInstant(iso: String): Instant? {
    return try {
        Instant.parse(iso)
    } catch (_: Exception) {
        try {
            java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
private fun RenameRegisteredUserDialog(
    original: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(original) }
    val canSave = value.isNotBlank() && value.trim() != original
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_user)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.server_username)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onSave(value) }, enabled = canSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun UnregisterUserDialog(
    userName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unregister_user)) },
        text = { Text(stringResource(R.string.unregister_user_confirm, userName)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.unregister_user))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
