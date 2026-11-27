package com.callbackreminder.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/*
 * The two lists behind Home's Needs you tiles: To schedule (no time yet) and Overdue. Both have the
 * Follow-ups | My tasks box at the top and the same cards as everywhere else.
 */

object ToScheduleUi {
    /** Search tapped a follow-up waiting here: scroll to it and outline it for a moment. */
    val show = MutableStateFlow<Long?>(null)
}

/** Back arrow, the page name with its count, then the Follow-ups | My tasks box. */
@Composable
private fun WaitingHeader(title: String, total: Int, about: String, fu: Int, mine: Int, tab: Int, onBack: () -> Unit, showTabs: Boolean = true, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(
                if (total > 0) "$title · $total" else title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            about,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        if (showTabs) BoxTabs(listOf("Follow-ups · $fu", "My tasks · $mine"), tab, onTab)
    }
}

/** My tasks as cards in one block, opened in place, with New time / Set time, Edit, Move and Delete. */
private fun LazyListScope.myTaskCards(
    list: List<MyTask>,
    now: Long,
    openId: Long?,
    ticking: Set<Long>,
    onToggle: (MyTask) -> Unit,
    onTick: (MyTask) -> Unit,
    onEdit: (MyTask) -> Unit,
    onNewTime: (MyTask) -> Unit,
    onMove: (MyTask) -> Unit,
    onDelete: (MyTask) -> Unit,
) {
    itemsIndexed(list, key = { _, t -> "my-${t.id}" }) { i, t ->
        Box(Modifier.animateItem()) {
            Box {
                MyTaskCard(
                    t = t,
                    pos = rowPos(i, list.size),
                    now = now,
                    listName = null,
                    finishing = t.id in ticking,
                    expanded = openId == t.id,
                    onToggle = { onToggle(t) },
                    onTick = { onTick(t) },
                    onStar = { MyTaskStore.setStar(t.id, !t.starred) },
                    onEdit = { onEdit(t) },
                    onNewTime = { onNewTime(t) },
                    onMoveToFollowUps = { onMove(t) },
                    onDelete = { onDelete(t) },
                )
            }
        }
    }
}

/** Shared tick / delete / New time for My task cards on these pages, each with Undo. */
internal class MyCardActions(
    val scope: kotlinx.coroutines.CoroutineScope,
    val snack: SnackbarHostState,
) {
    var openId by mutableStateOf<Long?>(null)
    var ticking by mutableStateOf(setOf<Long>())
    var timeFor by mutableStateOf<MyTask?>(null)
    var editFor by mutableStateOf<MyTask?>(null)
    var moveFor by mutableStateOf<MyTask?>(null)

    fun tick(t: MyTask) {
        if (t.id in ticking) return
        ticking = ticking + t.id
        scope.launch {
            kotlinx.coroutines.delay(380L)
            MyTaskStore.setDone(t.id, true)
            ticking = ticking - t.id
            scope.offerUndo(snack, "Done · ${t.title}") { MyTaskStore.restore(t) }
        }
    }

    fun delete(t: MyTask) {
        MyTaskStore.delete(t.id)
        scope.offerUndo(snack, "Deleted · ${t.title}") { MyTaskStore.restore(t) }
    }

    @Composable
    fun Sheets() {
        timeFor?.let { t ->
            SetTimeSheet(
                heading = if (t.timed) "New time" else "Set time",
                about = t.title,
                onPick = { ms ->
                    timeFor = null
                    openId = null
                    MyTaskStore.setDue(t.id, ms)
                    scope.offerUndo(snack, "Set for ${Fmt.dueLabel(ms)}") { MyTaskStore.restore(t) }
                },
                onDismiss = { timeFor = null },
            )
        }
        editFor?.let { t ->
            MyTaskDialog(existing = t, onDismiss = { editFor = null }, onSwitchKind = null, onDelete = { delete(it) }, onMoveToFollowUps = { moveFor = it })
        }
        moveFor?.let { t -> FollowUpDialog(onDismiss = { moveFor = null }, fromMyTask = t) }
    }
}

/**
 * The To schedule page: everything waiting with no time. Follow-ups (from Remove time or a quick save) and
 * My tasks with no time yet, each with Set time.
 */
