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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which list My tasks shows; a notification can ask for one. */
object MyTasksUi {
    const val ONE_TIME = 0
    const val DOSSIER = 1
    val tabRequest = MutableStateFlow<Int?>(null)
}

private val Teal = Color(0xFF0FA68E)
private val Amber = Color(0xFFB36B00)
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Refreshes "overdue" and "was due" labels while a screen is open. */
@Composable
private fun rememberNow(resumeKey: Int): Long {
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

/** The My tasks tab: One-time tasks of your own, and the Dossier you tick every day. */
@Composable
fun MyTasksScreen(resumeKey: Int) {
    val ctx = LocalContext.current
    val tasks by MyTaskStore.items.collectAsState()
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    var sub by rememberSaveable { mutableIntStateOf(MyTasksUi.ONE_TIME) }
    var chip by rememberSaveable { mutableIntStateOf(0) } // 0 To do, 1 Overdue, 2 Done
    val now = rememberNow(resumeKey)

    val tabRequest by MyTasksUi.tabRequest.collectAsState()
    LaunchedEffect(tabRequest) {
        val t = tabRequest ?: return@LaunchedEffect
        MyTasksUi.tabRequest.value = null
        sub = t
    }

    var newKind by remember { mutableStateOf<Int?>(null) }
    var editTask by remember { mutableStateOf<MyTask?>(null) }
    var editDossier by remember { mutableStateOf<DossierTask?>(null) }
    var deleteTask by remember { mutableStateOf<MyTask?>(null) }
    var deleteDossier by remember { mutableStateOf<DossierTask?>(null) }
    var whyFor by remember { mutableStateOf<DossierTask?>(null) }
    var historyFor by remember { mutableStateOf<DossierTask?>(null) }

    val day = Dossier.openDay(now)
    val todays = dossier.filter { it.isOn(day) }.sortedBy { it.minute }
    val notToday = dossier.filter { !it.isOn(day) }.sortedBy { it.minute }
    val doneToday = todays.count { records[DossierStore.key(it.id, day)]?.done == true }

    val pending = tasks.filter { !it.done }.sortedBy { it.dueAt }
    val overdue = pending.filter { it.dueAt <= now }
    val finished = tasks.filter { it.done }.sortedByDescending { it.doneAt }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newKind = sub },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Task") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("My tasks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                overdue.isNotEmpty() -> "${overdue.size} overdue"
                                pending.isEmpty() && todays.isEmpty() -> "Nothing waiting"
                                else -> "${pending.size} to do · dossier $doneToday of ${todays.size} done"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (overdue.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TopActions()
                }
            }

            item(key = "sub") {
                KindTabs(
                    selected = sub,
                    oneTimeLabel = "One-time · ${pending.size}",
                    dossierLabel = "Dossier · $doneToday/${todays.size}",
                    onSelect = { sub = it }
                )
            }

            if (sub == MyTasksUi.ONE_TIME) {
                item(key = "chips") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = chip == 0, onClick = { chip = 0 }, label = { Text("To do (${pending.size})") })
                        FilterChip(selected = chip == 1, onClick = { chip = 1 }, label = { Text("Overdue (${overdue.size})") })
                        FilterChip(selected = chip == 2, onClick = { chip = 2 }, label = { Text("Done (${finished.size})") })
                    }
                }
                val shown = when (chip) {
                    1 -> overdue
                    2 -> finished
                    else -> pending
                }
                if (shown.isEmpty()) {
                    item(key = "empty-one") {
                        HintText(
                            when (chip) {
                                1 -> "Nothing overdue. Well done!"
                                2 -> "Tasks you tick will show here."
                                else -> "Your own to-dos, apart from follow-ups. Tap ＋ Task to add one. " +
                                    "Add a person to get Call and Text buttons on it."
                            }
                        )
                    }
                }
                items(shown, key = { "t${it.id}" }) { t ->
                    MyTaskCard(
                        t = t,
                        now = now,
                        onToggle = { MyTaskStore.setDone(t.id, !t.done) },
                        onEdit = { editTask = t },
                        onDelete = { deleteTask = t },
                    )
                }
            } else {
                item(key = "progress") { DossierProgress(day, now, doneToday, todays.size) }
                if (dossier.isEmpty()) {
                    item(key = "empty-dossier") {
                        HintText(
                            "The tasks you do on set days, like checking the cash book every morning. " +
                                "Tick each one when it's done, or say why not. Tap ＋ Task to add one."
                        )
                    }
                }
                items(todays, key = { "d${it.id}" }) { t ->
                    DossierRow(
                        t = t,
                        day = day,
                        rec = records[DossierStore.key(t.id, day)],
                        now = now,
                        showScore = true,
                        onTick = { tickOrUntick(ctx, t, day, records[DossierStore.key(t.id, day)]) },
                        onWhy = { whyFor = t },
                        onOpen = { historyFor = t },
                        onEdit = { editDossier = t },
                        onDelete = { deleteDossier = t },
                    )
                }
                if (notToday.isNotEmpty()) {
                    item(key = "not-today") {
                        Text(
                            "Not today · ${notToday.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(notToday, key = { "n${it.id}" }) { t ->
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
                            onDelete = { deleteDossier = t },
                        )
                    }
                }
            }
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
            )
        }
    }
    editTask?.let { t -> MyTaskDialog(existing = t, onDismiss = { editTask = null }, onSwitchKind = null) }
    editDossier?.let { t -> DossierTaskDialog(existing = t, onDismiss = { editDossier = null }, onSwitchKind = null) }

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

    deleteTask?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTask = null },
            title = { Text("Delete task?") },
            text = { Text("Remove “${t.title}”.") },
            confirmButton = {
                TextButton(onClick = {
                    MyTaskStore.delete(t.id)
                    deleteTask = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTask = null }) { Text("Cancel") } }
        )
    }
    deleteDossier?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteDossier = null },
            title = { Text("Delete dossier task?") },
            text = { Text("Remove “${t.title}” and its History. To keep the History, edit the task instead.") },
            confirmButton = {
                TextButton(onClick = {
                    DossierStore.delete(t.id)
                    deleteDossier = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteDossier = null }) { Text("Cancel") } }
        )
    }
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
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(4.dp)) {
            listOf(MyTasksUi.ONE_TIME to oneTimeLabel, MyTasksUi.DOSSIER to dossierLabel).forEach { (kind, label) ->
                val on = kind == selected
                Surface(
                    onClick = { onSelect(kind) },
                    shape = RoundedCornerShape(11.dp),
                    color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (on) 1.dp else 0.dp,
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** The round tick box. */
@Composable
private fun TickCircle(done: Boolean, missed: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = when {
            done -> Teal
            missed -> MaterialTheme.colorScheme.errorContainer
            else -> Color.Transparent
        },
        border = if (done || missed) null else BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Rounded.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(18.dp))
            if (missed) Icon(Icons.Rounded.Close, contentDescription = "Missed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

// ---------------------------------------------------------------- One-time

@Composable
private fun MyTaskCard(t: MyTask, now: Long, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val overdue = !t.done && t.dueAt <= now
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp)) { TickCircle(done = t.done, onClick = onToggle) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    if (t.note.isNotBlank()) {
                        Text(t.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                t.done -> "Done ${Fmt.dueLabel(t.doneAt)}"
                                overdue -> "${Fmt.dueLabel(t.dueAt)} · overdue"
                                else -> Fmt.dueLabel(t.dueAt)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(
                            text = { Text(if (t.done) "Not done yet" else "Mark done") },
                            onClick = { menuOpen = false; onToggle() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
            if (t.hasPerson) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(text = t.personTitle, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.personTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (t.name.isNotBlank()) {
                                Text(t.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        FilledTonalIconButton(onClick = { dial(ctx, t.number) }) {
                            Icon(Icons.Rounded.Call, contentDescription = "Call ${t.personTitle}")
                        }
                        FilledTonalIconButton(onClick = { openTextApp(ctx, t.number) }) {
                            Icon(Icons.Rounded.Sms, contentDescription = "Text ${t.personTitle}")
                        }
                    }
                }
            }
        }
    }
}

/** Opens your Messages app on a new text to [number]. */
private fun openTextApp(ctx: android.content.Context, number: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number.trim())))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "No messages app found", Toast.LENGTH_SHORT).show()
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                }
                content()
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

/** New one-time task, or Edit. [onSwitchKind] (new tasks only) flips to a dossier task. */
@Composable
private fun MyTaskDialog(existing: MyTask?, onDismiss: () -> Unit, onSwitchKind: (() -> Unit)?) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var due by remember {
        mutableLongStateOf(
            existing?.dueAt ?: run {
                val zone = ZoneId.systemDefault()
                java.time.ZonedDateTime.now(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0)
                    .toInstant().toEpochMilli()
            }
        )
    }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var number by remember { mutableStateOf(existing?.number.orEmpty()) }
    var addPerson by remember { mutableStateOf(false) }
    var showDateTime by remember { mutableStateOf(false) }

    TaskWindow(
        title = if (existing == null) "New task" else "Edit task",
        onDismiss = onDismiss,
        saveLabel = "Save task",
        canSave = title.isNotBlank(),
        onSave = {
            if (existing == null) MyTaskStore.add(title, note, due, name, number)
            else MyTaskStore.edit(existing.id, title, note, due, name, number)
            onDismiss()
        }
    ) {
        if (onSwitchKind != null) {
            KindTabs(MyTasksUi.ONE_TIME, "One-time", "Dossier (repeat)") { if (it == MyTasksUi.DOSSIER) onSwitchKind() }
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Task") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Note (optional)") },
            maxLines = 3,
            shape = RoundedCornerShape(14.dp)
        )
        OutlinedButton(
            onClick = { showDateTime = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(Fmt.dueLabel(due), fontWeight = FontWeight.SemiBold)
                Text("Tap to change the date or time", style = MaterialTheme.typography.bodySmall)
            }
        }
        when {
            number.isNotBlank() -> ContactCard(name = name, number = number, onClear = { name = ""; number = "" })
            addPerson -> PersonPicker(prompt = "Who is this task with?") { n, num ->
                name = n
                number = num
                addPerson = false
            }
            else -> OutlinedButton(
                onClick = { addPerson = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add person (optional)")
            }
        }
        Text(
            "A person adds Call and Text buttons to the task.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDateTime) {
        DateTimePickerFlow(
            initialMs = due,
            onPicked = { ms -> due = ms; showDateTime = false },
            onDismiss = { showDateTime = false }
        )
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    val done = rec?.done == true
    val wasDue = today && !done && t.dueAt(day) <= now
    Card(
        onClick = onOpen,
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
                    t.timeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (wasDue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = if (onEdit == null) 10.dp else 0.dp)
                )
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reason by remember { mutableStateOf(rec?.reason.orEmpty()) }
    val due = t.dueAt(day) <= System.currentTimeMillis()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun DossierTaskDialog(existing: DossierTask?, onDismiss: () -> Unit, onSwitchKind: (() -> Unit)?) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var monthly by remember { mutableStateOf((existing?.monthDay ?: 0) > 0) }
    var days by remember { mutableIntStateOf(existing?.days ?: 0b0111111) }
    var monthDay by remember { mutableIntStateOf(existing?.monthDay?.takeIf { it > 0 } ?: LocalDate.now().dayOfMonth) }
    var minute by remember { mutableStateOf(existing?.minute) }
    var remind by remember { mutableStateOf((existing?.remindBefore ?: 0) > 0) }
    var remindMin by remember { mutableIntStateOf(existing?.remindBefore?.takeIf { it > 0 } ?: 15) }
    var showClock by remember { mutableStateOf(false) }

    TaskWindow(
        title = if (existing == null) "New task" else "Edit dossier task",
        onDismiss = onDismiss,
        saveLabel = "Save dossier task",
        canSave = title.isNotBlank() && minute != null && (monthly || days != 0),
        onSave = {
            minute?.let { m ->
                val d = if (monthly) Dossier.ALL_DAYS else days
                val md = if (monthly) monthDay else 0
                val rb = if (remind) remindMin else 0
                if (existing == null) DossierStore.add(title, note, m, d, md, rb)
                else DossierStore.edit(existing.id, title, note, m, d, md, rb)
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
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Note (optional)") },
            maxLines = 2,
            shape = RoundedCornerShape(14.dp)
        )

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

        OutlinedButton(
            onClick = { showClock = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val m = minute
                if (m == null) {
                    Text("Pick a time (required)", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(Dossier.timeLabel(m), fontWeight = FontWeight.SemiBold)
                }
                Text("The Done notification comes at this time", style = MaterialTheme.typography.bodySmall)
            }
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
                minute = t.hour * 60 + t.minute
                showClock = false
            },
            onDismiss = { showClock = false }
        )
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "head") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(
                                "${t.frequency} · ${t.timeLabel}",
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

    val day = Dossier.openDay(now)
    val todays = dossier.filter { it.isOn(day) }.sortedBy { it.minute }
    val left = todays.count { records[DossierStore.key(it.id, day)]?.done != true }
    val callsToReview = calls.count { it.done && it.review }
    val callsOverdue = calls.count { !it.done && it.dueAt <= now }
    val textsToReview = texts.count { !it.done && it.review != null }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
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
}
