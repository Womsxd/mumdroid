package dev.woms.mumdroid.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AccessTokens

/**
 * Desktop `Tokens` dialog: the local user's access-token list for this server.
 * Tokens are shown in plain text; add/edit/remove persist immediately.
 */
@Composable
fun AccessTokensDialog(
    tokens: List<String>,
    onReplace: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var editor by remember { mutableStateOf<TokenEditor?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.access_tokens)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.access_tokens_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (tokens.isEmpty()) {
                    Text(
                        stringResource(R.string.no_access_tokens),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        tokens.forEachIndexed { index, token ->
                            if (index > 0) HorizontalDivider()
                            TokenRow(
                                token = token,
                                onEdit = { editor = TokenEditor.Edit(token) },
                                onDelete = {
                                    onReplace(AccessTokens.remove(tokens, token))
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = { editor = TokenEditor.Add },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.add_access_token))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        },
    )

    val currentEditor = editor
    if (currentEditor != null) {
        TokenEditDialog(
            editor = currentEditor,
            onSave = { value ->
                val next = when (currentEditor) {
                    TokenEditor.Add -> AccessTokens.add(tokens, value)
                    is TokenEditor.Edit -> AccessTokens.replace(tokens, currentEditor.original, value)
                }
                onReplace(next)
                editor = null
            },
            onDismiss = { editor = null },
        )
    }
}

private sealed class TokenEditor {
    data object Add : TokenEditor()
    data class Edit(val original: String) : TokenEditor()
}

@Composable
private fun TokenRow(
    token: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            token,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_access_token))
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
private fun TokenEditDialog(
    editor: TokenEditor,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(editor) {
        mutableStateOf(if (editor is TokenEditor.Edit) editor.original else "")
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor) { focus.requestFocus() }
    val canSave = value.isNotBlank()

    fun submit() {
        if (!canSave) return
        onSave(value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editor is TokenEditor.Edit) {
                        R.string.edit_access_token
                    } else {
                        R.string.add_access_token
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.access_token)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = canSave) {
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
