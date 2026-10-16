package com.callbackreminder.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

private val NoTimeBg = Color(0xFFFFF1D6)
private val NoTimeFg = Color(0xFF8A5300)

/** The list tabs under "Tasks": All, Unsorted, your lists, ＋ New list. [selected] -1 is All. */
@Composable
internal fun ListTabs(lists: List<TaskList>, selected: Long, onSelect: (Long) -> Unit, onNewList: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListTab("All", selected == ALL_LISTS) { onSelect(ALL_LISTS) }
        lists.forEach { l -> ListTab(l.name, selected == l.id) { onSelect(l.id) } }
        TextButton(onClick = onNewList) { Text("＋ New list", fontWeight = FontWeight.SemiBold) }
    }
}

const val ALL_LISTS = -1L

@Composable
private fun ListTab(text: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = if (on) cs.primary else cs.onSurfaceVariant,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
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
    val (bg, fg, border) = when (tone) {
        CHIP_RED -> Triple(cs.errorContainer, cs.error, null)
        CHIP_ORANGE -> Triple(NoTimeBg, NoTimeFg, null)
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
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

const val CHIP_PLAIN = 0
const val CHIP_RED = 1
const val CHIP_ORANGE = 2

/**
 * One task, Google Tasks style: tick circle, bold task, note, the list's name (All tab), date chip
 * (red when overdue) or an orange "No time yet", the person, and ★ on the right. [handle] shows ⠿ for
 * "My order" dragging.
 */
@Composable
internal fun GTaskRow(
    t: MyTask,
    now: Long,
    listName: String?,
    highlighted: Boolean = false,
    handle: Modifier? = null,
    onTick: () -> Unit,
    onStar: () -> Unit,
    onOpen: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val overdue = !t.done && t.timed && t.dueAt <= now
    var personMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.border(BorderStroke(2.dp, cs.primary), RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 1.dp)) { TickCircle(done = t.done, onClick = onTick) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (t.done) cs.onSurfaceVariant else if (highlighted) cs.primary else cs.onSurface,
                textDecoration = if (t.done) TextDecoration.LineThrough else null
            )
            if (t.note.isNotBlank() && !t.done) {
                Text(t.note, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (listName != null) {
                Text(listName, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (!t.done) {
                Row(
                    Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (t.timed) TaskChip(Fmt.dueLabel(t.dueAt), if (overdue) CHIP_RED else CHIP_PLAIN)
                    else TaskChip("⏱ No time yet", CHIP_ORANGE)
                    if (t.hasPerson) {
                        Box {
                            TaskChip(
                                t.personTitle,
                                CHIP_PLAIN,
                                icon = { Icon(Icons.Rounded.Person, null, Modifier.size(14.dp).padding(end = 2.dp)) },
                                onClick = { personMenu = true }
                            )
                            DropdownMenu(expanded = personMenu, onDismissRequest = { personMenu = false }) {
                                DropdownMenuItem(text = { Text("Call ${t.personTitle}") }, onClick = { personMenu = false; dial(ctx, t.number) })
                                DropdownMenuItem(text = { Text("Text ${t.personTitle}") }, onClick = { personMenu = false; textApp(ctx, t.number) })
                            }
                        }
                    }
                }
            } else {
                Text("Done ${Fmt.dueLabel(t.doneAt)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        if (handle != null) {
            Box(handle.padding(top = 4.dp, start = 4.dp, end = 2.dp)) {
                Icon(Icons.Rounded.DragIndicator, contentDescription = "Drag to move", tint = cs.outline, modifier = Modifier.size(22.dp))
            }
        }
        if (!t.done) StarButton(t.starred, onStar)
    }
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
                    Text(if (count == 0) "Delete list" else "Move them to Unsorted")
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

/** Reorder lists: ↑ ↓ for each of your lists (Unsorted always stays first). */
@Composable
internal fun ReorderListsDialog(lists: List<TaskList>, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf(lists.filter { it.id != MyTaskStore.UNSORTED }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder lists") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Unsorted always comes first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
) {
    val ctx = LocalContext.current
    val lists by MyTaskStore.lists.collectAsState()
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var showNote by remember { mutableStateOf(existing?.note?.isNotBlank() == true) }
    var due by remember { mutableLongStateOf(existing?.dueAt ?: 0L) }
    var showTime by remember { mutableStateOf(existing?.timed == true) }
    var picked by remember { mutableStateOf<Long?>(null) }
    var list by remember { mutableLongStateOf(existing?.listId ?: listId.takeIf { it >= 0 } ?: MyTaskStore.UNSORTED) }
    var starred by remember { mutableStateOf(existing?.starred ?: false) }
    var name by remember { mutableStateOf(existing?.name ?: personName) }
    var number by remember { mutableStateOf(existing?.number ?: personNumber) }
    var addPerson by remember { mutableStateOf(false) }
    var showDateTime by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var newList by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(existing?.remindBefore ?: emptyList()) }

    val start = remember { listOf(title, note, due, list, starred, name, number, before) }
    val cancel = rememberDiscardGuard(
        changed = { listOf(title, note, due, list, starred, name, number, before) != start },
        onClose = onDismiss,
    )
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && listOf(title, note, due, list, starred, name, number, before) != start) {
                cancel()
                false
            } else true
        }
    )
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (existing == null) {
            delay(250L)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    fun save() {
        val t = if (existing == null) {
            MyTaskStore.add(title, note, due, name, number, listId = list, starred = starred, remindBefore = before)
        } else {
            MyTaskStore.edit(existing.id, title, note, due, name, number, listId = list, starred = starred, remindBefore = before)
            MyTaskStore.get(existing.id) ?: return
        }
        onSaved?.invoke(existing, t)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = cancel, sheetState = sheetState) {
        Column(
            Modifier
                .padding(start = 20.dp, end = 12.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (existing != null) {
                Text("Edit task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("New task") },
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
                Text(
                    "WHEN? (optional)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.padding(end = 8.dp)) {
                    TimePickerPanel(selected = due.takeIf { it > 0L }) { ms -> due = ms; picked = ms }
                }
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (due > 0L) {
                        Text("📅 ${Fmt.dueLabel(due)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { due = 0L; picked = null }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "No time", modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Text("No time yet", color = NoTimeFg, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDateTime = true }) { Text("Date & time ›") }
                }
                if (due > 0L) {
                    // Extra heads-ups before the due time; tap to add or remove, as many as you like.
                    Text(
                        "REMIND ME BEFORE (optional, pick any)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
                    ContactCard(name = name, number = number, onClear = { name = ""; number = "" })
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
                                lists.firstOrNull { it.id == list }?.name ?: "Unsorted",
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
                voiceMic(title) { title = it }?.invoke()
                Spacer(Modifier.weight(1f))
                if (existing != null && onDelete != null) {
                    IconButton(onClick = { onDelete(existing); onDismiss() }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete task", tint = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(enabled = title.isNotBlank(), onClick = { save() }) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
            if (onSwitchKind != null && existing == null) {
                TextButton(onClick = onSwitchKind) { Text("Repeats on set days? Make it a Dossier task") }
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
    if (newList) {
        ListNameDialog(title = "New list", initial = "", onDismiss = { newList = false }) { n ->
            list = MyTaskStore.addList(n).id
            newList = false
        }
    }
}

/** Overdue, Today, This week, Later and No time yet, for the All tab. */
internal fun allGroups(tasks: List<MyTask>, now: Long): List<Pair<String, List<MyTask>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val endWeek = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
    val timed = tasks.filter { it.timed }.sortedBy { it.dueAt }
    return listOf(
        "Overdue" to timed.filter { it.dueAt <= now },
        "Today" to timed.filter { it.dueAt in (now + 1) until endToday },
        "This week" to timed.filter { it.dueAt in endToday until endWeek },
        "Later" to timed.filter { it.dueAt >= endWeek },
        "No time yet" to tasks.filter { !it.timed }.sortedBy { it.createdAt },
    ).filter { it.second.isNotEmpty() }
}

/** A list's open tasks in its sort: My order, or By date (untimed ones last). */
internal fun sortedForList(tasks: List<MyTask>, byDate: Boolean): List<MyTask> =
    if (byDate) tasks.filter { it.timed }.sortedBy { it.dueAt } + tasks.filter { !it.timed }.sortedBy { it.createdAt }
    else tasks.sortedWith(compareBy<MyTask> { it.position }.thenByDescending { it.createdAt })
