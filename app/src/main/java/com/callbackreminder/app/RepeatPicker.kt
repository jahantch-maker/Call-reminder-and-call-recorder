package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private enum class EndChoice { NEVER, ON_DATE, AFTER }

private val dateFmt: DateTimeFormatter
    get() = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

/**
 * Full-screen repeat page, like Google Tasks: "Repeats every [N] [day/week/month/year]",
 * weekday circles for weekly repeats, when it starts, and when it ends
 * (never, on a date, or after a number of times).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatScreen(
    initial: Repeat?,
    startDate: LocalDate,
    startTime: String,
    onDismiss: () -> Unit,
    onDone: (Repeat, LocalDate) -> Unit,
) {
    var start by remember { mutableStateOf(startDate) }
    var every by remember { mutableStateOf(initial?.unit ?: Repeat.Every.WEEK) }
    var intervalText by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var days by remember {
        mutableStateOf(initial?.weekDays?.ifEmpty { null } ?: setOf(startDate.dayOfWeek))
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
    var pickDateFor by remember { mutableStateOf<String?>(null) } // "start" or "end"

    val interval = intervalText.toIntOrNull()?.takeIf { it in 1..99 }
    val count = countText.toIntOrNull()?.takeIf { it in 1..999 }
    val valid = interval != null &&
        (endChoice != EndChoice.AFTER || count != null) &&
        (endChoice != EndChoice.ON_DATE || !endDate.isBefore(start)) &&
        (every != Repeat.Every.WEEK || days.isNotEmpty())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().safeBottomPadding(0.dp)) {
                // Top bar: close on the left, Done on the right
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = valid,
                        onClick = {
                            val end = when (endChoice) {
                                EndChoice.NEVER -> Repeat.End.Never
                                EndChoice.ON_DATE -> Repeat.End.OnDate(endDate)
                                EndChoice.AFTER -> Repeat.End.AfterCount(count ?: 1)
                            }
                            onDone(
                                Repeat(
                                    unit = every,
                                    interval = interval ?: 1,
                                    weekDays = if (every == Repeat.Every.WEEK) days else emptySet(),
                                    end = end,
                                ),
                                start
                            )
                        }
                    ) { Text("Done") }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionLabel("Repeats every")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { v -> intervalText = v.filter { it.isDigit() }.take(2) },
                            modifier = Modifier.width(80.dp),
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
                                            if (endChoice != EndChoice.ON_DATE) endDate = defaultEndDate(start, u)
                                            every = u
                                            unitMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (every == Repeat.Every.WEEK) {
                        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            (0 until 7).map { firstDay.plus(it.toLong()) }.forEach { d ->
                                WeekdayCircle(d, d in days) { days = if (d in days) days - d else days + d }
                            }
                        }
                    }

                    HorizontalDivider()
                    SectionLabel("Starts")
                    OutlinedButton(onClick = { pickDateFor = "start" }, shape = RoundedCornerShape(14.dp)) {
                        Text("${start.format(dateFmt)} · $startTime")
                    }

                    HorizontalDivider()
                    SectionLabel("Ends")
                    EndRow(endChoice == EndChoice.NEVER, { endChoice = EndChoice.NEVER }) { Text("Never") }
                    EndRow(endChoice == EndChoice.ON_DATE, { endChoice = EndChoice.ON_DATE }) {
                        Text("On")
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { endChoice = EndChoice.ON_DATE; pickDateFor = "end" },
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(endDate.format(dateFmt)) }
                    }
                    EndRow(endChoice == EndChoice.AFTER, { endChoice = EndChoice.AFTER }) {
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
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    pickDateFor?.let { which ->
        val current = if (which == "start") start else endDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDateFor = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "start") start = d else endDate = d
                    }
                    pickDateFor = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDateFor = null }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WeekdayCircle(day: DayOfWeek, on: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Text(
            day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
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
