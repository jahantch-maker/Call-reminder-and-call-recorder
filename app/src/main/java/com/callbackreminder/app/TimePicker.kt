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
import androidx.compose.runtime.CompositionLocalProvider
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
    /** One more is kept, to fill in when a recent time is the same as "Tomorrow, same time". */
    private const val KEEP = MAX + 1

    fun load(c: Context): List<Long> = loadAll(c).take(MAX)

    fun loadAll(c: Context): List<Long> =
        AppSettings.getString(c, KEY).split(",").mapNotNull { it.toLongOrNull() }.take(KEEP)

    fun record(c: Context, ms: Long) {
        val minute = ms / 60_000L * 60_000L
        val list = (listOf(minute) + loadAll(c).filter { it != minute }).take(KEEP)
        AppSettings.setString(c, KEY, list.joinToString(","))
    }
}

/** The task's current time, for the "Tomorrow, same time" chip. Null: your work start on the next working day. */
val LocalSameBase = androidx.compose.runtime.compositionLocalOf<Long?> { null }

/** "Tomorrow, same time": the same clock time on the next working day, inside working hours, else your work start. */
object SameTime {
    fun next(ctx: Context, base: Long?): Pair<Long, Boolean> {
        val same = base?.takeIf { it > 0L }?.let { ReviewData.sameTimeNextDay(ctx, it) }
        return if (same != null) same to true else ReviewData.nextWorkStart(ctx) to false
    }
}

