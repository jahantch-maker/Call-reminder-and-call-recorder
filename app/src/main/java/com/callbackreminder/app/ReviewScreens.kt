package com.callbackreminder.app

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.material3.HorizontalDivider

// Each has a light and a dark version, so the Review pages read well in both modes.
private val LateRed: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFC62828)
private val LateBg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF5C1A1A) else Color(0xFFFFE3E6)
private val PausedBg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF4A3A0E) else Color(0xFFFFF0D4)
private val PausedFg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFFFFD36B) else Color(0xFF7A4B00)
/** Text on a [PausedFg] button. */
private val PausedInk: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF2A1C00) else Color.White
private val DoneGreen = Color(0xFF4FD6B0)

/** Done, New time, Call and the rest, the same on the Review page and in the session. */
object ReviewActions {
    fun call(ctx: android.content.Context, item: ReviewItem) {
        when (item.kind) {
            ReviewItem.Kind.CALL, ReviewItem.Kind.CALL_REVIEW -> ReminderStore.get(item.id)?.let { FollowUp.mark(ctx, it) }
            ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.get(item.id)?.let { FollowUp.markText(ctx, it) }
            else -> {}
        }
        dial(ctx, item.number)
    }

    fun done(ctx: android.content.Context, item: ReviewItem) {
        ReplyNotes.clear(ctx, item.code)
        when (item.kind) {
            ReviewItem.Kind.CALL -> ReminderStore.markDone(item.id)
            ReviewItem.Kind.CALL_REVIEW -> ReminderStore.reviewed(item.id)
            ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.markDone(item.id)
            ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> MyTaskStore.setDone(item.id, true)
            ReviewItem.Kind.SCHED_FU -> ToScheduleStore.remove(item.id)
        }
    }

    /** Everything a Done or New time on [item] changes, put back by the returned Undo. */
    fun snapshot(item: ReviewItem): () -> Unit = when (item.kind) {
        ReviewItem.Kind.CALL, ReviewItem.Kind.CALL_REVIEW -> ReminderStore.get(item.id)?.let { r ->
            val linked = FollowUps.snapshot(r.linkId)
            val f: () -> Unit = { ReminderStore.restore(r); linked() }
            f
        } ?: {}
        ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.get(item.id)?.let { t ->
            val linked = FollowUps.snapshot(t.linkId)
            val f: () -> Unit = { SmsStore.restore(t); linked() }
            f
        } ?: {}
        ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> MyTaskStore.get(item.id)?.let { t -> { MyTaskStore.restore(t) } } ?: {}
        ReviewItem.Kind.SCHED_FU -> ToScheduleStore.get(item.id)?.let { t -> { ToScheduleStore.put(t); Unit } } ?: {}
    }

    /** [reason] is the optional "Why not done?", kept in his history. */
    fun newTime(ctx: android.content.Context, item: ReviewItem, ms: Long, reason: String = "") {
        ReplyNotes.clear(ctx, item.code)
        when (item.kind) {
            ReviewItem.Kind.CALL -> {
                val before = ReminderStore.get(item.id) ?: return
                ReminderStore.postpone(item.id, ms, reason)
                FollowUps.onNewTime(ctx, before, ms, moveTexts = true)
            }
            ReviewItem.Kind.CALL_REVIEW -> ReminderStore.reschedule(item.id, ms, "Not done, moved to")
            ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> {
                val before = SmsStore.get(item.id)
                SmsStore.reschedule(item.id, ms)
                // A text follow-up is overdue by its task's due time, so that moves too (else it stays overdue).
                if ((before?.taskDue ?: 0L) > 0L) SmsStore.setTaskDue(item.id, ms, "Message updated: due ${Fmt.fullDate(ms)}")
                if (reason.isNotBlank()) {
                    SmsStore.addLog(item.id, SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.MARKED_NOT_DONE, "Why not done: ${reason.trim()}"))
                }
            }
            ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> MyTaskStore.setDue(item.id, ms)
            ReviewItem.Kind.SCHED_FU -> {
                val t = ToScheduleStore.items.value.firstOrNull { it.id == item.id } ?: return
                val ready = if (!t.callOn && !t.textOn) t.copy(callOn = true) else t
                FollowUps.setTime(ctx, ready, ms)
            }
        }
    }

    /** A To schedule follow-up gets its person (and the call switch, if nothing was on). */
    fun setPerson(item: ReviewItem, name: String, number: String) {
        val t = ToScheduleStore.items.value.firstOrNull { it.id == item.id } ?: return
        ToScheduleStore.put(t.copy(name = name, number = number, callOn = t.callOn || !t.textOn))
    }

    fun canCall(item: ReviewItem) = item.number.isNotBlank() &&
        item.kind in setOf(ReviewItem.Kind.CALL, ReviewItem.Kind.TEXT, ReviewItem.Kind.CALL_REVIEW, ReviewItem.Kind.TEXT_REVIEW)
}

