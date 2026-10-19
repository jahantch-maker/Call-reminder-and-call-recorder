package com.callbackreminder.app

import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Settings › Time picker: the first and last time on the time grid and the gap between chips
 * (8:00 AM to 9:00 PM every 30 min to start with). Saved as minutes of the day.
 */
object TimeGrid {
    private const val KEY_START = "tp_start"
    private const val KEY_END = "tp_end"
    private const val KEY_GAP = "tp_gap"
    const val DEFAULT_START = 8 * 60
    const val DEFAULT_END = 21 * 60
    const val DEFAULT_GAP = 30
    val GAPS = listOf(15, 30, 60)

    private fun int(c: Context, key: String, default: Int) =
        AppSettings.getString(c, key).toIntOrNull() ?: default

    fun start(c: Context) = int(c, KEY_START, DEFAULT_START).coerceIn(0, 23 * 60 + 59)
    fun end(c: Context) = int(c, KEY_END, DEFAULT_END).coerceIn(start(c), 23 * 60 + 59)
    fun gap(c: Context) = int(c, KEY_GAP, DEFAULT_GAP).takeIf { it in GAPS } ?: DEFAULT_GAP

    fun setStart(c: Context, minute: Int) {
        AppSettings.setString(c, KEY_START, minute.toString())
        // The last time can't be before the first.
        if (int(c, KEY_END, DEFAULT_END) < minute) AppSettings.setString(c, KEY_END, minute.toString())
    }

    fun setEnd(c: Context, minute: Int) {
        AppSettings.setString(c, KEY_END, minute.toString())
        if (int(c, KEY_START, DEFAULT_START) > minute) AppSettings.setString(c, KEY_START, minute.toString())
    }

    fun setGap(c: Context, gap: Int) = AppSettings.setString(c, KEY_GAP, gap.toString())

    /** The chips as minutes of the day. */
    fun slots(start: Int, end: Int, gap: Int): List<Int> = (start..end step gap).toList()
    fun slots(c: Context) = slots(start(c), end(c), gap(c))

    fun label(minute: Int): String {
        val h = (minute / 60) % 12
        return "${if (h == 0) 12 else h}:${(minute % 60).toString().padStart(2, '0')}"
    }

    fun clock(minute: Int): String =
        LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
}

/** The last 3 date-and-times you picked (newest first), shared by calls, texts and tasks. */
object RecentPicks {
    private const val KEY = "recent_picks"
    const val MAX = 3

    fun load(c: Context): List<Long> =
        AppSettings.getString(c, KEY).split(",").mapNotNull { it.toLongOrNull() }.take(MAX)

    fun record(c: Context, ms: Long) {
        val minute = ms / 60_000L * 60_000L
        val list = (listOf(minute) + load(c).filter { it != minute }).take(MAX)
        AppSettings.setString(c, KEY, list.joinToString(","))
    }
}

private val OffRed = Color(0xFFC62828)
private val OffBg = Color(0xFFFDECEC)
private val PlusBg = Color(0xFFFFF1D6)
private val PlusFg = Color(0xFF7A4B00)
private val LastDot = Color(0xFFFF6B3D)

/**
 * The quick time picker: Recent picks, 7 day bubbles (Today to 6 days ahead), then the time grid
 * in Morning / Afternoon / Evening. Times already gone today are greyed out. [plusRow] adds
 * +15m / +30m / +1h / +2h on top (Snooze and the after-call popup). [selected] marks the
 * current choice. Every pick is remembered for Recent and passed to [onPick].
 */
