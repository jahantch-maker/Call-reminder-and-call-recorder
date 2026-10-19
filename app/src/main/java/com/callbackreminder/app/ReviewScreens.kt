package com.callbackreminder.app

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.Scaffold
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

private val LateRed = Color(0xFFFF8A80)
private val LateBg = Color(0xFF5C1A1A)
private val PausedBg = Color(0xFF4A3A0E)
private val PausedFg = Color(0xFFFFD36B)
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
        when (item.kind) {
            ReviewItem.Kind.CALL -> ReminderStore.markDone(item.id)
            ReviewItem.Kind.CALL_REVIEW -> ReminderStore.reviewed(item.id)
            ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.markDone(item.id)
            ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> MyTaskStore.setDone(item.id, true)
            ReviewItem.Kind.SCHED_FU -> ToScheduleStore.remove(item.id)
        }
    }

    fun newTime(ctx: android.content.Context, item: ReviewItem, ms: Long) {
        when (item.kind) {
            ReviewItem.Kind.CALL -> {
                val before = ReminderStore.get(item.id) ?: return
                ReminderStore.postpone(item.id, ms, "")
                FollowUps.onNewTime(ctx, before, ms, moveTexts = true)
            }
            ReviewItem.Kind.CALL_REVIEW -> ReminderStore.reschedule(item.id, ms, "Not done, moved to")
            ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.reschedule(item.id, ms)
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
                    colors = ButtonDefaults.buttonColors(containerColor = PausedFg, contentColor = Color(0xFF2A1C00))
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }
            if (ReviewActions.canCall(item)) {
                Surface(onClick = onCall, shape = CircleShape, color = CallBlue, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Call, contentDescription = "Call", tint = Color(0xFF0B1A4A))
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
 * The review session, one item at a time: calls get the Next card (Call now / Skip / Stop; you tap each call and
 * your usual after-call popup follows), the rest a card with buttons that fit. Back pauses it.
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
    LaunchedEffect(resumeKey) {
        val st = ReviewSession.init(ctx)
        // Back from the after-call popup: read how the call went.
        if (st.awaiting.isNotEmpty() && ReviewSession.popupClosedAt(ctx) > st.awaitingAt) ReviewSession.settle(ctx)
        tick++
    }
    val item = remember(s, calls, texts, sched, mine, tick) { if (s.status == ReviewSession.Status.RUNNING) ReviewSession.current(ctx) else null }
    val upNext = remember(s, calls, texts, tick) { if (item?.isCall == true) ReviewSession.upNext(ctx) else emptyList() }
    var newTimeFor by remember { mutableStateOf<ReviewItem?>(null) }

    fun back() {
        ReviewSession.pause(ctx)
        onBack()
    }
    androidx.activity.compose.BackHandler { back() }

    // Leaving the app between calls pauses the session (not while a call it started is going on).
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val st = ReviewSession.state.value
                val cur = st.current
                if (st.status == ReviewSession.Status.RUNNING && st.awaiting.isEmpty() &&
                    (cur?.startsWith("c:") == true || cur?.startsWith("t:") == true)
                ) ReviewSession.pause(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val callsMode = s.mode == ReviewSession.MODE_CALLS
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
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
                ReviewSession.Status.ENDED -> SessionSummary(s, onClose = { ReviewSession.clear(ctx); onBack() })
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
                            ReviewSession.calling(ctx, item.key)
                            ReviewActions.call(ctx, item)
                        },
                        onBackFromCall = { ReviewSession.settle(ctx); tick++ },
                        onDone = { ReviewActions.done(ctx, item); ReviewSession.markDone(ctx) },
                        onTime = { ms -> ReviewActions.newTime(ctx, item, ms); ReviewSession.markNewTime(ctx) },
                        onPickTime = { newTimeFor = item },
                        onPerson = { n, num -> ReviewActions.setPerson(item, n, num); tick++ },
                        onSkip = { ReviewSession.skip(ctx) },
                        onStop = { ReviewSession.finish(ctx) },
                    )
                    if (upNext.isNotEmpty()) {
                        Text("UP NEXT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        upNext.forEach { n ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(text = n.person.ifBlank { n.title }, size = 28.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(n.person.ifBlank { "—" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(n.title, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    newTimeFor?.let { it0 ->
        TimePickerDialog(
            title = if (it0.at > 0L) "New time" else "Set time",
            selected = null,
            onPick = { ms ->
                newTimeFor = null
                ReviewActions.newTime(ctx, it0, ms)
                ReviewSession.markNewTime(ctx)
            },
            onDismiss = { newTimeFor = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    item: ReviewItem,
    awaiting: Boolean,
    onCall: () -> Unit,
    onBackFromCall: () -> Unit,
    onDone: () -> Unit,
    onTime: (Long) -> Unit,
    onPickTime: () -> Unit,
    onPerson: (String, String) -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when (item.kind) {
                    ReviewItem.Kind.CALL, ReviewItem.Kind.TEXT -> "NEXT"
                    ReviewItem.Kind.SCHED_FU -> "TO SCHEDULE"
                    ReviewItem.Kind.SCHED_MY -> "TO SCHEDULE · MY TASK"
                    ReviewItem.Kind.MY_TASK -> "MY TASK · OVERDUE"
                    else -> "REPLIED · CHECK IT"
                },
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant
            )
            if (item.person.isNotBlank() && item.isCall) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(text = item.person, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(item.person, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (item.number.isNotBlank() && item.number != item.person) Text(item.number, color = cs.onSurfaceVariant)
                    }
                }
            }
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!item.isCall && item.person.isNotBlank()) Text("👤 ${item.person}", color = cs.onSurfaceVariant)
            if (item.chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { item.chips.forEach { ReviewChip(it) } }
            }

            when (item.kind) {
                ReviewItem.Kind.CALL, ReviewItem.Kind.TEXT -> {
                    if (awaiting) {
                        Text("Back from the call? Your after-call popup saves how it went.", color = cs.onSurfaceVariant)
                        BigButton("Next ›", onClick = onBackFromCall)
                        OutlinedButton(onClick = onCall, modifier = Modifier.fillMaxWidth()) { Text("📞 Call again") }
                    } else {
                        BigButton("📞 Call now", onClick = onCall)
                    }
                }
                ReviewItem.Kind.CALL_REVIEW, ReviewItem.Kind.TEXT_REVIEW -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TileButton("✓", "Accept", DoneGreen, Modifier.weight(1f), onDone)
                        TileButton("🕘", "Not done · New time", CallBlue, Modifier.weight(1f), onPickTime)
                    }
                    if (item.number.isNotBlank()) OutlinedButton(onClick = onCall, modifier = Modifier.fillMaxWidth()) { Text("📞 Call") }
                }
                ReviewItem.Kind.MY_TASK -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TileButton("✓", "Done", DoneGreen, Modifier.weight(1f), onDone)
                        TileButton("🌅", "Tomorrow 9 AM", CallBlue, Modifier.weight(1f)) { onTime(ReviewData.tomorrowAt(9)) }
                    }
                    OutlinedButton(onClick = onPickTime, modifier = Modifier.fillMaxWidth()) { Text("🕘 New time") }
                }
                ReviewItem.Kind.SCHED_FU, ReviewItem.Kind.SCHED_MY -> {
                    if (item.kind == ReviewItem.Kind.SCHED_FU && item.number.isBlank()) {
                        PersonPicker(prompt = "Pick person first") { n, num -> onPerson(n, num) }
                    } else {
                        Text("THEN ONE TAP SETS THE TIME", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        val recent = remember { RecentPicks.load(ctx) }
                        val now = remember { System.currentTimeMillis() }
                        if (recent.isNotEmpty()) RecentChips(recent, now, null) { ms -> RecentPicks.record(ctx, ms); onTime(ms) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onTime(ReviewData.tomorrowAt(9)) }, modifier = Modifier.weight(1f)) { Text("Tomorrow 9:00 AM") }
                            OutlinedButton(onClick = onPickTime, modifier = Modifier.weight(1f)) { Text("📅 Pick time") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f).height(48.dp)) { Text("⏭ Skip") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(48.dp)) { Text("■ Stop", color = LateRed) }
            }
        }
    }
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
            Text(label, fontWeight = FontWeight.Bold, color = Color(0xFF0B1A2A), fontSize = 15.sp)
        }
    }
}

