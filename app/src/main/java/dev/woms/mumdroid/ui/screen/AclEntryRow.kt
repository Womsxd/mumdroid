package dev.woms.mumdroid.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.AclUserNames
import dev.woms.mumdroid.core.model.ChanACL
import dev.woms.mumdroid.core.model.ChanAclDraft
import dev.woms.mumdroid.core.model.ChanAclRule
import dev.woms.mumdroid.core.model.ChannelAclEdit
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Fixed height of an entries-list row, so a drag maps cleanly onto a distance. */
private val ENTRY_ROW_HEIGHT = 72.dp

/** Leading slot of an editable row, holding the drag handle. */
private val ENTRY_HANDLE_WIDTH = 40.dp

/** Permission names listed per entry before the summary is cut short. */
private const val SUMMARY_NAMES = 4

/** Share of a row's width a left swipe must cover before it asks to delete. */
private const val SWIPE_TRIGGER_FRACTION = 0.35f

/**
 * One row of the list: a handle to reorder it, its label and summary, and a
 * left swipe that asks to remove it.
 *
 * Both gestures only exist on rows this channel owns — an inherited row belongs
 * to a parent channel, so it can neither be moved nor removed here (desktop
 * `numInheritACL`, `ACLEnableCheck`).
 *
 * The drag slides the row under the finger and applies the new position on
 * release: unlike the output-device list, the rows here have no stable identity
 * to key the item on, and shuffling them live would tear down the very node
 * holding the gesture. The swipe only uncovers the delete colour and springs
 * back — removing is the confirmation dialog's decision, not the gesture's —
 * which is also why this is a horizontal drag of its own rather than
 * `SwipeToDismissBox`: that component's veto hook (`confirmValueChange`) is
 * deprecated in favour of owning the anchors, which is what this does.
 */
@Composable
internal fun AclEntryRow(
    draft: ChanAclDraft,
    index: Int,
    userNames: AclUserNames,
    names: List<Pair<Int, String>>,
    onOpen: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRequestDelete: (Int) -> Unit,
) {
    val rule = draft.rules[index]
    val editable = ChannelAclEdit.rowEditable(rule)
    var dragging by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val swipeX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { ENTRY_ROW_HEIGHT.toPx() }
    val haptic = LocalHapticFeedback.current
    val revealedWidth = with(density) { (-swipeX.value).coerceAtLeast(0f).toDp() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ENTRY_ROW_HEIGHT)
            .onSizeChanged { rowWidthPx = it.width },
    ) {
        // Behind the row, so only the strip the row has moved away from shows.
        DeleteSwipeStrip(revealedWidth)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ENTRY_ROW_HEIGHT)
                .zIndex(if (dragging) 1f else 0f)
                .offset {
                    IntOffset(
                        swipeX.value.roundToInt(),
                        if (dragging) dragOffsetY.roundToInt() else 0,
                    )
                }
                .background(
                    if (dragging) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The grab area is the whole leading slot, not just the glyph, so
            // the handle is as easy to catch as a list row's touch target. Rows
            // this channel does not own have no handle, and no slot for one
            // either: their text starts at the block title's margin.
            if (editable) {
                Box(
                    modifier = Modifier
                        .width(ENTRY_HANDLE_WIDTH)
                        .fillMaxHeight()
                        .pointerInput(index) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    val delta = (dragOffsetY / rowHeightPx).roundToInt()
                                    dragging = false
                                    dragOffsetY = 0f
                                    if (delta != 0) {
                                        onMove(index, index + delta)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetY += amount.y
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = stringResource(R.string.acl_reorder_handle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (editable) {
                            // Horizontal only, so a vertical flick still scrolls
                            // the list and the handle keeps its own drag.
                            Modifier.pointerInput(index) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val triggered = rowWidthPx > 0 &&
                                            -swipeX.value >= rowWidthPx * SWIPE_TRIGGER_FRACTION
                                        if (triggered) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onRequestDelete(index)
                                        }
                                        scope.launch { swipeX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                    },
                                    onDragCancel = {
                                        scope.launch { swipeX.animateTo(0f, spring()) }
                                    },
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        scope.launch {
                                            swipeX.snapTo(
                                                (swipeX.value + amount)
                                                    .coerceIn(-rowWidthPx.toFloat(), 0f),
                                            )
                                        }
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onOpen(index) }
                    .padding(
                        start = if (editable) 0.dp else 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    ChannelAclEdit.ruleLabel(draft, userNames, index),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (rule.inherited) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AclEntrySummary(rule, names)
            }
        }
    }
    HorizontalDivider()
}

/** The delete colour a left swipe uncovers, clipped to the part it uncovered. */
@Composable
private fun BoxScope.DeleteSwipeStrip(width: Dp) {
    if (width <= 0.dp) return
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(width)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 16.dp),
        )
    }
}

/**
 * What an entry grants and denies, so the list can be read without opening
 * every row in turn — the desktop prints the same summary from
 * `ChanACL::operator QString`, shortened here to fit a list row.
 */
@Composable
private fun AclEntrySummary(rule: ChanAclRule, names: List<Pair<Int, String>>) {
    val separator = stringResource(R.string.acl_list_separator)
    val allowed = maskNames(rule.grant, names)
    val denied = maskNames(rule.deny, names)
    val summary = listOfNotNull(
        allowed.takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.acl_entry_allowed, formatNames(it, separator))
        },
        denied.takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.acl_entry_denied, formatNames(it, separator))
        },
    ).joinToString(" · ")
    if (summary.isEmpty()) return
    Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun maskNames(mask: Long, names: List<Pair<Int, String>>): List<String> =
    names.filter { ChanACL.has(mask, it.first) }.map { it.second }

private fun formatNames(names: List<String>, separator: String): String {
    val shown = names.take(SUMMARY_NAMES).joinToString(separator)
    return if (names.size > SUMMARY_NAMES) "$shown…" else shown
}
