package com.callbackreminder.app

import androidx.compose.foundation.relocation.bringIntoViewRequester
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

internal val NoTimeBg: Color @Composable get() = Tones.amber.bg
internal val NoTimeFg: Color @Composable get() = Tones.amber.fg

/**
 * The list tabs under "Tasks": All, 📥 Assigned to me (in a team; a blue dot counts the new ones), your lists,
 * ＋ New list. Tasks in no list show only in All. [selected] -1 is All, -2 Assigned to me.
 */
@Composable
internal fun ListTabs(
    lists: List<TaskList>, selected: Long, onSelect: (Long) -> Unit, onNewList: () -> Unit,
    assigned: Boolean = false, assignedNew: Int = 0,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListTab("All", selected == ALL_LISTS) { onSelect(ALL_LISTS) }
        if (assigned) ListTab("Assigned to me", selected == ASSIGNED_LIST, dot = assignedNew) { onSelect(ASSIGNED_LIST) }
        lists.filter { it.id != MyTaskStore.UNSORTED }.forEach { l -> ListTab(l.name, selected == l.id) { onSelect(l.id) } }
        TextButton(onClick = onNewList) { Text("＋ New list", fontWeight = FontWeight.SemiBold) }
    }
}

const val ALL_LISTS = -1L
const val ASSIGNED_LIST = -2L

@Composable
private fun ListTab(text: String, on: Boolean, dot: Int = 0, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                color = if (on) cs.primary else cs.onSurfaceVariant,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            if (dot > 0) {
                Spacer(Modifier.width(4.dp))
                Surface(color = Tones.blue.fg, shape = RoundedCornerShape(50)) {
                    Text(
                        "$dot", color = Tones.blue.bg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            color = if (on) cs.primary else Color.Transparent,
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.height(3.dp).width(28.dp)
        ) {}
    }
}

/** A small rounded chip on a task row. */
@Composable
internal fun TaskChip(text: String, tone: Int, icon: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val red = chipColors(ChipTone.RED)
    val orange = chipColors(ChipTone.ORANGE)
    // The same chip as every other: red late, orange no time, outlined for the person and a plain time.
    val (bg, fg, border) = when (tone) {
        CHIP_RED -> Triple(red.first, red.second, BorderStroke(1.dp, red.first))
        CHIP_ORANGE -> Triple(orange.first, orange.second, BorderStroke(1.dp, orange.first))
        else -> Triple(Color.Transparent, cs.onSurface, BorderStroke(1.dp, cs.outlineVariant))
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        contentColor = fg,
        border = border,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            // On a narrow screen a long time goes on two lines ("Yesterday,\n7:22 PM") rather than being cut.
            FitText(text, 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = CHIP_LINE)
        }
    }
}

const val CHIP_PLAIN = 0
const val CHIP_RED = 1
const val CHIP_ORANGE = 2