/** The end: counts, and the skipped ones still open, with "move them to tomorrow 10:00 AM". */
@Composable
private fun SessionSummary(s: ReviewSession.State, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val minutes = ((System.currentTimeMillis() - s.startedAt) / 60_000L).coerceAtLeast(1)
    val left = remember(s) { (s.skipped + s.noAnswer).distinct().mapNotNull { ReviewData.find(ctx, it) } }
    var ticked by remember(left) { mutableStateOf(left.map { it.key }.toSet()) }
    var moved by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }

    fun moveAll(ms: Long) {
        left.filter { it.key in ticked }.forEach { ReviewActions.newTime(ctx, it, ms) }
        Toast.makeText(ctx, "Moved ${ticked.size} to ${Fmt.dueLabel(ms)}", Toast.LENGTH_SHORT).show()
        moved = true
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("🎉", fontSize = 56.sp)
        Text("Session finished", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${s.handled} items · $minutes min", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountBox("${s.done}", "Done", DoneGreen, Modifier.weight(1f))
            CountBox("${s.newTime}", "New time", CallBlue, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountBox("${s.skipped.size}", "Skipped", PausedFg, Modifier.weight(1f))
            CountBox("${s.noAnswer.size}", "No answer twice", LateRed, Modifier.weight(1f))
        }
        if (left.isNotEmpty() && !moved) {
            Surface(shape = RoundedCornerShape(18.dp), color = PausedBg, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("${left.size} skipped: move to tomorrow 10:00 AM?", color = PausedFg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Untick any you want to leave as they are", style = MaterialTheme.typography.bodySmall, color = PausedFg.copy(alpha = 0.8f))
                    left.forEach { it0 ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            ticked = if (it0.key in ticked) ticked - it0.key else ticked + it0.key
                        }) {
                            Checkbox(checked = it0.key in ticked, onCheckedChange = { on -> ticked = if (on) ticked + it0.key else ticked - it0.key })
                            Text(it0.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(it0.person, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                    Button(onClick = { moveAll(ReviewData.tomorrowAt(10)) }, enabled = ticked.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Move ${ticked.size} to tomorrow 10:00 AM")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { other = true }, enabled = ticked.isNotEmpty()) { Text("🕘 Other time") }
                        TextButton(onClick = { moved = true }) { Text("Leave all") }
                    }
                }
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Back to Review", fontWeight = FontWeight.Bold) }
    }
    if (other) {
        TimePickerDialog(title = "Move to", selected = null, onPick = { other = false; moveAll(it) }, onDismiss = { other = false })
    }
}

@Composable
private fun CountBox(n: String, label: String, color: Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.padding(vertical = 16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(n, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = color)
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
    val total = counts.sumOf { it.second }

    if (s.active) {
        Surface(shape = RoundedCornerShape(20.dp), color = PausedBg, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.status == ReviewSession.Status.PAUSED) "⏸ Review session paused" else "▶ Review session running",
                        color = PausedFg, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f)
                    )
                    Text("${s.handled} / ${s.total}", color = PausedFg, fontWeight = FontWeight.Bold)
                }
                remember(s) { ReviewSession.nextName(ctx) }?.let { Text("Next: $it", color = PausedFg.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { ReviewSession.resume(ctx); onResume() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PausedFg, contentColor = Color(0xFF2A1C00))
                    ) { Text("▶ Resume", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { ReviewSession.clear(ctx) }, modifier = Modifier.weight(1f)) { Text("End", color = PausedFg) }
                }
            }
        }
        return
    }
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = Color(0xFF1E2A5E), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📋 Review", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                if (total > 0) {
                    Text(
                        "$total",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFE53950)).padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                counts.filter { it.second > 0 }.joinToString(" · ") { (sec, n) -> "$n ${sec.title.lowercase()}" }.ifBlank { "Nothing waiting" },
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, enabled = total > 0, modifier = Modifier.weight(1f)) { Text("▶ Start review") }
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open", color = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

