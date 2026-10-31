package com.callbackreminder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What changed in each version, in plain words for the owner and the team. No personal names.
 * Every PR that bumps the version adds its own entry at the top; the What's new page shows the last three.
 */
object ChangeLog {
    data class Entry(val version: String, val date: String, val new: List<String>, val fixed: List<String>)

    const val SHOWN = 3

    val entries = listOf(
        Entry(
            "0.48.1", "1 Nov",
            new = listOf("A meeting task the owner gives himself closes as soon as he marks it Done"),
            fixed = emptyList(),
        ),
        Entry(
            "0.48.0", "1 Nov",
            new = listOf(
                "When a date moves, more time is asked, or a task is done after its date, one tap tells why (the reasons come from the owner's list)",
                "Your tasks (except Private ones) are shared with the owner for team meetings, with their dates, moves and reasons",
                "Tasks agreed in a team meeting show a 🤝 Meeting label",
            ),
            fixed = emptyList(),
        ),
        Entry(
            "0.47.0", "31 Oct",
            new = listOf(
                "On-time mark on every finished team task: On time, or how late it was, measured against the first deadline, with how often it was extended",
                "Your own My tasks get the mark too (not Private ones): the first time you set is the promise, and every move later is counted",
                "Own tasks without a time show No date",
            ),
            fixed = emptyList(),
        ),
        Entry(
            "0.46.2", "31 Oct",
            new = emptyList(),
            fixed = listOf(
                "A task given to you shows Done, Deadline and My reminder in Needs you and the review session, without Call and WhatsApp",
                "Your reminder is set after your deadline, so the task stays in To schedule until both are set",
                "A task that asks for a photo can't be ticked off without it, and Not done yet asks for a new photo",
            ),
        ),
        Entry(
            "0.46.1", "31 Oct",
            new = listOf("The app now starts at Small size for anyone who never changed Display size"),
            fixed = listOf(
                "A task given to a team member waits in his To schedule until he sets his own reminder",
                "Not done yet clears his old reminder, so he sets it again",
            ),
        ),
        Entry(
            "0.46.0", "30 Oct",
            new = listOf("What's new page in Settings: see what changed in each version"),
            fixed = emptyList(),
        ),
        Entry(
            "0.45.7", "30 Oct",
            new = emptyList(),
            fixed = listOf("One person shows under one name everywhere, even if tasks were typed with different names"),
        ),
        Entry(
            "0.45.6", "30 Oct",
            new = listOf("Tasks waiting on someone's deadline now show in To schedule"),
            fixed = listOf("Fixes to the Not done flow", "Chips are one size everywhere"),
        ),
        Entry(
            "0.45.0", "30 Oct",
            new = listOf("Voice notes: play, pause, resume and faster playback", "One timeline per task"),
            fixed = listOf("Deleted messages show like in WhatsApp"),
        ),
    )

    val recent: List<Entry> get() = entries.take(SHOWN)
}

/** The What's new page, opened from the version bubble at the end of Settings. */
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SettingsPage(title = "What's new", onBack = onBack) {
        ChangeLog.recent.forEachIndexed { i, e ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row {
                        Text("Version ${e.version}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(e.date, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    if (e.new.isNotEmpty()) ChangeGroup("NEW", cs.secondary, e.new)
                    if (e.fixed.isNotEmpty()) ChangeGroup("FIXED", cs.primary, e.fixed)
                }
            }
        }
    }
}

@Composable
private fun ChangeGroup(title: String, color: androidx.compose.ui.graphics.Color, lines: List<String>) {
    Spacer(Modifier.height(10.dp))
    Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.5.sp)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { Text("•  $it", fontSize = 14.sp) }
    }
}