/**
 * One task as THE card: tick, bold task, note, the list's name (All tab), the time box (red when overdue) or an
 * orange "No time yet", the person box, 📞 and WA when it has a person, and ★. No ⋮: tapping opens it in place
 * with 🕘 New time and Edit / Remove time / Move to Follow-ups / Delete. [handle] shows ⠿ for "My order".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MyTaskCard(
    t: MyTask,
    now: Long,
    listName: String?,
    highlighted: Boolean = false,
    handle: Modifier? = null,
    /** Just ticked: shown done (filled circle, struck through) while it plays out, before it leaves. */
    finishing: Boolean = false,
    /** Set when the card draws its own block (To schedule, Overdue); left null inside a [TaskGroup]. */
    pos: RowPos? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTick: () -> Unit,
    onStar: () -> Unit,
    onEdit: () -> Unit,
    onNewTime: () -> Unit,
    onRemoveTime: (() -> Unit)? = null,
    onMoveToFollowUps: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val overdue = !t.done && t.timed && t.dueAt <= now
    // A task from your manager (↘ chip, his deadline, Pick your date) and the 🔒 Private mark.
    val teamTasks by TeamRepo.tasks.collectAsState()
    val teamV by TeamSync.version.collectAsState()
    val inTeam = TeamRepo.activeNow
    val teamTask = remember(t.id, teamTasks, teamV) { if (inTeam) TeamUi.taskOfMyTask(ctx, t.id, teamTasks) else null }
    val isPriv = remember(t.id, teamV) { inTeam && TeamLinks.isPrivate(ctx, t.id) }
    val teamBanner = remember(teamTask, teamV) { teamTask?.let { TeamUi.banner(ctx, it, TeamRepo.uid) } }
    var history by remember { mutableStateOf(false) }
    // Outlined after "Show in My tasks ›" or search: say it is on screen and bring all of it into view.
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val bringTick by MyTasksUi.bring.collectAsState()
    if (highlighted) {
        LaunchedEffect(t.id, bringTick) {
            MyTasksUi.seen.value = t.id
            kotlinx.coroutines.delay(60L)
            runCatching { bring.bringIntoView() }
        }
    }
    Box(Modifier.bringIntoViewRequester(bring)) {
        TaskCard(
            title = t.title,
            done = t.done || finishing,
            onTick = onTick,
            time = when {
                t.done -> "Done " + Fmt.dueLabel(t.doneAt)
                t.timed -> Fmt.dueLabel(t.dueAt)
                else -> null
            },
            late = overdue,
            person = if (t.hasPerson) Names.first(t.personTitle) ?: t.personTitle else null,
            chips = listOfNotNull(Held.label(Held.countMine(ctx, t.id))?.takeIf { !t.done }?.let { RowChip(it, ChipTone.ORANGE) }) +
                (teamTask?.let { TeamUi.receiverChips(it) } ?: emptyList()) +
                // His deadline is set but not his own reminder: it waits in To schedule until he sets one.
                listOfNotNull(RowChip("⏰ Set reminder", ChipTone.ORANGE, onClick = onNewTime)
                    .takeIf { teamTask != null && teamTask.open && teamTask.due > 0L && !t.timed && !t.done }) +
                listOfNotNull(RowChip("🔒", ChipTone.MUTED).takeIf { isPriv && !t.done }) +
                // v0.47 the on-time mark on his own tasks (a given task has it in its team chips above).
                listOfNotNull(OnTime.chip(OnTime.label(t)).takeIf { inTeam && teamTask == null && !isPriv }),
            note = t.note.takeIf { !t.done },
            sub = listName,
            starred = t.starred,
            onStar = if (t.done) null else onStar,
            noTimeChip = teamTask == null,
            banner = teamBanner,
            urgent = teamTask?.let { it.urgent && it.open } == true,
            onCall = if (t.hasPerson && !t.done) ({ dial(ctx, t.number) }) else null,
            onWhatsApp = if (t.hasPerson && !t.done) ({
                WhatsApp.open(ctx, t.number, WhatsApp.message(ctx, "", t.title, if (t.timed) t.dueAt else 0L, t.name))
            }) else null,
            expanded = expanded,
            onClick = onToggle,
            highlighted = highlighted,
            handle = handle,
            pos = pos,
        ) {
            if (teamTask != null) {
                TeamReceiverPanel(
                    teamTask, myTaskId = t.id,
                    onDone = onTick,
                    onReminder = if (t.done) null else onNewTime,
                    onMove = onMoveToFollowUps,
                )
                return@TaskCard
            }
            CardPanel(
                details = listOfNotNull(
                    t.note.takeIf { it.isNotBlank() }?.let { "Note: $it" },
                    listOfNotNull(
                        "List ${MyTaskStore.listName(t.listId)}",
                        t.personTitle.takeIf { t.hasPerson }?.let { p -> if (t.name.isNotBlank()) "$p · ${t.number}" else p },
                    ).joinToString(" · "),
                ),
                onNewTime = if (t.done) null else onNewTime,
                buttonLabel = if (t.timed) "New time" else "Set time",
                // The same order on every card: Timeline · Edit · (Remove time · Move to … · Private) · Delete.
                links = buildList {
                    // ✓ Done, ➜ next part with you: the task's own team Timeline, with every part before.
                    add(RowLink("Timeline", onClick = { TeamNext.teamOfMine(ctx, t.id)?.let { TeamUi.openTimeline(it) } ?: run { history = true } }))
                    add(RowLink("Edit", onClick = onEdit))
                    if (!t.done && t.timed && onRemoveTime != null) add(RowLink("Remove time", onClick = onRemoveTime))
                    if (!t.done && onMoveToFollowUps != null) add(RowLink("Move to Follow-ups", onClick = onMoveToFollowUps))
                    if (inTeam && teamTask == null && !t.done) add(RowLink(if (isPriv) "🔒 Private: on" else "🔒 Private", onClick = {
                        TeamLinks.setPrivate(ctx, t.id, !isPriv)
                    }))
                    if (onDelete != null) add(RowLink("Delete", danger = true, onClick = onDelete))
                }
            )
        }
    }
    if (history) MyTaskHistoryDialog(MyTaskStore.get(t.id) ?: t) { history = false }
}

