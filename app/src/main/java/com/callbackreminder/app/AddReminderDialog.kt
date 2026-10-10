package com.callbackreminder.app

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Add a follow-up by hand (for calls that did not trigger the popup). */
@Composable
fun AddReminderDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, number: String, note: String, dueAt: Long, repeat: Repeat?) -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableLongStateOf(quickOptions()[2].whenMs) }
    var showPicker by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf<Repeat?>(null) }

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
                        name = c.getString(0) ?: name
                        number = c.getString(1) ?: number
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New reminder") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                    shape = RoundedCornerShape(14.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Phone number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(14.dp)
                    )
                    IconButton(onClick = {
                        contactLauncher.launch(
                            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        )
                    }) {
                        Icon(Icons.Rounded.Contacts, contentDescription = "Pick from contacts")
                    }
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
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.dueLabel(due))
                }
                RepeatButton(repeat = repeat, startMs = due, onChange = { repeat = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() || number.isNotBlank(),
                onClick = { onSave(name, number, note, due, repeat) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showPicker) {
        DateTimePickerFlow(
            initialMs = due,
            onPicked = { due = it; showPicker = false },
            onDismiss = { showPicker = false }
        )
    }
}