object ReviewUi {
    /** The page was opened from the 1 PM / 6 PM popup or its notification: Back leaves the app again. */
    val fromOutside = mutableStateOf(false)
}

private fun whenLabel(at: Long, now: Long): String {
    if (at <= 0L) return "No time"
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    return when (d.toLocalDate()) {
        today -> d.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
}

// ---------------------------------------------------------------- Review page

/** The Review page: every section that is switched on, with Start review, Calls only and a ▶ Start per section. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewPage(resumeKey: Int, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenSession: () -> Unit) {
    val ctx = LocalContext.current
    // Recomputed whenever any list changes.
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val sched by ToScheduleStore.items.collectAsState()
    val mine by MyTaskStore.items.collectAsState()
    val session by ReviewSession.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis(); ReviewSession.init(ctx) }
    val data = remember(calls, texts, sched, mine, now) { ReviewData.build(ctx, now) }
    val shown = remember(resumeKey) { ReviewPrefs.shown(ctx) }
    val sessionSections = remember(resumeKey) { ReviewPrefs.order(ctx).filter { ReviewPrefs.session(ctx, it) } }
    val allCount = sessionSections.flatMap { data[it].orEmpty() }.distinctBy { it.key }.size
    val callCount = sessionSections.filter { it.isCalls }.ifEmpty { listOf(ReviewSection.OVERDUE) }
        .flatMap { data[it].orEmpty() }.distinctBy { it.key }.size
    var newTimeFor by remember { mutableStateOf<ReviewItem?>(null) }
    var editFor by remember { mutableStateOf<ToScheduleItem?>(null) }

    androidx.activity.compose.BackHandler { onBack() }

    fun start(mode: String) {
        if (ReviewSession.start(ctx, mode)) onOpenSession()
        else Toast.makeText(ctx, "Nothing to review there", Toast.LENGTH_SHORT).show()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("Review", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Review settings") }
                }
            }
            if (session.active) {
                item(key = "paused") {
                    SessionBanner(session, onResume = { ReviewSession.resume(ctx); onOpenSession() }, onEnd = { ReviewSession.clear(ctx) })
                }
            }
            item(key = "start") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { start(ReviewSession.MODE_ALL) },
                        enabled = allCount > 0,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("▶ Start review ($allCount)", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("All sections in order · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { start(ReviewSession.MODE_CALLS) }, enabled = callCount > 0) { Text("📞 Calls only ($callCount)") }
                    }
                }
            }
            shown.forEach { sec ->
                val list = data[sec].orEmpty()
                item(key = "h-${sec.key}") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${sec.emoji} ${sec.title.uppercase()} · ${list.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (sec == ReviewSection.OVERDUE) LateRed else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        if (list.isNotEmpty() && ReviewPrefs.session(ctx, sec)) {
                            FilledTonalButton(
                                onClick = { start(sec.key) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) { Text("▶ Start", fontSize = 13.sp) }
                        }
                    }
                }
                if (list.isEmpty()) {
                    item(key = "e-${sec.key}") {
                        Text("Nothing here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp))
                    }
                }
                items(list, key = { "${sec.key}-${it.key}" }) { item ->
                    ReviewRow(
                        item = item,
                        now = now,
                        onCall = { ReviewActions.call(ctx, item) },
                        onDone = {
                            ReviewActions.done(ctx, item)
                            Toast.makeText(ctx, "Done · ${item.title}", Toast.LENGTH_SHORT).show()
                        },
                        onNewTime = { newTimeFor = item },
                        onDetails = { editFor = ToScheduleStore.items.value.firstOrNull { it.id == item.id } },
                    )
                }
            }
        }
    }

    newTimeFor?.let { item ->
        TimePickerDialog(
            title = if (item.at > 0L) "New time" else "Set time",
            selected = null,
            onPick = { ms ->
                newTimeFor = null
                ReviewActions.newTime(ctx, item, ms)
                Toast.makeText(ctx, "Set · ${Fmt.dueLabel(ms)}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { newTimeFor = null },
        )
    }
    editFor?.let { t -> FollowUpDialog(onDismiss = { editFor = null }, item = t) }
}

@Composable
private fun SessionBanner(s: ReviewSession.State, onResume: () -> Unit, onEnd: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = PausedBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.status == ReviewSession.Status.PAUSED) "⏸ Review session paused" else "▶ Review session running",
                    color = PausedFg, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f)
                )
                Text("${s.handled} / ${s.total}", color = PausedFg, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PausedFg, contentColor = PausedInk)
                ) { Text("▶ Resume", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("End", color = PausedFg) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewRow(
    item: ReviewItem,
    now: Long,
    onCall: () -> Unit,
    onDone: () -> Unit,
    onNewTime: () -> Unit,
    onDetails: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val late = item.at in 1..now
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(
                whenLabel(item.at, now),
                color = if (late) LateRed else cs.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.width(66.dp).padding(top = 2.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (item.person.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(text = item.person, size = 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(item.person, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.chips.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.chips.forEach { ReviewChip(it) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (item.kind) {
                            ReviewItem.Kind.SCHED_FU -> {
                                if (item.number.isBlank()) TextButton(onClick = onDetails) { Text("👤 Set person") }
                                else TextButton(onClick = onNewTime) { Text("🕘 Set time") }
                                TextButton(onClick = onDetails) { Text("Edit") }
                            }
                            ReviewItem.Kind.SCHED_MY -> TextButton(onClick = onNewTime) { Text("🕘 Set time") }
                            ReviewItem.Kind.CALL_REVIEW, ReviewItem.Kind.TEXT_REVIEW -> {
                                TextButton(onClick = onDone) { Text("✓ Accept") }
                                TextButton(onClick = onNewTime) { Text("Not done · New time") }
                            }
                            else -> {
                                TextButton(onClick = onDone) { Text("✓ Done") }
                                TextButton(onClick = onNewTime) { Text("🕘 New time") }
                            }
                        }
                    }
                    // Small 📞 and WA on the same line, so the row stays as short as its text.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        if (ReviewActions.canCall(item)) {
                            RoundAction(Icons.Rounded.Call, "Call", CallBlue, Color(0xFF0B1A4A), onClick = onCall)
                        }
                        if (item.number.isNotBlank()) {
                            WhatsAppAction { WhatsApp.forItem(ctx, item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewChip(text: String) {
    val late = text.endsWith("late")
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = if (late) LateRed else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (late) LateBg else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

// ---------------------------------------------------------------- Session

/**
 * The review session, one item at a time: calls get the Next card (Call / WhatsApp / Done / New time; you tap
 * each call and your usual after-call popup follows), the rest a card with buttons that fit. Back pauses it.
 * Every Done and every time set here offers Undo for a few seconds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewSessionScreen(resumeKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by ReviewSession.state.collectAsState()
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val sched by ToScheduleStore.items.collectAsState()
    val mine by MyTaskStore.items.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // A call made from the card: how the task and the session were, so a Done or New time in the after-call
    // popup can be undone when you come back.
    val callUndo = remember { arrayOfNulls<Pair<ReviewItem, Pair<ReviewSession.State, () -> Unit>>>(1) }

    /** Back from a call: read how it went, with Undo when it was settled. */
    fun settle() {
        val before = ReviewSession.state.value
        ReviewSession.settle(ctx)
        val after = ReviewSession.state.value
        val saved = callUndo[0] ?: return
        callUndo[0] = null
        val (item, undo) = saved
        // A text changed to a call (Call later) can't be put back from here.
        if (item.kind == ReviewItem.Kind.TEXT && SmsStore.get(item.id) == null) return
        val msg = when {
            after.done > before.done -> "Done · ${item.title}"
            after.newTime > before.newTime -> "New time · ${item.title}"
            else -> return
        }
        scope.offerUndo(snack, msg) { undo.second(); ReviewSession.restore(ctx, undo.first) }
    }

    LaunchedEffect(resumeKey) {
        val st = ReviewSession.init(ctx)
        // Back from the after-call popup: read how the call went.
        if (st.awaiting.isNotEmpty() && ReviewSession.popupClosedAt(ctx) > st.awaitingAt) settle()
        tick++
    }
    val item = remember(s, calls, texts, sched, mine, tick) { if (s.status == ReviewSession.Status.RUNNING) ReviewSession.current(ctx) else null }
    val upNext = remember(s, calls, texts, tick) { if (item?.isCall == true || item?.isReplied == true) ReviewSession.upNext(ctx) else emptyList() }
    var newTimeFor by remember { mutableStateOf<ReviewItem?>(null) }
    var editFor by remember { mutableStateOf<ReviewItem?>(null) }
    // Off to WhatsApp from the card: leaving the app then doesn't pause the session.
    val toWhatsApp = remember { booleanArrayOf(false) }

    /** Done, a new time or Review later from the card, with Undo for a few seconds (puts the task and the session back). */
    fun handled(item: ReviewItem, message: String, act: () -> Unit) {
        val before = ReviewSession.state.value
        val undo = ReviewActions.snapshot(item)
        act()
        scope.offerUndo(snack, message) { undo(); ReviewSession.restore(ctx, before) }
    }

    fun setTime(item: ReviewItem, ms: Long) {
        RecentPicks.record(ctx, ms)
        handled(item, "Set · ${Fmt.dueLabel(ms)} · ${item.title}") {
            ReviewActions.newTime(ctx, item, ms)
            ReviewSession.markNewTime(ctx)
        }
    }

    fun back() {
        ReviewSession.pause(ctx)
        onBack()
    }
    androidx.activity.compose.BackHandler { back() }

    // Leaving the app between calls pauses the session (not while a call it started is going on).
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) toWhatsApp[0] = false
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !toWhatsApp[0]) {
                val st = ReviewSession.state.value
                // From any card (My tasks and To schedule too), so the session never stays "running" by mistake.
                if (st.status == ReviewSession.Status.RUNNING && st.awaiting.isEmpty()) ReviewSession.pause(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val callsMode = s.mode == ReviewSession.MODE_CALLS
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(if (callsMode) "Call session" else "Review session", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            when (s.status) {
                ReviewSession.Status.ENDED -> SessionSummary(
                    s,
                    offerUndo = { msg, undo -> scope.offerUndo(snack, msg, undo) },
                    onClose = { ReviewSession.clear(ctx); onBack() },
                )
                ReviewSession.Status.PAUSED -> SessionBanner(s, onResume = { ReviewSession.resume(ctx) }, onEnd = { ReviewSession.clear(ctx); onBack() })
                ReviewSession.Status.IDLE -> {
                    Text("No review session running.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onBack) { Text("Back to Review") }
                }
                ReviewSession.Status.RUNNING -> if (item != null) {
                    Row {
                        Text("${s.handled + 1} of ${s.total}", modifier = Modifier.weight(1f))
                        Text("${s.done} done · ${s.skipped.size} skipped", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(
                        progress = { if (s.total == 0) 0f else s.handled.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    SessionCard(
                        item = item,
                        awaiting = s.awaiting == item.key,
                        onCall = {
                            callUndo[0] = item to (ReviewSession.state.value to ReviewActions.snapshot(item))
                            ReviewSession.calling(ctx, item.key)
                            ReviewActions.call(ctx, item)
                        },
                        onWhatsApp = {
                            toWhatsApp[0] = true
                            if (!WhatsApp.forItem(ctx, item)) toWhatsApp[0] = false
                        },
                        onBackFromCall = { settle(); tick++ },
                        onDone = {
                            handled(item, (if (item.isReplied) "Accepted · " else "Done · ") + item.title) {
                                ReviewActions.done(ctx, item)
                                ReviewSession.markDone(ctx)
                            }
                        },
                        onTime = { ms -> setTime(item, ms) },
                        onPickTime = { newTimeFor = item },
                        onPerson = { n, num -> ReviewActions.setPerson(item, n, num); tick++ },
                        onEdit = { editFor = item },
                        onSkip = {
                            if (item.isReplied) {
                                handled(item, "Review later · back ${Fmt.dueLabel(ReviewData.nextReviewAt(ctx))}") {
                                    ReviewSession.reviewLater(ctx, item)
                                }
                            } else ReviewSession.skip(ctx)
                        },
                        onStop = { ReviewSession.finish(ctx) },
                    )
                    if (upNext.isNotEmpty()) {
                        Text("UP NEXT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        upNext.forEach { n ->
                            // Task first, the person small under it.
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(n.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (n.person.isNotBlank()) {
                                    Text(n.person, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    newTimeFor?.let { it0 ->
        if (it0.isCall || it0.isReplied) {
            // Overdue or replied follow-up: the short time row plus an optional "Why not done?".
            NewTimeReasonDialog(
                title = (if (it0.isReplied) "Not done · " else "New time · ") + it0.title,
                person = it0.person,
                onPick = { ms, reason ->
                    newTimeFor = null
                    handled(it0, "Moved to ${Fmt.dueLabel(ms)} · ${it0.title}") {
                        ReviewActions.newTime(ctx, it0, ms, reason)
                        ReviewSession.markNewTime(ctx)
                    }
                },
                onDismiss = { newTimeFor = null },
            )
        } else {
            // My tasks: your busy times crossed out and the best free slots for its length.
            val busy = if (it0.isMine) PickBusy(it0.lengthMin, it0.id) else null
            androidx.compose.runtime.CompositionLocalProvider(LocalPickBusy provides busy) {
                TimePickerDialog(
                    title = (if (it0.at > 0L) "New time · " else "Set time · ") + it0.title,
                    selected = null,
                    onPick = { ms ->
                        newTimeFor = null
                        setTime(it0, ms)
                    },
                    onDismiss = { newTimeFor = null },
                )
            }
        }
    }
    // ✏️ Edit: the task's own editor; the session carries on from the same card after it closes.
    editFor?.let { e -> TaskEditorDialog(task = OpenTask.of(e), onDismiss = { editFor = null; tick++ }) }
}

/** New time on a session card: the task as the title, then why it isn't done (optional), then tap a time and it saves. */
@Composable
private fun NewTimeReasonDialog(title: String, person: String, onPick: (Long, String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (person.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Avatar(text = person, size = 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(person, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortTimeRow(selected = null, showSelected = false, dialogTitle = "New time", onPick = { onPick(it, reason) })
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Why not done? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Saved in the task's history. Its texts move to the new time too. Tap a time to save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    item: ReviewItem,
    awaiting: Boolean,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onBackFromCall: () -> Unit,
    onDone: () -> Unit,
    onTime: (Long) -> Unit,
    onPickTime: () -> Unit,
    onPerson: (String, String) -> Unit,
    onEdit: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (item.kind) {
                        ReviewItem.Kind.CALL, ReviewItem.Kind.TEXT -> "NEXT"
                        ReviewItem.Kind.SCHED_FU -> "TO SCHEDULE · FOLLOW-UP"
                        ReviewItem.Kind.SCHED_MY -> "TO SCHEDULE · MY TASK"
                        ReviewItem.Kind.MY_TASK -> "MY TASK · OVERDUE"
                        else -> "REPLIED · CHECK IT"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("✏️ Edit", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            // Task first, the person small under it.
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (item.person.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(text = item.person, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(item.person, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (item.isCall && item.number.isNotBlank() && item.number != item.person) {
                        Text(" · ${item.number}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (item.code.isNotBlank() || item.chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.code.isNotBlank()) CodeChip(item.code)
                    item.chips.forEach { ReviewChip(it) }
                }
            }

            when (item.kind) {
                ReviewItem.Kind.CALL, ReviewItem.Kind.TEXT -> {
                    if (awaiting) {
                        Text("Back from the call? Your after-call popup saves how it went.", color = cs.onSurfaceVariant)
                        BigButton("Next ›", onClick = onBackFromCall)
                        OutlinedButton(onClick = onCall, modifier = Modifier.fillMaxWidth()) { Text("📞 Call again") }
                    } else {
                        // One row: call him, WhatsApp him, or settle it without calling.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (item.number.isNotBlank()) {
                                SmallTile("📞", "Call", CallBlue, Modifier.weight(1f), onCall)
                                SmallTile("🟢", "WhatsApp", WhatsAppGreen, Modifier.weight(1f), onWhatsApp)
                            }
                            SmallTile("✓", "Done", DoneGreen, Modifier.weight(1f), onDone)
                            SmallTile("🕘", "New time", NewTimeBlue, Modifier.weight(1f), onPickTime)
                        }
                    }
                }
                ReviewItem.Kind.CALL_REVIEW, ReviewItem.Kind.TEXT_REVIEW -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TileButton("✓", "Accept", DoneGreen, Modifier.weight(1f), onDone)
                        TileButton("🕘", "Not done · New time", NewTimeBlue, Modifier.weight(1f), onPickTime)
                    }
                    if (item.number.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WideTile("📞", "Call", CallBlue, Modifier.weight(1f), onCall)
                            WideTile("🟢", "WhatsApp", WhatsAppGreen, Modifier.weight(1f), onWhatsApp)
                        }
                    }
                }
                ReviewItem.Kind.MY_TASK -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallTile("✓", "Done", DoneGreen, Modifier.weight(1f), onDone)
                        SmallTile("🕘", "New time", NewTimeBlue, Modifier.weight(1f), onPickTime)
                    }
                    QuickTimes(item, onTime)
                }
                ReviewItem.Kind.SCHED_FU, ReviewItem.Kind.SCHED_MY -> {
                    if (item.kind == ReviewItem.Kind.SCHED_FU && item.number.isBlank()) {
                        PersonPicker(prompt = "Pick person first") { n, num -> onPerson(n, num) }
                    } else {
                        QuickTimes(item, onTime)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.kind == ReviewItem.Kind.SCHED_FU) {
                                // Follow-ups don't book time, but never outside your working hours: your next work start.
                                val start = remember { ReviewData.nextWorkStart(ctx) }
                                OutlinedButton(onClick = { onTime(start) }, modifier = Modifier.weight(1f)) {
                                    Text("🌅 ${dayWord(start)} ${Fmt.timeOnly(start)}", maxLines = 1)
                                }
                            }
                            OutlinedButton(onClick = onPickTime, modifier = Modifier.weight(1f)) { Text("📅 Pick time") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(if (item.isReplied) "🕘 Review later" else "⏭ Skip")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(48.dp)) { Text("■ Stop", color = LateRed) }
            }
        }
    }
}

/** Recently used times, then (My tasks) up to 6 best free slots from your Calendar inside working hours. */
@Composable
private fun QuickTimes(item: ReviewItem, onTime: (Long) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val now = remember(item.key) { System.currentTimeMillis() }
    val recent = remember(item.key) { RecentPicks.load(ctx) }
    if (recent.isNotEmpty()) {
        Text("RECENTLY USED TIMES", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        RecentChips(recent, now, null) { ms -> onTime(ms) }
    }
    if (!item.isMine) return
    val slots = remember(item.key) { ReviewData.freeSlots(ctx, item.lengthMin, item.id, 6, now) }
    Text(
        "BEST FREE SLOTS FOR MY TASKS · ${CalendarData.lengthLabel(item.lengthMin.toLong()).uppercase()}",
        style = MaterialTheme.typography.labelMedium,
        color = cs.onSurfaceVariant
    )
    if (slots.isEmpty()) {
        Text(
            if (WorkHours.isSet(ctx)) "No free time in your working hours in the next 2 weeks" else "Set your working hours in Calendar to see free slots",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        slots.chunked(3).forEach { rowSlots ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowSlots.forEach { ms ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlotGreenBg)
                            .border(1.5.dp, SlotGreen, RoundedCornerShape(10.dp))
                            .clickable { onTime(ms) }
                            .padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(dayWord(ms), fontSize = 10.sp, color = SlotGreenInk.copy(alpha = 0.75f), maxLines = 1)
                        Text(Fmt.timeOnly(ms), fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, color = SlotGreenInk, maxLines = 1)
                    }
                }
                repeat(3 - rowSlots.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val SlotGreen = Color(0xFF2E9E5B)
private val SlotGreenBg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF123A24) else Color(0xFFE3F6EA)
private val SlotGreenInk: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFFBDF5D2) else Color(0xFF145A32)

/** Today, Tomorrow, or the short day and date. */
private fun dayWord(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
    }
}

/** The task number, first in the chip row. */
@Composable
private fun CodeChip(code: String) {
    Text(
        "#$code",
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CallBlue, contentColor = Color(0xFF0B1A4A))
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun TileButton(icon: String, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = color, modifier = modifier.height(96.dp)) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 24.sp, color = Color(0xFF0B1A2A))
            Text(label, fontWeight = FontWeight.Bold, color = Color(0xFF0B1A2A), fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SmallTile(icon: String, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = color, modifier = modifier.height(76.dp)) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 20.sp, color = Color(0xFF0B1A2A))
            Text(label, fontWeight = FontWeight.Bold, color = Color(0xFF0B1A2A), fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** A low tile with the icon beside the label (Call / WhatsApp under Accept on a replied card). */
@Composable
private fun WideTile(icon: String, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = color, modifier = modifier.height(52.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 16.sp, color = Color(0xFF0B1A2A))
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.Bold, color = Color(0xFF0B1A2A), fontSize = 14.sp, maxLines = 1)
        }
    }
}

private val NewTimeBlue = Color(0xFFC9D6FF)

/**
 * The end: counts, then what is still open. Skipped ones with a time in working hours move to the same time on
 * the next working day; replied ones put to Review later are never moved (Accept or Not done here, or leave them);
 * ones with no time or a time outside working hours are listed with Set time.
 */
@Composable
private fun SessionSummary(s: ReviewSession.State, offerUndo: (String, () -> Unit) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val sched by ToScheduleStore.items.collectAsState()
    val mine by MyTaskStore.items.collectAsState()
    val minutes = ((System.currentTimeMillis() - s.startedAt) / 60_000L).coerceAtLeast(1)
    val left = remember(s, calls, texts, sched, mine) { (s.skipped + s.noAnswer).distinct().mapNotNull { ReviewData.find(ctx, it) } }
    val later = remember(s, calls, texts) {
        (s.later.mapNotNull { ReviewData.findLater(ctx, it) } + left.filter { it.isReplied }).distinctBy { it.key }
    }
    val movable = left.filter { !it.isReplied && ReviewData.sameTimeNextDay(ctx, it.at) != null }
    val notMoved = left.filter { !it.isReplied && ReviewData.sameTimeNextDay(ctx, it.at) == null }
    var unticked by remember { mutableStateOf(setOf<String>()) }
    val ticked = movable.filter { it.key !in unticked }
    var other by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<ReviewItem?>(null) }

    /** Moves [items] (each to [whenFor] its own time), with one Undo for all of them. */
    fun move(items: List<ReviewItem>, label: String, whenFor: (ReviewItem) -> Long?) {
        val undos = items.map { ReviewActions.snapshot(it) }
        items.forEach { i -> whenFor(i)?.let { ReviewActions.newTime(ctx, i, it) } }
        offerUndo("Moved ${items.size} $label") { undos.forEach { it() } }
    }

    fun one(item: ReviewItem, message: String, act: () -> Unit) {
        val undo = ReviewActions.snapshot(item)
        act()
        offerUndo(message) { undo() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("🎉 Session finished", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            listOfNotNull(
                "${s.handled} items · $minutes min",
                "${s.done} done",
                "${s.newTime} new time",
                "${s.skipped.size} skipped",
                s.noAnswer.size.takeIf { it > 0 }?.let { "$it no answer twice" },
                s.later.size.takeIf { it > 0 }?.let { "$it review later" },
            ).joinToString(" · "),
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (movable.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(18.dp), color = PausedBg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Move to tomorrow, same time", color = PausedFg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Untick any you want to leave as they are", style = MaterialTheme.typography.bodySmall, color = PausedFg.copy(alpha = 0.8f))
                    movable.forEach { it0 ->
                        val on = it0.key !in unticked
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            unticked = if (on) unticked + it0.key else unticked - it0.key
                        }) {
                            Checkbox(checked = on, onCheckedChange = { c -> unticked = if (c) unticked - it0.key else unticked + it0.key })
                            SummaryText(it0, listOf(it0.person, "now ${Fmt.timeOnly(it0.at)}"), Modifier.weight(1f))
                            Text("→ ${dayWord(ReviewData.sameTimeNextDay(ctx, it0.at)!!)} ${Fmt.timeOnly(it0.at)}",
                                color = DoneGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    Button(
                        onClick = { move(ticked, "to the same time tomorrow") { ReviewData.sameTimeNextDay(ctx, it.at) } },
                        enabled = ticked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PausedFg, contentColor = PausedInk)
                    ) { Text("Move ${ticked.size} · same time tomorrow", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { other = true }, enabled = ticked.isNotEmpty(), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("🕘 Other time", color = PausedFg)
                    }
                }
            }
        }

        if (later.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(18.dp), color = RepliedBg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Replied, review later · ${later.size}", color = RepliedFg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Back at your next review time. Checked it already? Tap below", style = MaterialTheme.typography.bodySmall,
                        color = RepliedFg.copy(alpha = 0.8f))
                    later.forEach { it0 ->
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            SummaryText(it0, listOf(it0.person, it0.at.takeIf { at -> at > System.currentTimeMillis() }?.let { at -> "back ${Fmt.dueLabel(at)}" }))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        one(it0, "Accepted · ${it0.title}") { ReviewActions.done(ctx, it0) }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("✓ Accept", color = RepliedFg, fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { timeFor = it0 }, modifier = Modifier.weight(1f)) {
                                    Text("🕘 Not done · New time", maxLines = 1, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (notMoved.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(18.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Not moved · ${notMoved.size}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("No time yet, or a time outside your working hours", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    notMoved.forEach { it0 ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            SummaryText(
                                it0,
                                listOf(it0.person.ifBlank { if (it0.isMine) "My task" else "" }, if (it0.at > 0L) Fmt.timeOnly(it0.at) else "no time"),
                                Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { timeFor = it0 }) { Text("🕘 Set time", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Back to Review", fontWeight = FontWeight.Bold) }
    }
    if (other) {
        TimePickerDialog(title = "Move ${ticked.size} to", selected = null, onPick = { ms ->
            other = false
            move(ticked, "to ${Fmt.dueLabel(ms)}") { ms }
        }, onDismiss = { other = false })
    }
    timeFor?.let { it0 ->
        val busy = if (it0.isMine) PickBusy(it0.lengthMin, it0.id) else null
        androidx.compose.runtime.CompositionLocalProvider(LocalPickBusy provides busy) {
            TimePickerDialog(
                title = (if (it0.isReplied) "Not done · " else "Set time · ") + it0.title,
                selected = null,
                onPick = { ms ->
                    timeFor = null
                    one(it0, "Set · ${Fmt.dueLabel(ms)} · ${it0.title}") { ReviewActions.newTime(ctx, it0, ms) }
                },
                onDismiss = { timeFor = null },
            )
        }
    }
}

private val RepliedBg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF0B3A30) else Color(0xFFDDF5EC)
private val RepliedFg: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF7BE8CC) else Color(0xFF0B6B52)

/** A task on the finished screen: the task in bold, small details (person, time) under it. */
@Composable
private fun SummaryText(item: ReviewItem, details: List<String?>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line = details.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun CountBox(n: String, label: String, color: Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.padding(vertical = 16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // The bright tile colours are too pale as text on white, so light mode uses a deeper shade.
            Text(n, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (LocalDarkTheme.current) color else androidx.compose.ui.graphics.lerp(color, Color.Black, 0.45f))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Home card

/** Home's Review card under search: count, breakdown, ▶ Start review and Open; Resume / End while a session waits. */
@Composable
fun ReviewHomeCard(resumeKey: Int, onStart: () -> Unit, onOpen: () -> Unit, onResume: () -> Unit) {
    val ctx = LocalContext.current
    val s by ReviewSession.state.collectAsState()
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val sched by ToScheduleStore.items.collectAsState()
    val mine by MyTaskStore.items.collectAsState()
    LaunchedEffect(resumeKey) { ReviewSession.init(ctx) }
    val counts = remember(calls, texts, sched, mine, resumeKey) { ReviewData.shownCounts(ctx) }
    // The red number is what "Start review" goes through; shown sections left out of the session
    // (like "Still due today") stay in the plain line under it only.
    val total = remember(counts) { counts.filter { ReviewPrefs.session(ctx, it.first) }.sumOf { it.second } }

    if (s.active) {
        Surface(shape = RoundedCornerShape(20.dp), color = PausedBg, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.status == ReviewSession.Status.PAUSED) "⏸ Continue review · ${s.total - s.handled} left" else "▶ Review session running",
                        color = PausedFg, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f)
                    )
                    Text("${s.handled} / ${s.total}", color = PausedFg, fontWeight = FontWeight.Bold)
                }
                remember(s) { ReviewSession.nextName(ctx) }?.let { Text("Next: $it", color = PausedFg.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { ReviewSession.resume(ctx); onResume() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PausedFg, contentColor = PausedInk)
                    ) { Text("▶ Continue", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { ReviewSession.clear(ctx) }, modifier = Modifier.weight(1f)) { Text("End", color = PausedFg) }
                }
            }
        }
        return
    }
    // The accent gradient card. Deeper in dark mode so white text stays readable.
    val cs = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val g1 = if (dark) androidx.compose.ui.graphics.lerp(cs.primary, Color.Black, 0.35f) else cs.primary
    val g2 = if (dark) androidx.compose.ui.graphics.lerp(LocalAccent2.current, Color.Black, 0.3f) else LocalAccent2.current
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(g1, g2)))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📋 Review", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (total > 0) {
                    Text(
                        "$total",
                        color = cs.error,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                counts.filter { it.second > 0 }.joinToString(" · ") { (sec, n) -> "$n ${sec.title.lowercase()}" }.ifBlank { "Nothing waiting" },
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    enabled = total > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = g1,
                        disabledContainerColor = Color.White.copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.8f)
                    )
                ) { Text("▶ Start review", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.7f))
                ) { Text("Open", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

/** Settings › Review: each section's Show and Session switches, drag ≡ to reorder. */
@Composable
fun ReviewSectionsSettings() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    SectionCard(title = "In Start review", subtitle = "Always in this order. Turn off what you don't want in the session.") {
        ReviewPrefs.Group.values().forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider()
            val on = remember(tick) { ReviewPrefs.inSession(ctx, g) }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${g.emoji} ${g.title}", style = MaterialTheme.typography.bodyLarge)
                    Text(g.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = on, onCheckedChange = { ReviewPrefs.setInSession(ctx, g, it); tick++ })
            }
        }
    }
}

/** True when this is a hint to let the activity leave (opened from the popup). */
fun leaveIfFromOutside(ctx: android.content.Context): Boolean {
    if (!ReviewUi.fromOutside.value) return false
    ReviewUi.fromOutside.value = false
    (ctx.findActivity())?.moveTaskToBack(true)
    return true
}
