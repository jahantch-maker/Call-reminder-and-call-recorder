package com.callbackreminder.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Teal = Color(0xFF0FA68E)
private val Amber = Color(0xFFB36B00)

@Composable
private fun rememberTicker(resumeKey: Int): Long {
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

private fun kindIcon(k: WorkItem.Kind): ImageVector = when (k) {
    WorkItem.Kind.CALL -> Icons.Rounded.Call
    WorkItem.Kind.TEXT -> Icons.Rounded.Sms
    WorkItem.Kind.MY_TASK -> Icons.Rounded.TaskAlt
    WorkItem.Kind.DOSSIER -> Icons.Rounded.Checklist
}

@Composable
private fun kindColors(k: WorkItem.Kind): Pair<Color, Color> = when (k) {
    WorkItem.Kind.CALL -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    WorkItem.Kind.TEXT -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    WorkItem.Kind.MY_TASK -> Color(0xFFEDE4FF) to Color(0xFF3F1F8C)
    WorkItem.Kind.DOSSIER -> Color(0xFFD9F3EC) to Color(0xFF0B5A4C)
}

/** "Delayed 3×" in red or amber, or "On time" in green. */
@Composable
private fun DelayPill(delayed: Int) {
    val (bg, fg, text) = when {
        delayed >= 3 -> Triple(Color(0xFFFFE3E6), Color(0xFF9A1B2B), "Delayed $delayed×")
        delayed > 0 -> Triple(Color(0xFFFFF1D6), Amber, "Delayed $delayed×")
        else -> Triple(Color(0xFFD9F3EC), Color(0xFF0B5A4C), "On time")
    }
    Surface(shape = RoundedCornerShape(12.dp), color = bg) {
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun PersonRow(p: PersonSummary, now: Long, withWaiting: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(text = p.title, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Work.line(p, now, withWaiting),
                style = MaterialTheme.typography.bodySmall,
                color = if (p.overdue > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        DelayPill(p.delayed)
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- People

private enum class PeopleSort(val label: String) { PENDING("Most pending"), DELAYED("Most delayed"), WAITING("Longest waiting"), AZ("A-Z") }

/** Everyone with pending work, sorted and filtered. Tap a person for their page. */
@Composable
fun PeopleScreen(resumeKey: Int, onBack: () -> Unit, onOpenPerson: (String) -> Unit) {
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val now = rememberTicker(resumeKey)
    var sort by rememberSaveable { mutableStateOf(PeopleSort.PENDING) }
    var delayedOnly by rememberSaveable { mutableStateOf(false) }
    var overdueOnly by rememberSaveable { mutableStateOf(false) }
    var noReplyOnly by rememberSaveable { mutableStateOf(false) }

    val all = Work.people(now, calls, texts, myTasks)
    val shown = all
        .filter { (!delayedOnly || it.delayed > 0) && (!overdueOnly || it.overdue > 0) && (!noReplyOnly || it.noReply > 0) }
        .let { list ->
            when (sort) {
                PeopleSort.PENDING -> list.sortedWith(compareByDescending<PersonSummary> { it.pending }.thenByDescending { it.overdue })
                PeopleSort.DELAYED -> list.sortedByDescending { it.delayed }
                PeopleSort.WAITING -> list.sortedBy { if (it.oldestAt == 0L) Long.MAX_VALUE else it.oldestAt }
                PeopleSort.AZ -> list.sortedBy { it.title.lowercase() }
            }
        }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "head") { PageHeader("People", onBack) }
            item(key = "sort") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeopleSort.values().forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
                }
            }
            item(key = "filters") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = delayedOnly, onClick = { delayedOnly = !delayedOnly }, label = { Text("Delayed 1+") })
                    FilterChip(selected = overdueOnly, onClick = { overdueOnly = !overdueOnly }, label = { Text("Has overdue") })
                    FilterChip(selected = noReplyOnly, onClick = { noReplyOnly = !noReplyOnly }, label = { Text("No reply") })
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (all.isEmpty()) "No one has pending work. People show up here once a call, text or My task has their number."
                        else "No one matches these filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            items(shown, key = { it.key }) { p ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        PersonRow(p, now, withWaiting = true) { onOpenPerson(p.key) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Person page

/** One line in a person's History. */
private data class PersonEvent(val at: Long, val kind: WorkItem.Kind?, val title: String, val sub: String, val quote: String = "")

private val histFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.getDefault())

/**
 * One person: Call / Text / + Task, counts (pending, overdue, delayed, done), and their Pending, Done
 * and History (calls, texts, My tasks with them, recordings and their notes).
 */
@Composable
fun PersonScreen(resumeKey: Int, onBack: () -> Unit, onOpenItem: (WorkItem) -> Unit) {
    val ctx = LocalContext.current
    val key by PeopleUi.current
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val recordings by RecordingStore.items.collectAsState()
    val now = rememberTicker(resumeKey)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addMenu by remember { mutableStateOf(false) }
    var newCall by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf(false) }
    var dailyOn by remember(key) { mutableStateOf(DailyList.isOn(ctx, key.orEmpty())) }
    var preview by remember { mutableStateOf<String?>(null) }

    val k = key.orEmpty()
    fun mine(number: String) = k.isNotEmpty() && Work.personKey(number) == k
    val pc = calls.filter { mine(it.number) }
    val pt = texts.filter { mine(it.number) }
    val pm = myTasks.filter { it.hasPerson && mine(it.number) }
    val pr = recordings.filter { it.number.isNotBlank() && mine(it.number) }
    val person = Work.people(now, calls, texts, myTasks, all = true).firstOrNull { it.key == k }
    val name = person?.title ?: pr.firstOrNull()?.let { it.name.ifBlank { it.number } } ?: "Unknown"
    val number = person?.number ?: pr.firstOrNull()?.number.orEmpty()

    val pending = Work.open(now, pc, pt, pm, emptyList(), emptyMap(), includeDossier = false).sortedBy { it.at }
    val done: List<Triple<Long, WorkItem.Kind, String>> = (
        pc.filter { it.done && !it.review }.map { Triple(it.doneAt, WorkItem.Kind.CALL, it.note.ifBlank { "Call back" }) } +
            pt.filter { it.done }.map { Triple(it.doneAt, WorkItem.Kind.TEXT, (if (it.code.isNotEmpty()) "${it.code} " else "") + it.message) } +
            pm.filter { it.done }.map { Triple(it.doneAt, WorkItem.Kind.MY_TASK, it.title) }
        ).sortedByDescending { it.first }

    val history = remember(pc, pt, pm, pr) {
        val out = mutableListOf<PersonEvent>()
        pc.forEach { r ->
            r.historyLines().forEach { e -> out += PersonEvent(e.at, WorkItem.Kind.CALL, e.label, r.note.ifBlank { "Call back" }, e.note) }
        }
        pt.forEach { t ->
            t.log.forEach { e ->
                val title = when (e.status) {
                    SmsLogEntry.Status.CALL_EVENT -> e.detail
                    SmsLogEntry.Status.CALLED -> "Called · ${e.detail}"
                    SmsLogEntry.Status.REPLIED_DONE -> "Replied ${e.detail}"
                    else -> e.status.label
                }
                val quote = if (e.status == SmsLogEntry.Status.CALL_EVENT || e.status == SmsLogEntry.Status.CALLED ||
                    e.status == SmsLogEntry.Status.REPLIED_DONE) "" else e.detail
                out += PersonEvent(e.at, WorkItem.Kind.TEXT, title, (if (t.code.isNotEmpty()) "${t.code} · " else "") + t.message, quote)
            }
            out += PersonEvent(t.createdAt, WorkItem.Kind.TEXT, "Text created", t.message)
        }
        pm.forEach { m ->
            out += PersonEvent(m.createdAt, WorkItem.Kind.MY_TASK, "My task added: ${m.title}", m.note)
            if (m.done) out += PersonEvent(m.doneAt, WorkItem.Kind.MY_TASK, "My task done: ${m.title}", "")
        }
        pr.forEach { rec ->
            out += PersonEvent(rec.startedAt, null, "Recording · ${Fmt.duration(rec.durationSec)}", if (rec.whatsApp) "WhatsApp call" else "Phone call", rec.note)
        }
        out.sortedByDescending { it.at }.take(300)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "back") {
                Row(Modifier.padding(top = 8.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                }
            }
            item(key = "who") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(text = name, size = 64.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (number.isNotBlank() && number != name) {
                        Text(number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { dial(ctx, number) }, enabled = number.isNotBlank()) {
                            Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Call")
                        }
                        FilledTonalButton(onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "No messages app found", Toast.LENGTH_SHORT).show()
                            }
                        }, enabled = number.isNotBlank()) {
                            Icon(Icons.Rounded.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Text")
                        }
                        Box {
                            OutlinedButton(onClick = { addMenu = true }) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Task")
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                DropdownMenuItem(text = { Text("Call-back reminder") }, onClick = { addMenu = false; newCall = true })
                                DropdownMenuItem(text = { Text("Text message") }, onClick = { addMenu = false; newText = true })
                                DropdownMenuItem(text = { Text("My task with $name") }, onClick = { addMenu = false; newTask = true })
                            }
                        }
                    }
                }
            }
            item(key = "counts") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTile("${pending.size}", "Pending", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.weight(1f))
                    MiniTile("${pending.count { it.overdue }}", "Overdue", Color(0xFFFFE3E6), Color(0xFF9A1B2B), Modifier.weight(1f))
                    MiniTile("${person?.delayed ?: 0}×", "Delayed", Color(0xFFFFF1D6), Amber, Modifier.weight(1f))
                    MiniTile("${done.size}", "Done", Color(0xFFD9F3EC), Color(0xFF0B5A4C), Modifier.weight(1f))
                }
            }
            item(key = "daily") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Daily list", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    if (dailyOn) "Texts $name their Calls and Texts tasks at ${Dossier.timeLabel(DailyList.minute(ctx))}" +
                                        (DailyList.lastSent(ctx, k).takeIf { it > 0L }?.let { " · last sent ${Fmt.dueLabel(it)}" } ?: "")
                                    else "Off: $name gets no daily list",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = dailyOn, enabled = number.isNotBlank(), onCheckedChange = {
                                dailyOn = it
                                DailyList.setOn(ctx, k, it)
                            })
                        }
                        if (dailyOn) {
                            TextButton(onClick = {
                                preview = DailyList.build(ctx, k) ?: "Nothing to send: $name has no tasks for that day."
                            }) { Text("Preview the list") }
                        }
                    }
                }
            }
            item(key = "tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Pending ${pending.size}") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Done ${done.size}") })
                    FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("History") })
                }
            }
            when (tab) {
                0 -> {
                    if (pending.isEmpty()) item(key = "none-p") { Hint("Nothing pending with $name.") }
                    if (pending.isNotEmpty()) item(key = "pending") { WorkBlock(pending, now, showPerson = false, onOpen = onOpenItem) }
                }
                1 -> {
                    if (done.isEmpty()) item(key = "none-d") { Hint("Nothing done yet.") }
                    items(done.size) { i ->
                        val (at, kind, text) = done[i]
                        DoneLine(kind, text, at)
                    }
                }
                else -> {
                    if (history.isEmpty()) item(key = "none-h") { Hint("No history yet.") }
                    items(history.size) { i -> HistoryRow(history[i]) }
                }
            }
        }
    }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Daily list for $name") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
    }

    if (newCall) {
        AddReminderDialog(
            initialName = person?.name.orEmpty(),
            initialNumber = number,
            onDismiss = { newCall = false },
            onSave = { n, num, note, dueAt, repeat, starred ->
                ReminderStore.add(n, num, note, dueAt, repeat, starred = starred)
                newCall = false
            }
        )
    }
    if (newText) {
        SmsReminderDialog(
            prefill = TextPrefill(name = person?.name.orEmpty(), number = number, message = ""),
            onDismiss = { newText = false },
            onSave = { n, num, message, dueAt, repeat, burst, tracked ->
                SmsStore.add(n, num, message, dueAt, repeat, burst, tracked)
                newText = false
            }
        )
    }
    if (newTask) {
        MyTaskDialog(
            existing = null,
            onDismiss = { newTask = false },
            onSwitchKind = null,
            personName = person?.name.orEmpty(),
            personNumber = number,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp)
    )
}

