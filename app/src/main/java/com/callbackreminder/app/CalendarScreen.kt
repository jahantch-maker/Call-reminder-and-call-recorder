package com.callbackreminder.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The Calendar's view state, kept while you move between tabs. */
object CalendarUi {
    /** Swiped left: the follow-ups lane is wide, your own work slim. */
    val wide = mutableStateOf(false)

    /** The bottom toggle: Best fit (busy hours grow) or Working hours (fill the screen). Opens on Best fit. */
    val bestFit = mutableStateOf(true)

    /** Pinch level: 1 = normal, smaller squeezes the hours (down to the whole day), bigger stretches them. */
    val zoom = mutableFloatStateOf(1f)
}

private val TimeCol = 28.dp
private val BaseHour = 54.dp
private val CardGap = 3.dp
private val CardPad = 4.dp
private val ToggleSpace = 64.dp

// Colours that match the mockups: Dossier purple, My task blue, call blue, text green, Google amber.
/** The Calendar's card colours: the four picked in Calendar settings › Card colours, plus the fixed bits. */
private class CalColors(
    dark: Boolean,
    task: CardColours.Swatch,
    fu: CardColours.Swatch,
    dos: CardColours.Swatch,
    google: CardColours.Swatch,
) {
    val taskBg = task.fill(dark)
    val taskInk = task.ink(dark)
    val taskEdge = task.edge(dark)
    val dosBg = dos.fill(dark)
    val dosInk = dos.ink(dark)
    val dosEdge = dos.edge(dark)
    val gBg = google.fill(dark)
    val gInk = google.ink(dark)
    val gEdge = google.edge(dark)
    val callBg = fu.fill(dark)
    val callInk = fu.ink(dark)
    val callEdge = fu.edge(dark)
    val textBg = fu.fill(dark)
    val textInk = fu.ink(dark)
    val textEdge = fu.edge(dark)
    val band = if (dark) Color(0x1A5C7CFA) else Color(0x125C7CFA)
    val offHours = if (dark) Color(0x22000000) else Color(0x0A000000)
    val now = Color(0xFFE53935)
}

/** [CalColors] for the current theme and colour picks; redraws when the picks change. */
@Composable
private fun rememberCalColors(): CalColors {
    val ctx = LocalContext.current
    val dark = LocalDarkTheme.current
    val v by CardColours.version.collectAsState()
    return remember(dark, v) {
        CalColors(
            dark,
            CardColours.swatch(ctx, CardColours.Kind.TASK),
            CardColours.swatch(ctx, CardColours.Kind.FOLLOW_UP),
            CardColours.swatch(ctx, CardColours.Kind.DOSSIER),
            CardColours.swatch(ctx, CardColours.Kind.GOOGLE),
        )
    }
}