/** Opens your Messages app on a new text to [number]. */
internal fun textApp(ctx: android.content.Context, number: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number.trim())))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "No messages app found", Toast.LENGTH_SHORT).show()
    }
}

/** A white rounded block that holds task rows with thin lines between them. */
@Composable
internal fun TaskGroup(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) { Column { content() } }
}

/**
 * The tasks of one list in "My order": drag ⠿ up or down to move a task. [onDrop] gets the new
 * order, top to bottom.
 */
@Composable
internal fun ReorderableTasks(
    tasks: List<MyTask>,
    enabled: Boolean,
    onDrop: (List<Long>) -> Unit,
    row: @Composable (MyTask, Modifier?) -> Unit,
) {
    var order by remember(tasks) { mutableStateOf(tasks) }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Long, Int>() }
    Column {
        order.forEachIndexed { index, t ->
            key(t.id) {
                val dragging = dragId == t.id
                val handle = if (!enabled) null else Modifier.pointerInput(t.id) {
                    detectDragGestures(
                        onDragStart = { dragId = t.id; offset = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val i = order.indexOfFirst { it.id == t.id }
                            if (offset > 0 && i in 0 until order.lastIndex) {
                                val next = heights[order[i + 1].id] ?: 0
                                if (next > 0 && offset > next / 2f) {
                                    order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                    offset -= next
                                }
                            } else if (offset < 0 && i > 0) {
                                val prev = heights[order[i - 1].id] ?: 0
                                if (prev > 0 && -offset > prev / 2f) {
                                    order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                    offset += prev
                                }
                            }
                        },
                        onDragEnd = {
                            dragId = null
                            offset = 0f
                            onDrop(order.map { it.id })
                        },
                        onDragCancel = {
                            dragId = null
                            offset = 0f
                        },
                    )
                }
                Box(
                    Modifier
                        .onSizeChanged { heights[t.id] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) offset else 0f
                            shadowElevation = if (dragging) 8f else 0f
                        }
                ) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column {
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 52.dp))
                            row(t, handle)
                        }
                    }
                }
            }
        }
    }
}

/** A name for a new list, or a new name for one. */
@Composable
internal fun ListNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val cancel = rememberDiscardGuard(changed = { name != initial }, onClose = onDismiss)
    LaunchedEffect(Unit) {
        delay(150L)
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = cancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                singleLine = true,
                placeholder = { Text("e.g. Office, Vendors, Home") },
                trailingIcon = voiceMic(name) { name = it.take(30) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
    )
}

