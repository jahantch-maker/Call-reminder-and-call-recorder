package com.callbackreminder.app

import android.Manifest
import android.content.Intent
import android.provider.CallLog
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Add a follow-up by hand: pick who to call, add a note, choose the date & time (and repeat). */
@Composable
fun AddReminderDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, number: String, note: String, dueAt: Long, repeat: Repeat?) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember {
        mutableLongStateOf(
            QuickTimes.options(ctx).getOrNull(1)?.whenMs ?: (System.currentTimeMillis() + 3_600_000L)
        )
    }
    var repeat by remember { mutableStateOf<Repeat?>(null) }
    var showDateTime by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }

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

    // Recent calls need the call-log permission; ask for it the first time if needed.
    val callLogPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showRecent = true
        else Toast.makeText(ctx, "Allow call log access to pick from recent calls", Toast.LENGTH_LONG).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New reminder") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (number.isBlank()) {
                    Text(
                        "Who do you want to call?",
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
                } else {
                    ContactCard(name = name, number = number, onClear = { name = ""; number = "" })
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note (what to discuss)") },
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = number.isNotBlank(),
                onClick = { onSave(name, number, note, due, repeat) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showDateTime) {
        DateTimeDialog(
            initialMs = due,
            initialRepeat = repeat,
            allowRepeat = true,
            onDone = { ms, r ->
                due = ms
                repeat = r
                showDateTime = false
            },
            onDismiss = { showDateTime = false }
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

/** The person you picked: avatar, name and number, with ✕ to pick someone else. */
@Composable
private fun ContactCard(name: String, number: String, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(text = name.ifBlank { number })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifBlank { number },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (name.isNotBlank()) {
                    Text(
                        number,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = "Pick someone else")
            }
        }
    }
}

/** The phone's last 100 calls; tap one to pick that person. Repeated calls in a row show once with a count. */
@Composable
private fun RecentCallsDialog(onPick: (CallInfo) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val groups = remember {
        val out = mutableListOf<Pair<CallInfo, Int>>()
        CallLookup.recent(ctx, 100).filter { it.number.isNotBlank() }.forEach { c ->
            val last = out.lastOrNull()
            if (last != null && last.first.number == c.number) out[out.size - 1] = last.first to last.second + 1
            else out.add(c to 1)
        }
        out
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent calls") },
        text = {
            if (groups.isEmpty()) {
                Text("No recent calls found.")
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(groups, key = { it.first.id }) { (call, count) ->
                        RecentCallRow(call, count) { onPick(call) }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RecentCallRow(call: CallInfo, count: Int, onClick: () -> Unit) {
    val title = call.name.ifBlank { call.number }
    val (icon, tint) = when (call.type) {
        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade to MaterialTheme.colorScheme.primary
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE ->
            Icons.AutoMirrored.Rounded.CallMissed to MaterialTheme.colorScheme.error
        else -> Icons.AutoMirrored.Rounded.CallReceived to MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(text = title, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (count > 1) "$title ($count)" else title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    listOfNotNull(
                        call.number.takeIf { call.name.isNotBlank() },
                        Fmt.dueLabel(call.endedAt)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
