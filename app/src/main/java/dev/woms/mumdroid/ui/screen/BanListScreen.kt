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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import dev.woms.mumdroid.core.model.BanAddresses
import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.BanTimes

/**
 * Desktop `BanEditor` as a screen: list, add, edit, remove. Changes are
 * sent as a full BanList replacement, matching PC `accept()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BanListScreen(
    bans: List<BanEntry>?,
    isRefreshing: Boolean = false,
    onBack: () -> Unit,
    onReplace: (List<BanEntry>) -> Unit,
    onRefresh: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<BanEditorState?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<Int, BanEntry>?>(null) }

    val current = editor
    if (current != null && bans != null) {
        BanEditScreen(
            state = current,
            onSave = { next ->
                val list = bans.toMutableList()
                if (current.index == null) list += next else list[current.index] = next
                onReplace(list)
                editor = null
            },
            onUnban = {
                val index = current.index
                if (index != null) {
                    onReplace(bans.filterIndexed { i, _ -> i != index })
                }
                editor = null
            },
            onDismiss = { editor = null },
        )
        return
    }

    BackHandler(onBack = onBack)
    val filtered = remember(bans, query) {
        val list = bans ?: return@remember emptyList()
        val q = query.trim()
        if (q.isEmpty()) list.mapIndexed { index, ban -> index to ban }
        else list.mapIndexed { index, ban -> index to ban }.filter { (_, ban) ->
            BanAddresses.label(ban.name, ban.address, ban.hash, ban.mask)
                .contains(q, ignoreCase = true) ||
                ban.reason.contains(q, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (bans == null) {
                            stringResource(R.string.ban_list)
                        } else {
                            stringResource(R.string.ban_list_count, bans.size)
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
                actions = {
                    IconButton(
                        onClick = { editor = BanEditorState() },
                        enabled = bans != null,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_ban))
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
                label = { Text(stringResource(R.string.search_bans)) },
            )
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    bans == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    filtered.isEmpty() -> {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                stringResource(R.string.no_bans),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(filtered, key = { _, item -> item.first }) { _, item ->
                                val (index, ban) = item
                                BanRow(
                                    ban = ban,
                                    onEdit = { editor = BanEditorState.from(ban, index) },
                                    onDelete = { pendingDelete = index to ban },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    val deleting = pendingDelete
    if (deleting != null && bans != null) {
        DeleteBanDialog(
            label = BanAddresses.label(
                deleting.second.name,
                deleting.second.address,
                deleting.second.hash,
                deleting.second.mask,
            ),
            onConfirm = {
                onReplace(bans.filterIndexed { i, _ -> i != deleting.first })
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun BanRow(
    ban: BanEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 12.dp),
        ) {
            Text(
                BanAddresses.label(ban.name, ban.address, ban.hash, ban.mask),
                style = MaterialTheme.typography.bodyLarge,
            )
            val detail = listOfNotNull(
                ban.reason.trim().ifEmpty { null },
                if (ban.duration <= 0) {
                    stringResource(R.string.ban_permanent)
                } else {
                    banEndLabel(ban.start, ban.duration)
                },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_ban))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeleteBanDialog(
    label: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ban_unban)) },
        text = { Text(stringResource(R.string.delete_ban_confirm, label)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.ban_unban))
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
private fun banEndLabel(startIso: String, duration: Int): String {
    val start = BanTimes.parse(startIso)
    return if (start == null) {
        stringResource(R.string.ban_hours, (duration / 3600).coerceAtLeast(0))
    } else {
        BanTimes.format(start.plusSeconds(duration.toLong()))
    }
}
