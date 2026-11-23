package com.callbackreminder.app

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalTime

// ---------------------------------------------------------------- Notifications

/** Off · Silent · Sound for each kind of notification, grouped like the app. Some are locked on. */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Bumped on every change so the rows re-read their level.
    var tick by remember { mutableIntStateOf(0) }

    SettingsPage(title = "Notifications", onBack = onBack) {
        Text(
            "Silent ones still show in the notification shade, just without sound or vibration.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        // v0.44: one switch per category, the same names the notifications show next to "Callback Reminder".
        SectionCard(title = "Each category", subtitle = "Silence one, keep the rest") {
            NotifKind.values().forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                val level = tick.let { Notify.kindLevel(ctx, k) }
                Column(Modifier.fillMaxWidth()) {
                    Text("${k.emoji} ${k.label}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotifLevel.values().forEach { l ->
                            FilterChip(selected = level == l, onClick = { Notify.setKindLevel(ctx, k, l); tick++ }, label = { Text(l.label) })
                        }
                    }
                }
            }
        }
        NotifType.values().groupBy { it.group }.forEach { (group, types) ->
            SectionCard(title = group, subtitle = "") {
                types.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    NotifRow(t, tick.let { Notify.level(ctx, t) }) { l ->
                        Notify.setLevel(ctx, t, l)
                        tick++
                    }
                }
            }
        }
    }
}

