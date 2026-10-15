package com.callbackreminder.app

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The date & time window, laid out like Google Tasks: month with ‹ › arrows, the calendar,
 * a "Set time" row (opens a clock) and, when [allowRepeat], a "Repeat" row (opens the full-screen
 * repeat page). Cancel / Done at the bottom.
 */
@Composable
fun DateTimeDialog(
    initialMs: Long,
    initialRepeat: Repeat? = null,
    allowRepeat: Boolean = false,
    onDone: (whenMs: Long, repeat: Repeat?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val initial = Instant.ofEpochMilli(initialMs).atZone(zone)
    val today = LocalDate.now(zone)

    var date by remember { mutableStateOf(initial.toLocalDate()) }
    var time by remember { mutableStateOf(LocalTime.of(initial.hour, initial.minute)) }
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var repeat by remember { mutableStateOf(initialRepeat) }
    var showClock by remember { mutableStateOf(false) }
    var showRepeat by remember { mutableStateOf(false) }

    val is24h = DateFormat.is24HourFormat(ctx)
    val timeFmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault())
    fun startMs() = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                // Month and arrows
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { month = month.minusMonths(1) },
                        enabled = month.isAfter(YearMonth.from(today))
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }

                MonthGrid(
                    month = month,
                    selected = date,
                    today = today,
                    onPick = { date = it },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                OptionRow(
                    icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                    label = "Set time",
                    value = time.format(timeFmt),
                    onClick = { showClock = true }
                )
                if (allowRepeat) {
                    OptionRow(
                        icon = { Icon(Icons.Rounded.Repeat, contentDescription = null) },
                        label = "Repeat",
                        value = repeat?.label(Repeat.at(startMs())),
                        onClick = { showRepeat = true },
                        onClear = if (repeat != null) ({ repeat = null }) else null
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onDone(startMs(), repeat) }) { Text("Done") }
                }
            }
        }
    }

    if (showClock) {
        ClockDialog(
            initial = time,
            is24h = is24h,
            onPicked = { time = it; showClock = false },
            onDismiss = { showClock = false }
        )
    }

    if (showRepeat) {
        RepeatScreen(
            initial = repeat,
            startDate = date,
            startTime = time.format(timeFmt),
            onDismiss = { showRepeat = false },
            onDone = { r, start ->
                repeat = r
                date = start
                month = YearMonth.from(start)
                showRepeat = false
            }
        )
    }
}

/** Kept for the Change time / Snooze / after-call screens: the same window, without Repeat. */
@Composable
fun DateTimePickerFlow(
    initialMs: Long,
    onPicked: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DateTimeDialog(
        initialMs = initialMs,
        onDone = { ms, _ -> RecentPicks.record(ctx, ms); onPicked(ms) },
        onDismiss = onDismiss
    )
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val weekDays = (0 until 7).map { firstDay.plus(it.toLong()) }
    val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            weekDays.forEach { d ->
                Text(
                    d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day != null) DayCell(day, day == selected, day == today, !day.isBefore(today)) { onPick(day) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, selected: Boolean, isToday: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(scheme.primary) else Modifier)
            .then(
                if (isToday && !selected) Modifier.border(BorderStroke(1.dp, scheme.primary), CircleShape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            day.dayOfMonth.toString(),
            color = when {
                selected -> scheme.onPrimary
                !enabled -> scheme.onSurface.copy(alpha = 0.38f)
                isToday -> scheme.primary
                else -> scheme.onSurface
            },
            fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun OptionRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String?,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (onClear != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove $label", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClockDialog(
    initial: LocalTime,
    is24h: Boolean,
    onPicked: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set time") },
        text = { Row { TimePicker(state = state) } },
        confirmButton = { TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