/** True unless the phone's "Remove animations" is on. */
private fun motionOn(ctx: Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f

private fun <T> calSpec(on: Boolean, ms: Int = 280): FiniteAnimationSpec<T> =
    if (on) tween(durationMillis = ms, easing = FastOutSlowInEasing) else snap()

/**
 * The Calendar tab: one day, 24 hours, your own work on the left (My tasks with a time, Dossier blocks,
 * Google Calendar events) and follow-ups on the right, each its own card. Swipe left to widen the
 * follow-ups, right to go back. The bottom toggle switches Best fit and Working hours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(resumeKey: Int, onOpenSearch: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val motion = remember(resumeKey) { motionOn(ctx) }
    val whVersion by WorkHours.version.collectAsState()
    val whSet = remember(whVersion) { WorkHours.isSet(ctx) }

    if (!whSet) {
        WorkHoursSetup(first = true, onSaved = {})
        return
    }

    var dateDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(dateDay)
    var wide by CalendarUi.wide
    var bestFit by CalendarUi.bestFit
    val wideF by animateFloatAsState(if (wide) 1f else 0f, calSpec(motion, 300), label = "lanes")
    val mix by animateFloatAsState(if (bestFit) 1f else 0f, calSpec(motion, 320), label = "fit")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumeKey) {
        now = System.currentTimeMillis()
        GoogleCal.refresh()
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }

    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDate by remember { mutableStateOf(false) }
    var openFu by remember { mutableStateOf<CalFollowUp?>(null) }
    var openBlock by remember { mutableStateOf<CalBlock?>(null) }
    var hourList by remember { mutableStateOf<List<CalFollowUp>?>(null) }
    var snoozeFu by remember { mutableStateOf<CalFollowUp?>(null) }
    val gVersion by GoogleCal.version.collectAsState()
    val googleOn = remember(gVersion) { GoogleCal.enabled(ctx) }
    var syncing by remember { mutableStateOf(false) }
    var pickCals by remember { mutableStateOf(false) }
    // First time Google is on (also right after this update): the calendar list opens once, all off.
    LaunchedEffect(googleOn) { if (googleOn && !GoogleCal.picked(ctx)) pickCals = true }
    fun syncGoogle() {
        if (syncing) return
        if (GoogleCal.onIds(ctx).isEmpty()) {
            pickCals = true
            return
        }
        syncing = true
        scope.launch {
            GoogleCal.syncNow(ctx)
            syncing = false
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar("Google Calendar updated")
        }
    }
    var addAt by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var editTask by remember { mutableStateOf<MyTask?>(null) }
    var editDossier by remember { mutableStateOf<DossierTask?>(null) }

    val day = rememberDay(date, now)
    val today = LocalDate.now()

    fun callFu(f: CalFollowUp) {
        if (f.isCall) ReminderStore.items.value.firstOrNull { it.id == f.id }?.let { r ->
            if (r.review || !r.done) FollowUp.mark(ctx, r)
        }
        dial(ctx, f.number)
    }

    fun undoFor(f: CalFollowUp): () -> Unit {
        val r = if (f.isCall) ReminderStore.items.value.firstOrNull { it.id == f.id } else null
        val t = if (!f.isCall) SmsStore.items.value.firstOrNull { it.id == f.id } else null
        val link = FollowUps.snapshot(r?.linkId ?: t?.linkId ?: 0L)
        return {
            r?.let { ReminderStore.restore(it) }
            t?.let { SmsStore.restore(it) }
            link()
        }
    }

    fun doneFu(f: CalFollowUp) {
        val undo = undoFor(f)
        if (f.isCall) ReminderStore.markDone(f.id) else SmsStore.markDone(f.id)
        scope.offerUndo(snack, "Done · ${f.title}", undo)
    }

    fun snoozeFuTo(f: CalFollowUp, ms: Long) {
        val undo = undoFor(f)
        // New time moves the task's due date: a call's texts follow it, like everywhere else.
        if (f.isCall) {
            ReminderStore.newTime(f.id, ms)
            ReminderStore.get(f.id)?.let { FollowUps.onNewTime(ctx, it, ms, true) }
        } else SmsStore.reschedule(f.id, ms)
        scope.offerUndo(snack, "Moved to ${Fmt.dueLabel(ms)}", undo)
    }

    /** Undo text after a slide or stretch, with ⚠ when it now overlaps your own items. */
    fun clashNote(id: Long): String {
        val t = MyTaskStore.get(id) ?: return ""
        val n = CalendarData.clashes(ctx, t.dueAt, t.length, id).size
        return if (n > 0) " · ⚠ clashes with $n" else ""
    }

    fun moveTask(id: Long, start: Long) {
        val before = MyTaskStore.get(id) ?: return
        MyTaskStore.setDue(id, start)
        scope.offerUndo(snack, "Moved to ${Fmt.timeOnly(start)}" + clashNote(id)) { MyTaskStore.restore(before) }
    }

    fun resizeTask(id: Long, len: Int) {
        val before = MyTaskStore.get(id) ?: return
        MyTaskStore.setLength(id, len)
        scope.offerUndo(snack, "Now ${CalendarData.lengthLabel(len.toLong())}" + clashNote(id)) { MyTaskStore.restore(before) }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize()) {
            CalHeader(
                date = date,
                isToday = date == today,
                freeMin = day.freeMin,
                fuCount = day.followUps.count { !it.done },
                motion = motion,
                onPickDate = { showDate = true },
                onToday = { dateDay = today.toEpochDay() },
                onSearch = onOpenSearch,
                onSettings = onOpenSettings,
                refreshing = syncing,
                onRefresh = if (googleOn) ({ syncGoogle() }) else null,
            )
            DayStrip(date = date, today = today, motion = motion) { dateDay = it.toEpochDay() }
            GoogleAsk()
            if (day.allDay.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = TimeCol + 4.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val cc = rememberCalColors()
                    day.allDay.forEach { e ->
                        Text(
                            "All day · ${e.title}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cc.gInk,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cc.gBg).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            LaneHeaders(wideF = wideF, onMine = { wide = false }, onFollowUps = { wide = !wide })
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AnimatedContent(
                    targetState = dateDay,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val spec = calSpec<androidx.compose.ui.unit.IntOffset>(motion, 260)
                        val fade = calSpec<Float>(motion, 200)
                        (slideInHorizontally(spec) { if (forward) it / 3 else -it / 3 } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(spec) { if (forward) -it / 3 else it / 3 } + fadeOut(fade))
                    },
                    label = "day"
                ) { epochDay ->
                    val d = LocalDate.ofEpochDay(epochDay)
                    DayBody(
                        date = d,
                        data = rememberDay(d, now),
                        now = now,
                        wideF = wideF,
                        mix = mix,
                        bestFit = bestFit,
                        motion = motion,
                        onSwipe = { toWide -> wide = toWide },
                        onFollowUp = { openFu = it },
                        onCall = { callFu(it) },
                        onHour = { hourList = it },
                        onBlock = { openBlock = it },
                        onAdd = { start, len -> addAt = start to len },
                        onMoveTask = { id, start -> moveTask(id, start) },
                        onResizeTask = { id, len -> resizeTask(id, len) },
                        onMoveFollowUp = { f, at -> snoozeFuTo(f, at) },
                        onFixed = { b ->
                            scope.launch {
                                snack.currentSnackbarData?.dismiss()
                                snack.showSnackbar(
                                    if (b.kind == CalBlock.Kind.GOOGLE) "Google events are read only. Change them in Google Calendar."
                                    else if (b.kind == CalBlock.Kind.DOSSIER) "Dossier blocks are fixed. Tap it › Change times."
                                    else "Done tasks don't move."
                                )
                            }
                        },
                    )
                }
                FitToggle(
                    bestFit = bestFit,
                    motion = motion,
                    onChange = { bestFit = it },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                )
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = ToggleSpace))
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        dateDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }

    if (pickCals) {
        GoogleCalendarsSheet(onDismiss = {
            GoogleCal.setPicked(ctx)
            pickCals = false
        })
    }
    openFu?.let { f -> CalFollowUpSheet(f, System.currentTimeMillis(), onDismiss = { openFu = null }) }
    hourList?.let { list ->
        HourSheet(list = list, onDismiss = { hourList = null }, onOpen = { hourList = null; openFu = it }, onCall = { callFu(it) })
    }
    snoozeFu?.let { f ->
        SnoozeSheet(
            heading = "New time",
            about = f.title,
            onPick = { ms -> snoozeFu = null; snoozeFuTo(f, ms) },
            onPickTime = { snoozeFu = null },
            onDismiss = { snoozeFu = null },
            plusRow = true
        )
    }
    openBlock?.takeIf { it.kind == CalBlock.Kind.TASK }?.let { b ->
        CalMyTaskSheet(b.id, System.currentTimeMillis(), onDismiss = { openBlock = null })
    }
    openBlock?.takeIf { it.kind != CalBlock.Kind.TASK }?.let { b ->
        BlockActions(
            b = b,
            date = date,
            onDismiss = { openBlock = null },
            onEditTask = { openBlock = null; editTask = MyTaskStore.get(b.id) },
            onEditDossier = { openBlock = null; editDossier = DossierStore.get(b.id) },
            onTaskDone = {
                openBlock = null
                MyTaskStore.get(b.id)?.let { before ->
                    MyTaskStore.setDone(b.id, !before.done)
                    scope.offerUndo(snack, (if (before.done) "Not done · " else "Done · ") + before.title) { MyTaskStore.restore(before) }
                }
            },
            onCallPerson = { number -> openBlock = null; dial(ctx, number) },
        )
    }
    addAt?.let { (start, len) ->
        MyTaskDialog(
            existing = null,
            onDismiss = { addAt = null },
            onSwitchKind = null,
            initialDue = start,
            initialLength = len,
        )
    }
    editTask?.let { t ->
        MyTaskDialog(existing = t, onDismiss = { editTask = null }, onSwitchKind = null)
    }
    editDossier?.let { t ->
        DossierTaskDialog(existing = t, onDismiss = { editDossier = null }, onSwitchKind = null)
    }
}

/** Everything one day shows. */
private class DayData(
    val blocks: List<CalBlock>,
    val allDay: List<GEvent>,
    val followUps: List<CalFollowUp>,
    val gaps: List<Pair<Long, Long>>,
    val freeMin: Long,
)

/** Reads a day's items (Google off the main thread) and redraws when any of them change. */
@Composable
private fun rememberDay(date: LocalDate, now: Long): DayData {
    val ctx = LocalContext.current
    val tasks by MyTaskStore.items.collectAsState()
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val g by GoogleCal.version.collectAsState()
    val wh by WorkHours.version.collectAsState()
    val off by OffDays.mask.collectAsState()
    val cv by CardColours.version.collectAsState()
    val nowMin = now / 60_000L
    val loaded by produceState<Pair<List<CalBlock>, List<GEvent>>?>(null, date, tasks, dossier, records, g, off, cv) {
        value = withContext(Dispatchers.IO) {
            CalendarData.blocks(ctx, date) to GoogleCal.events(ctx, date).filter { it.allDay }
        }
    }
    val blocks = loaded?.first ?: emptyList()
    val followUps = remember(date, calls, texts, nowMin, cv) { CalendarData.followUps(ctx, date, now) }
    val gaps = remember(blocks, wh, off, nowMin, date) { CalendarData.freeGaps(ctx, date, blocks, now) }
    return DayData(
        blocks = blocks,
        allDay = loaded?.second ?: emptyList(),
        followUps = followUps,
        gaps = gaps,
        freeMin = gaps.sumOf { (it.second - it.first) / 60_000L },
    )
}