@Composable
private fun NotifRow(t: NotifType, level: NotifLevel, onLevel: (NotifLevel) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (t.locked != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${level.label} · ${t.locked}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotifLevel.values().forEach { l ->
                    FilterChip(selected = level == l, onClick = { onLevel(l) }, label = { Text(l.label) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Shared: days of the week

@Composable
private fun DaysRow(mask: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.values().forEach { day ->
            val bit = 1 shl (day.value - 1)
            val selected = mask and bit != 0
            Surface(
                onClick = { onChange(mask xor bit) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) { Text(day.name.take(1), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------------------------------------------------------- Daily list

/** Send times (the same for everyone) for people whose Daily list switch is on (on their person page). */
@Composable
fun DailyListSettingsScreen(onBack: () -> Unit, onOpenPeople: () -> Unit) {
    val ctx = LocalContext.current
    var times by remember { mutableStateOf(DailyList.times(ctx)) }
    var days by remember { mutableStateOf(DailyList.days(ctx)) }
    // -1 = adding a new time; otherwise the index being changed.
    var editIndex by remember { mutableStateOf<Int?>(null) }
    val count = remember { DailyList.people(ctx).size }

    fun saveTimes(list: List<Int>) {
        times = list.distinct().sorted()
        DailyList.setTimes(ctx, times)
    }

    SettingsPage(title = "Daily list", onBack = onBack) {
        SectionCard(
            title = "Who gets it",
            subtitle = "Switch it on for a person on their page (Home › People › tap a name) and pick SMS, WhatsApp or both. " +
                "They get all their open follow-ups (calls and texts), never your My tasks or dossier."
        ) {
            Text(
                if (count == 0) "Nobody yet." else "$count ${if (count == 1) "person" else "people"} switched on.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpenPeople, modifier = Modifier.fillMaxWidth()) { Text("Open People") }
        }
        var round by remember { mutableStateOf(DailyList.smsRound(ctx)) }
        if (round.keys.isNotEmpty()) {
            SectionCard(
                title = "Last SMS round · ${Fmt.dueLabel(round.at)}",
                subtitle = "Android lets an app send about 30 texts in 30 minutes, so long rounds go out a little at a time. " +
                    "If Android asks to allow many messages, tick \"Always allow\"."
            ) {
                Text(
                    "${round.sent.size} sent · ${round.waiting} waiting · ${round.failed.size} failed",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (round.failed.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (round.failed.isNotEmpty()) {
                    Text(
                        "Failed: " + round.failed.joinToString(", ") { DailyList.nameFor(ctx, it) },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { DailyList.retryFailed(ctx); round = DailyList.smsRound(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retry failed") }
                }
            }
        }
        SectionCard(
            title = "Send times",
            subtitle = "The same times for everyone. Each list has all their open tasks with the task number, " +
                "late ones first. SMS ones go by themselves; WhatsApp ones wait in a notification for you to send."
        ) {
            times.forEachIndexed { i, m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Dossier.timeLabel(m),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { editIndex = i }) { Text("Change") }
                    if (times.size > 1) TextButton(onClick = { saveTimes(times.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
                }
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = { editIndex = -1 }, modifier = Modifier.fillMaxWidth()) { Text("+ Add time") }
        }
        SectionCard(title = "Days", subtitle = "Sends on ${ReviewReminder.daysLabel(days)}. Nothing is sent to someone with no open tasks.") {
            DaysRow(days) {
                days = it
                DailyList.setDays(ctx, it)
            }
        }
    }

    editIndex?.let { idx ->
        val start = times.getOrNull(idx) ?: (12 * 60)
        ClockDialog(
            initial = LocalTime.of(start / 60, start % 60),
            is24h = DateFormat.is24HourFormat(ctx),
            onPicked = { t ->
                val m = t.hour * 60 + t.minute
                saveTimes(if (idx < 0) times + m else times.mapIndexed { j, old -> if (j == idx) m else old })
                editIndex = null
            },
            onDismiss = { editIndex = null }
        )
    }
}

// ---------------------------------------------------------------- Backup

/** The nightly backup folder, Back up now, Share, and Restore. */
@Composable
fun BackupSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var restoreList by remember { mutableStateOf<List<Backup.Item>?>(null) }
    var confirm by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val inTeam = TeamRepo.me.collectAsState().value?.active == true
    var cloudNights by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }
    var cloudPick by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudMsg by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Backup.setFolder(ctx, uri)
            val error = Backup.backUpNow(ctx)
            Toast.makeText(ctx, error ?: "Folder saved. First backup done.", Toast.LENGTH_LONG).show()
            tick++
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirm = uri
    }

    val folder = remember(tick) { Backup.folder(ctx) }
    val last = remember(tick) { Backup.lastAt(ctx) }
    val error = remember(tick) { Backup.lastError(ctx) }

    SettingsPage(title = "Backup", onBack = onBack) {
        SectionCard(
            title = "Nightly backup",
            subtitle = "Every night at 11 PM the app saves one file with your calls, texts, My tasks, dossier, history, " +
                "settings and recording notes. Recordings themselves are left out (too big). The newest ${Backup.KEEP} are kept."
        ) {
            Text(
                Backup.folderLabel(ctx),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when {
                    error.isNotEmpty() -> "Last try failed: $error"
                    folder == null && last > 0L -> "Last backup: ${Fmt.dueLabel(last)} · the last 7 nights are kept"
                    folder == null -> "On from the start: the last 7 nights are kept on this phone"
                    last > 0L -> "Last backup: ${Fmt.dueLabel(last)}"
                    else -> "No backup yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (folder == null) "Choose a folder" else "Change folder")
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                val e = Backup.backUpNow(ctx)
                Toast.makeText(ctx, e ?: "Backed up", Toast.LENGTH_SHORT).show()
                tick++
            }, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
            if (folder != null) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = {
                    val share = Backup.shareIntent(ctx)
                    if (share == null) Toast.makeText(ctx, "No backup yet", Toast.LENGTH_SHORT).show()
                    else ctx.startActivity(share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth()) { Text("Share the latest backup") }
            }
        }

        SectionCard(
            title = "Keep it off the phone",
            subtitle = "A backup on the same phone doesn't help if the phone is lost. Google Drive often can't be " +
                "picked as the folder itself, so use \"Share the latest backup\" now and then to save a copy to Drive, " +
                "or send it to yourself on WhatsApp or email."
        ) {}

        SectionCard(
            title = "Restore",
            subtitle = "Replaces everything in the app with a backup, then restarts the app. Recordings on the phone stay as they are."
        ) {
            FilledTonalButton(onClick = { restoreList = Backup.list(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (folder != null) "Restore from the backup folder" else "Restore from the backups on this phone")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { pickFile.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Restore from a file…")
            }
        }

        if (inTeam) SectionCard(
            title = "Cloud backup",
            subtitle = "Every night, while charging and online, a copy of your tasks, Dossier and settings is saved to your own space " +
                "in the cloud. The last 7 nights stay. Recordings are never saved there."
        ) {
            FilledTonalButton(onClick = {
                cloudBusy = true
                cloudMsg = null
                scope.launch {
                    val list = CloudBackup.available()
                    cloudBusy = false
                    if (list.isEmpty()) cloudMsg = "No cloud backup yet. The first one is made tonight while the phone is charging and online."
                    else cloudNights = list
                }
            }, enabled = !cloudBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (cloudBusy) "Looking…" else "Restore from the cloud backup")
            }
            cloudMsg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    cloudNights?.let { list ->
        AlertDialog(
            onDismissRequest = { cloudNights = null },
            title = { Text("Choose a cloud backup") },
            text = {
                Column {
                    list.forEach { (day, at) ->
                        TextButton(onClick = { cloudNights = null; cloudPick = day to at }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (day == CloudBackup.SIGN_OUT_DAY) "Saved when you signed out · " + Fmt.dueLabel(at) else Fmt.dueLabel(at),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cloudNights = null }) { Text("Close") } }
        )
    }

    cloudPick?.let { (day, at) ->
        AlertDialog(
            onDismissRequest = { if (!cloudBusy) cloudPick = null },
            title = { Text("Restore this cloud backup?") },
            text = {
                Text("Made " + Fmt.dueLabel(at) + ". Everything now in the app is replaced by it. Recordings on the phone stay. The app will close and open again." +
                    (cloudMsg?.let { "\n\n$it" } ?: ""))
            },
            confirmButton = {
                Button(enabled = !cloudBusy, onClick = {
                    cloudBusy = true
                    cloudMsg = null
                    scope.launch {
                        val err = CloudBackup.restore(ctx, day)
                        cloudBusy = false
                        if (err != null) cloudMsg = "Couldn't restore: $err"
                    }
                }) { Text(if (cloudBusy) "Restoring…" else "Restore") }
            },
            dismissButton = { TextButton(enabled = !cloudBusy, onClick = { cloudPick = null }) { Text("Cancel") } }
        )
    }

    restoreList?.let { items ->
        AlertDialog(
            onDismissRequest = { restoreList = null },
            title = { Text("Choose a backup") },
            text = {
                Column {
                    if (items.isEmpty()) Text("No backups in the folder yet.")
                    items.take(Backup.KEEP).forEach { item ->
                        TextButton(onClick = {
                            restoreList = null
                            confirm = item.uri
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                item.name.removePrefix(Backup.PREFIX).removeSuffix(".json").replace('_', ' '),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { restoreList = null }) { Text("Close") } }
        )
    }

    confirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restore this backup?") },
            text = { Text("Everything now in the app is replaced by the backup. The app will close and open again.") },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    val e = Backup.restore(ctx, uri)
                    if (e != null) Toast.makeText(ctx, e, Toast.LENGTH_LONG).show()
                    else Backup.restartApp(ctx)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------- Voice typing

@Composable
fun VoiceTypingSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var on by VoiceTyping.state(ctx)
    var lang by remember { mutableStateOf(VoiceTyping.lang(ctx)) }

    SettingsPage(title = "Voice typing", onBack = onBack) {
        SectionCard(
            title = "Voice typing button",
            subtitle = "Adds a 🎤 to every note and message box. It uses your phone's voice typing (Google), " +
                "so it works in English and Urdu. Your keyboard's own mic key does the same thing."
        ) {
            SwitchRow("Show the 🎤 button", on) {
                on = it
                VoiceTyping.setEnabled(ctx, it)
            }
        }
        if (on) {
            SectionCard(title = "Language", subtitle = "What you speak into the 🎤.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceTyping.Lang.values().forEach { l ->
                        FilterChip(selected = lang == l, onClick = {
                            lang = l
                            VoiceTyping.setLang(ctx, l)
                        }, label = { Text(l.label) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Weekly off day

@Composable
fun WeeklyOffDayScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mask by remember { mutableIntStateOf(OffDays.mask.value) }
    val count = Integer.bitCount(mask)
    // In a seat: from your timetable on the website, read only (locked 23 Nov).
    val managed = remember { Seats.managed(ctx) }

    SettingsPage(title = "Weekly off day", onBack = onBack) {
        SectionCard(title = "Your off days", subtitle = if (managed) "Set by your manager (timetable ${Seats.myTimetable(ctx).ifBlank { "not set yet" }}). Company holidays are off too."
            else "Pick up to ${OffDays.MAX} days. Untick all if you work every day.") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.values().forEach { day ->
                        val bit = 1 shl (day.value - 1)
                        val on = mask and bit != 0
                        val faded = !on && count >= OffDays.MAX
                        Surface(
                            enabled = !managed,
                            onClick = {
                                if (!on && count >= OffDays.MAX) {
                                    Toast.makeText(ctx, "Max ${OffDays.MAX} off days. Untick one first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    mask = mask xor bit
                                    OffDays.set(ctx, mask)
                                }
                            },
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = when {
                                on -> MaterialTheme.colorScheme.onPrimary
                                faded -> MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Text(
                    if (mask == 0) "No off day, every day is a working day" else "Off: ${OffDays.label(mask)}",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        SectionCard(
            title = "On your off day the app pauses",
            subtitle = "Review reminder · Morning summary · Dossier tasks (not counted as Missed) · Daily lists sent to people"
        ) {
            Text(
                "Still goes out as normal: call-back reminders and text messages you set for that day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
