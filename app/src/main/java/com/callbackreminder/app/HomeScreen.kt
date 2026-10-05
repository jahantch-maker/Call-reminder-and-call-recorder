package com.callbackreminder.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(resumeKey: Int, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val items by ReminderStore.items.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var rescheduleFor by remember { mutableStateOf<Reminder?>(null) }
    var deleteFor by remember { mutableStateOf<Reminder?>(null) }
    val status = remember(resumeKey) { SetupStatus.read(ctx) }

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

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    val pending = items.filter { !it.done }.sortedBy { it.dueAt }
    val finished = items.filter { it.done }.sortedByDescending { it.doneAt }
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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                }
            }

            if (!status.requiredOk) {
                item(key = "setup") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Finish setup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${status.missingRequired} permission(s) still needed for the popup after each call.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenSettings) { Text("Set up now") }
                        }
                    }
                }
            }

            item(key = "tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        label = { Text("To call (${pending.size})") }
                    )
                    FilterChip(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        label = { Text("Done (${finished.size})") }
                    )
                }
            }

            if (tab == 0) {
                if (pending.isEmpty()) {
                    item(key = "empty") { EmptyState() }
                }
                reminderGroup("Overdue", overdue, errorColor) { r ->
                    ReminderCard(r, now, done = false, ctx = ctx,
                        onDone = { ReminderStore.markDone(r.id) },
                        onSnooze = { ms -> ReminderStore.reschedule(r.id, ms) },
                        onPickTime = { rescheduleFor = r },
                        onDelete = { deleteFor = r })
                }
                reminderGroup("Later today", laterToday, primaryColor) { r ->
                    ReminderCard(r, now, done = false, ctx = ctx,
                        onDone = { ReminderStore.markDone(r.id) },
                        onSnooze = { ms -> ReminderStore.reschedule(r.id, ms) },
                        onPickTime = { rescheduleFor = r },
                        onDelete = { deleteFor = r })
                }
                reminderGroup("Upcoming", upcoming, mutedColor) { r ->
                    ReminderCard(r, now, done = false, ctx = ctx,
                        onDone = { ReminderStore.markDone(r.id) },
                        onSnooze = { ms -> ReminderStore.reschedule(r.id, ms) },
                        onPickTime = { rescheduleFor = r },
                        onDelete = { deleteFor = r })
                }
            } else {
                if (finished.isEmpty()) {
                    item(key = "empty-done") {
                        Text(
                            "Finished call-backs will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                items(finished, key = { it.id }) { r ->
                    ReminderCard(r, now, done = true, ctx = ctx,
                        onDone = {},
                        onSnooze = {},
                        onPickTime = { rescheduleFor = r },
                        onDelete = { deleteFor = r })
                }
            }
        }
    }

    if (showAdd) {
        AddReminderDialog(
            onDismiss = { showAdd = false },
            onSave = { name, number, note, dueAt ->
                ReminderStore.add(name, number, note, dueAt)
                showAdd = false
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
    items(list, key = { it.id }) { r -> card(r) }
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
                        DropdownMenuItem(
                            text = { Text(if (done) "Reschedule" else "Change time") },
                            onClick = { menuOpen = false; onPickTime() }
                        )
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
                val text = if (done) "Done · ${Fmt.dueLabel(r.doneAt)}"
                else "${Fmt.dueLabel(r.dueAt)} · ${Fmt.relative(r.dueAt, now)}"
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isOverdue) FontWeight.SemiBold else FontWeight.Normal
                )
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
                    onClick = { dial(ctx, r.number) },
                    enabled = r.number.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (done) "Call again" else "Call")
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
                            quickOptions().take(5).forEach { o ->
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
        }
    }
}
