package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Requests from outside the Calls tab (the review reminder) for which chip to show. */
object CallsUi {
    const val TO_CALL = 0
    const val OVERDUE = 1
    const val TO_REVIEW = 2
    const val DONE = 3

    val tabRequest = MutableStateFlow<Int?>(null)
}

/** The Calls tab: every call-back, grouped by when it's due, with Created and Due filters. */
@Composable
fun CallsScreen(resumeKey: Int) {
    val ctx = LocalContext.current
    val all by ReminderStore.items.collectAsState()
    var tab by rememberSaveable { mutableStateOf(CallsUi.TO_CALL) }
    var historyFor by remember { mutableStateOf<Reminder?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var rescheduleFor by remember { mutableStateOf<Reminder?>(null) }
    var deleteFor by remember { mutableStateOf<Reminder?>(null) }
    var textInsteadFor by remember { mutableStateOf<Reminder?>(null) }
    var editFor by remember { mutableStateOf<Reminder?>(null) }
    var createdFilter by ListFilters.callsCreated
    var dueFilter by ListFilters.callsDue
    val items = all.filter { createdFilter.matches(it.createdAt) && dueFilter.matches(it.dueAt) }
    val filtering = createdFilter.active || dueFilter.active

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

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    val pending = items.filter { !it.done }.sortedBy { it.dueAt }
    val toReview = items.filter { it.done && it.review }.sortedByDescending { it.doneAt }
    val finished = items.filter { it.done && !it.review }.sortedByDescending { it.doneAt }
    val overdue = pending.filter { it.dueAt <= now }
    val laterToday = pending.filter { it.dueAt > now && dayOf(it.dueAt) == today }
    val upcoming = pending.filter { it.dueAt > now && dayOf(it.dueAt) != today }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
                    val groups = if (tab == CallsUi.OVERDUE) {
                        listOf(Triple("Overdue", overdue, errorColor))
                    } else {
                        listOf(
                            Triple("Overdue", overdue, errorColor),
                            Triple("Later today", laterToday, primaryColor),
                            Triple("Upcoming", upcoming, mutedColor),
                        )
                    }
                    groups.forEach { (title, list, color) ->
                        reminderGroup(title, list, color) { r ->
                            ReminderCard(r, now, done = false, ctx = ctx,
                                onDone = { ReminderStore.markDone(r.id) },
                                onSnooze = { ms -> ReminderStore.reschedule(r.id, ms, "Snoozed to") },
                                onPickTime = { rescheduleFor = r },
                                onDelete = { deleteFor = r },
                                onHistory = { historyFor = r },
                                onTextInstead = { textInsteadFor = r },
                                onEdit = { editFor = r })
                        }
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
                    items(toReview, key = { it.id }) { r ->
                        Box(Modifier.animateItem()) {
                            ReminderCard(r, now, done = true, ctx = ctx,
                                onDone = { ReminderStore.reviewed(r.id) },
                                onSnooze = {},
                                onPickTime = { rescheduleFor = r },
                                onDelete = { deleteFor = r },
                                onHistory = { historyFor = r },
                                review = true)
                        }
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
                    items(finished, key = { it.id }) { r ->
                        Box(Modifier.animateItem()) {
                            ReminderCard(r, now, done = true, ctx = ctx,
                                onDone = {},
                                onSnooze = {},
                                onPickTime = { rescheduleFor = r },
                                onDelete = { deleteFor = r },
                                onHistory = { historyFor = r })
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddReminderDialog(
            onDismiss = { showAdd = false },
            onSave = { name, number, note, dueAt, repeat ->
                ReminderStore.add(name, number, note, dueAt, repeat)
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
            onDismiss = { editFor = null },
            onSave = { name, number, note, dueAt, repeat ->
                ReminderStore.edit(r.id, name, number, note, dueAt, repeat)
                editFor = null
            }
        )
    }

    rescheduleFor?.let { r ->
        DateTimePickerFlow(
            initialMs = maxOf(r.dueAt, now),
            onPicked = { ms ->
                ReminderStore.reschedule(r.id, ms)
                rescheduleFor = null
            },
            onDismiss = { rescheduleFor = null }
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

    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete reminder?") },
            text = { Text("Remove the call-back for ${r.title}.") },
            confirmButton = {
                TextButton(onClick = {
                    ReminderStore.delete(r.id)
                    deleteFor = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

private fun LazyListScope.reminderGroup(
    title: String,
    list: List<Reminder>,
    color: Color,
    card: @Composable (Reminder) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "group-$title") {
        Text(
            text = "$title · ${list.size}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    items(list, key = { it.id }) { r -> Box(Modifier.animateItem()) { card(r) } }
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

@Composable
private fun ReminderCard(
    r: Reminder,
    now: Long,
    done: Boolean,
    ctx: android.content.Context,
    onDone: () -> Unit,
    onSnooze: (Long) -> Unit,
    onPickTime: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onTextInstead: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
    review: Boolean = false,
) {
    val isOverdue = !done && r.dueAt <= now
    var menuOpen by remember { mutableStateOf(false) }
    var snoozeOpen by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(text = r.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (r.name.isNotBlank() && r.number.isNotBlank()) {
                        Text(
                            r.number,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = { menuOpen = false; onEdit() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (done) "Reschedule" else "Change time") },
                            onClick = { menuOpen = false; onPickTime() }
                        )
                        if (r.repeat != null && !done) {
                            DropdownMenuItem(
                                text = { Text("Stop repeating") },
                                onClick = { menuOpen = false; ReminderStore.stopRepeating(r.id) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                val text = if (review) "Done, to review · ${Fmt.dueLabel(r.doneAt)}"
                else if (done) "Done · ${Fmt.dueLabel(r.doneAt)}"
                else "${Fmt.dueLabel(r.dueAt)} · ${Fmt.relative(r.dueAt, now)}"
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isOverdue) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            val repeatLabel = r.repeatLabel
            if (repeatLabel != null && !done) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        repeatLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (r.delays.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.clickable(onClick = onHistory),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(6.dp))
                    val times = if (r.delays.size == 1) "once" else "${r.delays.size} times"
                    Text(
                        "Delayed $times · see why",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (r.note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        r.note,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        // Calling a task that is already due, or one waiting in To review: the after-call
                        // popup asks how it went.
                        if (isOverdue || review) FollowUp.mark(ctx, r)
                        dial(ctx, r.number)
                    },
                    enabled = r.number.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (review) "Call now" else if (done) "Call again" else "Call", maxLines = 1)
                }
                if (review) {
                    OutlinedButton(
                        onClick = onPickTime,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New time", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mark done", maxLines = 1)
                    }
                }
                if (!done) {
                    Column(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { snoozeOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Snooze")
                        }
                        DropdownMenu(expanded = snoozeOpen, onDismissRequest = { snoozeOpen = false }) {
                            QuickTimes.options(ctx).forEach { o ->
                                DropdownMenuItem(
                                    text = { Text("${o.title} · ${o.subtitle}") },
                                    onClick = { snoozeOpen = false; onSnooze(o.whenMs) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Pick date & time…") },
                                onClick = { snoozeOpen = false; onPickTime() }
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = onDone,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Done")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onHistory,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("History", maxLines = 1)
                }
                if (!done) {
                    OutlinedButton(
                        onClick = onTextInstead,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Rounded.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Text instead", maxLines = 1)
                    }
                }
            }
        }
    }
}