@Composable
private fun MiniTile(value: String, label: String, bg: Color, fg: Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = bg, contentColor = fg, modifier = modifier) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DoneLine(kind: WorkItem.Kind, text: String, at: Long) {
    val (bg, fg) = kindColors(kind)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(kindIcon(kind), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Done ${Fmt.dueLabel(at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryRow(e: PersonEvent) {
    val zone = ZoneId.systemDefault()
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val (bg, fg) = e.kind?.let { kindColors(it) } ?: (MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.padding(top = 2.dp).size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(e.kind?.let { kindIcon(it) } ?: Icons.Rounded.Mic, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(java.time.Instant.ofEpochMilli(e.at).atZone(zone).format(histFmt), e.sub).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (e.quote.isNotBlank()) {
                Text("“${e.quote}”", style = MaterialTheme.typography.bodyMedium, color = Amber)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- Your day (morning summary)

/** The page the morning summary opens: today's counts, what's overdue, and what comes first. */
@Composable
fun MyDayScreen(resumeKey: Int, onBack: () -> Unit, onStart: () -> Unit, onOpenItem: (WorkItem) -> Unit) {
    val ctx = LocalContext.current
    // Recomputed whenever any list changes.
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val records by DossierStore.records.collectAsState()
    val now = rememberTicker(resumeKey)
    val day = remember(calls, texts, myTasks, records, now) { MorningSummary.compute(ctx, now) }
    val today = LocalDate.now()
    val hello = when {
        LocalTime.now().hour < 12 -> "Good morning"
        LocalTime.now().hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "head") { PageHeader("Your day", onBack) }
            item(key = "counts") {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("$hello · ${today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${day.total} ${if (day.total == 1) "thing" else "things"} today",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(day.calls to "Calls", day.texts to "Texts", day.myTasks to "My tasks", day.dossier to "Dossier").forEach { (n, l) ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.18f),
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$n", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                        Text(l, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (day.overdue.isNotEmpty()) {
                item(key = "overdue") {
                    Card(
                        onClick = onStart,
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${day.overdue.size} overdue from before",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            val names = day.overdue.take(2).joinToString(" · ") { w ->
                                val days = ((now - w.at) / 86_400_000L).toInt()
                                w.title + if (days >= 1) " ($days ${if (days == 1) "day" else "days"})" else ""
                            }
                            val more = day.overdue.size - 2
                            Text(
                                names + if (more > 0) " · $more more" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item(key = "first") {
                Text("First up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
            }
            val first = day.today.filter { it.at >= now - 3_600_000L }.take(6)
            if (first.isEmpty()) item(key = "none") { Hint("Nothing else due today.") }
            if (first.isNotEmpty()) item(key = "first-list") { WorkBlock(first, now, showPerson = true, onOpen = onOpenItem) }
            item(key = "buttons") {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).height(48.dp)) { Text("Later") }
                    Button(onClick = onStart, modifier = Modifier.weight(1f).height(48.dp)) { Text("Start my day") }
                }
            }
        }
    }
}