/** Settings › Review: each section's Show and Session switches, drag ≡ to reorder, and the resume reminder. */
@Composable
fun ReviewSectionsSettings() {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var order by remember { mutableStateOf(ReviewPrefs.order(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<ReviewSection?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowH = 96.dp
    val rowPx = with(density) { rowH.toPx() }
    var minutes by remember { mutableIntStateOf(ReviewPrefs.resumeMinutes(ctx)) }
    var menu by remember { mutableStateOf(false) }

    SectionCard(
        title = "Sections",
        subtitle = "Show = on the Review page. Session = included in \"Start review\". Drag ≡ to reorder. " +
            "The 1 PM / 6 PM popup and the Home card count only the shown ones."
    ) {
        Column {
            order.forEach { sec ->
                val isDrag = dragging == sec
                androidx.compose.runtime.key(sec) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(rowH)
                            .zIndex(if (isDrag) 1f else 0f)
                            .graphicsLayer { translationY = if (isDrag) offset else 0f }
                            .padding(vertical = 4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isDrag) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    modifier = Modifier
                                        .size(36.dp)
                                        .padding(6.dp)
                                        .pointerInput(sec) {
                                            detectDragGestures(
                                                onDragStart = { dragging = sec; offset = 0f },
                                                onDragEnd = { dragging = null; offset = 0f; ReviewPrefs.setOrder(ctx, order) },
                                                onDragCancel = { dragging = null; offset = 0f },
                                                onDrag = { change, d ->
                                                    change.consume()
                                                    offset += d.y
                                                    val idx = order.indexOf(sec)
                                                    val target = (idx + (offset / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                                    if (target != idx) {
                                                        order = order.toMutableList().apply { removeAt(idx); add(target, sec) }
                                                        offset -= (target - idx) * rowPx
                                                    }
                                                }
                                            )
                                        }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("${sec.emoji} ${sec.title}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val show = remember(tick) { ReviewPrefs.show(ctx, sec) }
                                        val ses = remember(tick) { ReviewPrefs.session(ctx, sec) }
                                        Text("Show", style = MaterialTheme.typography.bodySmall)
                                        Switch(checked = show, onCheckedChange = { ReviewPrefs.setShow(ctx, sec, it); tick++ },
                                            modifier = Modifier.padding(horizontal = 6.dp))
                                        Text("Session", style = MaterialTheme.typography.bodySmall)
                                        Switch(checked = ses, enabled = show, onCheckedChange = { ReviewPrefs.setSession(ctx, sec, it); tick++ },
                                            modifier = Modifier.padding(horizontal = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    SectionCard(title = "Call session", subtitle = "While a session is paused, a popup asks you to resume after this long. Kept until midnight.") {
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remind me to resume after $minutes min paused ▾")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ReviewPrefs.RESUME_CHOICES.forEach { m ->
                    DropdownMenuItem(text = { Text("$m min") }, onClick = { minutes = m; ReviewPrefs.setResumeMinutes(ctx, m); menu = false })
                }
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
