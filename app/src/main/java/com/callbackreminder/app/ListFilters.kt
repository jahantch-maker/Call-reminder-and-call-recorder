package com.callbackreminder.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The quick choices of a date filter. Each list page offers the ones that make sense for it. */
enum class DatePreset(val label: String) {
    ANY("Any time"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    TOMORROW("Tomorrow"),
    LAST_7("Last 7 days"),
    NEXT_7("Next 7 days"),
    THIS_MONTH("This month"),
    CUSTOM("Custom range…"),
}

/** Keeps items whose date falls on the chosen days (whole days, phone's time zone). */
data class DateFilter(
    val preset: DatePreset = DatePreset.ANY,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    val active: Boolean get() = preset != DatePreset.ANY

    private fun days(today: LocalDate): Pair<LocalDate, LocalDate>? = when (preset) {
        DatePreset.ANY -> null
        DatePreset.TODAY -> today to today
        DatePreset.YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        DatePreset.TOMORROW -> today.plusDays(1) to today.plusDays(1)
        DatePreset.LAST_7 -> today.minusDays(6) to today
        DatePreset.NEXT_7 -> today to today.plusDays(6)
        DatePreset.THIS_MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
        DatePreset.CUSTOM -> if (from != null && to != null) from to to else null
    }

    fun matches(ms: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val (start, end) = days(LocalDate.now(zone)) ?: return true
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return !day.isBefore(start) && !day.isAfter(end)
    }

    /** Like "Last 7 days" or "3 Oct – 9 Oct". */
    val label: String
        get() = if (preset == DatePreset.CUSTOM && from != null && to != null) {
            val f = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            if (from == to) from.format(f) else "${from.format(f)} – ${to.format(f)}"
        } else preset.label
}

/** How the Recordings page is ordered. */
enum class RecordingSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME_AZ("Name A–Z"),
    NAME_ZA("Name Z–A"),
    LONGEST("Longest first"),
    SHORTEST("Shortest first"),
}

/** Call length buckets for the Recordings filter. */
enum class LengthFilter(val label: String, val range: LongRange?) {
    ANY("Any length", null),
    UNDER_1("Under 1 min", 0L until 60L),
    ONE_TO_5("1–5 min", 60L until 300L),
    FIVE_TO_15("5–15 min", 300L until 900L),
    OVER_15("Over 15 min", 900L..Long.MAX_VALUE),
}

/**
 * The filters of each list page. Kept here (not in the screens) so they stay put while you
 * switch between the bottom tabs; they reset when the app is closed.
 */
object ListFilters {
    val callsCreated = mutableStateOf(DateFilter())
    val callsDue = mutableStateOf(DateFilter())
    val textsCreated = mutableStateOf(DateFilter())
    val textsDue = mutableStateOf(DateFilter())
    val recQuery = mutableStateOf("")
    val recDate = mutableStateOf(DateFilter())
    val recLength = mutableStateOf(LengthFilter.ANY)
    val recSort = mutableStateOf(RecordingSort.NEWEST)
    val recStarred = mutableStateOf(false)
    val homePerson = mutableStateOf<String?>(null)
    val homeType = mutableStateOf<WorkItem.Kind?>(null)
    val homeCreated = mutableStateOf(DateFilter())
    val homeDue = mutableStateOf(DateFilter())
    /** A bar of "Overdue by age" was tapped: 0 = 1 day … 3 = 7+ days. */
    val homeAge = mutableStateOf<Int?>(null)

    val CREATED_PRESETS = listOf(
        DatePreset.ANY, DatePreset.TODAY, DatePreset.YESTERDAY, DatePreset.LAST_7, DatePreset.THIS_MONTH, DatePreset.CUSTOM,
    )
    val DUE_PRESETS = listOf(
        DatePreset.ANY, DatePreset.TODAY, DatePreset.TOMORROW, DatePreset.YESTERDAY, DatePreset.NEXT_7,
        DatePreset.LAST_7, DatePreset.THIS_MONTH, DatePreset.CUSTOM,
    )
}

/** The "Created" and "Due" chips under the tabs of Calls and Texts, plus Clear when one is set. */
@Composable
fun CreatedDueFilterRow(
    created: DateFilter,
    due: DateFilter,
    onCreated: (DateFilter) -> Unit,
    onDue: (DateFilter) -> Unit,
    starredOnly: Boolean? = null,
    onStarred: (Boolean) -> Unit = {},
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DateFilterChip("Created", created, ListFilters.CREATED_PRESETS, onCreated)
        DateFilterChip("Due", due, ListFilters.DUE_PRESETS, onDue)
        if (starredOnly != null) StarredFilterChip(starredOnly, onStarred)
        if (created.active || due.active || starredOnly == true) {
            ClearChip { onCreated(DateFilter()); onDue(DateFilter()); onStarred(false) }
        }
    }
}

/** "★ Starred": shows only tasks starred as important. */
@Composable
fun StarredFilterChip(on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text("Starred") },
        leadingIcon = {
            Icon(
                androidx.compose.material.icons.Icons.Rounded.Star,
                contentDescription = null,
                tint = StarGold,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

@Composable
fun ClearChip(onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text("Clear") },
        leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) }
    )
}

/** A chip like "Due: Today" that opens the quick choices; "Custom range…" opens a calendar. */
@Composable
fun DateFilterChip(title: String, filter: DateFilter, presets: List<DatePreset>, onChange: (DateFilter) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var pickRange by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = filter.active,
            onClick = { menuOpen = true },
            label = { Text(if (filter.active) "$title: ${filter.label}" else title) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            presets.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    trailingIcon = if (p == filter.preset) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        menuOpen = false
                        if (p == DatePreset.CUSTOM) pickRange = true else onChange(DateFilter(p))
                    }
                )
            }
        }
    }
    if (pickRange) {
        DateRangeDialog(
            initial = filter.takeIf { it.preset == DatePreset.CUSTOM },
            onPicked = { from, to -> onChange(DateFilter(DatePreset.CUSTOM, from, to)); pickRange = false },
            onDismiss = { pickRange = false }
        )
    }
}

/** A from–to calendar. The picker works in UTC days, so dates are converted as plain days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(initial: DateFilter?, onPicked: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    fun toMs(d: LocalDate?) = d?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    fun toDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = toMs(initial?.from),
        initialSelectedEndDateMillis = toMs(initial?.to),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis ?: return@TextButton
                    val end = state.selectedEndDateMillis ?: start
                    onPicked(toDay(start), toDay(end))
                }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f), showModeToggle = false)
    }
}
