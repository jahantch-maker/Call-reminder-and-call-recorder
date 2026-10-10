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
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
    onSave: (name: String, number: String, message: String, dueAt: Long, repeat: Repeat?) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var number by remember { mutableStateOf(initial?.number.orEmpty()) }
    var message by remember { mutableStateOf(initial?.message.orEmpty()) }
    var due by remember {
        mutableLongStateOf(
            initial?.dueAt?.takeIf { it > System.currentTimeMillis() }
                ?: QuickTimes.options(ctx).getOrNull(1)?.whenMs
                ?: (System.currentTimeMillis() + 3_600_000L)
        )
    }
    var repeat by remember { mutableStateOf(initial?.repeat) }
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
                enabled = number.isNotBlank() && message.isNotBlank(),
                onClick = { onSave(name, number, message, due, repeat) }
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