@Composable
fun ToScheduleScreen(onBack: () -> Unit, only: Int? = null) {
    val ctx = LocalContext.current
    val items by ToScheduleStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val mine = myTasks.filter { !it.done && !it.timed }.sortedByDescending { it.createdAt }
    val fus = items.sortedByDescending { it.createdAt }
    // From the Follow-ups or My tasks tab it shows only that kind, without the box.
    var tab by rememberSaveable { mutableIntStateOf(only ?: if (fus.isEmpty() && mine.isNotEmpty()) 1 else 0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sched = remember(snack) { SchedActions(scope, snack) }
    val my = remember { MyCardActions(scope, snack) }
    val now = rememberNow(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var outlined by remember { mutableStateOf<Long?>(null) }
    val showReq by ToScheduleUi.show.collectAsState()
    // Search asked for one: scroll to it, outline it for 3 s, then clear the ask (clearing it first would cancel this).
    LaunchedEffect(showReq) {
        val id = showReq ?: return@LaunchedEffect
        tab = 0
        outlined = id
        listState.revealKey("fu-$id")
        kotlinx.coroutines.delay(3_000L)
        if (outlined == id) outlined = null
        ToScheduleUi.show.value = null
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ToScheduleUi.show.value = null } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) }) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
        ) {
            item(key = "header") {
                WaitingHeader(
                    "To schedule", when (only) { 0 -> fus.size; 1 -> mine.size; else -> fus.size + mine.size },
                    "No time set yet. Give each one a time, or open it to add what is missing.",
                    fus.size, mine.size, tab, onBack, showTabs = only == null
                ) { tab = it }
            }
            if (tab == 0) {
                if (fus.isEmpty()) item(key = "empty-fu") {
                    EmptyPicture(Icons.Rounded.DoneAll, "Nothing waiting", "A follow-up saved with No time shows here until it gets a time.")
                }
                itemsIndexed(fus, key = { _, t -> "fu-${t.id}" }) { i, t ->
                    Box(Modifier.animateItem()) { sched.Card(t, rowPos(i, fus.size), highlighted = outlined == t.id) }
                }
            } else {
                if (mine.isEmpty()) item(key = "empty-my") {
                    EmptyPicture(Icons.Rounded.DoneAll, "Nothing waiting", "A task saved without a time shows here until it gets one.")
                }
                myTaskCards(
                    mine, now, my.openId, my.ticking,
                    onToggle = { t -> my.openId = if (my.openId == t.id) null else t.id },
                    onTick = { my.tick(it) },
                    onEdit = { my.editFor = it },
                    onNewTime = { my.timeFor = it },
                    onMove = { my.moveFor = it },
                    onDelete = { my.delete(it) },
                )
            }
        }
    }

    sched.Sheets()
    my.Sheets()
}

/** The Overdue page: follow-ups whose call time has passed, and My tasks past their time. */
@Composable
fun OverdueScreen(resumeKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val calls by ReminderStore.items.collectAsState()
    val texts = SmsStore.shown(LocalContext.current, SmsStore.items.collectAsState().value)
    val myTasks by MyTaskStore.items.collectAsState()
    val stars by FuStars.keys.collectAsState()
    LaunchedEffect(Unit) { FuStars.load(ctx) }
    val now = rememberNow(resumeKey)
    val fus = entries(calls, texts).filter { it.open && !it.inReview && it.callPending && it.at <= now }.sortedBy { it.at }
    val mine = myTasks.filter { !it.done && it.timed && it.dueAt <= now }.sortedBy { it.dueAt }
    var tab by rememberSaveable { mutableIntStateOf(if (fus.isEmpty() && mine.isNotEmpty()) 1 else 0) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fu = rememberFuController(snack)
    val my = remember { MyCardActions(scope, snack) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
        ) {
            item(key = "header") {
                WaitingHeader(
                    "Overdue", fus.size + mine.size, "Past their time. Tick what is done, or give it a new time.",
                    fus.size, mine.size, tab, onBack
                ) { tab = it }
            }
            if (tab == 0) {
                if (fus.isEmpty()) item(key = "empty-fu") {
                    EmptyPicture(Icons.Rounded.DoneAll, "Nothing overdue", "Well done! Every follow-up is on time.")
                }
                itemsIndexed(fus, key = { _, e -> e.key }) { i, e ->
                    Box(Modifier.animateItem()) { fu.Card(e, rowPos(i, fus.size), now, e.call?.starred ?: (e.key in stars)) }
                }
            } else {
                if (mine.isEmpty()) item(key = "empty-my") {
                    EmptyPicture(Icons.Rounded.DoneAll, "Nothing overdue", "Well done! Every task is on time.")
                }
                myTaskCards(
                    mine, now, my.openId, my.ticking,
                    onToggle = { t -> my.openId = if (my.openId == t.id) null else t.id },
                    onTick = { my.tick(it) },
                    onEdit = { my.editFor = it },
                    onNewTime = { my.timeFor = it },
                    onMove = { my.moveFor = it },
                    onDelete = { my.delete(it) },
                )
            }
        }
    }
    fu.Sheets()
    my.Sheets()
}

/**
 * The card of a follow-up waiting in To schedule (no time yet), with its Set time and Edit windows. One place, so the
 * To schedule page, the person page and Follow-ups (filtered to a person) all show the very same card.
 */
class SchedActions(private val scope: kotlinx.coroutines.CoroutineScope, private val snack: SnackbarHostState) {
    var openKey by mutableStateOf<Long?>(null)
    var ticking by mutableStateOf(setOf<Long>())
    var setFor by mutableStateOf<ToScheduleItem?>(null)
    var editFor by mutableStateOf<ToScheduleItem?>(null)