/** Delete a list: move its tasks to Unsorted, or delete them too. */
@Composable
internal fun DeleteListDialog(list: TaskList, count: Int, onDismiss: () -> Unit, onDelete: (deleteTasks: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${list.name}”?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (count == 0) "This list has no tasks."
                    else "This list has $count ${if (count == 1) "task" else "tasks"}. What should happen to them?"
                )
                Button(onClick = { onDelete(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (count == 0) "Delete list" else "Keep them (no list, in All)")
                }
                if (count > 0) {
                    OutlinedButton(
                        onClick = { onDelete(true) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete the tasks too") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Reorder lists: ↑ ↓ for each of your lists. */
@Composable
internal fun ReorderListsDialog(lists: List<TaskList>, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf(lists.filter { it.id != MyTaskStore.UNSORTED }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder lists") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                order.forEachIndexed { i, l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(enabled = i > 0, onClick = {
                            order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                        }) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up") }
                        IconButton(enabled = i < order.lastIndex, onClick = {
                            order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                        }) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down") }
                    }
                }
                if (order.isEmpty()) Text("Make a list with ＋ New list first.")
            }
        },
        confirmButton = {
            TextButton(onClick = { MyTaskStore.reorderLists(order.map { it.id }); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** "Completed (N)" folded at the bottom of a list; tap to open it. */
@Composable
internal fun CompletedHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Completed ($count)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
    }
}

/**
 * New task (quick add) or Edit, as a sheet from the bottom like Google Tasks. The keyboard opens at
 * once; the icons add a note, a time (your Quick times, optional), the list, ★, a person or voice.
 * [onSwitchKind] (new tasks only) offers a Dossier task instead. A new task can start with a person
 * already on it (from their person page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyTaskDialog(
    existing: MyTask?,
    onDismiss: () -> Unit,
    onSwitchKind: (() -> Unit)?,
    personName: String = "",
    personNumber: String = "",
    listId: Long = MyTaskStore.UNSORTED,
    onDelete: ((MyTask) -> Unit)? = null,
    onSaved: ((before: MyTask?, after: MyTask) -> Unit)? = null,
    onMoveToFollowUps: ((MyTask) -> Unit)? = null,
    /** A new task's time and length to start with (the Calendar's "＋ Add" in a free gap). */
    initialDue: Long = 0L,
    initialLength: Int = 0,
    /** A new task's text to start with (the widget's 🎤). */
    initialTitle: String = "",
    /** A new task's note to start with ("What's left?" of a next part). */
    initialNote: String = "",
    /** Opened in place (a person page, Home, the review session): "Show in My tasks ›", History and WA. */
    extras: TaskExtras? = null,
) {
    val ctx = LocalContext.current
    val lists by MyTaskStore.lists.collectAsState()
    var title by remember { mutableStateOf(existing?.title ?: initialTitle) }
    var note by remember { mutableStateOf(existing?.note ?: initialNote) }
    var showNote by remember { mutableStateOf((existing?.note ?: initialNote).isNotBlank()) }
    var due by remember { mutableLongStateOf(existing?.dueAt ?: initialDue) }
    // New tasks show the time rows at once, with no time and no length yet: both are chosen every time.
    var showTime by remember { mutableStateOf(existing == null || existing.timed || initialDue > 0L) }
    var length by remember { mutableIntStateOf(existing?.durationMin ?: initialLength) }
    var customLength by remember { mutableStateOf(false) }
    var pickAnother by remember { mutableStateOf(false) }
    // "Keep both" on the clash warning: remembered for that time and length only.
    var keptClash by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var picked by remember { mutableStateOf<Long?>(null) }
    var recent by remember { mutableStateOf(RecentPicks.load(ctx)) }
    val openedAt = remember { System.currentTimeMillis() }
    var list by remember { mutableLongStateOf(existing?.listId ?: listId.takeIf { it >= 0 } ?: MyTaskStore.UNSORTED) }
    var starred by remember { mutableStateOf(existing?.starred ?: false) }
    var name by remember { mutableStateOf(existing?.name ?: personName) }
    var number by remember { mutableStateOf(existing?.number ?: personNumber) }
    var addPerson by remember { mutableStateOf(false) }
    var showDateTime by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var newList by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(existing?.remindBefore ?: emptyList()) }

    val start = remember { listOf(title, note, due, list, starred, name, number, before, length) }
    val sheetScope = rememberCoroutineScope()
    val sheetRef = remember { arrayOfNulls<SheetState>(1) }
    val cancel = rememberDiscardGuard(
        changed = { listOf(title, note, due, list, starred, name, number, before, length) != start },
        onClose = onDismiss,
        onKeep = { sheetRef[0]?.let { s -> sheetScope.launch { s.show() } } },
    )
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && listOf(title, note, due, list, starred, name, number, before, length) != start) {
                cancel()
                false
            } else true
        }
    )
    sheetRef[0] = sheetState
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (existing == null) {
            delay(250L)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    // A time needs a length, so the Calendar books the real time; with no time the length can wait.
    val needLength = due > 0L && length <= 0

    fun pickTime(ms: Long) {
        RecentPicks.record(ctx, ms)
        recent = RecentPicks.load(ctx)
        due = ms
        picked = ms
    }

    fun save() {
        if (needLength) return
        val t = if (existing == null) {
            MyTaskStore.add(title, note, due, name, number, listId = list, starred = starred, remindBefore = before, durationMin = length)
                .also { if (due <= 0L) android.widget.Toast.makeText(ctx, "Saved · no time yet, in To schedule", android.widget.Toast.LENGTH_SHORT).show() }
        } else {
            MyTaskStore.edit(existing.id, title, note, due, name, number, listId = list, starred = starred, remindBefore = before, durationMin = length)
            MyTaskStore.get(existing.id) ?: return
        }
        onSaved?.invoke(existing, t)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = cancel, sheetState = sheetState) {
        Column(
            Modifier
                .padding(start = 20.dp, end = 12.dp, bottom = 16.dp + navBarMissing())
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (existing != null) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Edit task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (extras != null) {
                        TaskExtraButtons(extras)
                        extras.onShowInList?.let { go -> ShowInListLink(extras.listName) { onDismiss(); go() } }
                    }
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Task *") },
                placeholder = { Text("New task") },
                trailingIcon = voiceMic(title) { title = it },
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focus),
                shape = RoundedCornerShape(14.dp)
            )
            if (showNote) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    trailingIcon = voiceMic(note) { note = it },
                    placeholder = { Text("Add details") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    shape = RoundedCornerShape(14.dp)
                )
            }
            if (showTime) {
                // WHEN, IN (urgent ones), HOW LONG first, then the free slots for that length and Recent:
                // the same rows and headings as the review session's My task card.
                Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowLabel("WHEN")
                        Spacer(Modifier.width(6.dp))
                        if (due > 0L) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), RoundedCornerShape(9.dp))
                                    .padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🕒 " + Fmt.dueLabel(due), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(" ✕", fontSize = 13.sp, modifier = Modifier.clickable { due = 0L; picked = null; before = emptyList() }.padding(horizontal = 5.dp))
                            }
                        } else {
                            Text("No time", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    ChipHeading("IN")
                    PlusRow(compact = true) { pickTime(it) }
                    HowLongRow(length, onLength = { length = it })
                    if (needLength) {
                        Text("Pick how long to save it with a time", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NeedOrange)
                    }
                    FreeSlotsRow(length, existing?.id ?: -1L, due.takeIf { it > 0L }, onPick = { pickTime(it) }, compact = true)
                    ChipHeading("RECENT")
                    RecentChips(
                        recent, openedAt, due.takeIf { it > 0L }, compact = true,
                        base = existing?.dueAt?.takeIf { it > 0L },
                        busy = PickBusy(length, existing?.id ?: -1L), enabled = length > 0,
                        onMore = { pickAnother = true },
                    ) { pickTime(it) }
                }
                val clashes = remember(due, length, existing?.id) {
                    if (due > 0L && length > 0) CalendarData.clashes(ctx, due, length, existing?.id ?: -1L) else emptyList()
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = clashes.isNotEmpty() && keptClash != (due to length),
                    enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(260)) +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 6 },
                    exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200)) +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
                ) {
                    ClashWarning(
                        clashes = clashes,
                        onNextFree = {
                            CalendarData.nextFree(ctx, due, length, existing?.id ?: -1L)?.let { due = it; picked = it }
                        },
                        onPickAnother = { pickAnother = true },
                        onKeepBoth = { keptClash = due to length },
                    )
                }
                if (due > 0L) {
                    // Extra heads-ups before the due time; tap to add or remove, as many as you like.
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RowLabel("REMIND BEFORE")
                        listOf(10, 30, 60, 120, 180, 1440, 2880).forEach { m ->
                            val on = m in before
                            androidx.compose.material3.FilterChip(
                                selected = on,
                                onClick = { before = if (on) before - m else (before + m).sorted() },
                                label = { Text(TaskAlarms.beforeLabel(m)) },
                            )
                        }
                    }
                    if (before.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        val passed = before.count { due - it * 60_000L <= now }
                        Text(
                            "Heads-ups " + before.joinToString(", ") { TaskAlarms.beforeLabel(it) } + " before" +
                                (if (passed > 0) " · $passed already passed, skipped" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            when {
                number.isNotBlank() -> Box(Modifier.padding(end = 8.dp)) {
                    ContactCard(name = name, number = number, onClear = { name = ""; number = "" }, compact = true)
                }
                addPerson -> PersonPicker(prompt = "Who is this task with?") { n, num ->
                    name = n
                    number = num
                    addPerson = false
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showNote = !showNote }) {
                    Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = "Add details", tint = if (showNote) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showTime = !showTime }) {
                    Icon(Icons.Rounded.Schedule, contentDescription = "Add a time", tint = if (showTime || due > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    Surface(
                        onClick = { listMenu = true },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                lists.firstOrNull { it.id == list }?.name ?: "No list",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 90.dp)
                            )
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose list", modifier = Modifier.size(18.dp))
                        }
                    }
                    DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                        Text(
                            "MOVE TO LIST",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        lists.forEach { l ->
                            DropdownMenuItem(
                                text = { Text(l.name, color = if (l.id == list) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                trailingIcon = if (l.id == list) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                                onClick = { list = l.id; listMenu = false }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("＋ New list", color = MaterialTheme.colorScheme.primary) },
                            onClick = { listMenu = false; newList = true }
                        )
                    }
                }
                StarButton(starred) { starred = !starred }
                if (number.isBlank() && !addPerson) {
                    IconButton(onClick = { addPerson = true }) {
                        Icon(Icons.Rounded.Person, contentDescription = "Add person", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (existing != null && onDelete != null) {
                    IconButton(onClick = { onDelete(existing); onDismiss() }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete task", tint = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(enabled = title.isNotBlank() && !needLength, onClick = { save() }) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
            if (onSwitchKind != null && existing == null) {
                TextButton(onClick = onSwitchKind) { Text("Repeats on set days? Make it a Dossier task") }
            }
            if (existing != null && onMoveToFollowUps != null) {
                Box(Modifier.padding(end = 8.dp)) {
                    ArrowCard(
                        icon = { Icon(Icons.Rounded.Notifications, contentDescription = null, tint = Color(0xFFF0B850)) },
                        title = "Move to Follow-ups",
                        subtitle = "Opens New follow-up filled in",
                        onClick = { onDismiss(); onMoveToFollowUps(existing) },
                    )
                }
            }
        }
    }

    if (showDateTime) {
        DateTimePickerFlow(
            initialMs = if (due > 0L) due else QuickTime.OnDay(1, 10).toOption().whenMs,
            onPicked = { ms -> due = ms; picked = null; showDateTime = false },
            onDismiss = { showDateTime = false }
        )
    }
    if (pickAnother) {
        CompositionLocalProvider(LocalPickBusy provides PickBusy(length, existing?.id ?: -1L) { length = it }) {
            TimePickerDialog(
                title = if (due > 0L) "Pick another time" else "Pick time",
                selected = due.takeIf { it > 0L },
                onPick = { ms -> due = ms; picked = ms; recent = RecentPicks.load(ctx); pickAnother = false },
                onDismiss = { pickAnother = false },
            )
        }
    }
    if (customLength) {
        LengthDialog(initial = length, onDismiss = { customLength = false }) { length = it; customLength = false }
    }
    if (newList) {
        ListNameDialog(title = "New list", initial = "", onDismiss = { newList = false }) { n ->
            list = MyTaskStore.addList(n).id
            newList = false
        }
    }
}

/** Overdue, Today, This week, Later and No time yet, for the All tab. */
internal fun allGroups(tasks: List<MyTask>, now: Long): List<Pair<String, List<MyTask>>> {
    // Day blocks like Follow-ups: Overdue, Today, Tomorrow, then each date; tasks with no time last.
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val timed = tasks.filter { it.timed }.sortedBy { it.dueAt }
    val overdue = timed.filter { it.dueAt <= now }
    val days = timed.filter { it.dueAt > now }
        .groupBy { Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() }
        .toSortedMap()
        .map { (day, list) -> (if (day == today) "Today" else RowFmt.dayTitle(day)) to list }
    return (listOf("Overdue" to overdue) + days + listOf("No time yet" to tasks.filter { !it.timed }.sortedBy { it.createdAt }))
        .filter { it.second.isNotEmpty() }
}

/** A list's open tasks in its sort: My order, or By date (untimed ones last). */
internal fun sortedForList(tasks: List<MyTask>, byDate: Boolean): List<MyTask> =
    if (byDate) tasks.filter { it.timed }.sortedBy { it.dueAt } + tasks.filter { !it.timed }.sortedBy { it.createdAt }
    else tasks.sortedWith(compareBy<MyTask> { it.position }.thenByDescending { it.createdAt })

/** Like "15m", "1h", "1½h" or "2h 15m" for the How long? chips. */
internal fun lengthChip(m: Int): String = when {
    m < 60 -> "${m}m"
    m % 60 == 0 -> "${m / 60}h"
    m % 60 == 30 && m < 180 -> "${m / 60}½h"
    else -> "${m / 60}h ${m % 60}m"
}

/** The red warning under the time when your own items already book it, with the three ways out. */
@Composable
internal fun ClashWarning(clashes: List<CalBlock>, onNextFree: () -> Unit, onPickAnother: () -> Unit, onKeepBoth: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.errorContainer,
        contentColor = cs.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠ Clashes with", fontWeight = FontWeight.Bold)
            clashes.take(3).forEach { b ->
                Text(
                    "• ${b.title} · ${Fmt.timeOnly(b.start)} - ${Fmt.timeOnly(b.end)}" + when (b.kind) {
                        CalBlock.Kind.DOSSIER -> " (Dossier)"
                        CalBlock.Kind.GOOGLE -> " (Google)"
                        else -> ""
                    },
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (clashes.size > 3) Text("and ${clashes.size - 3} more", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = onNextFree, shape = RoundedCornerShape(10.dp)) { Text("Move to next free") }
                OutlinedButton(onClick = onPickAnother, shape = RoundedCornerShape(10.dp)) { Text("Pick another") }
                TextButton(onClick = onKeepBoth) { Text("Keep both") }
            }
        }
    }
}

/** ✎ on How long?: hours and minutes. */
@Composable
internal fun LengthDialog(initial: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    var hours by remember { mutableStateOf((initial / 60).toString()) }
    var mins by remember { mutableStateOf((initial % 60).toString()) }
    val total = (hours.toIntOrNull() ?: 0) * 60 + (mins.toIntOrNull() ?: 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How long?") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hours,
                    onValueChange = { v -> hours = v.filter { it.isDigit() }.take(2) },
                    label = { Text("Hours") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = mins,
                    onValueChange = { v -> mins = v.filter { it.isDigit() }.take(3) },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = { TextButton(enabled = total in 5..(24 * 60), onClick = { onPicked(total) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A small label in front of a row of chips in the packed My task window ("WHEN", "HOW LONG"). */
@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier.padding(end = 2.dp)
    )
}
