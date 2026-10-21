package com.callbackreminder.app

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.MutableTransitionState

/** Which list My tasks shows; a notification can ask for one. */
object MyTasksUi {
    const val ONE_TIME = 0
    const val DOSSIER = 1
    val tabRequest = MutableStateFlow<Int?>(null)

    /** Search tapped a task: open its list and outline it for a moment. */
    val showTask = MutableStateFlow<Long?>(null)

    /** The outlined task's row is on screen (the list stops scrolling down to find it). */
    val seen = MutableStateFlow(0L)
    val showDossier = MutableStateFlow<Long?>(null)
}

private val Teal = Color(0xFF0FA68E)
private val Amber: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFFFFC266) else Color(0xFFB36B00)
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Refreshes "overdue" and "was due" labels while a screen is open. */
@Composable
internal fun rememberNow(resumeKey: Int): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }
    return now
}

/** The My tasks tab: your Tasks in lists (like Google Tasks), and the Dossier you tick every day. */
@Composable
fun MyTasksScreen(resumeKey: Int) {
    val ctx = LocalContext.current
    val tasks by MyTaskStore.items.collectAsState()
    val lists by MyTaskStore.lists.collectAsState()
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    var sub by rememberSaveable { mutableIntStateOf(MyTasksUi.ONE_TIME) }
    // Unsorted opens first; -1 is the All tab.
    var listSel by rememberSaveable { mutableLongStateOf(MyTaskStore.UNSORTED) }
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    var dossierStarred by rememberSaveable { mutableStateOf(false) }
    var completedOpen by rememberSaveable { mutableStateOf(false) }
    val now = rememberNow(resumeKey)
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val tabRequest by MyTasksUi.tabRequest.collectAsState()
    LaunchedEffect(tabRequest) {
        val t = tabRequest ?: return@LaunchedEffect
        MyTasksUi.tabRequest.value = null
        sub = t
    }
    var highlightId by remember { mutableStateOf<Long?>(null) }
    val showTask by MyTasksUi.showTask.collectAsState()
    LaunchedEffect(showTask) {
        val id = showTask ?: return@LaunchedEffect
        MyTasksUi.showTask.value = null
        val t = MyTaskStore.get(id) ?: return@LaunchedEffect
        sub = MyTasksUi.ONE_TIME
        listSel = t.listId
        starredOnly = false
        if (t.done) completedOpen = true
        highlightId = id
        delay(3_000L)
        if (highlightId == id) highlightId = null
    }
    val showDossierReq by MyTasksUi.showDossier.collectAsState()
    LaunchedEffect(showDossierReq) {
        val id = showDossierReq ?: return@LaunchedEffect
        MyTasksUi.showDossier.value = null
        sub = MyTasksUi.DOSSIER
        dossierStarred = false
        highlightId = -id
        delay(3_000L)
        if (highlightId == -id) highlightId = null
    }
    if (listSel != ALL_LISTS && lists.none { it.id == listSel }) listSel = MyTaskStore.UNSORTED
    val current = lists.firstOrNull { it.id == listSel }

    var newKind by remember { mutableStateOf<Int?>(null) }
    var editTask by remember { mutableStateOf<MyTask?>(null) }
    var moveFor by remember { mutableStateOf<MyTask?>(null) }
    var editDossier by remember { mutableStateOf<DossierTask?>(null) }
    var whyFor by remember { mutableStateOf<DossierTask?>(null) }
    var historyFor by remember { mutableStateOf<DossierTask?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    var newList by remember { mutableStateOf(false) }
    var renameList by remember { mutableStateOf(false) }
    var reorderLists by remember { mutableStateOf(false) }
    var deleteList by remember { mutableStateOf(false) }

    val day = Dossier.openDay(now)
    val shownDossier = dossier.filter { !dossierStarred || it.starred }
    val todays = shownDossier.filter { it.isOn(day) }.sortedBy { it.minute }
    val notToday = shownDossier.filter { !it.isOn(day) }.sortedBy { it.minute }
    val allTodays = dossier.filter { it.isOn(day) }
    val doneToday = allTodays.count { records[DossierStore.key(it.id, day)]?.done == true }

    val pending = tasks.filter { !it.done }
    val overdue = pending.filter { it.timed && it.dueAt <= now }
    val inView = if (listSel == ALL_LISTS) tasks else tasks.filter { it.listId == listSel }
    val viewPending = inView.filter { !it.done && (!starredOnly || it.starred) }
    val viewDone = inView.filter { it.done && (!starredOnly || it.starred) }.sortedByDescending { it.doneAt }

    // Ticking done plays first (circle fills, ✓ pops, buzz, strike), then the row slides away and only
    // then is it marked done; marking it at once moved it out of the list before anything could be seen.
    var ticking by remember { mutableStateOf(setOf<Long>()) }
    var leaving by remember { mutableStateOf(setOf<Long>()) }
    // Tapping a card opens it in place (details, New time and the links); tapping again closes it.
    var openTask by rememberSaveable { mutableStateOf<Long?>(null) }
    var newTimeFor by remember { mutableStateOf<MyTask?>(null) }
    var newPickFor by remember { mutableStateOf<MyTask?>(null) }
    fun tick(t: MyTask) {
        val before = t
        if (t.done) {
            Arrivals.arrive("mt-${t.id}")
            MyTaskStore.setDone(t.id, false)
            scope.offerUndo(snack, "Not done · ${t.title}") { Arrivals.arrive("mt-${t.id}"); MyTaskStore.restore(before) }
            return
        }
        if (t.id in ticking) return
        ticking = ticking + t.id
        scope.launch {
            delay(450L)
            leaving = leaving + t.id
            delay(280L)
            MyTaskStore.setDone(t.id, true)
            ticking = ticking - t.id
            leaving = leaving - t.id
            // Undo brings it back with the same slide, not in one jump.
            scope.offerUndo(snack, "Done · ${t.title}") { Arrivals.arrive("mt-${t.id}"); MyTaskStore.restore(before) }
        }
    }
    fun deleteTask(t: MyTask) {
        if (openTask == t.id) openTask = null
        MyTaskStore.delete(t.id)
        scope.offerUndo(snack, "Deleted · ${t.title}") { Arrivals.arrive("mt-${t.id}"); MyTaskStore.restore(t) }
    }
    fun dossierTick(t: DossierTask) {
        val rec = records[DossierStore.key(t.id, day)]
        val wasDone = rec?.done == true
        val ok = if (wasDone) DossierStore.untick(t.id, day) else DossierStore.tick(t.id, day)
        if (!ok) {
            Toast.makeText(ctx, "This day is locked in History", Toast.LENGTH_SHORT).show()
            return
        }
        scope.offerUndo(snack, if (wasDone) "Not done · ${t.title}" else "Done · ${t.title}") {
            if (wasDone) DossierStore.tick(t.id, day) else {
                DossierStore.untick(t.id, day)
                rec?.reason?.takeIf { it.isNotBlank() }?.let { DossierStore.setReason(t.id, day, it) }
            }
        }
    }
    fun deleteDossier(t: DossierTask) {
        val recs = records.filterValues { it.taskId == t.id }
        DossierStore.delete(t.id)
        scope.offerUndo(snack, "Deleted · ${t.title}") { DossierStore.restore(t, recs) }
    }

    @Composable
    fun row(t: MyTask, showList: Boolean, handle: Modifier? = null) {
        key(t.id) {
            // Comes in with a slide when it returns (Undo, Not done); leaves with one when ticked.
            val state = remember { MutableTransitionState("mt-${t.id}" !in Arrivals.keys.value) }
            LaunchedEffect(Unit) { Arrivals.seen("mt-${t.id}") }
            SideEffect { state.targetState = t.id !in leaving }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = state,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(280)) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)) +
                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { it / 3 },
                exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280)) { it / 3 } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240)) +
                    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(280)),
            ) {
                MyTaskCard(
                    t = t,
                    finishing = t.id in ticking,
                    now = now,
                    listName = if (showList) MyTaskStore.listName(t.listId) else null,
                    highlighted = t.id == highlightId,
                    handle = handle,
                    expanded = openTask == t.id,
                    onToggle = { openTask = if (openTask == t.id) null else t.id },
                    onTick = { tick(t) },
                    onStar = { MyTaskStore.setStar(t.id, !t.starred) },
                    onEdit = { editTask = t },
                    onNewTime = { newTimeFor = t },
                    onRemoveTime = {
                        val before = t
                        MyTaskStore.edit(t.id, t.title, t.note, 0L, t.name, t.number, remindBefore = emptyList())
                        scope.offerUndo(snack, "Time removed · in To schedule") { MyTaskStore.restore(before) }
                    },
                    onMoveToFollowUps = { moveFor = t },
                    onDelete = { deleteTask(t) },
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { newKind = sub },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Rounded.Add, contentDescription = "New task") }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // An outlined task further down: scroll until its row shows (it then brings itself fully into view).
        LaunchedEffect(highlightId) {
            val id = highlightId?.takeIf { it > 0L } ?: return@LaunchedEffect
            MyTasksUi.seen.value = 0L
            listState.revealUntil { MyTasksUi.seen.value == id }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("My tasks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        DateStatusLine(
                            when {
                                overdue.isNotEmpty() -> "${overdue.size} overdue"
                                pending.isEmpty() && allTodays.isEmpty() -> "Nothing waiting"
                                else -> "${pending.size} to do · dossier $doneToday of ${allTodays.size} done"
                            },
                            red = overdue.isNotEmpty()
                        )
                    }
                    TopActions()
                }
            }

            item(key = "sub") {
                KindTabs(
                    selected = sub,
                    oneTimeLabel = "Tasks · ${pending.size}",
                    dossierLabel = "Dossier · $doneToday/${allTodays.size}",
                    onSelect = { sub = it }
                )
            }

            if (sub == MyTasksUi.ONE_TIME) {
                item(key = "lists") {
                    ListTabs(lists = lists, selected = listSel, onSelect = { listSel = it; completedOpen = false }, onNewList = { newList = true })
                }
                item(key = "list-head") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                current?.name ?: "All lists",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (current == null || current.byDate) "By date" else "My order",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (current != null) {
                                Box {
                                    IconButton(onClick = { listMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "List menu") }
                                    DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                                        MenuLabel("SORT")
                                        DropdownMenuItem(
                                            text = { Text("My order (drag)") },
                                            trailingIcon = if (!current.byDate) ({ Icon(Icons.Rounded.Check, null) }) else null,
                                            onClick = { listMenu = false; MyTaskStore.setListByDate(current.id, false) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("By date") },
                                            trailingIcon = if (current.byDate) ({ Icon(Icons.Rounded.Check, null) }) else null,
                                            onClick = { listMenu = false; MyTaskStore.setListByDate(current.id, true) }
                                        )
                                        MenuLabel("LIST")
                                        if (current.id != MyTaskStore.UNSORTED) {
                                            DropdownMenuItem(text = { Text("Rename list") }, onClick = { listMenu = false; renameList = true })
                                        }
                                        DropdownMenuItem(text = { Text("Reorder lists") }, onClick = { listMenu = false; reorderLists = true })
                                        if (current.id != MyTaskStore.UNSORTED) {
                                            DropdownMenuItem(
                                                text = { Text("Delete list", color = MaterialTheme.colorScheme.error) },
                                                onClick = { listMenu = false; deleteList = true }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        StarredFilterChip(starredOnly) { starredOnly = it }
                    }
                }
                if (viewPending.isEmpty()) {
                    item(key = "empty-one") {
                        when {
                            starredOnly -> HintText("No starred tasks here.")
                            viewDone.isNotEmpty() -> EmptyPicture(Icons.Rounded.DoneAll, "All done here", "Well done! Nothing left on this list.")
                            else -> EmptyPicture(
                                Icons.Rounded.TaskAlt,
                                "No tasks yet",
                                "Your own to-dos, apart from follow-ups. A time is optional: tasks without one wait in To review.",
                                button = "＋ New task",
                                onButton = { newKind = sub }
                            )
                        }
                    }
                }
                if (current == null) {
                    allGroups(viewPending, now).forEach { (title, group) ->
                        item(key = "g-$title") {
                            Column {
                                DayHeader(
                                    title,
                                    "${group.size}",
                                    when (title) {
                                        "Overdue" -> MaterialTheme.colorScheme.error
                                        "Today" -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                TaskGroup {
                                    group.forEachIndexed { i, t ->
                                        if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp))
                                        row(t, showList = true)
                                    }
                                }
                            }
                        }
                    }
                } else if (viewPending.isNotEmpty()) {
                    item(key = "list-${current.id}") {
                        TaskGroup {
                            ReorderableTasks(
                                tasks = sortedForList(viewPending, current.byDate),
                                enabled = !current.byDate && !starredOnly,
                                onDrop = { ids ->
                                    // Keep the hidden (starred-off) tasks after the moved ones.
                                    MyTaskStore.reorder(ids)
                                },
                            ) { t, handle -> row(t, showList = false, handle = handle) }
                        }
                    }
                }
                if (viewDone.isNotEmpty()) {
                    item(key = "completed") { CompletedHeader(viewDone.size, completedOpen) { completedOpen = !completedOpen } }
                    if (completedOpen) {
                        item(key = "completed-list") {
                            TaskGroup {
                                viewDone.forEachIndexed { i, t ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp))
                                    row(t, showList = current == null)
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "progress") { DossierProgress(day, now, doneToday, allTodays.size) }
                item(key = "dossier-star") { StarredFilterChip(dossierStarred) { dossierStarred = it } }
                if (shownDossier.isEmpty()) {
                    item(key = "empty-dossier") {
                        if (dossierStarred) HintText("No starred dossier tasks.")
                        else EmptyPicture(
                            Icons.Rounded.EventRepeat,
                            "No dossier tasks",
                            "The tasks you do on set days, like checking the cash book every morning. Tick each one when it's done, or say why not.",
                            button = "＋ New dossier task",
                            onButton = { newKind = sub }
                        )
                    }
                }
                items(todays, key = { "d${it.id}" }) { t ->
                    Box(Modifier.animateItem()) {
                    DossierRow(
                        t = t,
                        day = day,
                        rec = records[DossierStore.key(t.id, day)],
                        now = now,
                        showScore = true,
                        onTick = { dossierTick(t) },
                        onWhy = { whyFor = t },
                        onOpen = { historyFor = t },
                        onEdit = { editDossier = t },
                        onDelete = { deleteDossier(t) },
                        onStar = { DossierStore.setStar(t.id, !t.starred) },
                        highlighted = -t.id == highlightId,
                    )
                    }
                }
                if (notToday.isNotEmpty()) {
                    item(key = "not-today") {
                        Text(
                            if (OffDays.isOff(day)) "Weekly off day · ${notToday.size} paused" else "Not today · ${notToday.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(notToday, key = { "n${it.id}" }) { t ->
                        Box(Modifier.animateItem()) {
                        DossierRow(
                            t = t,
                            day = day,
                            rec = null,
                            now = now,
                            showScore = true,
                            today = false,
                            onTick = {},
                            onWhy = {},
                            onOpen = { historyFor = t },
                            onEdit = { editDossier = t },
                            onDelete = { deleteDossier(t) },
                            onStar = { DossierStore.setStar(t.id, !t.starred) },
                            highlighted = -t.id == highlightId,
                        )
                        }
                    }
                }
            }
        }
        SlimTitleBar(listState, "My tasks", padding.calculateTopPadding(), Modifier.align(Alignment.TopCenter))
        }
    }

    newKind?.let { kind ->
        if (kind == MyTasksUi.DOSSIER) {
            DossierTaskDialog(
                existing = null,
                onDismiss = { newKind = null },
                onSwitchKind = { newKind = MyTasksUi.ONE_TIME },
            )
        } else {
            MyTaskDialog(
                existing = null,
                onDismiss = { newKind = null },
                onSwitchKind = { newKind = MyTasksUi.DOSSIER },
                listId = if (listSel == ALL_LISTS) MyTaskStore.UNSORTED else listSel,
            )
        }
    }
    editTask?.let { t ->
        MyTaskDialog(
            existing = t,
            onDismiss = { editTask = null },
            onSwitchKind = null,
            onDelete = { deleteTask(it) },
            onSaved = { before, after ->
                if (before != null && before.listId != after.listId) {
                    scope.offerUndo(snack, "Moved to ${MyTaskStore.listName(after.listId)}") { MyTaskStore.restore(before) }
                }
            },
            onMoveToFollowUps = { moveFor = it },
        )
    }
    // Move to Follow-ups: New follow-up filled in from the task; on Save the task leaves My tasks.
    moveFor?.let { t -> FollowUpDialog(onDismiss = { moveFor = null }, fromMyTask = t) }
    editDossier?.let { t -> DossierTaskDialog(existing = t, onDismiss = { editDossier = null }, onSwitchKind = null) }

    newTimeFor?.let { t ->
        SnoozeSheet(
            heading = "New time",
            about = t.title,
            onPick = { ms ->
                newTimeFor = null
                openTask = null
                MyTaskStore.setDue(t.id, ms)
                scope.offerUndo(snack, "Moved to ${Fmt.dueLabel(ms)}") { MyTaskStore.restore(t) }
            },
            onPickTime = { newTimeFor = null; newPickFor = t },
            onDismiss = { newTimeFor = null },
            plusRow = true
        )
    }
    newPickFor?.let { t ->
        DateTimePickerFlow(
            initialMs = maxOf(t.dueAt, System.currentTimeMillis() + 60_000L),
            onPicked = { ms ->
                newPickFor = null
                openTask = null
                MyTaskStore.setDue(t.id, ms)
                scope.offerUndo(snack, "Moved to ${Fmt.dueLabel(ms)}") { MyTaskStore.restore(t) }
            },
            onDismiss = { newPickFor = null }
        )
    }
    whyFor?.let { t ->
        WhyNotSheet(t = t, day = day, rec = records[DossierStore.key(t.id, day)], onDismiss = { whyFor = null })
    }
    historyFor?.let { t ->
        DossierHistoryDialog(
            t = dossier.firstOrNull { it.id == t.id } ?: t,
            onDismiss = { historyFor = null },
            onEdit = { historyFor = null; editDossier = t },
        )
    }

    if (newList) {
        ListNameDialog(title = "New list", initial = "", onDismiss = { newList = false }) { n ->
            listSel = MyTaskStore.addList(n).id
            newList = false
        }
    }
    if (renameList && current != null) {
        ListNameDialog(title = "Rename list", initial = current.name, onDismiss = { renameList = false }) { n ->
            MyTaskStore.renameList(current.id, n)
            renameList = false
        }
    }
    if (reorderLists) ReorderListsDialog(lists) { reorderLists = false }
    if (deleteList && current != null) {
        DeleteListDialog(
            list = current,
            count = tasks.count { it.listId == current.id },
            onDismiss = { deleteList = false },
        ) { deleteTasks ->
            deleteList = false
            val name = current.name
            val before = MyTaskStore.deleteList(current.id, deleteTasks)
            listSel = MyTaskStore.UNSORTED
            if (before != null) {
                scope.offerUndo(snack, "Deleted list · $name") {
                    MyTaskStore.restoreLists(before.first, before.second)
                }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

private fun tickOrUntick(ctx: android.content.Context, t: DossierTask, day: LocalDate, rec: DossierRecord?) {
    val ok = if (rec?.done == true) DossierStore.untick(t.id, day) else DossierStore.tick(t.id, day)
    if (!ok) Toast.makeText(ctx, "This day is locked in History", Toast.LENGTH_SHORT).show()
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

/** One-time / Dossier switch, used on the tab and in the New task window. */
@Composable
private fun KindTabs(selected: Int, oneTimeLabel: String, dossierLabel: String, onSelect: (Int) -> Unit) {
    val kinds = listOf(MyTasksUi.ONE_TIME, MyTasksUi.DOSSIER)
    BoxTabs(listOf(oneTimeLabel, dossierLabel), kinds.indexOf(selected).coerceAtLeast(0), { onSelect(kinds[it]) })
}

/** The round tick box. Ticking fills it with a soft pop, draws the check in and gives a light buzz. */
@Composable
internal fun TickCircle(done: Boolean, missed: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val fill by androidx.compose.animation.animateColorAsState(
        when {
            done -> Teal
            missed -> MaterialTheme.colorScheme.errorContainer
            else -> Color.Transparent
        },
        androidx.compose.animation.core.tween(220), label = "tickFill"
    )
    val pop = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    var first by androidx.compose.runtime.remember { mutableStateOf(true) }
    LaunchedEffect(done) {
        if (first) { first = false; return@LaunchedEffect }
        if (done) {
            pop.snapTo(0.8f)
            pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 600f))
        }
    }
    Surface(
        onClick = {
            if (!done) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shape = CircleShape,
        color = fill,
        border = if (done || missed) null else BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(28.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                visible = done,
                enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (missed) Icon(Icons.Rounded.Close, contentDescription = "Missed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

/** The window shared by New task and Edit, laid out as a card with ✕ and a Save button. */
@Composable
private fun TaskWindow(
    title: String,
    onDismiss: () -> Unit,
    saveLabel: String,
    canSave: Boolean,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Kept clear of the phone's Home/Back buttons; Save stays at the bottom while the form scrolls.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().safeBottomPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        content()
                    }
                    Button(
                        onClick = onSave,
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(saveLabel, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Dossier

/** "Today · Tue 14 Oct · 3 of 6 done", the bar, and when the day locks. */
@Composable
private fun DossierProgress(day: LocalDate, now: Long, done: Int, total: Int) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (day == LocalDate.now()) "Today" else "Yesterday") + " · " + day.format(dayFmt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text("$done of $total done", fontWeight = FontWeight.Bold, color = Teal)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = Teal,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val lockTonight = Dossier.lockAt(day) - now < 24 * 3_600_000L && day == LocalDate.now()
            Text(
                if (lockTonight) "Tick until 3:00 AM tonight. Then today locks into History."
                else "Tick until 3:00 AM. Then this day locks into History.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One dossier task: tick box, title, how often it repeats (and when it was ticked), this month's
 * score, and "＋ Why not done?" under every unticked task, whether or not its time has passed.
 */
@Composable
private fun DossierRow(
    t: DossierTask,
    day: LocalDate,
    rec: DossierRecord?,
    now: Long,
    showScore: Boolean,
    today: Boolean = true,
    onTick: () -> Unit,
    onWhy: () -> Unit,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onStar: (() -> Unit)? = null,
    highlighted: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val done = rec?.done == true
    val wasDue = today && !done && t.dueAt(day) <= now
    Card(
        onClick = onOpen,
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (done) 0.dp else 1.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp)) {
                if (today) TickCircle(done = done, onClick = onTick)
                else TickCircle(done = false, enabled = false, onClick = {})
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done || !today) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                val line = buildString {
                    append(t.frequency)
                    when {
                        done -> append(" · ✓ ").append(Fmt.timeOnly(rec!!.doneAt))
                        wasDue -> append(" · was due")
                        !today -> nextDay(t, day)?.let { append(" · next ").append(it.format(dayFmt)) }
                    }
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showScore) {
                    val (d, total) = DossierStore.monthScore(t, now)
                    if (total > 0) {
                        Text(
                            "This month: $d of $total · ${d * 100 / total}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (d * 100 / total >= 80) Teal else Amber
                        )
                    }
                }
                if (today && !done) {
                    val reason = rec?.reason.orEmpty()
                    Text(
                        if (reason.isBlank()) "＋ Why not done?" else "“$reason” · edit",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (reason.isBlank()) FontWeight.Bold else FontWeight.Normal,
                        color = Amber,
                        modifier = Modifier.padding(top = 4.dp).clickable(onClick = onWhy)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    t.timeLabel + "\n– " + Dossier.timeLabel(t.endMinute % (24 * 60)),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (wasDue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = if (onEdit == null) 10.dp else 0.dp)
                )
                if (onStar != null) StarButton(t.starred, onStar)
                else if (t.starred) Icon(Icons.Rounded.Star, contentDescription = "Starred", tint = StarGold, modifier = Modifier.padding(end = 10.dp).size(18.dp))
                if (onEdit != null || onDelete != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("History") }, onClick = { menuOpen = false; onOpen() })
                            if (onEdit != null) {
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; onDelete() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun nextDay(t: DossierTask, after: LocalDate): LocalDate? {
    var d = after.plusDays(1)
    repeat(62) {
        if (t.isOn(d)) return d
        d = d.plusDays(1)
    }
    return null
}

/** "Why not done?": a reason (kept in History, editable until 3:00 AM), or tick it after all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhyNotSheet(t: DossierTask, day: LocalDate, rec: DossierRecord?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var reason by remember { mutableStateOf(rec?.reason.orEmpty()) }
    val sheetScope = rememberCoroutineScope()
    val sheetRef = remember { arrayOfNulls<SheetState>(1) }
    val cancel = rememberDiscardGuard(
        changed = { reason != rec?.reason.orEmpty() },
        onClose = onDismiss,
        onKeep = { sheetRef[0]?.let { s -> sheetScope.launch { s.show() } } },
    )
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = {
            if (it == SheetValue.Hidden && reason != rec?.reason.orEmpty()) { cancel(); false } else true
        }
    )
    sheetRef[0] = sheetState
    val due = t.dueAt(day) <= System.currentTimeMillis()

    ModalBottomSheet(onDismissRequest = cancel, sheetState = sheetState) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp + navBarMissing()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${t.frequency} · ${if (due) "was due" else "due"} ${t.timeLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                trailingIcon = voiceMic(reason) { reason = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Why not done?") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp)
            )
            Text(
                "Saved in this task's History. You can change it until 3:00 AM. If you tick the task later, the reason is removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (!DossierStore.tick(t.id, day)) Toast.makeText(ctx, "This day is locked in History", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.White)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("It's done")
                }
                Button(
                    onClick = {
                        if (!DossierStore.setReason(t.id, day, reason)) {
                            Toast.makeText(ctx, "This day is locked in History", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Save reason") }
            }
        }
    }
}

/** New dossier task, or Edit: the title, the days it repeats on, the time (required) and "remind me before". */
@Composable
internal fun DossierTaskDialog(existing: DossierTask?, onDismiss: () -> Unit, onSwitchKind: (() -> Unit)?) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var monthly by remember { mutableStateOf((existing?.monthDay ?: 0) > 0) }
    var days by remember { mutableIntStateOf(existing?.days ?: 0b0111111) }
    var monthDay by remember { mutableIntStateOf(existing?.monthDay?.takeIf { it > 0 } ?: LocalDate.now().dayOfMonth) }
    var minute by remember { mutableStateOf(existing?.minute) }
    var remind by remember { mutableStateOf((existing?.remindBefore ?: 0) > 0) }
    var remindMin by remember { mutableIntStateOf(existing?.remindBefore?.takeIf { it > 0 } ?: 15) }
    var starred by remember { mutableStateOf(existing?.starred ?: false) }
    var endMinute by remember { mutableStateOf(existing?.endMinute) }
    var everyWeeks by remember { mutableIntStateOf(existing?.everyWeeks ?: 1) }
    var onOffDay by remember { mutableStateOf(existing?.onOffDay ?: false) }
    var showClock by remember { mutableStateOf(false) }
    var showEndClock by remember { mutableStateOf(false) }
    val start = remember { listOf(title, note, monthly, days, monthDay, minute, remind, remindMin, starred, endMinute, everyWeeks, onOffDay) }
    val cancel = rememberDiscardGuard(
        changed = { listOf(title, note, monthly, days, monthDay, minute, remind, remindMin, starred, endMinute, everyWeeks, onOffDay) != start },
        onClose = onDismiss,
    )

    TaskWindow(
        title = if (existing == null) "New task" else "Edit dossier task",
        onDismiss = cancel,
        saveLabel = "Save dossier task",
        canSave = title.isNotBlank() && minute != null && endMinute != null && (monthly || days != 0),
        onSave = {
            minute?.let { m ->
                val d = if (monthly) Dossier.ALL_DAYS else days
                val md = if (monthly) monthDay else 0
                val rb = if (remind) remindMin else 0
                val end = endMinute ?: minOf(m + 30, 24 * 60)
                val weeks = if (monthly) 1 else everyWeeks
                if (existing == null) DossierStore.add(title, note, m, d, md, rb, starred, end, weeks, onOffDay)
                else DossierStore.edit(existing.id, title, note, m, d, md, rb, starred, end, weeks, onOffDay)
                onDismiss()
            }
        }
    ) {
        if (onSwitchKind != null) {
            KindTabs(MyTasksUi.DOSSIER, "One-time", "Dossier (repeat)") { if (it == MyTasksUi.ONE_TIME) onSwitchKind() }
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Dossier task") },
            trailingIcon = voiceMic(title) { title = it },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            trailingIcon = voiceMic(note) { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Note (optional)") },
            maxLines = 2,
            shape = RoundedCornerShape(14.dp)
        )

        StarSwitchRow(starred) { starred = it }

        Text("Repeats", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = !monthly && days == Dossier.ALL_DAYS, onClick = { monthly = false; days = Dossier.ALL_DAYS }, label = { Text("Daily") })
            FilterChip(selected = !monthly && days == 0b0111111, onClick = { monthly = false; days = 0b0111111 }, label = { Text("Mon-Sat") })
            FilterChip(
                selected = !monthly && Integer.bitCount(days) == 1,
                onClick = { monthly = false; days = Dossier.dayOfWeekBit(LocalDate.now().dayOfWeek) },
                label = { Text("Weekly") }
            )
            FilterChip(selected = monthly, onClick = { monthly = true }, label = { Text("Monthly") })
        }
        if (!monthly) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DayOfWeek.values().forEach { d ->
                    val on = days and Dossier.dayOfWeekBit(d) != 0
                    Surface(
                        onClick = { days = days xor Dossier.dayOfWeekBit(d) },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(19.dp),
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(d.name.take(1), fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text(
                if (days == 0) "Pick at least one day." else "${Dossier.frequency(days, 0)}. Days you leave out are your off days.",
                style = MaterialTheme.typography.bodySmall,
                color = if (days == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Every", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                (1..4).forEach { n ->
                    FilterChip(
                        selected = everyWeeks == n,
                        onClick = { everyWeeks = n },
                        label = { Text(if (n == 1) "Every week" else "$n weeks") }
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { monthDay = if (monthDay <= 1) 31 else monthDay - 1 }) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Earlier day")
                }
                Text(
                    "On the ${Dossier.ordinal(monthDay)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(onClick = { monthDay = if (monthDay >= 31) 1 else monthDay + 1 }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Later day")
                }
            }
            if (monthDay > 28) {
                Text(
                    "In shorter months it comes on the last day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // A fixed time block: Start and End book this time in the Calendar every day it comes up.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true, false).forEach { isStart ->
                OutlinedButton(
                    onClick = { if (isStart) showClock = true else showEndClock = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (isStart) "Start" else "End", style = MaterialTheme.typography.labelMedium)
                        val m = if (isStart) minute else endMinute
                        if (m == null) {
                            Text("Pick (required)", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(Dossier.timeLabel(m % (24 * 60)), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Text(
            "The Done notification comes at Start. The Calendar keeps Start to End booked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Also on my off day", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (onOffDay) "Comes up on your off day too, when it's one of its days" else "Pauses on your weekly off day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = onOffDay, onCheckedChange = { onOffDay = it })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remind me before", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (remind) "An extra notification, ${remindLabel(remindMin)} before" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = remind, onCheckedChange = { remind = it })
        }
        if (remind) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Dossier.REMIND_CHOICES.forEach { m ->
                    FilterChip(selected = remindMin == m, onClick = { remindMin = m }, label = { Text(remindLabel(m)) })
                }
            }
        }
    }

    if (showClock) {
        val m = minute ?: (9 * 60)
        ClockDialog(
            initial = LocalTime.of(m / 60, m % 60),
            is24h = DateFormat.is24HourFormat(ctx),
            onPicked = { t ->
                val newStart = t.hour * 60 + t.minute
                // Keep the block's length when the start moves; a new task gets 30 minutes.
                val len = minute?.let { old -> endMinute?.let { it - old } }?.takeIf { it > 0 } ?: 30
                minute = newStart
                endMinute = minOf(newStart + len, 24 * 60)
                showClock = false
            },
            onDismiss = { showClock = false }
        )
    }
    if (showEndClock) {
        val m = endMinute ?: minOf((minute ?: (9 * 60)) + 30, 24 * 60)
        ClockDialog(
            initial = LocalTime.of((m / 60) % 24, m % 60),
            is24h = DateFormat.is24HourFormat(ctx),
            onPicked = { t ->
                val e = t.hour * 60 + t.minute
                // Midnight as the end means the end of the day.
                endMinute = if (e == 0) 24 * 60 else e
                showEndClock = false
            },
            onDismiss = { showEndClock = false }
        )
    }
    val s = minute
    val e = endMinute
    if (s != null && e != null && e <= s && !showClock && !showEndClock) {
        LaunchedEffect(s, e) { endMinute = minOf(s + 30, 24 * 60) }
    }
}

private fun remindLabel(m: Int) = if (m >= 60) "${m / 60} hour" else "$m min"

/** A dossier task's History: this month's score and calendar, then every day, ✓ Done or ✕ Missed with the reason. */
@Composable
private fun DossierHistoryDialog(t: DossierTask, onDismiss: () -> Unit, onEdit: () -> Unit) {
    val records by DossierStore.records.collectAsState()
    val now = System.currentTimeMillis()
    val history = remember(records, t) { DossierStore.history(t, now) }
    val open = Dossier.openDay(now)
    val (done, total) = remember(records, t) { DossierStore.monthScore(t, now) }
    val missed = total - done

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.statusBarsPadding().safeBottomPadding(0.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "head") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(
                                "${t.frequency} · ${t.rangeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onEdit) { Text("Edit") }
                    }
                }
                item(key = "month") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                open.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())) + " so far",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Stat("$done", "done", Teal)
                                Stat("$missed", "missed", MaterialTheme.colorScheme.error)
                                Stat(if (total == 0) "–" else "${done * 100 / total}%", "on record", MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(12.dp))
                            MonthGrid(t, open, now)
                        }
                    }
                }
                item(key = "hist-title") {
                    Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                }
                if (history.isEmpty()) {
                    item(key = "none") { HintText("Its first day is ${t.start.format(dayFmt)}.") }
                }
                items(history, key = { it.date.toString() }) { h -> HistoryLine(h, open) }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** This month as a calendar: ✓ done, ✕ missed, ? still open, – an off day. */
@Composable
private fun MonthGrid(t: DossierTask, open: LocalDate, now: Long) {
    val first = open.withDayOfMonth(1)
    val offset = first.dayOfWeek.value - 1
    val cells: List<LocalDate?> = List(offset) { null } + (1..open.dayOfMonth).map { first.withDayOfMonth(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                        if (d != null) {
                            val rec = DossierStore.record(t.id, d)
                            val (mark, bg, fg) = when {
                                !t.isOn(d) -> Triple("–", Color.Transparent, MaterialTheme.colorScheme.outline)
                                rec?.done == true -> Triple("✓", Teal, Color.White)
                                Dossier.isLocked(d, now) -> Triple("✕", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
                                else -> Triple("?", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Surface(shape = RoundedCornerShape(8.dp), color = bg, modifier = Modifier.fillMaxSize()) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(mark, color = fg, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryLine(h: DossierDay, open: LocalDate) {
    val (title, sub, color) = when (h.status) {
        DossierDay.Status.DONE -> Triple("✓ Done · ${h.date.format(dayFmt)}", "Ticked ${Fmt.timeOnly(h.doneAt)}", Teal)
        DossierDay.Status.MISSED -> Triple(
            "✕ Missed · ${h.date.format(dayFmt)}",
            if (h.reason.isBlank()) "Locked 3:00 AM · no reason given" else "Locked 3:00 AM",
            MaterialTheme.colorScheme.error
        )
        DossierDay.Status.OPEN -> Triple(
            (if (h.date == open) "Today" else h.date.format(dayFmt)) + " · not ticked yet",
            "Open until 3:00 AM",
            MaterialTheme.colorScheme.primary
        )
    }
    Row(Modifier.fillMaxWidth()) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.padding(top = 6.dp).size(10.dp)) {}
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (h.reason.isNotBlank()) {
                Text("“${h.reason}”", style = MaterialTheme.typography.bodyMedium, color = Amber, modifier = Modifier.padding(top = 2.dp))
            }
            HorizontalDivider(Modifier.padding(top = 10.dp))
        }
    }
}

// ---------------------------------------------------------------- To review › Dossier

/**
 * To review › Dossier: today's dossier, ticked and unticked, to tick or say why not. Opened from
 * the review reminder (or the separate dossier reminder). Chips jump to calls and texts waiting.
 */
@Composable
fun DossierReviewScreen(
    resumeKey: Int,
    onBack: () -> Unit,
    onOpenCalls: () -> Unit,
    onOpenOverdueCalls: () -> Unit,
    onOpenTexts: () -> Unit,
) {
    val ctx = LocalContext.current
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val now = rememberNow(resumeKey)
    var whyFor by remember { mutableStateOf<DossierTask?>(null) }
    val myTasks by MyTaskStore.items.collectAsState()
    val needsTime = myTasks.filter { !it.done && !it.timed }.sortedBy { it.createdAt }
    var timeFor by remember { mutableStateOf<MyTask?>(null) }
    var pickFor by remember { mutableStateOf<MyTask?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val day = Dossier.openDay(now)
    val todays = dossier.filter { it.isOn(day) }.sortedBy { it.minute }
    val left = todays.count { records[DossierStore.key(it.id, day)]?.done != true }
    val callsToReview = calls.count { it.done && it.review }
    val callsOverdue = calls.count { !it.done && it.dueAt <= now }
    val textsToReview = texts.count { !it.done && it.review != null }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("To review", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            item(key = "chips") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = false,
                        onClick = { if (callsToReview > 0 || callsOverdue == 0) onOpenCalls() else onOpenOverdueCalls() },
                        label = { Text("Calls ${callsToReview + callsOverdue}") }
                    )
                    FilterChip(selected = false, onClick = onOpenTexts, label = { Text("Texts $textsToReview") })
                    FilterChip(selected = true, onClick = {}, label = { Text("Dossier $left left") })
                }
            }
            if (needsTime.isNotEmpty()) {
                item(key = "needs-time") {
                    Column {
                        DayHeader("⏱ Needs a time", "${needsTime.size}", MaterialTheme.colorScheme.onSurfaceVariant)
                        TaskGroup {
                            needsTime.forEachIndexed { i, t ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp))
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TickCircle(done = false) {
                                        MyTaskStore.setDone(t.id, true)
                                        scope.offerUndo(snack, "Done · ${t.title}") { MyTaskStore.restore(t) }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(t.title, fontWeight = FontWeight.Bold)
                                        Text(
                                            MyTaskStore.listName(t.listId),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FilledTonalButton(onClick = { timeFor = t }) {
                                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Set time")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "hint") {
                Text(
                    "Today's dossier. Tick what's done, or say why not. Locks at 3:00 AM.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (todays.isEmpty()) {
                item(key = "empty") { HintText("No dossier tasks today.") }
            }
            items(todays, key = { it.id }) { t ->
                DossierRow(
                    t = t,
                    day = day,
                    rec = records[DossierStore.key(t.id, day)],
                    now = now,
                    showScore = false,
                    onTick = { tickOrUntick(ctx, t, day, records[DossierStore.key(t.id, day)]) },
                    onWhy = { whyFor = t },
                    onOpen = { whyFor = t.takeIf { records[DossierStore.key(it.id, day)]?.done != true } },
                    onEdit = null,
                    onDelete = null,
                )
            }
            item(key = "lock") {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "At 3:00 AM the day is saved to History. Unticked tasks become Missed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    whyFor?.let { t ->
        WhyNotSheet(t = t, day = day, rec = records[DossierStore.key(t.id, day)], onDismiss = { whyFor = null })
    }
    timeFor?.let { t ->
        SnoozeSheet(
            heading = "Set time",
            about = t.title,
            onPick = { ms ->
                timeFor = null
                MyTaskStore.setDue(t.id, ms)
                scope.offerUndo(snack, "Set for ${Fmt.dueLabel(ms)}") { MyTaskStore.restore(t) }
            },
            onPickTime = { timeFor = null; pickFor = t },
            onDismiss = { timeFor = null }
        )
    }
    pickFor?.let { t ->
        DateTimePickerFlow(
            initialMs = QuickTime.OnDay(1, 10).toOption().whenMs,
            onPicked = { ms ->
                pickFor = null
                MyTaskStore.setDue(t.id, ms)
                scope.offerUndo(snack, "Set for ${Fmt.dueLabel(ms)}") { MyTaskStore.restore(t) }
            },
            onDismiss = { pickFor = null }
        )
    }
}