    @Composable
    fun Card(t: ToScheduleItem, pos: RowPos, highlighted: Boolean = false) {
        val ctx = LocalContext.current
        val hasNumber = t.number.isNotBlank()
        // A task you gave waiting for his deadline: its chips, ticks, banner and Timeline.
        val teamTasks by TeamRepo.tasks.collectAsState()
        val teamV by TeamSync.version.collectAsState()
        val tt = remember(t.linkId, teamTasks, teamV) { if (TeamRepo.activeNow) TeamUi.taskOfLink(ctx, t.linkId, teamTasks) else null }
        TaskCard(
            title = t.headline,
            done = t.id in ticking,
            onTick = {
                if (t.id !in ticking) {
                    ticking = ticking + t.id
                    scope.launch {
                        kotlinx.coroutines.delay(380L)
                        TeamDelete.closeGiven(ctx, t.linkId)
                        ToScheduleStore.remove(t.id)
                        ticking = ticking - t.id
                        scope.offerUndo(snack, "Done · ${t.headline}") { ToScheduleStore.put(t) }
                    }
                }
            },
            time = null,
            timeIcons = when {
                t.callOn && t.textOn -> TimeIcons.BOTH
                t.textOn -> TimeIcons.TEXT
                t.callOn -> TimeIcons.CALL
                else -> TimeIcons.NONE
            },
            person = if (hasNumber || t.name.isNotBlank()) Names.first(t.title) ?: t.title else null,
            chips = listOfNotNull(
                    RowChip("👤 No person", ChipTone.ORANGE) { editFor = t }.takeIf { !hasNumber },
                    RowChip("🔕 No alert", ChipTone.ORANGE) { editFor = t }.takeIf { !t.callOn && !t.textOn },
                ) +
                (tt?.let { TeamUi.giverChips(it) } ?: emptyList()),
            noTimeChip = tt == null && !t.needsDetails,
            corner = tt?.takeIf { it.fromUid == TeamRepo.uid }?.let { x -> { TeamTicks(x) } },
            banner = tt?.let { TeamUi.banner(ctx, it, TeamRepo.uid) },
            urgent = tt?.let { it.urgent && it.open } == true,
            starred = t.starred,
            onStar = { ToScheduleStore.put(t.copy(starred = !t.starred)) },
            onCall = if (hasNumber && t.callOn) ({ dial(ctx, t.number) }) else null,
            onWhatsApp = if (hasNumber) ({ WhatsApp.open(ctx, t.number, WhatsApp.message(ctx, t.code, t.headline, 0L, t.name)) }) else null,
            expanded = openKey == t.id,
            onClick = { openKey = if (openKey == t.id) null else t.id },
            pos = pos,
            highlighted = highlighted,
        ) {
            CardPanel(
                details = listOf(
                    listOfNotNull(
                        "Saved ${Fmt.dueLabel(t.createdAt)}",
                        "📞 call".takeIf { t.callOn },
                        "💬 texts paused".takeIf { t.textOn },
                    ).joinToString(" · ")
                ) + listOfNotNull(t.number.takeIf { hasNumber && t.name.isNotBlank() }?.let { "${t.name} · $it" }),
                onNewTime = { if (t.needsDetails) editFor = t else setFor = t },
                buttonLabel = if (t.needsDetails) "Add details" else "Set time",
                links = listOfNotNull(
                    tt?.let { x -> RowLink(TeamUi.timelineLabel(x)) { TeamUi.openTimeline(x.id) } },
                    RowLink("Edit") { editFor = t },
                    if (tt != null) null else RowLink("Move to My tasks") {
                        openKey = null
                        val task = FollowUps.moveToMyTasks(ctx, t)
                        scope.offerUndo(snack, "Moved to My tasks") { MyTaskStore.delete(task.id); ToScheduleStore.put(t) }
                    },
                    RowLink("Delete", danger = true) {
                        openKey = null
                        if (TeamDelete.givenOpen(ctx, t.linkId) != null) TeamDelete.guard(ctx, t.linkId) { ToScheduleStore.remove(t.id) }
                        else {
                            ToScheduleStore.remove(t.id)
                            scope.offerUndo(snack, "Deleted · ${t.headline}") { ToScheduleStore.put(t) }
                        }
                    },
                )
            )
        }
    }

    @Composable
    fun Sheets() {
        val ctx = LocalContext.current
        setFor?.let { t ->
            SetTimeSheet(
                heading = "Set time",
                about = t.headline,
                onPick = { ms ->
                    setFor = null
                    openKey = null
                    FollowUps.setTime(ctx, t, ms)
                    // A task you gave with no deadline yet: your time becomes his deadline (I pick).
                    TeamDelete.givenOpen(ctx, t.linkId)?.takeIf { it.due == 0L }?.let { TeamRepo.setGiverDate(it, ms) }
                    android.widget.Toast.makeText(ctx, "Set · ${Fmt.dueLabel(ms)}", android.widget.Toast.LENGTH_SHORT).show()
                },
                onDismiss = { setFor = null },
            )
        }
        editFor?.let { t -> FollowUpDialog(onDismiss = { editFor = null }, item = t) }
    }
}
