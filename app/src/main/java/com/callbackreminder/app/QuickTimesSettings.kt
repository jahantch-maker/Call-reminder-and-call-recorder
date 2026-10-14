package com.callbackreminder.app

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val KEY_PREFIX = "qt:"

/**
 * Its own page under Settings: the popup's quick times (1 to 12). Hold the handle on the left
 * and drag a time up or down to change its place.
 */
@Composable
fun QuickTimesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var list by remember { mutableStateOf(QuickTimes.load(ctx)) }
    var adding by remember { mutableStateOf(false) }

    // The row being dragged, and how far it has moved from its current slot.
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun update(newList: List<QuickTime>) {
        list = newList
        QuickTimes.save(ctx, newList)
    }

    fun move(from: Int, to: Int) {
        if (from == to || to !in list.indices) return
        update(list.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** While dragging: once the row's middle passes over a neighbour, swap places with it. */
    fun swapIfNeeded() {
        val key = draggingKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == key } ?: return
        val middle = current.offset + dragOffset + current.size / 2f
        val target = items.firstOrNull {
            it.key != key && (it.key as? String)?.startsWith(KEY_PREFIX) == true &&
                middle > it.offset && middle < it.offset + it.size
        } ?: return
        val from = list.indexOfFirst { KEY_PREFIX + it.encode() == key }
        val to = list.indexOfFirst { KEY_PREFIX + it.encode() == target.key }
        if (from < 0 || to < 0) return
        // Moving changes the row's slot, so shift the offset by the same amount to keep it under the finger.
        dragOffset += (current.offset - target.offset).toFloat()
        list = list.toMutableList().apply { add(to, removeAt(from)) }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun endDrag() {
        QuickTimes.save(ctx, list)
        // Glide the row into its slot rather than jumping.
        settleJob = scope.launch {
            animate(dragOffset, 0f) { v, _ -> dragOffset = v }
            draggingKey = null
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                        Text("Quick times", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "The one-tap times on the after-call popup and the Snooze menu, in this order. " +
                            "Hold \u2261 and drag to reorder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }

            itemsIndexed(list, key = { _, qt -> KEY_PREFIX + qt.encode() }) { i, qt ->
                val key = KEY_PREFIX + qt.encode()
                val dragging = key == draggingKey
                val rowModifier = if (dragging) {
                    Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset; scaleX = 1.02f; scaleY = 1.02f }
                } else {
                    Modifier.animateItem()
                }
                Surface(
                    modifier = rowModifier.fillMaxWidth().semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Move up") { move(i, i - 1); true },
                            CustomAccessibilityAction("Move down") { move(i, i + 1); true },
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = if (dragging) 8.dp else 1.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .pointerInput(key) {
                                    detectDragGestures(
                                        onDragStart = {
                                            settleJob?.cancel()
                                            draggingKey = key
                                            dragOffset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragEnd = { endDrag() },
                                        onDragCancel = { endDrag() },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            swapIfNeeded()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(qt.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                qt.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { update(list.filterNot { it == qt }) },
                            enabled = list.size > 1
                        ) { Icon(Icons.Rounded.Close, contentDescription = "Remove") }
                    }
                }
            }

            item(key = "footer") {
                Column(Modifier.animateItem()) {
                    Text(
                        "${list.size} of ${QuickTime.MAX} · at least 1 is needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { adding = true }, enabled = list.size < QuickTime.MAX) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add time")
                        }
                        OutlinedButton(onClick = { QuickTimes.reset(ctx); list = QuickTimes.load(ctx) }) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Reset to default")
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddQuickTimeDialog(
            existing = list,
            onAdd = { update(list + it); adding = false },
            onDismiss = { adding = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddQuickTimeDialog(
    existing: List<QuickTime>,
    onAdd: (QuickTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var onDay by remember { mutableStateOf(false) }

    // "After a while"
    var amountText by remember { mutableStateOf("") }
    var span by remember { mutableStateOf(QuickTime.Span.MINUTES) }

    // "On a day"
    var daysAhead by remember { mutableIntStateOf(1) }
    var dayMenu by remember { mutableStateOf(false) }
    var sameTime by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(initialHour = 10, initialMinute = 0, is24Hour = false)

    val amount = amountText.toIntOrNull()
    val candidate: QuickTime? = if (onDay) {
        if (sameTime) QuickTime.OnDay(daysAhead, null)
        else QuickTime.OnDay(daysAhead, timeState.hour, timeState.minute)
    } else {
        if (amount != null && amount in 1..span.max) QuickTime.After(amount, span) else null
    }
    val duplicate = candidate != null && candidate in existing
    val cancel = rememberDiscardGuard(
        changed = { amountText.isNotEmpty() || onDay || daysAhead != 1 || sameTime || timeState.hour != 10 || timeState.minute != 0 },
        onClose = onDismiss,
    )

    AlertDialog(
        onDismissRequest = cancel,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = { Text("Add a quick time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onDay, onClick = { onDay = false }, label = { Text("After a while") })
                    FilterChip(selected = onDay, onClick = { onDay = true }, label = { Text("On a day") })
                }

                if (!onDay) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { v -> amountText = v.filter { it.isDigit() }.take(3) },
                        label = { Text("How many") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = amount != null && amount !in 1..span.max,
                        supportingText = { Text("1 to ${span.max}") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(span == QuickTime.Span.MINUTES, { span = QuickTime.Span.MINUTES }, { Text("Minutes") })
                        FilterChip(span == QuickTime.Span.HOURS, { span = QuickTime.Span.HOURS }, { Text("Hours") })
                        FilterChip(span == QuickTime.Span.DAYS, { span = QuickTime.Span.DAYS }, { Text("Days") })
                    }
                } else {
                    Box {
                        OutlinedButton(onClick = { dayMenu = true }) { Text(QuickTime.dayName(daysAhead)) }
                        DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                            (0..QuickTime.MAX_DAYS_AHEAD).forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(QuickTime.dayName(d)) },
                                    onClick = { daysAhead = d; dayMenu = false }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!sameTime, { sameTime = false }, { Text("At a set time") })
                        FilterChip(sameTime, { sameTime = true }, { Text("Same time as now") })
                    }
                    if (!sameTime) TimeInput(state = timeState)
                    if (daysAhead == 0) {
                        Text(
                            "If that time has already passed, it moves to tomorrow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (candidate != null) {
                    Text(
                        if (duplicate) "Already in your list" else "Shows as: ${candidate.description}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (duplicate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { candidate?.let(onAdd) },
                enabled = candidate != null && !duplicate
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
}
