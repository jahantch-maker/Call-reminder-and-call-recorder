package com.callbackreminder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Settings list where the user picks, orders and removes the popup's quick times (1 to 12). */
@Composable
fun QuickTimesEditor() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(QuickTimes.load(ctx)) }
    var adding by remember { mutableStateOf(false) }

    fun update(newList: List<QuickTime>) {
        list = newList
        QuickTimes.save(ctx, newList)
    }

    list.forEachIndexed { i, qt ->
        if (i > 0) HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(qt.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { update(list.toMutableList().apply { add(i - 1, removeAt(i)) }) },
                enabled = i > 0
            ) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up") }
            IconButton(
                onClick = { update(list.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                enabled = i < list.lastIndex
            ) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down") }
            IconButton(
                onClick = { update(list.toMutableList().apply { removeAt(i) }) },
                enabled = list.size > 1
            ) { Icon(Icons.Rounded.Close, contentDescription = "Remove") }
        }
    }

    Text(
        "${list.size} of ${QuickTime.MAX} · at least 1 is needed",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
