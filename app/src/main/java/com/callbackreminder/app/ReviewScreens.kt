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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.Person

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
            ReviewItem.Kind.SCHED_FU -> {
                ToScheduleStore.items.value.firstOrNull { it.id == item.id }?.let { TeamDelete.closeGiven(ctx, it.linkId) }
                ToScheduleStore.remove(item.id)
            }
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
            ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> MyTaskStore.setDue(item.id, ms, reason)
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

    /** Which Needs you tab to open on ([ReviewData.TABS] index), asked by a Home tile. */
    val tab = MutableStateFlow<Int?>(null)
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
    val teamTasks by TeamRepo.tasks.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis(); ReviewSession.init(ctx) }
    val data = remember(calls, texts, sched, mine, teamTasks, now) { ReviewData.build(ctx, now) }
    // ✋ Needs you: All · Replied · Overdue · To sched. · Team; every Home tile opens its own tab.
    val asked by ReviewUi.tab.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(asked) { asked?.let { tab = it; ReviewUi.tab.value = null } }
    val tabs = ReviewData.TABS.indices.filter { it != 4 || TeamRepo.activeNow }
    fun countOf(i: Int) = ReviewData.tabSections(i).flatMap { data[it].orEmpty() }.distinctBy { it.key }.size
    val shown = ReviewData.tabSections(tab)
    val allCount = countOf(tab)
    var newTimeFor by remember { mutableStateOf<ReviewItem?>(null) }
    var editFor by remember { mutableStateOf<ToScheduleItem?>(null) }
    var byPerson by remember { mutableStateOf(ReviewSession.byPerson(ctx)) }

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
                    Text("✋ Needs you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Review settings") }
                }
            }
            if (session.active) {
                item(key = "paused") {
                    SessionBanner(session, onResume = { ReviewSession.resume(ctx); onOpenSession() }, onEnd = { ReviewSession.clear(ctx) })
                }
            }
            item(key = "tabs") {
                BoxTabs(tabs.map { "${ReviewData.TABS[it].first}\n${countOf(it)}" }, tabs.indexOf(tab).coerceAtLeast(0),
                    { tab = tabs[it] }, compact = true)
            }
            item(key = "start") {
                // ▶ Start review runs only the tab you are on.
                Button(
                    onClick = { start("tab:$tab") },
                    enabled = allCount > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Text(if (allCount > 0) "▶ Start review · $allCount" else "Nothing here", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
            item(key = "order") {
                // Parts (today's order) or 👤 By person: picked once, remembered.
                Column {
                    ChipHeading("REVIEW ORDER", modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                    BoxTabs(listOf("Parts", "👤 By person"), if (byPerson) 1 else 0, { byPerson = it == 1; ReviewSession.setByPerson(ctx, it == 1) })
                }
            }
            if (byPerson) {
                // 👤 By person: one row per person (most tasks first, no person last); ▶ reviews only that person.
                val people = shown.flatMap { data[it].orEmpty() }.distinctBy { it.key }.groupBy { ReviewSession.personOf(it) }
                val order = people.keys.sortedWith(compareBy<String>({ it.isEmpty() }, { -(people[it]?.size ?: 0) }))
                item(key = "people-h") { ChipHeading("PEOPLE IN THIS REVIEW", modifier = Modifier.padding(start = 4.dp, top = 4.dp)) }
                if (order.isEmpty()) item(key = "people-e") {
                    Text("Nothing here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp))
                }
                items(order, key = { "p-$it" }) { k ->
                    val list = people[k].orEmpty()
                    val name = if (k.isEmpty()) "No person" else TeamRepo.memberByKey(k)?.name
                        ?: list.firstOrNull { it.person.isNotBlank() }?.person ?: list.first().number
                    val replied = list.count { it.section == ReviewSection.REPLIED }
                    PersonReviewRow(
                        name = name, none = k.isEmpty(),
                        count = "${list.size}" + (if (replied > 0) " · $replied replied" else "") + if (k.isEmpty()) " · last" else "",
                        onStart = { start("person:$tab:$k") },
                    )
                }
                return@LazyColumn
            }
            val seen = mutableSetOf<String>()
            shown.forEach { sec ->
                // In All a task shows once, in the first part it belongs to.
                val list = data[sec].orEmpty().filter { seen.add(it.key) }
                item(key = "h-${sec.key}") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${sec.emoji} ${sec.title.uppercase()} · ${list.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (sec == ReviewSection.OVERDUE) LateRed else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        if (list.isNotEmpty() && tab == 0) {
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
                    Box(Modifier.animateItem()) {
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

/** One person on Needs you › 👤 By person: initial, name, how many (and replied), ▶ to review only them. */
@Composable
private fun PersonReviewRow(name: String, none: Boolean, count: String, onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onStart)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (none) cs.outlineVariant else cs.primaryContainer), contentAlignment = Alignment.Center) {
                Text(if (none) "–" else name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = if (none) cs.onSurfaceVariant else cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count, fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(width = 40.dp, height = 30.dp).clip(RoundedCornerShape(9.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Review only $name", tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
        }
    }
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

/** One item on the Review page: THE card, shut; tap to open its ✓ Done · 🕘 New time · 📞 · WA bar. */
@Composable
private fun ReviewRow(
    item: ReviewItem,
    now: Long,
    onCall: () -> Unit,
    onDone: () -> Unit,
    onNewTime: () -> Unit,
    onDetails: () -> Unit,
    startOpen: Boolean = false,
    onNotDoneSent: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(startOpen) }
    var timeline by remember { mutableStateOf(false) }
    if (timeline) ReviewItemTimeline(item) { timeline = false }
    val late = item.at in 1..now
    val sched = item.kind == ReviewItem.Kind.SCHED_FU || item.kind == ReviewItem.Kind.SCHED_MY
    val needsPerson = item.kind == ReviewItem.Kind.SCHED_FU && item.number.isBlank()
    // A team task shows the same chips, banner, ticks and Timeline as on its own page.
    val teamTasks by TeamRepo.tasks.collectAsState()
    val teamV by TeamSync.version.collectAsState()
    val tt = remember(item.key, teamTasks, teamV) { if (TeamRepo.activeNow) reviewTeamTask(ctx, item, teamTasks) else null }
    val giver = tt != null && tt.fromUid == TeamRepo.uid
    val banner = remember(tt, teamV) {
        tt?.let { t ->
            TeamUi.banner(ctx, t, TeamRepo.uid)
                // Needs you › Team: the grey early warning, with Call ›.
                ?: TeamUi.earlyWarning(ctx, t)?.takeIf { item.section == ReviewSection.TEAM }
                    ?.let { CardBanner(it, ChipTone.MUTED, { ReviewActions.call(ctx, item) }, "Call") }
        }
    }
    // His Done waits for your check: ✓ Received, thanks · ↩ Not done yet, the same on every card.
    val check = tt?.takeIf { giver && it.doneCheck }
    var notYet by remember(check?.id) { mutableStateOf(false) }
    if (notYet && check != null) TeamNotDoneSheet(check, onSent = onNotDoneSent) { notYet = false }
    TaskCard(
        title = item.title,
        done = false,
        onTick = onDone,
        tickEnabled = !sched,
        time = if (item.at > 0L) Fmt.dueLabel(item.at) else null,
        late = late,
        person = item.person.takeIf { it.isNotBlank() }?.let { Names.first(it) ?: it },
        chips = run {
            val team = tt?.let { if (giver) TeamUi.giverChips(it) else TeamUi.receiverChips(it) } ?: emptyList()
            // One chip per meaning: skip ours when the team chips already say it.
            item.chips.filterNot { c -> c.endsWith("late") || team.any { it.text == c } }.map { RowChip(it, ChipTone.MUTED) } + team
        },
        onCall = if (ReviewActions.canCall(item)) onCall else null,
        onWhatsApp = if (item.number.isNotBlank()) ({ WhatsApp.forItem(ctx, item) }) else null,
        expanded = open,
        onClick = { open = !open },
        pos = RowPos.SINGLE,
        corner = tt?.takeIf { giver }?.let { t -> { TeamTicks(t) } },
        banner = banner,
        urgent = tt?.let { it.urgent && it.open } == true,
    ) {
        CardPanel(
            details = listOfNotNull(item.code.takeIf { it.isNotEmpty() }?.let { "# Task no. $it" }),
            links = buildList {
                if (tt != null) add(RowLink(TeamUi.timelineLabel(tt), onClick = { TeamUi.openTimeline(tt.id) }))
                else add(RowLink("Timeline", onClick = { timeline = true }))
                if (item.kind == ReviewItem.Kind.SCHED_FU) add(RowLink(if (needsPerson) "👤 Set person" else "Edit", onClick = onDetails))
            },
            onNewTime = if (needsPerson) null else onNewTime,
            buttonLabel = if (sched) "Set time" else "New time",
            onNotDone = check?.let { { notYet = true } },
        )
    }
}

/** The team task behind a Review row: one you gave (a follow-up) or one given to you (a My task). */
private fun reviewTeamTask(c: android.content.Context, item: ReviewItem, tasks: List<TeamTask>): TeamTask? {
    val link = when (item.kind) {
        ReviewItem.Kind.CALL, ReviewItem.Kind.CALL_REVIEW -> ReminderStore.get(item.id)?.linkId
        ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.get(item.id)?.linkId
        ReviewItem.Kind.SCHED_FU -> ToScheduleStore.get(item.id)?.linkId
        ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> return TeamUi.taskOfMyTask(c, item.id, tasks)
    } ?: 0L
    return TeamUi.taskOfLink(c, link, tasks)
}

@Composable
private fun ReviewChip(text: String) = SmallChip(RowChip(text, if (text.endsWith("late")) ChipTone.RED else ChipTone.MUTED))

// ---------------------------------------------------------------- Session

/**
 * The review session, one task at a time, in 4 parts (Replied, Late follow-ups, Late My tasks, To schedule), each
 * with its own bar at the top. Up next on top, the task card at the bottom: its text grows upward, while the quick
 * times, the one bar (✓ Done · 🕘 New time · 📞 Call · WA) and Skip stay in the same spot for every task.
 * Back pauses it. Every Done and every time set here offers Undo just above the card.
 */
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
    val upNext = remember(s, calls, texts, sched, mine, tick) { if (item != null && !s.byPerson) ReviewSession.upNext(ctx, 3) else emptyList() }
    // 👤 By person: everything still open for this person, on one screen.
    val left = remember(s, calls, texts, sched, mine, tick) { if (item != null && s.byPerson) ReviewSession.groupLeft(ctx) else emptyList() }
    var newTimeFor by remember { mutableStateOf<ReviewItem?>(null) }
    var editFor by remember { mutableStateOf<ReviewItem?>(null) }
    var historyFor by remember { mutableStateOf<ReviewItem?>(null) }
    var notDoneFor by remember { mutableStateOf<Pair<ReviewItem, TeamTask>?>(null) }
    // "Why not done?" typed on the card, saved in its history with the new time.
    var reason by remember(item?.key) { mutableStateOf("") }
    // Off to WhatsApp, or a call that isn't a follow-up call: leaving the app then doesn't pause the session.
    val leaving = remember { booleanArrayOf(false) }

    /**
     * Done, a new time or Skip on a replied one, with Undo for a few seconds (puts the task and the session back).
     * [act] returns its own extra Undo, if any (the Held count).
     */
    fun handled(item: ReviewItem, message: String, act: () -> (() -> Unit)?) {
        val before = ReviewSession.state.value
        val undo = ReviewActions.snapshot(item)
        val more = act()
        scope.offerUndo(snack, message) { undo(); more?.invoke(); ReviewSession.restore(ctx, before) }
    }

    fun setTime(item: ReviewItem, ms: Long) {
        RecentPicks.record(ctx, ms)
        val why = reason
        handled(item, (if (item.at > 0L && !item.isReplied) "Moved to " else "Set · ") + "${Fmt.dueLabel(ms)} · ${item.title}") {
            ReviewActions.newTime(ctx, item, ms, why)
            // A late or replied task pushed on once more counts towards its "Held N×" tag.
            val held = if (item.isCall || item.isReplied || item.kind == ReviewItem.Kind.MY_TASK) Held.push(ctx, item) else null
            ReviewSession.markNewTime(ctx)
            held
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
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) leaving[0] = false
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !leaving[0]) {
                val st = ReviewSession.state.value
                // From any card (My tasks and To schedule too), so the session never stays "running" by mistake.
                if (st.status == ReviewSession.Status.RUNNING && st.awaiting.isEmpty()) ReviewSession.pause(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    /** 📞 on a task that isn't a follow-up call (My task, To schedule, Up next): just dial, the session waits. */
    fun dialOnly(n: ReviewItem) {
        leaving[0] = true
        if (ReviewActions.canCall(n)) ReviewActions.call(ctx, n) else dial(ctx, n.number)
    }

    fun whatsApp(n: ReviewItem) {
        leaving[0] = true
        if (!WhatsApp.forItem(ctx, n)) leaving[0] = false
    }

    val callsMode = s.mode == ReviewSession.MODE_CALLS
    val running = s.status == ReviewSession.Status.RUNNING && item != null
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 8.dp)
                .padding(horizontal = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(
                    if (callsMode) "Call session" else "Review session",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                if (running) {
                    TextButton(onClick = { ReviewSession.finish(ctx) }) { Text("■ Stop", color = LateRed, fontWeight = FontWeight.Bold) }
                }
            }
            when (s.status) {
                ReviewSession.Status.ENDED -> SessionSummary(
                    s,
                    offerUndo = { msg, undo -> scope.offerUndo(snack, msg, undo) },
                    snackHost = { SnackbarHost(snack) },
                    onClose = { ReviewSession.clear(ctx); onBack() },
                    modifier = Modifier.weight(1f),
                )
                ReviewSession.Status.PAUSED -> SessionBanner(s, onResume = { ReviewSession.resume(ctx) }, onEnd = { ReviewSession.clear(ctx); onBack() })
                ReviewSession.Status.IDLE -> {
                    Text("No review session running.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onBack) { Text("Back to Review") }
                }
                ReviewSession.Status.RUNNING -> if (item != null && s.byPerson) {
                    val g = ReviewSession.currentPart(s)
                    val number = ReviewSession.groupNumber(s, g)
                    val next = ReviewSession.groupName(s, g + 1)
                    PartBars(s)
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        item(key = "person") {
                            PersonHead(
                                name = ReviewSession.groupName(s, g),
                                number = number,
                                items = left,
                                onCall = { leaving[0] = true; dial(ctx, number) },
                                onWhatsApp = {
                                    leaving[0] = true
                                    val list = left.mapIndexed { i, n -> "${i + 1}. ${n.title}" }.joinToString("\n")
                                    if (!WhatsApp.open(ctx, number, "Please update me on these:\n$list")) leaving[0] = false
                                },
                            )
                        }
                        items(left, key = { it.key }) { n ->
                            Box(Modifier.animateItem()) {
                                // THE card, open, with its own buttons: tap them while you talk.
                                ReviewRow(
                                    item = n,
                                    now = System.currentTimeMillis(),
                                    onCall = { dialOnly(n) },
                                    onDone = {
                                        ReviewSession.focus(ctx, n.key)
                                        handled(n, (if (n.isReplied) "Accepted · " else "Done · ") + n.title) {
                                            ReviewActions.done(ctx, n)
                                            ReviewSession.markDone(ctx)
                                            null
                                        }
                                        tick++
                                    },
                                    onNewTime = { newTimeFor = n },
                                    onDetails = { editFor = n },
                                    startOpen = true,
                                    onNotDoneSent = {
                                        ReviewSession.focus(ctx, n.key)
                                        ReviewSession.markNewTime(ctx)
                                        scope.launch { snack.showSnackbar("↩ Not done yet · ${n.title}") }
                                        tick++
                                    },
                                )
                            }
                        }
                    }
                    SnackbarHost(snack)
                    Button(
                        onClick = { ReviewSession.skipGroup(ctx); tick++ },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            if (next.isNotEmpty()) "⏭ Next: ${Names.first(next) ?: next} ›" else "⏭ Finish",
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (item != null) {
                    PartBars(s)
                    // Up next on top; the card below keeps its place whatever is here.
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        if (upNext.isNotEmpty()) {
                            ChipHeading("UP NEXT", modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                            UpNextList(upNext)
                        }
                    }
                    // Undo shows here, above the card, never over it.
                    SnackbarHost(snack)
                    SessionCard(
                        item = item,
                        awaiting = s.awaiting == item.key,
                        reason = reason,
                        onReason = { reason = it },
                        onCall = {
                            if (ReviewActions.canCall(item)) {
                                callUndo[0] = item to (ReviewSession.state.value to ReviewActions.snapshot(item))
                                ReviewSession.calling(ctx, item.key)
                                ReviewActions.call(ctx, item)
                            } else dialOnly(item)
                        },
                        onWhatsApp = { whatsApp(item) },
                        onBackFromCall = { settle(); tick++ },
                        onDone = {
                            handled(item, (if (item.isReplied) "Accepted · " else "Done · ") + item.title) {
                                ReviewActions.done(ctx, item)
                                ReviewSession.markDone(ctx)
                                null
                            }
                        },
                        onTime = { ms -> setTime(item, ms) },
                        onPickTime = { newTimeFor = item },
                        onPerson = { n, num -> ReviewActions.setPerson(item, n, num); tick++ },
                        onEdit = { editFor = item },
                        onHistory = { historyFor = item },
                        onNotDone = { t -> notDoneFor = item to t },
                        onSkip = {
                            if (item.isReplied) {
                                handled(item, "Skipped · back ${Fmt.dueLabel(ReviewData.nextReviewAt(ctx))}") {
                                    ReviewSession.reviewLater(ctx, item)
                                    null
                                }
                            } else ReviewSession.skip(ctx)
                        },
                    )
                }
            }
        }
    }

    newTimeFor?.let { it0 ->
        // My tasks: your busy times crossed out and the best free slots for its length.
        val busy = if (it0.isMine) myBusy(it0) else null
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPickBusy provides busy,
            LocalSameBase provides it0.at.takeIf { a -> a > 0L && !it0.isReplied },
        ) {
            TimePickerDialog(
                title = (if (it0.at > 0L) "New time · " else "Set time · ") + it0.title,
                selected = null,
                onPick = { ms ->
                    newTimeFor = null
                    ReviewSession.focus(ctx, it0.key)
                    setTime(it0, ms)
                    tick++
                },
                onDismiss = { newTimeFor = null },
            )
        }
    }
    // ✏️ Edit: the task's own editor; the session carries on from the same card after it closes.
    editFor?.let { e -> TaskEditorDialog(task = OpenTask.of(e), onDismiss = { editFor = null; tick++ }) }
    historyFor?.let { h -> ReviewItemTimeline(h) { historyFor = null } }
    // ↩ Not done yet: back to him with the reason, and the session moves on.
    notDoneFor?.let { (it0, t) ->
        TeamNotDoneSheet(t, onSent = {
            ReviewSession.markNewTime(ctx)
            scope.launch { snack.showSnackbar("↩ Not done yet · ${it0.title}") }
            tick++
        }) { notDoneFor = null }
    }
}

/** The full-window Timeline of a Needs you / review item, whatever kind it is. */
@Composable
internal fun ReviewItemTimeline(h: ReviewItem, close: () -> Unit) {
    when (h.kind) {
        ReviewItem.Kind.CALL, ReviewItem.Kind.CALL_REVIEW -> ReminderStore.get(h.id)?.let { CallHistoryDialog(it, close) }
        ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.get(h.id)?.let { HistoryDialog(it, close) }
        ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> MyTaskStore.get(h.id)?.let { MyTaskHistoryDialog(it, close) }
        ReviewItem.Kind.SCHED_FU -> ToScheduleStore.get(h.id)?.let { ScheduledTimeline(it, close) }
    }
}

/** One small bar per part: its name, how far you are (x/y), the current one bold, finished ones ✓. */
@Composable
private fun PartBars(s: ReviewSession.State) {
    val cs = MaterialTheme.colorScheme
    val counts = ReviewSession.partCounts(s)
    val cur = ReviewSession.currentPart(s)
    // 👤 By person: one bar per person instead of the 4 parts; more than 4 scroll sideways.
    val bars = if (s.byPerson) s.groups.indices.map { i ->
        val n = ReviewSession.groupName(s, i)
        if (ReviewSession.groupNumber(s, i).isBlank()) "–" to n else "👤" to (Names.first(n) ?: n)
    } else ReviewSession.PARTS
    val many = bars.size > 4
    Row(
        Modifier.fillMaxWidth().then(if (many) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        bars.forEachIndexed { i, (emoji, name) ->
            val (behind, total) = counts[i]
            if (total == 0) return@forEachIndexed
            val finished = behind >= total
            val on = i == cur && !finished
            val progress by androidx.compose.animation.core.animateFloatAsState(
                behind.toFloat() / total, androidx.compose.animation.core.tween(300), label = "partBar"
            )
            val ink = when {
                on -> cs.primary
                finished -> DoneInk
                else -> cs.onSurfaceVariant
            }
            Column(if (many) Modifier.width(84.dp) else Modifier.weight(1f)) {
                Text(
                    if (finished) "✓ $name" else "$emoji $name",
                    fontSize = 10.5.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, color = ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = if (finished) DoneGreen else cs.primary.copy(alpha = if (on) 1f else 0.45f),
                    trackColor = cs.surfaceVariant,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).height(if (on) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp))
                )
                Text("$behind/$total", fontSize = 10.sp, color = ink, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private val DoneInk: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF7FE0C2) else Color(0xFF0B6B55)

/** 👤 By person: who this screen is about, with one call (or one WhatsApp list) for all of their tasks. */
@Composable
private fun PersonHead(name: String, number: String, items: List<ReviewItem>, onCall: () -> Unit, onWhatsApp: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val replied = items.count { it.isReplied }
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase().ifBlank { "–" }, color = cs.onPrimaryContainer, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${items.size} task${if (items.size == 1) "" else "s"}" + (if (replied > 0) " · $replied already say Done" else ""),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
            }
            if (number.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCall, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp)) {
                        Text("📞 Call about all ${items.size}", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Button(
                        onClick = onWhatsApp,
                        modifier = Modifier.weight(0.7f).height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = WhatsAppInk),
                    ) { Text("WA list", fontWeight = FontWeight.Bold, maxLines = 1) }
                }
            }
        }
    }
}

/** What comes after this one: only the task text, one block with thin lines between (no buttons, chips or ticks). */
@Composable
private fun UpNextList(items: List<ReviewItem>) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            items.forEachIndexed { i, n ->
                if (i > 0) HorizontalDivider(color = cs.surfaceVariant)
                Text(
                    n.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

private fun timeIconsOf(n: ReviewItem) = when (n.kind) {
    ReviewItem.Kind.CALL, ReviewItem.Kind.CALL_REVIEW -> TimeIcons.CALL
    ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> TimeIcons.TEXT
    else -> TimeIcons.NONE
}

/** The small chips after the time and person: #number, Held N×. */
private fun taskChips(ctx: android.content.Context, n: ReviewItem): List<RowChip> = listOfNotNull(
    n.code.takeIf { it.isNotBlank() }?.let { RowChip("#$it", ChipTone.BLUE) },
    // One Snoozed chip: an overdue call already counts its holds in its own.
    Held.label(Held.count(ctx, n))?.takeIf { n.chips.none { c -> c.startsWith("💤") } }?.let { RowChip(it, ChipTone.ORANGE) },
)

/**
 * The card at the bottom. Its top (the task, chips, "Why not done?", Edit · History) changes with each task and
 * grows upward; under it the quick times, the bar and Skip never move.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    item: ReviewItem,
    awaiting: Boolean,
    reason: String,
    onReason: (String) -> Unit,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onBackFromCall: () -> Unit,
    onDone: () -> Unit,
    onTime: (Long) -> Unit,
    onPickTime: () -> Unit,
    onPerson: (String, String) -> Unit,
    onEdit: () -> Unit,
    onHistory: (() -> Unit)?,
    onSkip: () -> Unit,
    onNotDone: (TeamTask) -> Unit = {},
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val noPerson = item.number.isBlank()
    // His Done waits for your check: ✓ Received, thanks · ↩ Not done yet in the same two places.
    val teamTasks by TeamRepo.tasks.collectAsState()
    val check = remember(item.key, teamTasks) {
        if (TeamRepo.activeNow) reviewTeamTask(ctx, item, teamTasks)?.takeIf { it.fromUid == TeamRepo.uid && it.doneCheck } else null
    }
    // A follow-up can't get a time before it has a person.
    val needsPerson = item.kind == ReviewItem.Kind.SCHED_FU && noPerson
    var addingPerson by remember(item.key) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(22.dp), color = cs.surface, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            androidx.compose.animation.AnimatedContent(
                targetState = item,
                contentKey = { it.key },
                transitionSpec = {
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 3 } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { -it / 3 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
                },
                modifier = Modifier.weight(1f, fill = false),
                label = "sessionTask",
            ) { it0 ->
                CardTop(
                    it0, awaiting && it0.key == item.key, reason, onReason,
                    addingPerson = addingPerson && it0.key == item.key,
                    onPerson = { n, num -> addingPerson = false; onPerson(n, num) },
                    onEdit = onEdit, onHistory = onHistory,
                )
            }
            Spacer(Modifier.height(10.dp))
            QuickRow(item, enabled = !needsPerson, onTime = onTime, onPickTime = onPickTime)
            Spacer(Modifier.height(8.dp))
            TaskActionBar(
                onDone = onDone,
                onNewTime = if (needsPerson) null else onPickTime,
                onCall = if (noPerson) null else onCall,
                onWhatsApp = if (noPerson) null else onWhatsApp,
                newTimeLabel = if (item.at > 0L) "New time" else "Set time",
                onAddPerson = if (needsPerson) ({ addingPerson = !addingPerson }) else null,
                onNotDone = check?.let { t -> { onNotDone(t) } },
            )
            Spacer(Modifier.height(6.dp))
            if (awaiting) {
                Button(
                    onClick = onBackFromCall,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Next ›", fontWeight = FontWeight.Bold) }
            } else {
                val back = remember(item.key) { if (item.isReplied) ReviewData.nextReviewAt(ctx) else 0L }
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (item.isReplied) "⏭ Skip · back " + (if (dayWord(back) == "Today") "at " else "${dayWord(back)} ") + Fmt.timeOnly(back)
                        else "⏭ Skip",
                        fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
            }
        }
    }
}

/** The card's top: what kind it is, the task (4 lines, "more"), its chips, "Why not done?" and Edit · History. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardTop(
    item: ReviewItem,
    awaiting: Boolean,
    reason: String,
    onReason: (String) -> Unit,
    addingPerson: Boolean,
    onPerson: (String, String) -> Unit,
    onEdit: () -> Unit,
    onHistory: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val now = remember(item.key) { System.currentTimeMillis() }
    var more by remember(item.key) { mutableStateOf(false) }
    var long by remember(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when (item.kind) {
                ReviewItem.Kind.CALL, ReviewItem.Kind.TEXT -> "LATE FOLLOW-UP"
                ReviewItem.Kind.SCHED_FU -> "TO SCHEDULE · FOLLOW-UP"
                ReviewItem.Kind.SCHED_MY -> "TO SCHEDULE · MY TASK"
                ReviewItem.Kind.MY_TASK -> "LATE MY TASK"
                else -> "REPLIED · CHECK IT"
            },
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        Text(
            item.title,
            fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = if (more) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!more) long = it.hasVisualOverflow },
            modifier = Modifier.animateContentSize(androidx.compose.animation.core.tween(200)),
        )
        if (long || more) {
            Text(
                if (more) "less" else "more",
                color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.clickable { more = !more }
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeBox(if (item.at > 0L) whenLabel(item.at, now) else null, item.at in 1..now && !item.isReplied, timeIconsOf(item))
            if (item.person.isNotBlank()) {
                TaskChip(Names.first(item.person) ?: item.person, CHIP_PLAIN,
                    icon = { Icon(androidx.compose.material.icons.Icons.Rounded.Person, null, Modifier.size(14.dp).padding(end = 2.dp)) })
            }
            taskChips(ctx, item).forEach { SmallChip(it) }
            item.chips.filter { !it.endsWith("late") }.forEach { ReviewChip(it) }
        }
        if (awaiting) {
            Text("Back from the call? Your after-call popup saves how it went. Tap Next.", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        if (item.isCall || item.isReplied || item.kind == ReviewItem.Kind.MY_TASK) {
            OutlinedTextField(
                value = reason,
                onValueChange = onReason,
                placeholder = { Text("Why not done? (optional)", fontSize = 14.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = addingPerson) {
            PersonPicker(prompt = "Pick the person first") { n, num -> onPerson(n, num) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (onHistory != null) {
                Text("Timeline", color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onHistory).padding(vertical = 2.dp))
            }
            Text("Edit", color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 2.dp))
        }
    }
}

/**
 * The quick times. My tasks: HOW LONG first, then BEST FREE SLOTS for that length with 📅 Pick, then RECENT
 * ("Tmrw same" + your 3 recent, red when busy); slots and Recent wait until a length is picked. Follow-ups:
 * RECENT and Pick (calls don't book time). Greyed until a follow-up has its person.
 */
@Composable
private fun QuickRow(item: ReviewItem, enabled: Boolean, onTime: (Long) -> Unit, onPickTime: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember(item.key) { System.currentTimeMillis() }
    // A replied task's time is when he replied, not a due time: tomorrow at work start instead.
    val base = item.at.takeIf { it > 0L && !item.isReplied }
    val recent = remember(item.key) { RecentPicks.load(ctx) }
    Box {
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.35f },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (item.isMine) {
                val mine by MyTaskStore.items.collectAsState()
                val len = mine.firstOrNull { it.id == item.id }?.durationMin ?: item.lengthMin
                HowLongRow(len, onLength = { MyTaskStore.setLength(item.id, it) })
                FreeSlotsRow(len, item.id, null, onPick = onTime)
                ChipHeading("RECENT")
                RecentChips(recent, now, null, base = base, busy = PickBusy(len, item.id), enabled = len > 0, onMore = onPickTime, onPick = onTime)
            } else {
                ChipHeading("RECENT")
                RecentChips(recent, now, null, base = base, onMore = onPickTime, onPick = onTime)
            }
        }
        if (!enabled) {
            Box(Modifier.matchParentSize().clickable {
                Toast.makeText(ctx, "Add a person first", Toast.LENGTH_SHORT).show()
            })
        }
    }
}

/** A My task's picker: its current length (HOW LONG first when it has none), saved as you pick it. */
private fun myBusy(item: ReviewItem): PickBusy =
    PickBusy(MyTaskStore.get(item.id)?.durationMin ?: item.lengthMin, item.id) { MyTaskStore.setLength(item.id, it) }

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

/**
 * The end: Done / New time / Left, then what is still open in one list with the All | Timed | No time box. It
 * walks you through: Timed first, all ticked (→ Tmrw, same time or ⏸ Leave for now), then No time, all ticked
 * (🕘 Set time or ⏸ Leave for now), then "All sorted ✓". Leave for now moves nothing: back at the next review.
 * Replied ones you skipped are already back at the next review; Accept or Not done here if you checked them.
 */
@Composable
private fun SessionSummary(
    s: ReviewSession.State,
    offerUndo: (String, () -> Unit) -> Unit,
    snackHost: @Composable () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
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
    var leftAlone by remember { mutableStateOf(setOf<String>()) }
    val open = left.filter { !it.isReplied && it.key !in leftAlone }
    val timed = open.filter { it.at > 0L }
    val untimed = open.filter { it.at <= 0L }
    var tab by remember { mutableIntStateOf(if (timed.isNotEmpty()) 1 else if (untimed.isNotEmpty()) 2 else 0) }
    val shown = when (tab) {
        1 -> timed
        2 -> untimed
        else -> open
    }
    // Every task ticked whenever a tab opens.
    var unticked by remember(tab) { mutableStateOf(setOf<String>()) }
    val ticked = shown.filter { it.key !in unticked }
    var pickFor by remember { mutableStateOf<List<ReviewItem>?>(null) }
    var timeFor by remember { mutableStateOf<ReviewItem?>(null) }
    val handledAny = s.handled > 0 || left.isNotEmpty()

    /** On to the other tab while it has tasks, so you are walked through without looking for it. */
    fun after(acted: Set<String>) {
        val t = timed.any { it.key !in acted }
        val u = untimed.any { it.key !in acted }
        tab = when (tab) {
            1 -> if (u) 2 else if (t) 1 else 0
            2 -> if (t) 1 else if (u) 2 else 0
            else -> if (u) 2 else if (t) 1 else 0
        }
    }

    /** Moves [items] (each to [whenFor] its own time), with one Undo for all of them. */
    fun move(items: List<ReviewItem>, label: String, held: Boolean, whenFor: (ReviewItem) -> Long) {
        val undos = items.map { ReviewActions.snapshot(it) } + if (held) items.map { Held.push(ctx, it) } else emptyList()
        items.forEach { i -> ReviewActions.newTime(ctx, i, whenFor(i)) }
        offerUndo("Moved ${items.size} $label") { undos.forEach { it() } }
        after(items.map { it.key }.toSet())
    }

    fun leave(items: List<ReviewItem>) {
        val keys = items.map { it.key }.toSet()
        val undos = items.map { Held.push(ctx, it) }
        leftAlone = leftAlone + keys
        offerUndo("Left for now · ${items.size} back ${Fmt.dueLabel(ReviewData.nextReviewAt(ctx))}") {
            undos.forEach { it() }
            leftAlone = leftAlone - keys
        }
        after(keys)
    }

    fun one(item: ReviewItem, message: String, act: () -> Unit) {
        val undo = ReviewActions.snapshot(item)
        act()
        offerUndo(message) { undo() }
    }

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("🎉 Session finished", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                "${s.handled} tasks · $minutes min",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountBox("${s.done}", "Done", DoneGreen, Modifier.weight(1f))
                CountBox("${s.newTime}", "New time", SameOrange, Modifier.weight(1f))
                CountBox("${open.size + later.size}", "Left", LateRed, Modifier.weight(1f))
            }

            if (open.isNotEmpty()) {
                BoxTabs(listOf("All · ${open.size}", "Timed · ${timed.size}", "No time · ${untimed.size}"), tab, { tab = it })
                Text(
                    when (tab) {
                        1 -> "Had a time. Move them to tomorrow, same time, or leave them for now."
                        2 -> "No time yet. Give them one time together, or leave them for now."
                        else -> "Everything still open. Untick any you want to keep out."
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                if (shown.isEmpty()) {
                    Text("Nothing here", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(18.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        shown.forEachIndexed { i, it0 ->
                            if (i > 0) HorizontalDivider(color = cs.surfaceVariant)
                            val on = it0.key !in unticked
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                unticked = if (on) unticked + it0.key else unticked - it0.key
                            }.padding(end = 12.dp)) {
                                Checkbox(checked = on, onCheckedChange = { c -> unticked = if (c) unticked - it0.key else unticked + it0.key })
                                SummaryText(
                                    it0,
                                    listOf(
                                        it0.person.ifBlank { if (it0.isMine) "My task" else "" },
                                        if (it0.at > 0L) Fmt.timeOnly(it0.at) else "no time",
                                        Held.label(Held.count(ctx, it0)),
                                    ),
                                    Modifier.weight(1f)
                                )
                                if (it0.at > 0L) {
                                    val to = ReviewData.sameTimeNextDay(ctx, it0.at) ?: ReviewData.nextWorkStart(ctx)
                                    Text("→ ${dayWord(to)} ${Fmt.timeOnly(to)}", color = DoneInk, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                } else {
                                    TextButton(onClick = { timeFor = it0 }) { Text("🕘 Set", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            } else if (handledAny) {
                Surface(shape = RoundedCornerShape(18.dp), color = RepliedBg, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "All sorted ✓",
                        color = RepliedFg, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            if (leftAlone.isNotEmpty()) {
                Text(
                    "⏸ ${leftAlone.size} left for now · back at your next review (${Fmt.dueLabel(ReviewData.nextReviewAt(ctx))})",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }

            if (later.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(18.dp), color = RepliedBg, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Replied, skipped · ${later.size}", color = RepliedFg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Back at your next review time. Checked it already? Tap below", style = MaterialTheme.typography.bodySmall,
                            color = RepliedFg.copy(alpha = 0.8f))
                        later.forEach { it0 ->
                            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                SummaryText(it0, listOf(it0.person, it0.at.takeIf { at -> at > System.currentTimeMillis() }?.let { at -> "back ${Fmt.dueLabel(at)}" }))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    OutlinedButton(
                                        onClick = { one(it0, "Accepted · ${it0.title}") { ReviewActions.done(ctx, it0) } },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("✓ Done", color = RepliedFg, fontWeight = FontWeight.Bold) }
                                    OutlinedButton(onClick = { timeFor = it0 }, modifier = Modifier.weight(1f)) {
                                        Text("🕘 New time", maxLines = 1, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        snackHost()
        if (open.isNotEmpty() && shown.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (tab == 1) {
                    Button(
                        onClick = {
                            move(ticked, "to tomorrow, same time", held = true) { ReviewData.sameTimeNextDay(ctx, it.at) ?: ReviewData.nextWorkStart(ctx) }
                        },
                        enabled = ticked.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SameOrange, contentColor = Color(0xFF3A2000))
                    ) { Text("→ Tmrw, same time · ${ticked.size}", fontWeight = FontWeight.Bold, maxLines = 1) }
                } else {
                    Button(
                        onClick = { pickFor = ticked },
                        enabled = ticked.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("🕘 Set time · ${ticked.size}", fontWeight = FontWeight.Bold, maxLines = 1) }
                }
                OutlinedButton(
                    onClick = { leave(ticked) },
                    enabled = ticked.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("⏸ Leave for now", fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Back to Review", fontWeight = FontWeight.Bold) }
    }
    pickFor?.let { items ->
        val single = items.singleOrNull()
        val busy = if (single?.isMine == true) myBusy(single) else null
        androidx.compose.runtime.CompositionLocalProvider(LocalPickBusy provides busy) {
            TimePickerDialog(title = if (single != null) "Set time · ${single.title}" else "Set time for ${items.size}", selected = null, onPick = { ms ->
                pickFor = null
                RecentPicks.record(ctx, ms)
                move(items, "to ${Fmt.dueLabel(ms)}", held = false) { ms }
            }, onDismiss = { pickFor = null })
        }
    }
    timeFor?.let { it0 ->
        val busy = if (it0.isMine) myBusy(it0) else null
        androidx.compose.runtime.CompositionLocalProvider(LocalPickBusy provides busy) {
            TimePickerDialog(
                title = (if (it0.isReplied) "Not done · " else "Set time · ") + it0.title,
                selected = null,
                onPick = { ms ->
                    timeFor = null
                    RecentPicks.record(ctx, ms)
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
    Column(modifier.padding(vertical = 6.dp)) {
        Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line = details.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun CountBox(n: String, label: String, color: Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // The bright tile colours are too pale as text on white, so light mode uses a deeper shade.
            Text(n, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (LocalDarkTheme.current) color else androidx.compose.ui.graphics.lerp(color, Color.Black, 0.45f))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Home card

/** Home's Review card under search: count, breakdown, ▶ Start review and Open; Resume / End while a session waits. */
@Composable
fun ReviewHomeCard(
    resumeKey: Int,
    onStart: () -> Unit,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onOpenOverdue: () -> Unit = onOpen,
    onOpenToSchedule: () -> Unit = onOpen,
) {
    val ctx = LocalContext.current
    val s by ReviewSession.state.collectAsState()
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val sched by ToScheduleStore.items.collectAsState()
    val mine by MyTaskStore.items.collectAsState()
    LaunchedEffect(resumeKey) { ReviewSession.init(ctx) }
    val teamTasks by TeamRepo.tasks.collectAsState()
    val counts = remember(calls, texts, sched, mine, teamTasks, resumeKey) { ReviewData.shownCounts(ctx) }
    val team = remember(calls, teamTasks, resumeKey) { ReviewData.build(ctx)[ReviewSection.TEAM].orEmpty().size }
    fun openTab(i: Int) { ReviewUi.tab.value = i; onOpen() }
    fun count(g: ReviewPrefs.Group) = counts.filter { ReviewPrefs.groupOf(it.first) == g }.sumOf { it.second }
    val replied = count(ReviewPrefs.Group.REPLIED)
    val overdue = count(ReviewPrefs.Group.OVERDUE)
    val schedule = count(ReviewPrefs.Group.SCHEDULE)
    val all = replied + overdue + schedule
    // "Start review" goes through the parts switched on in Settings › Review.
    val total = remember(counts) { counts.filter { ReviewPrefs.session(ctx, it.first) }.sumOf { it.second } }
    val cs = MaterialTheme.colorScheme

    Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).animateContentSize(androidx.compose.animation.core.tween(220)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpen)) {
                Text("✋ Needs you", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                if (all > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$all",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cs.error).padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("See all ›", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { openTab(0) }.padding(horizontal = 6.dp, vertical = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val green = Tones.done.pair
                val red = Tones.late.pair
                val amber = Tones.amber.pair
                NeedTile(replied, "Replied,\ncheck it", green, Modifier.weight(1f)) { openTab(1) }
                NeedTile(overdue, "Overdue", red, Modifier.weight(1f)) { openTab(2) }
                NeedTile(schedule, "To\nschedule", amber, Modifier.weight(1f)) { openTab(3) }
            }
            // Your staff waiting on you: one line, opens the Team tab. His own (need your deadline, sent back) stay lines.
            if (team > 0 && TeamRepo.activeNow) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tones.date.bg).clickable { openTab(4) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("👥 Team · $team waiting on you", color = Tones.date.fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("›", color = Tones.date.fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
            Column { TeamNeedsLines(mineOnly = true) }
            val paused = s.active && s.status == ReviewSession.Status.PAUSED
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        when {
                            paused -> { ReviewSession.resume(ctx); onResume() }
                            s.active -> onResume()
                            else -> onStart()
                        }
                    },
                    enabled = s.active || total > 0,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (s.active) ButtonDefaults.buttonColors(containerColor = PausedFg, contentColor = PausedInk) else ButtonDefaults.buttonColors(),
                ) {
                    Text(
                        when {
                            paused -> "▶ Continue · ${s.total - s.handled} left"
                            s.active -> "▶ Review running · ${s.handled}/${s.total}"
                            total > 0 -> "▶ Start review · $total"
                            else -> "Nothing to review"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                androidx.compose.animation.AnimatedVisibility(visible = s.active) {
                    TextButton(onClick = { ReviewSession.clear(ctx) }) { Text("End", color = cs.onSurfaceVariant) }
                }
            }
        }
    }
}

/** One coloured tile on Needs you: the count and what it is; tap opens that list. */
@Composable
private fun NeedTile(n: Int, label: String, colors: Pair<Color, Color>, modifier: Modifier, onClick: () -> Unit) {
    val (bg, fg) = colors
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = bg, contentColor = fg, modifier = modifier.heightIn(min = 48.dp)) {
        // A narrow tile (small phone, Large display size) puts the number above the words, so no word is broken.
        androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
            val count: @Composable () -> Unit = {
                androidx.compose.animation.AnimatedContent(n, label = "needCount") { v ->
                    Text("$v", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 24.sp)
                }
            }
            if (maxWidth >= 104.dp) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                count()
                Spacer(Modifier.width(6.dp))
                FitText(label, 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp, maxLines = 2)
            } else Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                count()
                FitText(label.replace("\n", " "), 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
