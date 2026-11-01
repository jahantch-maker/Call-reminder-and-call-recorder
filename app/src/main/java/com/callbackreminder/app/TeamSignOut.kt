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
                    Text("Your tasks (also Private ones), Dossier and settings are saved online first, then cleared from this phone. Sign in again, here or on a new phone, and everything comes back by itself. Call recordings stay on this phone.")
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

/** After signing in on a new phone or one cleared at sign-out: the saved tasks come back by themselves. */
@Composable
fun TeamRestoreOffer() {
    val ctx = LocalContext.current
    var day by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableStateOf(0) }

    LaunchedEffect(tries) {
        val fresh = Backup.wiped(ctx) || (Backup.isEmpty(ctx) && !Backup.restoredOnce(ctx))
        if (!fresh) return@LaunchedEffect
        val latest = CloudBackup.latest() ?: return@LaunchedEffect
        day = latest.first
        problem = null
        val err = CloudBackup.restore(ctx, latest.first)
        if (err != null) problem = err
    }

    if (day == null) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (problem == null) "Bringing your tasks back…" else "Couldn't bring your tasks back") },
        text = {
            Column {
                if (problem == null) Text("Your saved tasks, Dossier and settings are coming back. The app will restart. Please keep it open.")
                else Text(problem.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (problem != null) TextButton(onClick = { tries++ }) { Text("Try again") }
        },
    )
}