private val OffRed: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFC62828)
private val OffBg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF47161E) else Color(0xFFFDECEC)
private val PlusBg: Color @Composable get() = Tones.amber.bg
private val PlusFg: Color @Composable get() = Tones.amber.fg
private val LastDot = Color(0xFFFF6B3D)
private val FitGreen = Color(0xFF2E9E5B)
private val FitGreenBg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF123A24) else Color(0xFFE3F6EA)
private val FitGreenInk: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFFBDF5D2) else Color(0xFF145A32)
private val BusyBg = Color(0x22D93A4A)

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

        if (showRecent) {
            PanelLabel("RECENT")
            RecentChips(recent, now, selected ?: picked, latest, base = LocalSameBase.current ?: selected) { ms ->
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
        // A task editor with a length: busy times crossed out, green outline where it fits, best free slots.
        val busy = LocalPickBusy.current
        val dayBlocks = remember(day, busy) { busy?.let { CalendarData.blocks(ctx, day, it.excludeTaskId) } }
        val window = remember(day, busy) { busy?.let { WorkHours.window(ctx, day) } }
        if (busy != null) {
            val best = remember(day, busy) { CalendarData.bestSlots(ctx, day, busy.lengthMin, busy.excludeTaskId) }
            PanelLabel("BEST FREE SLOTS · ${CalendarData.lengthLabel(busy.lengthMin.toLong())}")
            if (best.isEmpty()) {
                Text(
                    if (window == null) "No working hours on this day" else "No free time left in working hours",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    best.forEach { ms ->
                        val on = (selected ?: picked) == ms
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) FitGreen else FitGreenBg)
                                .border(1.5.dp, FitGreen, RoundedCornerShape(10.dp))
                                .clickable { pick(ms) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                Fmt.timeOnly(ms),
                                color = if (on) Color.White else FitGreenInk,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    repeat(3 - best.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        TimeChipGrid(
            slots = slots,
            isPast = { m -> msOf(day, m) <= now || (latest != null && msOf(day, m) > latest) },
            isOn = { m -> (selected ?: picked) == msOf(day, m) },
            lastMinute = lastMinute,
            onPick = { m -> pick(msOf(day, m)) },
            isBusy = { m -> dayBlocks?.any { !it.done && it.overlaps(msOf(day, m), msOf(day, m) + 60_000L) } == true },
            fits = { m -> busy != null && dayBlocks != null && CalendarData.fits(dayBlocks, window, msOf(day, m), busy.lengthMin) },
        )
    }
}

/**
 * "Tomorrow, same time" first (orange), then the last 3 picks as chips (day over time). Passed ones, and ones after
 * [latest], are greyed. [base] is the task's time the first chip copies (null: your work start).
 */
@Composable
fun RecentChips(
    recent: List<Long>,
    now: Long,
    on: Long?,
    latest: Long? = null,
    compact: Boolean = false,
    base: Long? = LocalSameBase.current,
    onPick: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val (same, copied) = remember(base) { SameTime.next(ctx, base) }
    // A recent time equal to it is left out, and the next one fills its place.
    val shown = remember(recent, same) {
        (recent + RecentPicks.loadAll(ctx)).distinct().filter { it / 60_000L != same / 60_000L }.take(RecentPicks.MAX)
    }
    fun dayWord(at: java.time.ZonedDateTime, passed: Boolean) = when (at.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> at.format(DateTimeFormatter.ofPattern(if (passed) "EEE d" else "EEE d MMM", Locale.getDefault()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        SameTimeChip(same, copied, now, on, latest, compact, Modifier.weight(1f), onPick = onPick)
        shown.forEach { ms ->
            val passed = ms <= now
            val off = passed || (latest != null && ms > latest)
            val at = Instant.ofEpochMilli(ms).atZone(zone)
            val dayText = dayWord(at, passed) + if (passed) " · passed" else ""
            val sel = on == ms
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) cs.primary else cs.surface)
                    .border(1.5.dp, if (off) cs.outlineVariant else cs.primary, RoundedCornerShape(12.dp))
                    .clickable(enabled = !off) { onPick(ms) }
                    .padding(vertical = if (compact) 1.dp else 4.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val fg = when {
                    sel -> cs.onPrimary
                    off -> cs.outline
                    else -> cs.primary
                }
                Text(dayText, fontSize = if (compact) 9.5.sp else 10.5.sp, color = if (sel) cs.onPrimary else cs.outline, maxLines = 1)
                Text(
                    at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
                    fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1
                )
            }
        }
        repeat(RecentPicks.MAX - shown.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** The orange "Tmrw same" chip: [same] from [SameTime.next] ([copied] false: your work start, shown with the day only). */
@Composable
fun SameTimeChip(
    same: Long,
    copied: Boolean,
    now: Long,
    on: Long? = null,
    latest: Long? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onPick: (Long) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val at = Instant.ofEpochMilli(same).atZone(zone)
    val off = latest != null && same > latest
    val sel = on == same
    val amber = Tones.amber
    val day = if (at.toLocalDate() == today.plusDays(1)) "Tmrw" else at.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (sel) SameOrange else amber.bg)
            .border(1.5.dp, if (off) cs.outlineVariant else SameOrange, RoundedCornerShape(12.dp))
            .clickable(enabled = !off) { onPick(same) }
            .padding(vertical = if (compact) 1.dp else 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (copied) "$day same" else day, fontSize = if (compact) 9.5.sp else 10.5.sp,
            color = if (sel) Color(0xFF3A2000) else amber.fg.copy(alpha = 0.85f), maxLines = 1)
        Text(
            at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
            fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold,
            color = when {
                sel -> Color(0xFF3A2000)
                off -> cs.outline
                else -> amber.fg
            },
            maxLines = 1
        )
    }
}

/** The "Tomorrow, same time" chip's border. */
val SameOrange = Color(0xFFFF9F43)

/** The green colours of the "Text <first name>" card, for its chips, buttons and picker. */
@Composable
fun TextGreenTheme(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = cs.copy(
            primary = if (LocalDarkTheme.current) TextGreen else Color(0xFF0E9F87),
            onPrimary = if (LocalDarkTheme.current) Color(0xFF00382B) else Color.White,
            primaryContainer = if (LocalDarkTheme.current) Color(0xFF0F4A3A) else Color(0xFFD5F4EC),
            onPrimaryContainer = if (LocalDarkTheme.current) Color(0xFFBFF3DF) else Color(0xFF05584A),
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    /** Packed for the add windows: the time and a small Pick on one line, slim chips, no RECENT label. */
    compact: Boolean = false,
    /** Shown first on the compact line (the text times). */
    leading: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    /** The time "Tomorrow, same time" copies; by default the chosen time. */
    sameBase: Long? = selected?.takeIf { it > 0L },
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
    if (compact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                leading?.invoke(this)
                if (showSelected && selected != null && selected > 0L) {
                    Row(
                        Modifier
                            .align(Alignment.CenterVertically)
                            .clip(RoundedCornerShape(9.dp))
                            .border(1.5.dp, cs.onSurface.copy(alpha = 0.8f), RoundedCornerShape(9.dp))
                            .padding(start = 8.dp, end = if (onClear != null) 2.dp else 8.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🕒 " + Fmt.dueLabel(selected), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (onClear != null) {
                            Text(" ✕", fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 5.dp))
                        }
                    }
                }
                Text(
                    pickLabel,
                    color = cs.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.5.dp, cs.primary.copy(alpha = 0.7f), RoundedCornerShape(9.dp))
                        .clickable { open = true }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
            if (plusRow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("+15m" to 15, "+30m" to 30, "+1h" to 60, "+2h" to 120).forEach { (label, min) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PlusBg)
                                .clickable { pick(System.currentTimeMillis() + min * 60_000L) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = PlusFg, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalSameBase provides (sameBase ?: LocalSameBase.current)) {
                RecentChips(recent, now, if (showSelected) selected else null, latest, compact = true) { pick(it) }
            }
        }
    } else
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
        PanelLabel("RECENT")
        CompositionLocalProvider(LocalSameBase provides (sameBase ?: LocalSameBase.current)) {
            RecentChips(recent, now, if (showSelected) selected else null, latest) { pick(it) }
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) { Text(pickLabel, fontWeight = FontWeight.SemiBold) }
    }
    if (open) {
        CompositionLocalProvider(LocalSameBase provides (sameBase ?: LocalSameBase.current)) {
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
    /** Your own busy time (Calendar): crossed out, still pickable (a clash warning follows). */
    isBusy: (Int) -> Boolean = { false },
    /** The task's whole length fits here: a green outline. */
    fits: (Int) -> Boolean = { false },
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
                            val busy = !past && isBusy(m)
                            val fit = !past && !busy && fits(m)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        when {
                                            on -> cs.primary
                                            past -> cs.surfaceVariant.copy(alpha = 0.5f)
                                            busy -> BusyBg
                                            else -> cs.primaryContainer
                                        }
                                    )
                                    .then(if (fit && !on) Modifier.border(2.dp, FitGreen, RoundedCornerShape(9.dp)) else Modifier)
                                    .then(if (onPick != null && !past) Modifier.clickable { onPick(m) } else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    TimeGrid.label(m),
                                    fontSize = 13.sp,
                                    // Whole hours a little bolder so the eye finds them.
                                    fontWeight = if (m % 60 == 0 && !past) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    textDecoration = if (busy && !on) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                    color = when {
                                        on -> cs.onPrimary
                                        past -> cs.outline.copy(alpha = 0.6f)
                                        busy -> cs.error.copy(alpha = 0.75f)
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