@Composable
fun TimePickerPanel(
    selected: Long? = null,
    plusRow: Boolean = false,
    showRecent: Boolean = true,
    /** Times after this are greyed (text times after the call). */
    latest: Long? = null,
    onPick: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    // Built once when the window opens, so "+15m" and the greyed times count from then.
    val now = remember { System.currentTimeMillis() }
    val today = remember { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
    val slots = remember { TimeGrid.slots(ctx) }
    val recent = remember { RecentPicks.load(ctx) }
    remember { OffDays.init(ctx) }
    val offMask by OffDays.mask.collectAsState()

    val days = remember { (0..6).map { today.plusDays(it.toLong()) } }
    fun msOf(day: LocalDate, minute: Int) =
        day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()

    var dayIndex by remember {
        val sel = selected?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val i = sel?.let { days.indexOf(it) } ?: -1
        // Today, or Tomorrow once the last time on the grid has gone.
        val start = when {
            i >= 0 -> i
            slots.isEmpty() || msOf(today, slots.last()) <= now -> 1
            else -> 0
        }
        mutableIntStateOf(start)
    }
    var picked by remember { mutableStateOf(selected) }

    fun pick(ms: Long) {
        picked = ms
        RecentPicks.record(ctx, ms)
        onPick(ms)
    }

    val lastMinute = recent.firstOrNull()?.let {
        val t = Instant.ofEpochMilli(it).atZone(zone).toLocalTime()
        t.hour * 60 + t.minute
    }

    Column(Modifier.fillMaxWidth()) {
        if (plusRow) {
            PanelLabel("IN")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("+15m" to 15, "+30m" to 30, "+1h" to 60, "+2h" to 120).forEach { (label, min) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PlusBg)
                            .clickable { pick(now + min * 60_000L) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = PlusFg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                }
            }
        }

        if (showRecent && recent.isNotEmpty()) {
            PanelLabel("RECENT")
            RecentChips(recent, now, selected ?: picked, latest) { ms ->
                val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                days.indexOf(d).takeIf { it >= 0 }?.let { dayIndex = it }
                pick(ms)
            }
        }

        val latestDay = latest?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        PanelLabel("DAY")
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, day ->
                val on = i == dayIndex
                val off = offMask and (1 shl (day.dayOfWeek.value - 1)) != 0
                val tooLate = latestDay != null && day.isAfter(latestDay)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        when (i) { 0 -> "Today"; 1 -> "Tmrw"; else -> "" },
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                        modifier = Modifier.height(14.dp)
                    )
                    Surface(
                        onClick = { dayIndex = i },
                        enabled = !tooLate,
                        shape = CircleShape,
                        color = if (on) cs.primary else cs.surface,
                        contentColor = if (on) cs.onPrimary else cs.onSurface,
                        border = if (on) null else BorderStroke(1.5.dp, cs.outlineVariant),
                        modifier = Modifier.widthIn(max = 44.dp).padding(horizontal = 1.5.dp).fillMaxWidth().aspectRatio(1f)
                            .then(if (tooLate) Modifier.alpha(0.35f) else Modifier)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                fontSize = 9.5.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (on) cs.onPrimary.copy(alpha = 0.85f) else cs.onSurfaceVariant
                            )
                            Text(day.dayOfMonth.toString(), fontSize = 14.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                        if (off) {
                            Text(
                                "OFF",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = OffRed,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(OffBg)
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        val day = days[dayIndex]
        TimeChipGrid(
            slots = slots,
            isPast = { m -> msOf(day, m) <= now || (latest != null && msOf(day, m) > latest) },
            isOn = { m -> (selected ?: picked) == msOf(day, m) },
            lastMinute = lastMinute,
            onPick = { m -> pick(msOf(day, m)) }
        )
    }
}

/** The last 3 picks as chips (day over time). Passed ones, and ones after [latest], are greyed. */
@Composable
fun RecentChips(recent: List<Long>, now: Long, on: Long?, latest: Long? = null, onPick: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        recent.forEach { ms ->
            val passed = ms <= now
            val off = passed || (latest != null && ms > latest)
            val at = Instant.ofEpochMilli(ms).atZone(zone)
            val dayText = when (at.toLocalDate()) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> at.format(DateTimeFormatter.ofPattern(if (passed) "EEE d" else "EEE d MMM", Locale.getDefault()))
            } + if (passed) " · passed" else ""
            val sel = on == ms
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) cs.primary else cs.surface)
                    .border(1.5.dp, if (off) cs.outlineVariant else cs.primary, RoundedCornerShape(12.dp))
                    .clickable(enabled = !off) { onPick(ms) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val fg = when {
                    sel -> cs.onPrimary
                    off -> cs.outline
                    else -> cs.primary
                }
                Text(dayText, fontSize = 10.5.sp, color = if (sel) cs.onPrimary else cs.outline, maxLines = 1)
                Text(
                    at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1
                )
            }
        }
        repeat(RecentPicks.MAX - recent.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** The green colours of the "Text him" card, for its chips, buttons and picker. */
@Composable
fun TextGreenTheme(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = cs.copy(
            primary = TextGreen,
            onPrimary = Color(0xFF00382B),
            primaryContainer = Color(0xFF0F4A3A),
            onPrimaryContainer = Color(0xFFBFF3DF),
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

val CallBlue = Color(0xFF9DB4FF)
val TextGreen = Color(0xFF5FD6AE)

/**
 * The short time row used in forms instead of the big grid: the chosen time (with × when [onClear] is given),
 * the 3 Recent times, and a "Pick time" button that opens the full picker in its own popup. [plusRow] keeps the
 * +15m / +30m / +1h / +2h row (Snooze, New time, after-call popup). With [latest], later times are greyed and a
 * custom time after it asks first.
 */
@Composable
fun ShortTimeRow(
    selected: Long?,
    onPick: (Long) -> Unit,
    onClear: (() -> Unit)? = null,
    pickLabel: String = "📅 Pick time",
    dialogTitle: String = "Pick time",
    plusRow: Boolean = false,
    latest: Long? = null,
    latestNote: String? = null,
    showSelected: Boolean = true,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val now = remember { System.currentTimeMillis() }
    var recent by remember { mutableStateOf(RecentPicks.load(ctx)) }
    var open by remember { mutableStateOf(false) }
    fun pick(ms: Long) {
        RecentPicks.record(ctx, ms)
        recent = RecentPicks.load(ctx)
        onPick(ms)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showSelected && selected != null && selected > 0L) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.5.dp, cs.onSurface.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, end = if (onClear != null) 4.dp else 10.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🕒 " + Fmt.dueLabel(selected), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (onClear != null) {
                    Text(
                        "  ✕",
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 6.dp)
                    )
                }
            }
        }
        if (plusRow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("+15m" to 15, "+30m" to 30, "+1h" to 60, "+2h" to 120).forEach { (label, min) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PlusBg)
                            .clickable { pick(System.currentTimeMillis() + min * 60_000L) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = PlusFg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            PanelLabel("RECENT")
            RecentChips(recent, now, if (showSelected) selected else null, latest) { pick(it) }
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) { Text(pickLabel, fontWeight = FontWeight.SemiBold) }
    }
    if (open) {
        TimePickerDialog(
            title = dialogTitle,
            selected = if (showSelected) selected else null,
            latest = latest,
            note = latestNote,
            onPick = { open = false; recent = RecentPicks.load(ctx); onPick(it) },
            onDismiss = { open = false },
        )
    }
}