/** One line: date ▾ · Today · "1 h free · 9 FU" · 🔍 ⚙ */
@Composable
private fun CalHeader(
    date: LocalDate,
    isToday: Boolean,
    freeMin: Long,
    fuCount: Int,
    motion: Boolean,
    onPickDate: () -> Unit,
    onToday: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 2.dp, top = 2.dp).height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onPickDate).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedContent(targetState = date, transitionSpec = { fadeIn(calSpec(motion, 200)) togetherWith fadeOut(calSpec(motion, 150)) }, label = "title") { d ->
                Text(
                    d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
            }
            Text(" ▾", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        AnimatedVisibility(visible = !isToday, enter = fadeIn(calSpec(motion)), exit = fadeOut(calSpec(motion))) {
            Surface(
                onClick = onToday,
                shape = RoundedCornerShape(8.dp),
                color = cs.primaryContainer,
                contentColor = cs.onPrimaryContainer,
                modifier = Modifier.padding(start = 2.dp)
            ) {
                Text("Today", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Text(
            "${if (freeMin > 0) CalendarData.lengthLabel(freeMin) else "0 m"} free · $fuCount FU",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 6.dp)
        )
        if (onRefresh != null) {
            // ↻ spins while Google syncs, then stops where it is with a small ease.
            val spin = rememberInfiniteTransition(label = "sync")
            val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "syncAngle")
            IconButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh Google Calendar",
                    tint = if (refreshing) cs.primary else cs.onSurface,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (refreshing) angle else 0f }
                )
            }
        }
        IconButton(onClick = onSearch, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.Search, contentDescription = "Search", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.Tune, contentDescription = "Calendar settings", modifier = Modifier.size(20.dp))
        }
    }
}

/** The slim week row: M 19, T 20 … with the off day tagged. Swipe it sideways for the next or last week. */
@Composable
private fun DayStrip(date: LocalDate, today: LocalDate, motion: Boolean, onPick: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val off by OffDays.mask.collectAsState()
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    var drag by remember { mutableStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .pointerInput(date) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -120f) onPick(date.plusWeeks(1)) else if (drag > 120f) onPick(date.minusWeeks(1))
                        drag = 0f
                    },
                ) { change, amount -> drag += amount; change.consume() }
            }
    ) {
        (0..6).forEach { i ->
            val d = monday.plusDays(i.toLong())
            val on = d == date
            val isOff = off and (1 shl (d.dayOfWeek.value - 1)) != 0
            val bg by animateColorAsState(if (on) cs.primary else Color.Transparent, calSpec(motion, 220), label = "daybg")
            val fg by animateColorAsState(if (on) cs.onPrimary else cs.onSurface, calSpec(motion, 220), label = "dayfg")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onPick(d) }.padding(vertical = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isOff) cs.error else cs.onSurfaceVariant
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(bg)
                        .then(if (d == today && !on) Modifier.border(1.5.dp, cs.primary, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text(d.dayOfMonth.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
                }
                Box(Modifier.height(11.dp), contentAlignment = Alignment.Center) {
                    if (isOff) Text("OFF", fontSize = 7.5.sp, fontWeight = FontWeight.ExtraBold, color = cs.error)
                }
            }
        }
    }
}

/** Once: "Show your Google Calendar here?" with Allow / Not now. */
@Composable
private fun GoogleAsk() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val g by GoogleCal.version.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    val show = remember(g, hidden) { !hidden && !GoogleCal.asked(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        GoogleCal.setEnabled(ctx, ok)
        hidden = true
    }
    AnimatedVisibility(visible = show, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Tones.amber.bg,
            contentColor = Tones.amber.fg,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show your Google Calendar meetings here?", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (GoogleCal.hasPermission(ctx)) {
                        GoogleCal.setEnabled(ctx, true)
                        hidden = true
                    } else launcher.launch(Manifest.permission.READ_CALENDAR)
                }) { Text("Allow", fontWeight = FontWeight.Bold) }
                TextButton(onClick = { GoogleCal.setAsked(ctx); hidden = true }) { Text("Not now", color = cs.onSurfaceVariant) }
            }
        }
    }
}

/** MY WORK | FOLLOW-UPS ⇆ over the lanes; tap to switch which lane is wide. */
@Composable
private fun LaneHeaders(wideF: Float, onMine: () -> Unit, onFollowUps: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
        val (mineW, fuW) = laneWidths(maxWidth, wideF)
        Row(Modifier.fillMaxSize().padding(start = TimeCol), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (wideF > 0.5f) "MINE" else "MY WORK",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.6.sp,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(mineW).clickable(onClick = onMine).padding(start = 6.dp)
            )
            Spacer(Modifier.width(LaneGap))
            Text(
                if (wideF > 0.5f) "FOLLOW-UPS  ⇢ swipe back" else "FOLLOW-UPS ⇠",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.6.sp,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.width(fuW).clickable(onClick = onFollowUps).padding(start = 4.dp)
            )
        }
    }
}

private val LaneGap = 4.dp

/** My work and follow-ups widths: follow-ups take about a third, or most of it when swiped wide. */
private fun laneWidths(total: Dp, wideF: Float): Pair<Dp, Dp> {
    val lanes = total - TimeCol - LaneGap - 4.dp
    val fu = lanes * (0.34f + (0.70f - 0.34f) * wideF)
    return (lanes - fu) to fu
}

/** One item being slid (long-press) or stretched (resize handle). */
private class DragOp(val key: String, val resize: Boolean, val originPx: Float, val downY: Float) {
    var dy by mutableFloatStateOf(0f)
}

/** Rounds to the nearest 15 minutes, like Google Calendar's drag. */
private fun snap15(ms: Long): Long {
    val step = 15 * 60_000L
    return (ms + step / 2) / step * step
}

/**
 * The day itself: hours, own blocks, free gaps, follow-up cards (or marks), and the now line.
 * Two fingers pinch the hours (fully squeezed shows marks, half shows one-line cards, stretched shows
 * full cards); tapping an hour zooms in. Long-press slides a My task or a follow-up (Snooze) in
 * 15-minute steps; the handle under a My task changes its length.
 */
