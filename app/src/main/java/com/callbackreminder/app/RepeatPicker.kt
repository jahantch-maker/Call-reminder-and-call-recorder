package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The "Repeat" button in the New reminder dialog. Offers the same quick choices as Google Tasks
 * (daily, weekly, monthly, yearly) plus "Custom…" for every N days/weeks/months/years, weekdays
 * and an end.
 */
@Composable
fun RepeatButton(repeat: Repeat?, startMs: Long, onChange: (Repeat?) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    val start = Repeat.at(startMs)

    Box {
        OutlinedButton(
            onClick = { menuOpen = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Rounded.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(repeat?.label(start) ?: "Does not repeat")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val presets = listOf(
                null,
                Repeat(Repeat.Every.DAY),
                Repeat(Repeat.Every.WEEK),
                Repeat(Repeat.Every.MONTH),
                Repeat(Repeat.Every.YEAR),
            )
            presets.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p?.label(start) ?: "Does not repeat") },
                    onClick = {
                        menuOpen = false
                        onChange(p)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Custom…") },
                onClick = {
                    menuOpen = false
                    customOpen = true
                }
            )
        }
    }

    if (customOpen) {
        RepeatDialog(
            initial = repeat,
            startMs = startMs,
            onDismiss = { customOpen = false },
            onSave = {
                customOpen = false
                onChange(it)
            }
        )
    }
}

private enum class EndChoice { NEVER, ON_DATE, AFTER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatDialog(
    initial: Repeat?,
    startMs: Long,
    onDismiss: () -> Unit,
    onSave: (Repeat) -> Unit,
) {
    val start = Repeat.at(startMs)
    val startDate = start.toLocalDate()

    var every by remember { mutableStateOf(initial?.unit ?: Repeat.Every.WEEK) }
    var intervalText by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var days by remember {
        mutableStateOf(initial?.weekDays?.ifEmpty { null } ?: setOf(start.dayOfWeek))
    }
    var endChoice by remember {
        mutableStateOf(
            when (initial?.end) {
                is Repeat.End.OnDate -> EndChoice.ON_DATE
                is Repeat.End.AfterCount -> EndChoice.AFTER
                else -> EndChoice.NEVER
            }
        )
    }
    var endDate by remember {
        mutableStateOf((initial?.end as? Repeat.End.OnDate)?.date ?: defaultEndDate(startDate, every))
    }
    var countText by remember {
        mutableStateOf(((initial?.end as? Repeat.End.AfterCount)?.count ?: 10).toString())
    }
    var unitMenu by remember { mutableStateOf(false) }
    var pickEndDate by remember { mutableStateOf(false) }

    val interval = intervalText.toIntOrNull()?.takeIf { it in 1..99 }
    val count = countText.toIntOrNull()?.takeIf { it in 1..999 }
    val valid = interval != null &&
        (endChoice != EndChoice.AFTER || count != null) &&
        (endChoice != EndChoice.ON_DATE || !endDate.isBefore(startDate)) &&
        (every != Repeat.Every.WEEK || days.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeats every") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { v -> intervalText = v.filter { it.isDigit() }.take(2) },
                        modifier = Modifier.width(72.dp),
                        singleLine = true,
                        isError = interval == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Box {
                        FilledTonalButton(onClick = { unitMenu = true }) {
                            Text(if (interval == 1) every.one else every.many)
                        }
                        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            Repeat.Every.values().forEach { u ->
                                DropdownMenuItem(
                                    text = { Text(if (interval == 1) u.one else u.many) },
                                    onClick = {
                                        if (endChoice != EndChoice.ON_DATE) endDate = defaultEndDate(startDate, u)
                                        every = u
                                        unitMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (every == Repeat.Every.WEEK) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DayOfWeek.values().forEach { d ->
                            val on = d in days
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { days = if (on) days - d else days + d },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                    color = if (on) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("Ends", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                EndRow(selected = endChoice == EndChoice.NEVER, onSelect = { endChoice = EndChoice.NEVER }) {
                    Text("Never")
                }
                EndRow(selected = endChoice == EndChoice.ON_DATE, onSelect = { endChoice = EndChoice.ON_DATE }) {
                    Text("On")
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
                            endChoice = EndChoice.ON_DATE
                            pickEndDate = true
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(endDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())))
                    }
                }
                EndRow(selected = endChoice == EndChoice.AFTER, onSelect = { endChoice = EndChoice.AFTER }) {
                    Text("After")
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { v ->
                            countText = v.filter { it.isDigit() }.take(3)
                            endChoice = EndChoice.AFTER
                        },
                        modifier = Modifier.width(80.dp),
                        singleLine = true,
                        isError = endChoice == EndChoice.AFTER && count == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("times")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val end = when (endChoice) {
                        EndChoice.NEVER -> Repeat.End.Never
                        EndChoice.ON_DATE -> Repeat.End.OnDate(endDate)
                        EndChoice.AFTER -> Repeat.End.AfterCount(count ?: 1)
                    }
                    onSave(
                        Repeat(
                            unit = every,
                            interval = interval ?: 1,
                            weekDays = if (every == Repeat.Every.WEEK) days else emptySet(),
                            end = end,
                        )
                    )
                }
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickEndDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickEndDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        endDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickEndDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickEndDate = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun EndRow(selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        content()
    }
}

/** A daily repeat ends after a month by default, anything longer after a year. */
private fun defaultEndDate(start: LocalDate, every: Repeat.Every): LocalDate =
    if (every == Repeat.Every.DAY) start.plusMonths(1) else start.plusYears(1)
