package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneId

/** Requests from outside the Calls tab (the review reminder) for which chip to show. */
object CallsUi {
    const val TO_CALL = 0
    const val OVERDUE = 1
    const val TO_REVIEW = 2
    const val DONE = 3

    val tabRequest = MutableStateFlow<Int?>(null)

    /** Search tapped a call task: show it (its chip, scrolled to and outlined for a moment). */
    val showRequest = MutableStateFlow<Long?>(null)
}

/** Which list a call row sits in. */
private enum class CallMode { PENDING, REVIEW, DONE }

/** The bold line of a call row: the task, or "Call back Ali" when none is written. */
fun Reminder.headline(): String = note.ifBlank { "Call back $title" }

/** The number shown after the name, when the name is not already the number. */
fun Reminder.personDetail(): String? = number.takeIf { name.isNotBlank() && it.isNotBlank() }

/** The Calls tab: every call-back as a slim task row, in one block per day, with Created and Due filters. */
@Composable
fun CallsScreen(resumeKey: Int) {
    val ctx = LocalContext.current
    val all by ReminderStore.items.collectAsState()
    var tab by rememberSaveable { mutableStateOf(CallsUi.TO_CALL) }
    var historyFor by remember { mutableStateOf<Reminder?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var rescheduleFor by remember { mutableStateOf<Reminder?>(null) }
    var textInsteadFor by remember { mutableStateOf<Reminder?>(null) }
    var editFor by remember { mutableStateOf<Reminder?>(null) }
    var snoozeFor by remember { mutableStateOf<Reminder?>(null) }
    var addTaskFor by remember { mutableStateOf<Reminder?>(null) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var createdFilter by ListFilters.callsCreated
    var dueFilter by ListFilters.callsDue
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    var pickTimeFor by remember { mutableStateOf<Reminder?>(null) }
    val items = all.filter {
        createdFilter.matches(it.createdAt) && dueFilter.matches(it.dueAt) && (!starredOnly || it.starred)
    }
    val filtering = createdFilter.active || dueFilter.active || starredOnly

    // Refresh "in 5 min / overdue" labels while the screen is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }

    val errorColor = MaterialTheme.colorScheme.error
    val primaryColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    // The review reminder's "Review now" asked for a chip.
    val tabRequest by CallsUi.tabRequest.collectAsState()
    LaunchedEffect(tabRequest) {
        val t = tabRequest ?: return@LaunchedEffect
        CallsUi.tabRequest.value = null
        tab = t
    }

    val today = LocalDate.now(ZoneId.systemDefault())
    val listState = rememberLazyListState()
    var highlightId by remember { mutableStateOf<Long?>(null) }

    val showRequest by CallsUi.showRequest.collectAsState()
    LaunchedEffect(showRequest) {
        val id = showRequest ?: return@LaunchedEffect
        CallsUi.showRequest.value = null
        val r = ReminderStore.items.value.firstOrNull { it.id == id } ?: return@LaunchedEffect
        createdFilter = DateFilter()
        dueFilter = DateFilter()
        starredOnly = false
        val everything = ReminderStore.items.value
        val t = when {
            r.done && r.review -> CallsUi.TO_REVIEW
            r.done -> CallsUi.DONE
            else -> CallsUi.TO_CALL
        }
        tab = t
        highlightId = id
        // The order the list draws its items in: header, chips, then each block's header and rows.
        val keys = mutableListOf<Any>("header", "tabs")
        val nowMs = System.currentTimeMillis()
        val blocks: List<List<Reminder>> = when (t) {
            CallsUi.TO_REVIEW -> listOf(everything.filter { it.done && it.review }.sortedByDescending { it.doneAt })
            CallsUi.DONE -> byDay(everything.filter { it.done && !it.review }.sortedByDescending { it.doneAt }) { it.doneAt }.map { it.second }
            else -> {
                val p = everything.filter { !it.done }.sortedBy { it.dueAt }
                listOf(p.filter { it.dueAt <= nowMs }) + byDay(p.filter { it.dueAt > nowMs }) { it.dueAt }.map { it.second }
            }
        }
        blocks.filter { it.isNotEmpty() }.forEach { b -> keys += "group"; b.forEach { keys += it.id } }
        val index = keys.indexOf(id)
        if (index >= 0) {
            delay(150L)
            listState.animateScrollToItem(index)
        }
        delay(3_000L)
        if (highlightId == id) highlightId = null
    }

    val pending = items.filter { !it.done }.sortedBy { it.dueAt }
    val toReview = items.filter { it.done && it.review }.sortedByDescending { it.doneAt }
    val finished = items.filter { it.done && !it.review }.sortedByDescending { it.doneAt }
    val overdue = pending.filter { it.dueAt <= now }
    val ahead = pending.filter { it.dueAt > now }

    // Done and Snooze from a swipe or an open row, each with an Undo bar.
    fun done(r: Reminder) {
        val before = r
        ReminderStore.markDone(r.id)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Done · ${r.headline()}") { ReminderStore.restore(before) }
    }
    fun checked(r: Reminder) {
        val before = r
        ReminderStore.reviewed(r.id)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Checked · ${r.headline()}") { ReminderStore.restore(before) }
    }
    fun snoozeTo(r: Reminder, ms: Long, logAs: String = if (r.done) "Moved to" else "Snoozed to") {
        val before = r
        ReminderStore.reschedule(r.id, ms, logAs)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Moved to ${Fmt.dueLabel(ms)}") { ReminderStore.restore(before) }
    }
    fun deleteNow(r: Reminder) {
        val before = r
        ReminderStore.delete(r.id)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Deleted · ${r.headline()}") { ReminderStore.restore(before) }
    }
    fun moveBack(r: Reminder) {
        val before = r
        ReminderStore.reopen(r.id)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Moved back · ${r.headline()}") { ReminderStore.restore(before) }
    }

    val row: @Composable (Reminder, RowPos, CallMode) -> Unit = { r, pos, mode ->
        CallRow(
            r = r,
            pos = pos,
            mode = mode,
            now = now,
            expanded = openId == r.id,
            onToggle = { openId = if (openId == r.id) null else r.id },
            onCall = {
                // Calling a task that is already due, or one waiting in To review: the after-call
                // popup asks how it went.
                if (mode == CallMode.REVIEW || (mode == CallMode.PENDING && r.dueAt <= now)) FollowUp.mark(ctx, r)
                dial(ctx, r.number)
            },
            onDone = { if (mode == CallMode.REVIEW) checked(r) else done(r) },
            onSnooze = { snoozeFor = r },
            onPickTime = { rescheduleFor = r },
            onAddTask = { addTaskFor = r },
            onEdit = { editFor = r },
            onTextInstead = { textInsteadFor = r },
            onHistory = { historyFor = r },
            onDelete = { deleteNow(r) },
            onStar = { ReminderStore.setStar(r.id, !r.starred) },
            onMoveBack = { moveBack(r) },
            highlighted = r.id == highlightId,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New reminder") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Call-backs",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val summary = when {
                            overdue.isNotEmpty() -> "${overdue.size} overdue"
                            pending.isEmpty() -> "All caught up"
                            else -> "${pending.size} waiting"
                        }
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (overdue.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TopActions()
                }
            }

            item(key = "tabs") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = tab == CallsUi.TO_CALL,
                            onClick = { tab = CallsUi.TO_CALL },
                            label = { Text("To call (${pending.size})") }
                        )
                        FilterChip(
                            selected = tab == CallsUi.OVERDUE,
                            onClick = { tab = CallsUi.OVERDUE },
                            label = { Text("Overdue (${overdue.size})") }
                        )
                        FilterChip(
                            selected = tab == CallsUi.TO_REVIEW,
                            onClick = { tab = CallsUi.TO_REVIEW },
                            label = { Text("To review (${toReview.size})") }
                        )
                        FilterChip(
                            selected = tab == CallsUi.DONE,
                            onClick = { tab = CallsUi.DONE },
                            label = { Text("Done (${finished.size})") }
                        )
                    }
                    CreatedDueFilterRow(
                        created = createdFilter,
                        due = dueFilter,
                        onCreated = { createdFilter = it },
                        onDue = { dueFilter = it },
                        starredOnly = starredOnly,
                        onStarred = { starredOnly = it },
                    )
                }
            }

            when (tab) {
                CallsUi.TO_CALL, CallsUi.OVERDUE -> {
                    val shown = if (tab == CallsUi.OVERDUE) overdue else pending
                    if (shown.isEmpty()) {
                        item(key = "empty") {
                            when {
                                filtering -> NoMatches()
                                tab == CallsUi.OVERDUE -> EmptyText("Nothing overdue. Well done!")
                                else -> EmptyState()
                            }
                        }
                    }
                    callBlock("overdue", "Overdue", "${overdue.size}", errorColor, overdue) { r, pos ->
                        row(r, pos, CallMode.PENDING)
                    }
                    if (tab == CallsUi.TO_CALL) {
                        byDay(ahead) { it.dueAt }.forEach { (day, list) ->
                            val isToday = day == today
                            callBlock(
                                "day-$day",
                                RowFmt.dayTitle(day),
                                if (isToday) "${list.size} left" else "${list.size}",
                                if (isToday) primaryColor else mutedColor,
                                list
                            ) { r, pos -> row(r, pos, CallMode.PENDING) }
                        }
                    } else if (overdue.isNotEmpty()) {
                        item(key = "overdue-end") { EmptyText("That's all that's overdue.") }
                    }
                }
                CallsUi.TO_REVIEW -> {
                    if (toReview.isEmpty()) {
                        item(key = "empty-review") {
                            EmptyText(
                                if (filtering) "No call-backs to review match these filters."
                                else "Tasks you mark \"Done, review it\" after a follow-up call wait here for your check."
                            )
                        }
                    }
                    callBlock("review", "Waiting for you", "${toReview.size}", errorColor, toReview) { r, pos ->
                        row(r, pos, CallMode.REVIEW)
                    }
                }
                else -> {
                    if (finished.isEmpty()) {
                        item(key = "empty-done") {
                            EmptyText(
                                if (filtering) "No finished call-backs match these filters."
                                else "Finished call-backs will show up here."
                            )
                        }
                    }
                    byDay(finished) { it.doneAt }.forEach { (day, list) ->
                        callBlock("done-$day", RowFmt.dayTitle(day), "${list.size} done", mutedColor, list) { r, pos ->
                            row(r, pos, CallMode.DONE)
                        }
                    }
                }
            }
        }
    }

    snoozeFor?.let { r ->
        SnoozeSheet(
            heading = if (r.done) "New time" else "Snooze",
            about = r.headline(),
            onPick = { ms -> snoozeFor = null; snoozeTo(r, ms) },
            onPickTime = { snoozeFor = null; pickTimeFor = r },
            onDismiss = { snoozeFor = null },
            plusRow = !r.done
        )
    }

    addTaskFor?.let { r -> AddTaskDialog(r = r, onDismiss = { addTaskFor = null }) }

    if (showAdd) {
        AddReminderDialog(
            onDismiss = { showAdd = false },
            onSave = { name, number, note, dueAt, repeat, starred ->
                ReminderStore.add(name, number, note, dueAt, repeat, starred = starred)
                showAdd = false
            }
        )
    }

    editFor?.let { r ->
        AddReminderDialog(
            title = "Edit reminder",
            initialName = r.name,
            initialNumber = r.number,
            initialNote = r.note,
            initialDue = r.dueAt,
            initialRepeat = r.repeat,
            initialStarred = r.starred,
            onDismiss = { editFor = null },
            onSave = { name, number, note, dueAt, repeat, starred ->
                ReminderStore.edit(r.id, name, number, note, dueAt, repeat, starred)
                editFor = null
            }
        )
    }

    // New time: the time picker (one tap sets it), Custom date & time for anything else.
    rescheduleFor?.let { r ->
        SnoozeSheet(
            heading = "New time",
            about = r.headline(),
            onPick = { ms -> rescheduleFor = null; snoozeTo(r, ms, "Moved to") },
            onPickTime = { rescheduleFor = null; pickTimeFor = r },
            onDismiss = { rescheduleFor = null }
        )
    }

    pickTimeFor?.let { r ->
        DateTimePickerFlow(
            initialMs = maxOf(r.dueAt, now),
            onPicked = { ms ->
                pickTimeFor = null
                snoozeTo(r, ms, "Moved to")
            },
            onDismiss = { pickTimeFor = null }
        )
    }

    historyFor?.let { r ->
        // Read the live copy so new lines show while the window is open.
        CallHistoryDialog(r = all.firstOrNull { it.id == r.id } ?: r, onDismiss = { historyFor = null })
    }

    textInsteadFor?.let { r ->
        SmsReminderDialog(
            prefill = TextPrefill(
                name = r.name,
                number = r.number,
                message = r.note,
                heading = "Send as a text instead",
                info = "The call reminder and its history move into this text.",
            ),
            onDismiss = { textInsteadFor = null },
            onSave = { name, number, message, dueAt, repeat, burst, tracked ->
                val live = ReminderStore.get(r.id) ?: r
                SmsStore.add(name, number, message, dueAt, repeat, burst, tracked, history = live.historyForText())
                ReminderStore.delete(r.id)
                textInsteadFor = null
                Toast.makeText(ctx, "Moved to Texts · ${Fmt.dueLabel(dueAt)}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** A day block: its header, then the rows sitting together in one white block. */
private fun LazyListScope.callBlock(
    key: String,
    title: String,
    count: String,
    color: Color,
    list: List<Reminder>,
    row: @Composable (Reminder, RowPos) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "group-$key") { DayHeader(title, count, color) }
    itemsIndexed(list, key = { _, r -> r.id }) { i, r ->
        Box(Modifier.animateItem()) { row(r, rowPos(i, list.size)) }
    }
}

@Composable
private fun CallRow(
    r: Reminder,
    pos: RowPos,
    mode: CallMode,
    now: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onPickTime: () -> Unit,
    onAddTask: () -> Unit,
    onEdit: () -> Unit,
    onTextInstead: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
    onStar: () -> Unit,
    onMoveBack: () -> Unit,
    highlighted: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val noTask = r.note.isBlank()
    val overdue = mode == CallMode.PENDING && r.dueAt <= now
    val hasNumber = r.number.isNotBlank()

    val chips = buildList {
        when (mode) {
            CallMode.PENDING -> {
                if (overdue) add(RowChip(RowFmt.late(r.dueAt, now), ChipTone.RED))
                r.repeatLabel?.let { add(RowChip(it, ChipTone.MUTED, Icons.Rounded.Repeat)) }
            }
            CallMode.REVIEW -> add(
                if (noTask) RowChip("No task written", ChipTone.ORANGE, Icons.Rounded.Warning)
                else RowChip("Done, review it", ChipTone.GREEN)
            )
            CallMode.DONE -> {}
        }
        if (r.delays.isNotEmpty() && mode != CallMode.DONE) {
            add(RowChip("Delayed ${r.delays.size}×", ChipTone.ORANGE, Icons.Rounded.History, onHistory))
        }
        if (noTask && mode == CallMode.PENDING) add(RowChip("Add task", ChipTone.ORANGE, Icons.Rounded.Add, onAddTask))
    }

    val quote = if (mode == CallMode.REVIEW) {
        r.delays.lastOrNull()?.reason?.takeIf { it.isNotBlank() }?.let { "Why not done: “$it”" }
    } else null

    val (timeTop, timeSub) = when (mode) {
        CallMode.PENDING -> Fmt.timeOnly(r.dueAt) to RowFmt.timeSub(r.dueAt, now)
        CallMode.REVIEW -> "Done" to RowFmt.doneSub(r.doneAt)
        CallMode.DONE -> "✓ ${Fmt.timeOnly(r.doneAt)}" to null
    }

    val historyLabel = r.historyLines().size.let { if (it > 0) "History ($it)" else "History" }

    SwipeActions(
        enabled = !expanded && mode != CallMode.DONE,
        shape = rowShape(pos),
        doneLabel = if (mode == CallMode.REVIEW) "Mark done" else "Done",
        snoozeLabel = if (mode == CallMode.REVIEW) "New time" else "Snooze",
        onDone = onDone,
        onSnooze = onSnooze,
    ) {
        TaskRow(
            pos = pos,
            timeTop = timeTop,
            timeSub = timeSub,
            timeRed = overdue,
            title = r.headline(),
            titleStyle = when {
                mode == CallMode.DONE -> TitleStyle.DONE
                noTask -> TitleStyle.NO_TASK
                else -> TitleStyle.NORMAL
            },
            person = r.title,
            personDetail = r.personDetail(),
            chips = chips,
            expanded = expanded,
            onClick = onToggle,
            highlighted = highlighted,
            quote = quote,
            action = {
                if (mode == CallMode.DONE) {
                    RoundAction(Icons.Rounded.Replay, "Call again", cs.secondaryContainer, cs.onSecondaryContainer, hasNumber, onCall)
                } else {
                    // The ☆ sits just above the call button.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StarButton(r.starred, onStar)
                        RoundAction(Icons.Rounded.Call, "Call", cs.primary, cs.onPrimary, hasNumber, onCall)
                    }
                }
            },
            expandedContent = {
                when (mode) {
                    CallMode.PENDING -> RowActions(
                        actions = listOfNotNull(
                            RowAction("Call", Icons.Rounded.Call, ActionStyle.FILLED, onCall).takeIf { hasNumber },
                            RowAction("Snooze", Icons.Rounded.Snooze, ActionStyle.OUTLINED, onSnooze),
                            RowAction("New time", Icons.Rounded.Schedule, ActionStyle.OUTLINED, onPickTime),
                            RowAction("Done", Icons.Rounded.Check, ActionStyle.GREEN, onDone),
                        ),
                        links = listOfNotNull(
                            RowLink("Add task", onClick = onAddTask).takeIf { noTask },
                            RowLink("Edit", onClick = onEdit),
                            RowLink("Text instead", onClick = onTextInstead),
                            RowLink(historyLabel, onClick = onHistory),
                            RowLink("Stop repeating") { ReminderStore.stopRepeating(r.id) }.takeIf { r.repeat != null },
                            RowLink("Delete", danger = true, onClick = onDelete),
                        )
                    )
                    CallMode.REVIEW -> RowActions(
                        actions = listOfNotNull(
                            RowAction("Add task", Icons.Rounded.Edit, ActionStyle.ORANGE, onAddTask).takeIf { noTask },
                            RowAction("Call now", Icons.Rounded.Call, ActionStyle.FILLED, onCall).takeIf { hasNumber },
                            RowAction("New time", Icons.Rounded.Schedule, ActionStyle.OUTLINED, onSnooze),
                            RowAction("Mark done", Icons.Rounded.Check, ActionStyle.GREEN, onDone),
                        ),
                        links = listOf(
                            RowLink("Not done, move back", onClick = onMoveBack),
                            RowLink("Edit", onClick = onEdit),
                            RowLink(historyLabel, onClick = onHistory),
                            RowLink("Delete", danger = true, onClick = onDelete),
                        )
                    )
                    CallMode.DONE -> RowActions(
                        actions = listOfNotNull(
                            RowAction("Not done, move back", Icons.AutoMirrored.Rounded.Undo, ActionStyle.ORANGE, onMoveBack),
                            RowAction("Call again", Icons.Rounded.Replay, ActionStyle.FILLED, onCall).takeIf { hasNumber },
                            RowAction("Reschedule", Icons.Rounded.Schedule, ActionStyle.OUTLINED, onPickTime),
                        ),
                        links = listOf(
                            RowLink(historyLabel, onClick = onHistory),
                            RowLink("Delete", danger = true, onClick = onDelete),
                        )
                    )
                }
            }
        )
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

/** Everything that happened to a task, newest first: created, rang, snoozed, called, moved and why, done. */
@Composable
private fun CallHistoryDialog(r: Reminder, onDismiss: () -> Unit) {
    val lines = r.historyLines()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History · ${r.title}") },
        text = {
            if (lines.isEmpty()) {
                Text("Nothing yet. From now on, every ring, snooze, call and move is listed here.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (r.note.isNotBlank()) {
                        item {
                            Text(
                                r.note,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    items(lines) { e ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                e.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (e.what.contains("didn't pick up")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                Fmt.dueLabel(e.at),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (e.note.isNotBlank()) {
                                Text(
                                    "“${e.note}”",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun NoMatches() {
    Text(
        "No call-backs match these filters.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "No call-backs waiting",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "After your next call, a popup will let you set a follow-up in one tap.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

