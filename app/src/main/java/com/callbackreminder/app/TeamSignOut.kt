package com.callbackreminder.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v0.49.1 "Sign out on this phone". Someone in the team: everything is saved online first, then cleared from this phone;
 * signing in again (here or on a new phone) offers to bring it all back. Nobody in a team yet: plain sign-out.
 */
@Composable
fun TeamSignOut(inTeam: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    TextButton(
        onClick = { if (inTeam) { problem = null; ask = true } else TeamRepo.signOut() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sign out on this phone") }

    if (ask) {
        AlertDialog(
            onDismissRequest = { if (!busy) ask = false },
            title = { Text("Sign out?") },
            text = {
                Column {
                    Text("Your tasks (also Private ones), Dossier and settings are saved online first, then cleared from this phone. Sign in again, here or on a new phone, and everything comes back. Call recordings stay on this phone.")
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
                    if (busy) Text("Saving online… keep the app open.")
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        val err = CloudBackup.saveForSignOut(ctx)
                        if (err != null) {
                            problem = err
                            busy = false
                        } else {
                            TeamRepo.signOut {
                                Backup.wipeAll(ctx)
                                Backup.restartApp(ctx)
                            }
                        }
                    }
                }) { Text(if (busy) "Please wait" else "Save and sign out") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { ask = false }) { Text("Cancel") } },
        )
    }
}

/** After signing in: "Bring your tasks back?" on a new phone or one that was cleared at sign-out. */
@Composable
fun TeamRestoreOffer() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val fresh = !Backup.declined(ctx) && (Backup.wiped(ctx) || Backup.isEmpty(ctx))
        if (fresh) offer = CloudBackup.latest()
    }

    val o = offer ?: return
    val whenText = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())
        .format(Instant.ofEpochMilli(o.second).atZone(ZoneId.systemDefault()))
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Bring your tasks back?") },
        text = {
            Column {
                Text("Your saved tasks, Dossier and settings from $whenText are online. This phone is empty. Restore them now?")
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) Text("Restoring… the app will restart.")
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val err = CloudBackup.restore(ctx, o.first)
                    if (err != null) {
                        problem = err
                        busy = false
                    }
                }
            }) { Text("Restore") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    Backup.declineOffer(ctx)
                    if (o.first == "signout") CloudBackup.deleteSignOut()
                    offer = null
                }
            }) { Text("Start empty") }
        },
    )
}
