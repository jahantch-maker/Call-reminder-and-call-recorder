package com.callbackreminder.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Scheduled text messages: sent from your own SIM at the time you set, once or on a repeat. */
@Composable
fun MessagesScreen(resumeKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by SmsStore.items.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<SmsReminder?>(null) }
    var rescheduleFor by remember { mutableStateOf<SmsReminder?>(null) }
    var deleteFor by remember { mutableStateOf<SmsReminder?>(null) }
    var sendNowFor by remember { mutableStateOf<SmsReminder?>(null) }
    var historyFor by remember { mutableStateOf<SmsReminder?>(null) }
    var showPermissionHelp by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val canSend = remember(resumeKey, refresh) { SmsSender.hasPermission(ctx) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }

    // On Android 15+ an app installed outside Play Store can't even show the SMS question until
    // "Allow restricted settings" is switched on, so a refusal leads to the step-by-step help.
    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refresh++
        if (!granted) showPermissionHelp = true
    }

    val pending = items.filter { !it.done }.sortedBy { it.alarmAt }
    val finished = items.filter { it.done }.sortedByDescending { it.doneAt }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New message") },
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column {
                        Text(
                            "Text messages",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (pending.isEmpty()) "Nothing scheduled"
                            else "${pending.size} scheduled · sent from your SIM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!canSend) {
                item(key = "permission") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Allow sending SMS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "So your messages go out on their own at the set time. Until then you'll get " +
                                    "a notification instead, and send it yourself from your Messages app.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { smsPermission.launch(Manifest.permission.SEND_SMS) }) {
                                    Text("Allow")
                                }
                                TextButton(onClick = { showPermissionHelp = true }) { Text("Not working?") }
                            }
                        }
                    }
                }
            }

            item(key = "tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        label = { Text("Scheduled (${pending.size})") }
                    )
                    FilterChip(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        label = { Text("Finished (${finished.size})") }
                    )
                }
            }

            val list = if (tab == 0) pending else finished
            if (list.isEmpty()) {
                item(key = "empty-$tab") { EmptyMessages(finishedTab = tab == 1) }
            }
            items(list, key = { it.id }) { r ->
                Box(Modifier.animateItem()) {
                    SmsCard(
                        r = r,
                        now = now,
                        onSendNow = { sendNowFor = r },
                        onHistory = { historyFor = r },
                        onEdit = { editFor = r },
                        onPickTime = { rescheduleFor = r },
                        onDelete = { deleteFor = r },
                    )
                }
            }
        }
    }

    if (showAdd) {
        SmsReminderDialog(
            onDismiss = { showAdd = false },
            onSave = { name, number, message, dueAt, repeat ->
                SmsStore.add(name, number, message, dueAt, repeat)
                showAdd = false
            }
        )
    }

    editFor?.let { r ->
        SmsReminderDialog(
            initial = r,
            onDismiss = { editFor = null },
            onSave = { name, number, message, dueAt, repeat ->
                SmsStore.edit(r.id, name, number, message, dueAt, repeat)
                editFor = null
            }
        )
    }

    rescheduleFor?.let { r ->
        DateTimePickerFlow(
            initialMs = maxOf(r.dueAt, now + 60_000L),
            onPicked = { ms ->
                SmsStore.reschedule(r.id, ms)
                rescheduleFor = null
            },
            onDismiss = { rescheduleFor = null }
        )
    }

    sendNowFor?.let { r ->
        AlertDialog(
            onDismissRequest = { sendNowFor = null },
            title = { Text("Send now?") },
            text = {
                Text(
                    if (r.repeat != null) "Sends the message to ${r.title} now. It counts as the one due " +
                        "${Fmt.dueLabel(r.dueAt)}, and the next one follows the repeat."
                    else "Sends the message to ${r.title} now instead of ${Fmt.dueLabel(r.dueAt)}."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sendNowFor = null
                    if (SmsSender.InFlight.busy(ctx, r.id, System.currentTimeMillis())) {
                        Toast.makeText(ctx, "Already sending this message", Toast.LENGTH_SHORT).show()
                    } else {
                        SmsSender.sendDue(ctx, r.id, manual = true)
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sendNowFor = null }) { Text("Cancel") } }
        )
    }

    historyFor?.let { r ->
        // Read the live copy so new results show while the window is open.
        val live = items.firstOrNull { it.id == r.id } ?: r
        HistoryDialog(live, onDismiss = { historyFor = null })
    }

    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete message?") },
            text = { Text("Stop sending this message to ${r.title}.") },
            confirmButton = {
                TextButton(onClick = {
                    SmsStore.delete(r.id)
                    deleteFor = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }

    if (showPermissionHelp) {
        AlertDialog(
            onDismissRequest = { showPermissionHelp = false },
            title = { Text("Turn on SMS for this app") },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT >= 33) {
                        "Android locks SMS for apps installed outside Play Store. One time only:\n\n" +
                            "1. Tap Open app info below.\n" +
                            "2. Tap ⋮ (top right) › Allow restricted settings, and confirm.\n" +
                            "3. Tap Permissions › SMS › Allow.\n\n" +
                            "On Samsung the ⋮ menu may say More. Then come back here."
                    } else {
                        "Tap Open app info below, then Permissions › SMS › Allow. Then come back here."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionHelp = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text("Open app info") }
            },
            dismissButton = { TextButton(onClick = { showPermissionHelp = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun EmptyMessages(finishedTab: Boolean) {
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
                Icons.Rounded.Sms,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (finishedTab) "Nothing finished yet" else "No messages scheduled",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (finishedTab) "One-time messages that went out show up here."
            else "Schedule a text to someone, once or on a repeat, like a weekly \"please wash the solar panels\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SmsCard(
    r: SmsReminder,
    now: Long,
    onSendNow: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onPickTime: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

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
                    if (r.name.isNotBlank()) {
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
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(
                            text = { Text(if (r.done) "Send again later" else "Change time") },
                            onClick = { menuOpen = false; onPickTime() }
                        )
                        if (r.repeat != null && !r.done) {
                            DropdownMenuItem(
                                text = { Text("Stop repeating") },
                                onClick = { menuOpen = false; SmsStore.stopRepeating(r.id) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    r.message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))
            if (!r.done) {
                val retrying = r.retryAt > 0L
                InfoRow(
                    icon = Icons.Rounded.Schedule,
                    text = if (retrying) "Retrying ${Fmt.dueLabel(r.retryAt)} (try ${r.attempts + 1} of ${SmsSender.MAX_RETRIES + 1})"
                    else "${Fmt.dueLabel(r.dueAt)} · ${Fmt.relative(r.dueAt, now)}",
                    error = retrying
                )
                r.repeatLabel?.let { label ->
                    Spacer(Modifier.height(4.dp))
                    InfoRow(icon = Icons.Rounded.Repeat, text = label)
                }
            }
            r.lastLog?.let { last ->
                Spacer(Modifier.height(4.dp))
                val bad = last.status == SmsLogEntry.Status.FAILED || last.status == SmsLogEntry.Status.RETRYING
                InfoRow(
                    icon = if (bad) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                    text = "Last: ${last.status.label} · ${Fmt.dueLabel(last.at)}",
                    error = bad
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onHistory,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("History")
                }
                if (!r.done) {
                    FilledTonalButton(
                        onClick = onSendNow,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send now")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Every time this message was due: sent, delivered, failed, skipped. Newest first. */
@Composable
private fun HistoryDialog(r: SmsReminder, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History · ${r.title}") },
        text = {
            if (r.log.isEmpty()) {
                Text("Nothing sent yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(r.log.reversed()) { e ->
                        val bad = e.status == SmsLogEntry.Status.FAILED || e.status == SmsLogEntry.Status.RETRYING
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                e.status.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                listOf(Fmt.dueLabel(e.at), e.detail).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