/**
 * The full picker in its own popup: Recent, day bubbles and the grid. One tap on a time picks it and closes.
 * "Custom time (clock)" opens the calendar and clock; with [latest], a custom time after it asks first.
 */
@Composable
fun TimePickerDialog(
    title: String,
    selected: Long?,
    latest: Long? = null,
    note: String? = null,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    var askLate by remember { mutableStateOf<Long?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .safeBottomPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        androidx.compose.material3.IconButton(onClick = onDismiss) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Close")
                        }
                    }
                    if (note != null) {
                        Text(note, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    TimePickerPanel(selected = selected, latest = latest) { onPick(it) }
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { custom = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("🕘 Custom time (clock)", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
    if (custom) {
        DateTimePickerFlow(
            initialMs = selected ?: (System.currentTimeMillis() + 3_600_000L),
            onPicked = { ms ->
                custom = false
                if (latest != null && ms > latest) askLate = ms else onPick(ms)
            },
            onDismiss = { custom = false }
        )
    }
    askLate?.let { ms ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askLate = null },
            title = { Text("Text after the call time?") },
            text = { Text("${Fmt.dueLabel(ms)} is after the call (${Fmt.dueLabel(latest ?: ms)}). Usually texts go before you call.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { askLate = null; onPick(ms) }) { Text("Keep") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askLate = null; custom = true }) { Text("Change") } },
        )
    }
}

/** The time chips in Morning / Afternoon / Evening, 5 to a row. Also the Settings preview. */
@Composable
fun TimeChipGrid(
    slots: List<Int>,
    isPast: (Int) -> Boolean = { false },
    isOn: (Int) -> Boolean = { false },
    lastMinute: Int? = null,
    onPick: ((Int) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val groups = listOf(
        "Morning" to slots.filter { it < 12 * 60 },
        "Afternoon" to slots.filter { it in 12 * 60 until 17 * 60 },
        "Evening" to slots.filter { it >= 17 * 60 },
    ).filter { it.second.isNotEmpty() }

    Column(Modifier.fillMaxWidth()) {
        groups.forEach { (name, list) ->
            Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                Spacer(Modifier.size(6.dp))
                HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant.copy(alpha = 0.5f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                list.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { m ->
                            val past = isPast(m)
                            val on = isOn(m)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        when {
                                            on -> cs.primary
                                            past -> cs.surfaceVariant.copy(alpha = 0.5f)
                                            else -> cs.primaryContainer
                                        }
                                    )
                                    .then(if (onPick != null && !past) Modifier.clickable { onPick(m) } else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    TimeGrid.label(m),
                                    fontSize = 13.sp,
                                    // Whole hours a little bolder so the eye finds them.
                                    fontWeight = if (m % 60 == 0 && !past) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    color = when {
                                        on -> cs.onPrimary
                                        past -> cs.outline.copy(alpha = 0.6f)
                                        else -> cs.onPrimaryContainer
                                    }
                                )
                                if (m == lastMinute) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 4.dp, end = 5.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(LastDot)
                                    )
                                }
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp)
    )
}

/** Settings › Time picker: the grid's first time, last time and gap, with a preview. */
@Composable
fun TimePickerSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var start by remember { mutableIntStateOf(TimeGrid.start(ctx)) }
    var end by remember { mutableIntStateOf(TimeGrid.end(ctx)) }
    var gap by remember { mutableIntStateOf(TimeGrid.gap(ctx)) }
    var editing by remember { mutableStateOf<String?>(null) }

    SettingsPage(title = "Time picker", onBack = onBack) {
        SectionCard(
            title = "Time grid",
            subtitle = "The time chips you see when setting a time for calls, texts and My tasks."
        ) {
            Column {
                SettingValueRow("First time", TimeGrid.clock(start)) { editing = "start" }
                SettingValueRow("Last time", TimeGrid.clock(end)) { editing = "end" }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gap", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimeGrid.GAPS.forEach { g ->
                            androidx.compose.material3.FilterChip(
                                selected = gap == g,
                                onClick = { gap = g; TimeGrid.setGap(ctx, g) },
                                label = { Text("${g}m") }
                            )
                        }
                    }
                }
            }
        }
        SectionCard(
            title = "Preview",
            subtitle = "Recent shows your last 3 picks (day and time). Times already gone today are greyed out."
        ) {
            TimeChipGrid(slots = TimeGrid.slots(start, end, gap))
        }
        Text(
            "Reset to 8:00 AM to 9:00 PM, every 30 min",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable {
                    TimeGrid.setStart(ctx, TimeGrid.DEFAULT_START)
                    TimeGrid.setEnd(ctx, TimeGrid.DEFAULT_END)
                    TimeGrid.setGap(ctx, TimeGrid.DEFAULT_GAP)
                    start = TimeGrid.DEFAULT_START; end = TimeGrid.DEFAULT_END; gap = TimeGrid.DEFAULT_GAP
                }
                .padding(8.dp)
        )
    }

    editing?.let { which ->
        val m = if (which == "start") start else end
        ClockDialog(
            initial = LocalTime.of(m / 60, m % 60),
            is24h = DateFormat.is24HourFormat(ctx),
            onPicked = { t ->
                val v = t.hour * 60 + t.minute
                if (which == "start") TimeGrid.setStart(ctx, v) else TimeGrid.setEnd(ctx, v)
                start = TimeGrid.start(ctx); end = TimeGrid.end(ctx)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun SettingValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$value ›", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}
