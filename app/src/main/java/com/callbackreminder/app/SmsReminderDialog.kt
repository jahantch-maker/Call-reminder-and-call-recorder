package com.callbackreminder.app

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * New (or edit) scheduled text message: pick who, type the message, choose the date & time and
 * repeat. Same pickers as a new call-back reminder.
 */
@Composable
fun SmsReminderDialog(
    initial: SmsReminder? = null,
    onDismiss: () -> Unit,
    onSave: (
        name: String, number: String, message: String, dueAt: Long, repeat: Repeat?, burst: BurstTemplate?, tracked: Boolean,
    ) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var number by remember { mutableStateOf(initial?.number.orEmpty()) }
    var message by remember { mutableStateOf(initial?.message.orEmpty()) }
    // New messages start at 8:00 AM (opening time); you pick the date.
    var due by remember {
        mutableLongStateOf(initial?.dueAt?.takeIf { it > System.currentTimeMillis() } ?: nextEightAm())
    }
    var dateChosen by remember { mutableStateOf(initial != null) }
    var repeat by remember { mutableStateOf(initial?.repeat) }
    val templates = remember { BurstTemplates.load(ctx) }
    var burstOn by remember { mutableStateOf(initial?.isBurst == true) }
    var burst by remember {
        mutableStateOf(
            initial?.takeIf { it.isBurst }?.let { BurstTemplate(it.burstCount, it.burstGapMin) } ?: templates.firstOrNull()
        )
    }
    var tracked by remember { mutableStateOf(initial?.tracked ?: true) }
    var templateMenu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showDateTime by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    var showUsed by remember { mutableStateOf(false) }

    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            try {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0).orEmpty()
                        number = c.getString(1).orEmpty()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    val callLogPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showRecent = true
        else Toast.makeText(ctx, "Allow call log access to pick from recent calls", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New text message" else "Edit text message") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (number.isBlank()) {
                    Text(
                        "Who should get the message?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                contactLauncher.launch(
                                    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Contacts", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                if (CallLookup.hasPermission(ctx)) showRecent = true
                                else callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Recent calls", maxLines = 1)
                        }
                    }
                    OutlinedButton(
                        onClick = { showUsed = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.People, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Used recently", maxLines = 1)
                    }
                } else {
                    ContactCard(name = name, number = number, onClear = { name = ""; number = "" })
                }

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    placeholder = { Text("e.g. Please wash the solar panels today") },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(14.dp),
                    supportingText = {
                        if (message.isNotBlank()) {
                            val parts = SmsSender.partsFor(message)
                            Text(
                                "${message.length} letters" +
                                    if (parts > 1) " · sent as $parts texts" else " · 1 text"
                            )
                        }
                    }
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
                        Text(
                            if (dateChosen) Fmt.dueLabel(due) else "Pick a date · ${timeLabel(ctx, due)}",
                            fontWeight = FontWeight.SemiBold
                        )
                        repeat?.let { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Repeat, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    r.label(Repeat.at(due)),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                SwitchWithHelp(
                    label = "Tracking number",
                    checked = tracked,
                    onCheckedChange = { tracked = it },
                    onHelp = { help = "Tracking number" to TRACKING_HELP }
                )
                // Burst: off sends one text each time; on sends a template's burst ("Reminder 1 (…): …").
                SwitchWithHelp(
                    label = "Burst",
                    checked = burstOn,
                    onCheckedChange = { burstOn = it },
                    onHelp = { help = "Burst" to BURST_HELP }
                )
                if (burstOn) {
                    if (burst == null) {
                        Text(
                            "No burst templates yet. Add them in Settings › Text messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Box {
                            OutlinedButton(
                                onClick = { templateMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(burst?.label.orEmpty(), modifier = Modifier.weight(1f))
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose template")
                            }
                            DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                                templates.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label) },
                                        onClick = { burst = t; templateMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = number.isNotBlank() && message.isNotBlank() && dateChosen && (!burstOn || burst != null),
                onClick = { onSave(name, number, message, due, repeat, if (burstOn) burst else null, tracked) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    help?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { help = null }) { Text("OK") } }
        )
    }

    if (showDateTime) {
        DateTimeDialog(
            initialMs = due,
            initialRepeat = repeat,
            allowRepeat = true,
            onDone = { ms, r ->
                due = ms
                repeat = r
                dateChosen = true
                showDateTime = false
            },
            onDismiss = { showDateTime = false }
        )
    }

    if (showUsed) {
        UsedRecentlyDialog(
            onPick = { person ->
                number = person.number
                name = person.name.ifBlank { ContactLookup.nameFor(ctx, person.number) }
                showUsed = false
            },
            onDismiss = { showUsed = false }
        )
    }

    if (showRecent) {
        RecentCallsDialog(
            onPick = { call ->
                number = call.number
                name = call.name.ifBlank { ContactLookup.nameFor(ctx, call.number) }
                showRecent = false
            },
            onDismiss = { showRecent = false }
        )
    }
}

private const val TRACKING_HELP =
    "Adds a task number so the person can mark this task done by replying. Turn off for vendors."

private const val BURST_HELP =
    "Sends the same text several times, a few minutes apart, using a template you choose."

/** A switch with a small ⓘ next to its name that explains it. */
@Composable
private fun SwitchWithHelp(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onHelp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onHelp, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "What is $label?",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 8:00 AM today if it's still ahead, otherwise tomorrow. */
private fun nextEightAm(): Long {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    var t = now.toLocalDate().atTime(8, 0).atZone(zone)
    if (!t.isAfter(now)) t = t.plusDays(1)
    return t.toInstant().toEpochMilli()
}

private fun timeLabel(ctx: android.content.Context, ms: Long): String {
    val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
    return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault()))
}