@Composable
private fun DayBody(
    date: LocalDate,
    data: DayData,
    now: Long,
    wideF: Float,
    mix: Float,
    bestFit: Boolean,
    motion: Boolean,
    onSwipe: (Boolean) -> Unit,
    onFollowUp: (CalFollowUp) -> Unit,
    onCall: (CalFollowUp) -> Unit,
    onHour: (List<CalFollowUp>) -> Unit,
    onBlock: (CalBlock) -> Unit,
    onAdd: (Long, Int) -> Unit,
    onMoveTask: (Long, Long) -> Unit,
    onResizeTask: (Long, Int) -> Unit,
    onMoveFollowUp: (CalFollowUp, Long) -> Unit,
    onFixed: (CalBlock) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cc = rememberCalColors()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val wh by WorkHours.version.collectAsState()
    val ws = remember(wh) { WorkHours.start(ctx) }
    val we = remember(wh) { WorkHours.end(ctx) }
    val isOff = OffDays.isOff(date)
    val dayStart = CalendarData.at(date, 0)
    var zoomState by CalendarUi.zoom

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewH = maxHeight - ToggleSpace
        val viewPx = with(density) { maxHeight.toPx() }
        val (mineW, fuW) = laneWidths(maxWidth, wideF)
        val fuX = TimeCol + mineW + LaneGap
        val cardFull = lerp(36.dp, 46.dp, wideF)

        val firstWork = ws / 60
        val lastWork = ((we + 59) / 60).coerceIn(firstWork + 1, 24)
        val workH = (viewH / (lastWork - firstWork).toFloat()).coerceAtLeast(26.dp)
        val byHour = remember(data.followUps) { data.followUps.groupBy { ((it.at - dayStart) / 3_600_000L).toInt().coerceIn(0, 23) } }
        val base = (0 until 24).map { h ->
            val n = byHour[h]?.size ?: 0
            val fit = maxOf(BaseHour, (cardFull + CardGap) * n.toFloat() + CardPad * 2)
            lerp(workH, fit, mix)
        }
        val baseTotal = base.fold(0.dp) { acc, h -> acc + h }
        // Fully squeezed = the whole day on one screen.
        val minZoom = (viewH / baseTotal).coerceAtMost(1f)
        val maxZoom = 3f
        val z = zoomState.coerceIn(minZoom, maxZoom)
        // Below 1 the cards shrink with the hours: one-line cards, then marks (fully squeezed).
        val s = minOf(z, 1f)
        val cardH = cardFull * s
        val gap = CardGap * s
        val pad = CardPad * s
        val oneLine = cardH < 30.dp
        val cardsF = mix * ((z - 0.42f) / 0.18f).coerceIn(0f, 1f)

        val heights = base.map { it * z }
        val tops = heights.runningFold(0.dp) { acc, h -> acc + h }
        val totalH = tops.last()

        fun yOf(ms: Long): Dp {
            val m = ((ms - dayStart) / 60_000L).coerceIn(0, 24 * 60)
            if (m >= 24 * 60) return totalH
            val h = (m / 60).toInt()
            return tops[h] + heights[h] * ((m % 60) / 60f)
        }

        fun timeOf(y: Dp): Long {
            val yy = y.coerceIn(0.dp, totalH)
            var h = 0
            while (h < 23 && tops[h + 1] <= yy) h++
            val frac = ((yy - tops[h]) / heights[h]).coerceIn(0f, 1f)
            return dayStart + (h * 60 + (frac * 60f).toLong()) * 60_000L
        }

        val scroll = rememberScrollState()
        // Open at the current hour today, else at the start of working hours.
        LaunchedEffect(date) {
            val target = if (date == LocalDate.now()) yOf(now - 3_600_000L) else tops[firstWork]
            scroll.scrollTo(with(density) { target.roundToPx() })
        }
        // Switching the toggle resets the pinch; Working hours brings them into view.
        val lastMode = remember { mutableStateOf(bestFit) }
        LaunchedEffect(bestFit) {
            if (lastMode.value != bestFit) {
                lastMode.value = bestFit
                if (zoomState != 1f) {
                    androidx.compose.animation.core.animate(zoomState, 1f, animationSpec = calSpec(motion, 300)) { v, _ -> zoomState = v }
                }
            }
            if (!bestFit) {
                delay(40)
                scroll.animateScrollTo(with(density) { (workH * firstWork.toFloat()).roundToPx() }, calSpec(motion, 300))
            }
        }
        // Keeps the spot under the fingers still while pinching.
        var pendingScroll by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(pendingScroll) { pendingScroll?.let { scroll.scrollTo(it) } }

        fun pinch(factor: Float, focusY: Float) {
            val old = zoomState.coerceIn(minZoom, maxZoom)
            val nz = (old * factor).coerceIn(minZoom, maxZoom)
            if (nz == old) return
            zoomState = nz
            pendingScroll = ((scroll.value + focusY) * (nz / old) - focusY).roundToInt().coerceAtLeast(0)
        }

        fun zoomInto(h: Int) {
            val from = zoomState.coerceIn(minZoom, maxZoom)
            val to = (maxOf(from, 1f) * 1.6f).coerceAtMost(maxZoom)
            if (to <= from + 0.01f) return
            val baseTop = base.take(h).fold(0.dp) { acc, v -> acc + v }
            scope.launch {
                androidx.compose.animation.core.animate(from, to, animationSpec = calSpec(motion, 320)) { v, _ ->
                    zoomState = v
                    pendingScroll = with(density) { (baseTop * v).roundToPx() }
                }
            }
        }

        // Sliding and stretching.
        var drag by remember { mutableStateOf<DragOp?>(null) }
        var edge by remember { mutableIntStateOf(0) }
        // After a drop, the new spot is kept until the day's items reload, so nothing jumps back.
        var dropped by remember { mutableStateOf<Pair<String, Long>?>(null) }
        LaunchedEffect(data.blocks, data.followUps) { dropped = null }
        // Auto-scroll while an item is held near the top or bottom edge.
        LaunchedEffect(drag) {
            val op = drag ?: return@LaunchedEffect
            while (drag === op) {
                if (edge != 0) {
                    val moved = scroll.scrollBy(edge * 14f)
                    op.dy += moved
                }
                delay(16)
            }
        }
        fun trackEdge(op: DragOp, localY: Float) {
            val y = op.originPx + localY - scroll.value
            edge = when {
                y < 56f -> -1
                y > viewPx - with(density) { ToggleSpace.toPx() } -> 1
                else -> 0
            }
        }
        var lastStep by remember { mutableStateOf(0L) }
        fun stepBuzz(ms: Long) {
            if (ms != lastStep) {
                lastStep = ms
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        var swipe by remember { mutableStateOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(minZoom) {
                    // Two fingers: pinch the hours (seen before the scroll can take them).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var prev = 0f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.filter { it.pressed }
                            if (down.size >= 2) {
                                val a = down[0].position
                                val b = down[1].position
                                val span = maxOf(abs(a.y - b.y), (a - b).getDistance() * 0.6f).coerceAtLeast(1f)
                                if (prev > 0f) pinch(span / prev, (a.y + b.y) / 2f)
                                prev = span
                                event.changes.forEach { it.consume() }
                            } else prev = 0f
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipe = 0f },
                        onDragEnd = {
                            if (swipe < -90f) onSwipe(true) else if (swipe > 90f) onSwipe(false)
                            swipe = 0f
                        },
                    ) { change, amount -> swipe += amount; change.consume() }
                }
                .verticalScroll(scroll, enabled = drag == null)
        ) {
            Box(Modifier.fillMaxWidth().height(totalH + ToggleSpace)) {
                // Hours: lines, labels (tap to zoom in), outside-working-hours shade and Best fit's band on busy hours.
                for (h in 0 until 24) {
                    val inWork = !isOff && h * 60 < we && (h + 1) * 60 > ws
                    val grown = base[h] > BaseHour + 1.dp && mix > 0.01f
                    Box(
                        Modifier
                            .offset(y = tops[h])
                            .fillMaxWidth()
                            .height(heights[h])
                            .background(
                                when {
                                    grown -> cc.band.copy(alpha = cc.band.alpha * mix)
                                    !inWork -> cc.offHours
                                    else -> Color.Transparent
                                }
                            )
                    ) {
                        HorizontalDivider(Modifier.padding(start = TimeCol - 4.dp), color = cs.outlineVariant.copy(alpha = if (heights[h] > 14.dp) 0.6f else 0.3f))
                        Column(
                            Modifier.width(TimeCol).fillMaxHeight().clickable { zoomInto(h) }.padding(top = 1.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val h12 = if (h % 12 == 0) 12 else h % 12
                            // Fully squeezed: every other hour, so the labels don't pile up.
                            if (heights[h] > 12.dp || h % 2 == 0) {
                                Text(h12.toString(), fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                            }
                            if (heights[h] > 22.dp) {
                                Text(if (h < 12) "am" else "pm", fontSize = 8.sp, lineHeight = 8.sp, color = cs.outline)
                            }
                        }
                    }
                }

                // Free gaps inside working hours: dashed, with ＋ Add.
                data.gaps.forEach { (gs, ge) ->
                    val top = yOf(gs)
                    val hgt = yOf(ge) - top
                    if (hgt > 6.dp) {
                        val minutes = ((ge - gs) / 60_000L).toInt()
                        val dash = cs.secondary
                        Box(
                            Modifier
                                .offset(x = TimeCol + 2.dp, y = top + 1.dp)
                                .width(mineW - 4.dp)
                                .height(hgt - 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .drawBehind {
                                    drawRoundRect(
                                        color = dash.copy(alpha = 0.7f),
                                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                                        cornerRadius = CornerRadius(8.dp.toPx())
                                    )
                                }
                                .clickable { onAdd(gs, minutes.coerceIn(15, 180)) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (hgt > 14.dp) {
                                Text(
                                    if (hgt > 26.dp && wideF < 0.5f) "＋ Add · ${CalendarData.lengthLabel(minutes.toLong())} free" else "＋",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.secondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Own work: side by side when they overlap, red edge and ⚠ on a clash.
                val placed = remember(data.blocks) { placeBlocks(data.blocks) }
                placed.forEach { p ->
                    val b = p.block
                    val k = "b${b.kind}${b.id}"
                    val len = b.end - b.start
                    val top = yOf(b.start)
                    val hgt = maxOf(yOf(minOf(b.end, dayStart + 86_400_000L)) - top, 18.dp)
                    val colW = (mineW - 4.dp) / p.cols.toFloat()
                    val op = drag?.takeIf { it.key == k }
                    // Where it shows now: following a slide (snapped), or just dropped.
                    val shownStart = when {
                        op != null && !op.resize -> snap15(timeOf(top + with(density) { op.dy.toDp() }))
                        dropped?.first == k -> dropped!!.second
                        else -> b.start
                    }
                    val shownLen = when {
                        op != null && op.resize ->
                            (snap15(timeOf(top + hgt + with(density) { op.dy.toDp() })) - b.start).coerceAtLeast(15 * 60_000L)
                        dropped?.first == "r$k" -> dropped!!.second
                        else -> len
                    }
                    val shownTop = yOf(shownStart)
                    val shownH = maxOf(yOf(minOf(shownStart + shownLen, dayStart + 86_400_000L)) - shownTop, 18.dp)
                    val lifted = op != null
                    val movable = b.kind == CalBlock.Kind.TASK && !b.done
                    key(b.kind, b.id) {
                        val lift by animateFloatAsState(if (lifted) 1f else 0f, calSpec(motion, 160), label = "lift")
                        val ap = rememberAppear(motion)
                        BlockCard(
                            b = b,
                            clash = p.clash,
                            slim = wideF > 0.5f,
                            cc = cc,
                            modifier = Modifier
                                .zIndex(if (lifted) 2f else 0f)
                                .offset(x = TimeCol + 2.dp + colW * p.col.toFloat(), y = top + 1.dp)
                                .width(colW - 2.dp)
                                .height(shownH - 2.dp)
                                .pointerInput(k, movable, top, totalH) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (movable) {
                                                drag = DragOp(k, false, top.toPx(), at.y)
                                                lastStep = b.start
                                            } else onFixed(b)
                                        },
                                        onDrag = { change, _ ->
                                            val d = drag
                                            if (d != null && d.key == k) {
                                                change.consume()
                                                d.dy = change.position.y - d.downY
                                                trackEdge(d, change.position.y)
                                                stepBuzz(snap15(timeOf(top + d.dy.toDp())))
                                            }
                                        },
                                        onDragEnd = {
                                            val d = drag
                                            if (d != null && d.key == k) {
                                                val to = snap15(timeOf(top + d.dy.toDp()))
                                                if (to != b.start) {
                                                    dropped = k to to
                                                    onMoveTask(b.id, to)
                                                }
                                            }
                                            drag = null
                                            edge = 0
                                        },
                                        onDragCancel = { drag = null; edge = 0 },
                                    )
                                }
                                .graphicsLayer {
                                    alpha = ap
                                    val grow = 0.94f + 0.06f * ap + 0.03f * lift
                                    scaleX = grow
                                    scaleY = if (lifted) 1f else grow
                                    translationY = (shownTop - top).toPx()
                                    shadowElevation = 10.dp.toPx() * lift
                                    shape = RoundedCornerShape(8.dp)
                                },
                            onClick = { onBlock(b) }
                        )
                        // The resize handle at the bottom of a My task.
                        if (movable && wideF < 0.5f && hgt > 26.dp && (op == null || op.resize)) {
                            Box(
                                Modifier
                                    .zIndex(3f)
                                    .offset(x = TimeCol + 2.dp + colW * p.col.toFloat(), y = top + hgt - 14.dp)
                                    .width(colW - 2.dp)
                                    .height(14.dp)
                                    .pointerInput(k, top, hgt, totalH) {
                                        detectVerticalDragGestures(
                                            onDragStart = { at ->
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                drag = DragOp(k, true, (top + hgt - 14.dp).toPx(), at.y)
                                                lastStep = b.end
                                            },
                                            onVerticalDrag = { change, _ ->
                                                val d = drag
                                                if (d != null && d.key == k) {
                                                    change.consume()
                                                    d.dy = change.position.y - d.downY
                                                    trackEdge(d, change.position.y)
                                                    stepBuzz(snap15(timeOf(top + hgt + d.dy.toDp())))
                                                }
                                            },
                                            onDragEnd = {
                                                val d = drag
                                                if (d != null && d.key == k) {
                                                    val end = snap15(timeOf(top + hgt + d.dy.toDp()))
                                                    val newLen = ((end - b.start) / 60_000L).toInt().coerceAtLeast(15)
                                                    if (newLen.toLong() != len / 60_000L) {
                                                        dropped = "r$k" to newLen * 60_000L
                                                        onResizeTask(b.id, newLen)
                                                    }
                                                }
                                                drag = null
                                                edge = 0
                                            },
                                            onDragCancel = { drag = null; edge = 0 },
                                        )
                                    }
                                    .graphicsLayer { translationY = (shownTop + shownH - top - hgt).toPx() },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier
                                        .size(width = if (op != null) 34.dp else 22.dp, height = 4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(cc.taskEdge.copy(alpha = if (op != null) 1f else 0.55f))
                                )
                            }
                        }
                        // While sliding or stretching: the new time beside the hours.
                        if (op != null) {
                            val label = if (op.resize) Fmt.timeOnly(shownStart + shownLen) + " · " + CalendarData.lengthLabel(shownLen / 60_000L)
                            else Fmt.timeOnly(shownStart)
                            TimeBubble(label, Modifier.zIndex(4f).offset(x = 2.dp, y = (if (op.resize) shownTop + shownH else shownTop) - 9.dp))
                        }
                    }
                }

                // Follow-ups: each its own card inside its hour; marks when squeezed or in Working hours.
                for (h in 0 until 24) {
                    val list = byHour[h].orEmpty()
                    if (list.isNotEmpty() && cardsF > 0.02f) {
                        var prevBottom = tops[h] + pad - gap
                        list.forEachIndexed { i, f ->
                            val want = tops[h] + heights[h] * (((f.at - dayStart) / 60_000L % 60) / 60f) + pad
                            val latest = tops[h] + heights[h] - pad - (cardH + gap) * (list.size - i).toFloat() + gap
                            val y = minOf(maxOf(want, prevBottom + gap), maxOf(latest, prevBottom + gap))
                            prevBottom = y + cardH
                            val k = "f" + f.key
                            val op = drag?.takeIf { it.key == k }
                            val shownY = when {
                                op != null -> yOf(snap15(timeOf(y + with(density) { op.dy.toDp() })))
                                dropped?.first == k -> yOf(dropped!!.second)
                                else -> y
                            }
                            key(f.key) {
                                val lift by animateFloatAsState(if (op != null) 1f else 0f, calSpec(motion, 160), label = "fulift")
                                val ap = rememberAppear(motion)
                                FollowUpCard(
                                    f = f,
                                    wideF = wideF,
                                    cc = cc,
                                    oneLine = oneLine,
                                    modifier = Modifier
                                        .zIndex(if (op != null) 2f else 0f)
                                        .offset(x = fuX, y = y)
                                        .width(fuW)
                                        .height(maxOf(cardH, 18.dp))
                                        .pointerInput(k, cardsF > 0.5f, y, totalH) {
                                            if (cardsF <= 0.5f || f.done) return@pointerInput
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { at ->
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    drag = DragOp(k, false, y.toPx(), at.y)
                                                    lastStep = f.at
                                                },
                                                onDrag = { change, _ ->
                                                    val d = drag
                                                    if (d != null && d.key == k) {
                                                        change.consume()
                                                        d.dy = change.position.y - d.downY
                                                        trackEdge(d, change.position.y)
                                                        stepBuzz(snap15(timeOf(y + d.dy.toDp())))
                                                    }
                                                },
                                                onDragEnd = {
                                                    val d = drag
                                                    if (d != null && d.key == k) {
                                                        // Sliding a follow-up = New time; never into the past.
                                                        val to = maxOf(
                                                            snap15(timeOf(y + d.dy.toDp())),
                                                            CalendarData.roundUp(System.currentTimeMillis(), 15)
                                                        )
                                                        if (to != snap15(f.at)) {
                                                            dropped = k to to
                                                            onMoveFollowUp(f, to)
                                                        }
                                                    }
                                                    drag = null
                                                    edge = 0
                                                },
                                                onDragCancel = { drag = null; edge = 0 },
                                            )
                                        }
                                        .graphicsLayer {
                                            alpha = cardsF * ap
                                            val grow = 0.94f + 0.06f * ap + 0.04f * lift
                                            scaleX = grow
                                            scaleY = grow
                                            translationY = (shownY - y).toPx()
                                            shadowElevation = 10.dp.toPx() * lift
                                            shape = RoundedCornerShape(8.dp)
                                        },
                                    enabled = cardsF > 0.5f,
                                    onClick = { onFollowUp(f) },
                                    onCall = { onCall(f) },
                                )
                                if (op != null) {
                                    TimeBubble("Move to " + Fmt.timeOnly(snap15(timeOf(y + with(density) { op.dy.toDp() }))), Modifier.zIndex(4f).offset(x = 2.dp, y = shownY - 9.dp))
                                }
                            }
                        }
                    }
                    if (list.isNotEmpty() && cardsF < 0.98f) {
                        Row(
                            Modifier
                                .offset(x = fuX, y = tops[h] + (if (heights[h] > 16.dp) 3.dp else 0.dp))
                                .width(fuW)
                                .alpha(1f - cardsF)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = cardsF < 0.5f) { onHour(list) }
                                .padding(horizontal = 4.dp, vertical = if (heights[h] > 16.dp) 2.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            list.take(8).forEach { f ->
                                Box(
                                    Modifier
                                        .size(width = 10.dp, height = if (heights[h] > 16.dp) 7.dp else 5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (f.isCall) cc.callEdge else cc.textEdge)
                                )
                            }
                            if (heights[h] > 12.dp) {
                                Text(
                                    if (wideF > 0.5f) "${list.size} · ${list.first().title}" else "${list.size}",
                                    fontSize = 9.sp,
                                    lineHeight = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Now.
                if (date == LocalDate.now()) {
                    val y = yOf(now)
                    Box(Modifier.offset(x = TimeCol - 4.dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(cc.now))
                    Box(Modifier.offset(x = TimeCol, y = y - 1.dp).fillMaxWidth().height(2.dp).background(cc.now))
                }
            }
        }
        // Zoom level hint while pinched away from normal.
        AnimatedVisibility(
            visible = abs(z - 1f) > 0.05f,
            enter = fadeIn(calSpec(motion, 200)),
            exit = fadeOut(calSpec(motion, 200)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        androidx.compose.animation.core.animate(zoomState.coerceIn(minZoom, maxZoom), 1f, animationSpec = calSpec(motion, 300)) { v, _ -> zoomState = v }
                    }
                },
                shape = RoundedCornerShape(10.dp),
                color = cs.inverseSurface.copy(alpha = 0.85f),
                contentColor = cs.inverseOnSurface,
            ) {
                Text(
                    when {
                        z <= minZoom + 0.01f -> "Whole day · tap to reset"
                        z < 1f -> "Squeezed · tap to reset"
                        else -> "Stretched · tap to reset"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/** The small time label shown beside the hours while sliding or stretching. */
@Composable
private fun TimeBubble(text: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        color = cs.onPrimary,
        maxLines = 1,
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(9.dp))
            .clip(RoundedCornerShape(9.dp))
            .background(cs.primary)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/** 0 to 1 the first time a card shows: it fades and grows in. */
@Composable
private fun rememberAppear(motion: Boolean): Float {
    val a = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { a.animateTo(1f, calSpec(motion, 260)) }
    return a.value
}

private class Placed(val block: CalBlock, val col: Int, val cols: Int, val clash: Boolean)

/** Columns for overlapping own items (like Google Calendar), and which of them clash. */
private fun placeBlocks(blocks: List<CalBlock>): List<Placed> {
    val out = ArrayList<Placed>()
    val sorted = blocks.sortedWith(compareBy<CalBlock> { it.start }.thenByDescending { it.end })
    var group = ArrayList<Pair<CalBlock, Int>>()
    var colEnds = ArrayList<Long>()
    var groupEnd = Long.MIN_VALUE
    fun flush() {
        val cols = colEnds.size.coerceAtLeast(1)
        group.forEach { (b, c) ->
            val clash = !b.done && group.any { (o, _) -> o !== b && !o.done && o.overlaps(b.start, b.end) }
            out += Placed(b, c, cols, clash)
        }
        group = ArrayList()
        colEnds = ArrayList()
    }
    sorted.forEach { b ->
        if (b.start >= groupEnd && group.isNotEmpty()) flush()
        val col = colEnds.indexOfFirst { it <= b.start }.takeIf { it >= 0 } ?: colEnds.size.also { colEnds.add(0L) }
        colEnds[col] = b.end
        group.add(b to col)
        groupEnd = if (group.size == 1) b.end else maxOf(groupEnd, b.end)
    }
    if (group.isNotEmpty()) flush()
    return out
}

@Composable
private fun BlockCard(b: CalBlock, clash: Boolean, slim: Boolean, cc: CalColors, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, ink, edge) = when (b.kind) {
        CalBlock.Kind.TASK -> Triple(cc.taskBg, cc.taskInk, cc.taskEdge)
        CalBlock.Kind.DOSSIER -> Triple(cc.dosBg, cc.dosInk, cc.dosEdge)
        CalBlock.Kind.GOOGLE -> Triple(cc.gBg, cc.gInk, cc.gEdge)
    }
    Box(
        modifier
            .alpha(if (b.done) 0.55f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (clash) Modifier.border(2.dp, cs.error, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (clash) cs.error else edge))
        Column(Modifier.padding(start = 7.dp, end = 4.dp, top = 2.dp)) {
            Text(
                (if (clash) "⚠ " else "") + (if (b.done) "✓ " else "") + b.title,
                fontSize = if (slim) 10.sp else 12.sp,
                lineHeight = if (slim) 11.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                color = ink,
                maxLines = if (slim) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (b.done) TextDecoration.LineThrough else null
            )
            if (!slim) {
                Text(
                    Fmt.timeOnly(b.start) + " - " + Fmt.timeOnly(b.end) + when (b.kind) {
                        CalBlock.Kind.DOSSIER -> " · Dossier"
                        CalBlock.Kind.GOOGLE -> " · Google"
                        CalBlock.Kind.TASK -> if (b.sub.isNotBlank()) " · ${b.sub}" else ""
                    },
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = ink.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** One follow-up: the task first, the person small. Wide lane adds a 📞 button for a one-tap call. */
@Composable
private fun FollowUpCard(
    f: CalFollowUp,
    wideF: Float,
    cc: CalColors,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    onCall: () -> Unit,
    oneLine: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (f.isCall) cc.callBg else cc.textBg
    val ink = if (f.isCall) cc.callInk else cc.textInk
    val edge = if (f.isCall) cc.callEdge else cc.textEdge
    Row(
        modifier
            .alpha(if (f.done) 0.55f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled && !f.done, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (f.overdue) cs.error else edge))
        Column(Modifier.weight(1f).padding(start = 6.dp, end = 2.dp)) {
            Text(
                (if (oneLine) Fmt.timeOnly(f.at) + " " else "") + (if (f.done) "✓ " else "") + (if (f.isCall) "" else "💬 ") + f.title,
                fontSize = if (oneLine) 10.sp else if (wideF > 0.5f) 12.5.sp else 11.sp,
                lineHeight = if (oneLine) 11.sp else if (wideF > 0.5f) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (f.done) TextDecoration.LineThrough else null
            )
            if (!oneLine) Text(
                Fmt.timeOnly(f.at) + if (f.person.isNotBlank()) " · ${f.person}" else "",
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                color = if (f.overdue) cs.error else ink.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (wideF > 0.3f && f.number.isNotBlank() && !oneLine && !f.done) {
            Box(
                Modifier
                    .alpha(((wideF - 0.3f) / 0.7f).coerceIn(0f, 1f))
                    .padding(end = 4.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(edge)
                    .clickable(enabled = enabled, onClick = onCall),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Call, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** ⊟ Working hours | ⇕ Best fit, with a sliding thumb. */
@Composable
private fun FitToggle(bestFit: Boolean, motion: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val segW = 128.dp
    val x by animateDpAsState(if (bestFit) segW else 0.dp, calSpec(motion, 260), label = "thumb")
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = cs.surface,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Box(Modifier.padding(4.dp)) {
            Box(
                Modifier
                    .offset(x = x)
                    .width(segW)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(cs.primary)
            )
            Row {
                listOf(false to "⊟ Working hours", true to "⇕ Best fit").forEach { (fit, label) ->
                    val on = fit == bestFit
                    val color by animateColorAsState(if (on) cs.onPrimary else cs.onSurfaceVariant, calSpec(motion, 220), label = "tg")
                    Box(
                        Modifier.width(segW).height(36.dp).clip(RoundedCornerShape(18.dp)).clickable { onChange(fit) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Tap a follow-up: Call / Done / New time / WhatsApp (his chat opens with the message typed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowUpActions(f: CalFollowUp, onDismiss: () -> Unit, onCall: () -> Unit, onDone: () -> Unit, onSnooze: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp + navBarMissing()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(f.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                (if (f.isCall) "📞 Call" else "💬 Text") + " · " + Fmt.dueLabel(f.at) + if (f.person.isNotBlank()) " · ${f.person}" else "",
                color = if (f.overdue) cs.error else cs.onSurfaceVariant,
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (f.number.isNotBlank()) {
                    Button(onClick = onCall, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call")
                    }
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("✓ Done") }
                OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("New time", maxLines = 1) }
                if (f.number.isNotBlank()) {
                    WhatsAppAction(size = 48.dp) {
                        val sent = if (f.isCall) ReminderStore.get(f.id)?.let { WhatsApp.forCall(ctx, it) }
                        else SmsStore.get(f.id)?.let { WhatsApp.forText(ctx, it) }
                        if (sent == null) WhatsApp.open(ctx, f.number, WhatsApp.message(ctx, "", f.title, f.at, f.person))
                    }
                }
            }
        }
    }
}

/** Working hours view: tap an hour's marks to see its follow-ups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HourSheet(list: List<CalFollowUp>, onDismiss: () -> Unit, onOpen: (CalFollowUp) -> Unit, onCall: (CalFollowUp) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cc = rememberCalColors()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp + navBarMissing()).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("${list.size} follow-ups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            list.forEach { f ->
                FollowUpCard(
                    f = f,
                    wideF = 1f,
                    cc = cc,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = true,
                    onClick = { onOpen(f) },
                    onCall = { onCall(f) },
                )
            }
        }
    }
}

/** Tap your own item: a My task (Done, Edit, Call), a Dossier block (tick, change times) or a Google event (read only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockActions(
    b: CalBlock,
    date: LocalDate,
    onDismiss: () -> Unit,
    onEditTask: () -> Unit,
    onEditDossier: () -> Unit,
    onTaskDone: () -> Unit,
    onCallPerson: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp + navBarMissing()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                Fmt.timeOnly(b.start) + " - " + Fmt.timeOnly(b.end) + " · " + when (b.kind) {
                    CalBlock.Kind.TASK -> "My task"
                    CalBlock.Kind.DOSSIER -> "Dossier · ${b.sub}"
                    CalBlock.Kind.GOOGLE -> "Google Calendar" + if (b.sub.isNotBlank()) " · ${b.sub}" else ""
                },
                fontSize = 13.sp,
                color = cs.onSurfaceVariant
            )
            when (b.kind) {
                CalBlock.Kind.TASK -> {
                    val t = MyTaskStore.get(b.id)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTaskDone, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text(if (b.done) "Not done" else "✓ Done")
                        }
                        OutlinedButton(onClick = onEditTask, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Edit") }
                        if (t != null && t.hasPerson) {
                            OutlinedButton(onClick = { onCallPerson(t.number) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                                Text("Call")
                            }
                        }
                    }
                }
                CalBlock.Kind.DOSSIER -> {
                    val open = Dossier.openDay()
                    val canTick = !Dossier.isLocked(date) && !date.isAfter(open)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canTick) {
                            Button(
                                onClick = {
                                    if (b.done) DossierStore.untick(b.id, date) else DossierStore.tick(b.id, date)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text(if (b.done) "Untick" else "✓ Done") }
                        }
                        OutlinedButton(onClick = onEditDossier, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text("Change times")
                        }
                    }
                    Text(
                        "Dossier blocks are fixed. Change their Start and End in the Dossier task.",
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant
                    )
                }
                CalBlock.Kind.GOOGLE -> {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, b.id))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Open in Google Calendar") }
                    Text("Read only here. Change it in Google Calendar.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Working hours: asked the first time the Calendar opens (required), and in Settings › Calendar.
 * The free time and the best free slots only look inside these hours.
 */
@Composable
fun WorkHoursSetup(first: Boolean, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var start by remember { mutableIntStateOf(WorkHours.start(ctx)) }
    var end by remember { mutableIntStateOf(WorkHours.end(ctx)) }
    var editing by remember { mutableStateOf<Boolean?>(null) }
    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📅", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text("Your working hours", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "The Calendar needs them once. Free time and the best free slots for your tasks are found inside these hours.",
            textAlign = TextAlign.Center,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(true, false).forEach { isStart ->
                OutlinedButton(
                    onClick = { editing = isStart },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (isStart) "Start" else "End", fontSize = 12.sp)
                        Text(Dossier.timeLabel((if (isStart) start else end) % (24 * 60)), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { WorkHours.set(ctx, start, end); onSaved() },
            enabled = end > start,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text(if (first) "Save and open Calendar" else "Save", fontWeight = FontWeight.Bold) }
        if (end <= start) {
            Text("End must be after Start.", color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    editing?.let { isStart ->
        val m = if (isStart) start else end
        ClockDialog(
            initial = LocalTime.of((m / 60) % 24, m % 60),
            is24h = DateFormat.is24HourFormat(ctx),
            onPicked = { t ->
                val v = t.hour * 60 + t.minute
                if (isStart) start = v else end = if (v == 0) 24 * 60 else v
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

/** Opens once (and from ↻ when nothing is on yet): pick which Google calendars show. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoogleCalendarsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp + navBarMissing()).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Which calendars to show?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "All are off. Turn on the ones you want. You can change this later in Calendar settings.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GoogleCalendarSwitches()
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Done") }
        }
    }
}

/** Every account on the phone with its calendars as switches (Holidays and Birthdays are their own). */
@Composable
private fun GoogleCalendarSwitches() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val motion = motionOn(ctx)
    val g by GoogleCal.version.collectAsState()
    val all by produceState(initialValue = emptyList<GCalendar>(), g) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) { GoogleCal.calendars(ctx) }
    }
    val on = remember(g) { GoogleCal.onIds(ctx) }
    if (all.isEmpty()) {
        Text("No calendars found on this phone.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    Column(Modifier.fillMaxWidth()) {
        all.groupBy { it.account }.forEach { (account, list) ->
            Text(
                account.ifBlank { "On this phone" },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
            )
            list.forEach { cal ->
                val checked = cal.id in on
                val alpha by animateFloatAsState(if (checked) 1f else 0.55f, calSpec(motion, 220), label = "calOn")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { GoogleCal.setCalendarOn(ctx, cal.id, !checked) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(cal.color or 0xFF000000.toInt())).alpha(alpha))
                    Text(
                        cal.name,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp).alpha(alpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Switch(checked = checked, onCheckedChange = { GoogleCal.setCalendarOn(ctx, cal.id, it) })
                }
            }
        }
    }
}

/** Stay greyed | Leave the calendar, with a sliding thumb. */
@Composable
private fun DoneTasksChoice() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val motion = motionOn(ctx)
    val v by CardColours.version.collectAsState()
    val leave = remember(v) { CardColours.doneLeaves(ctx) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).padding(3.dp)
    ) {
        val segW = maxWidth / 2
        val x by animateDpAsState(if (leave) segW else 0.dp, calSpec(motion, 240), label = "doneThumb")
        Box(Modifier.offset(x = x).width(segW).height(40.dp).clip(RoundedCornerShape(11.dp)).background(cs.primary))
        Row(Modifier.fillMaxWidth()) {
            listOf("Stay greyed" to false, "Leave the calendar" to true).forEach { (label, value) ->
                val on = leave == value
                val fg by animateColorAsState(if (on) cs.onPrimary else cs.onSurfaceVariant, calSpec(motion, 240), label = "doneFg")
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                        .clickable { CardColours.setDoneLeaves(ctx, value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Each kind of card: its name and colour, the 12 dots (✓ on the chosen one) and a live sample card. */
@Composable
private fun CardColourPicker() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val motion = motionOn(ctx)
    val v by CardColours.version.collectAsState()
    val samples = mapOf(
        CardColours.Kind.TASK to ("Site visit RG2" to "10:00 - 11:00 AM"),
        CardColours.Kind.FOLLOW_UP to ("📞 Ask for vendor quote · Hasnain" to "2:30 PM"),
        CardColours.Kind.DOSSIER to ("RG2 visit" to "9:00 - 10:00 AM"),
        CardColours.Kind.GOOGLE to ("Weekly review" to "4:00 - 5:00 PM"),
    )
    Column(Modifier.fillMaxWidth()) {
        CardColours.Kind.entries.forEachIndexed { n, kind ->
            val picked = remember(v) { CardColours.index(ctx, kind) }
            val sw = CardColours.PALETTE[picked]
            val darkNow = LocalDarkTheme.current
            val fill by animateColorAsState(sw.fill(darkNow), calSpec(motion, 260), label = "ccFill")
            val edge by animateColorAsState(sw.edge(darkNow), calSpec(motion, 260), label = "ccEdge")
            val sampleInk = sw.ink(darkNow)
            if (n > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = cs.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind.label, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                Text(sw.name, fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                CardColours.PALETTE.forEachIndexed { i, c ->
                    val on = i == picked
                    // A 0 dp border would still draw a hairline, so the ring fades instead of shrinking.
                    val ring by animateFloatAsState(if (on) 1f else 0f, calSpec(motion, 200), label = "ccRing")
                    val tick by animateFloatAsState(if (on) 1f else 0f, calSpec(motion, 180), label = "ccTick")
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .border(2.dp, cs.onSurface.copy(alpha = ring), CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(c.fillColor)
                            .border(1.5.dp, c.edgeColor, CircleShape)
                            .clickable { CardColours.set(ctx, kind, i) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = CardColours.INK, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.alpha(tick))
                    }
                }
            }
            val (title, time) = samples.getValue(kind)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(10.dp)).background(fill)
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(edge))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(title, color = sampleInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time, color = sampleInk.copy(alpha = 0.75f), fontSize = 11.sp)
                }
            }
        }
        TextButton(onClick = { CardColours.reset(ctx) }, modifier = Modifier.padding(top = 4.dp)) {
            Text("↺ Reset to defaults", fontWeight = FontWeight.Bold)
        }
    }
}

/** Settings › Calendar: working hours (required), the Google Calendar switch and which calendars show. */
@Composable
fun CalendarSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val wh by WorkHours.version.collectAsState()
    val g by GoogleCal.version.collectAsState()
    var editHours by remember { mutableStateOf(false) }
    val googleOn = remember(g) { GoogleCal.enabled(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        GoogleCal.setEnabled(ctx, ok)
    }
    if (editHours) {
        androidx.activity.compose.BackHandler { editHours = false }
        WorkHoursSetup(first = false, onSaved = { editHours = false })
        return
    }
    SettingsPage(title = "Calendar", onBack = onBack) {
        SectionCard(
            title = "Working hours (required)",
            subtitle = "Free time, the best free slots and the free count only look inside these hours. Your off day has none."
        ) {
            // In a seat: from your timetable on the website, read only (locked 23 Nov).
            val managed = remember(wh) { Seats.managed(ctx) }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !managed) { editHours = true }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hours", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(remember(wh) { WorkHours.label(ctx) }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
        SectionCard(
            title = "Google Calendar",
            subtitle = "Shows the meetings and events of the Google Calendar on this phone, read only. Nothing is written back. Google Tasks need a Google sign-in, so they are not shown."
        ) {
            SwitchRow("Show Google Calendar", googleOn) { on ->
                if (on && !GoogleCal.hasPermission(ctx)) launcher.launch(Manifest.permission.READ_CALENDAR)
                else GoogleCal.setEnabled(ctx, on)
            }
        }
        AnimatedVisibility(visible = googleOn, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SectionCard(
                title = "Google calendars",
                subtitle = "Only the calendars switched on here show in the Calendar. Other accounts, Holidays and Birthdays stay out unless you turn them on. A calendar added later starts off."
            ) {
                GoogleCalendarSwitches()
            }
        }
        SectionCard(
            title = "Done tasks",
            subtitle = "What happens to a My task or follow-up when you mark it done. Dossier ticks always stay, greyed."
        ) {
            DoneTasksChoice()
        }
        SectionCard(
            title = "Card colours",
            subtitle = "Tap a colour for each kind of card. Also used by the calendar widgets."
        ) {
            CardColourPicker()
        }
    }
}
