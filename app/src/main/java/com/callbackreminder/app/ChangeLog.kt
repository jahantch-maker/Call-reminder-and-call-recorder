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
            "0.50.1", "7 Nov",
            new = emptyList(),
            fixed = listOf(
                "The \"why was it late?\" sheet now closes when you save, and Back closes it cleanly without freezing the app",
                "If you close the sheet with Back it waits until the next time the app starts instead of coming back straight away; an unanswered one is dropped after a week",
            ),
        ),
        Entry(
            "0.50.0", "1 Nov",
            new = listOf(
                "\"See how my people are doing\": when the owner switches it on for you, your team page shows how many tasks are late and how many are due today for the people you gave tasks to",
                "\"Edit late-reason list\": when the owner switches it on for you, you can add, retire and bring back late reasons from the phone",
                "Reading reminders: the phone reminds you the day before and on the last day of your reading deadline, then once a day while it is overdue, until everything on your reading list is read",
                "Settings › Backup › Cloud backup: restore from any of the last 7 nights saved in the cloud",
            ),
            fixed = emptyList(),
        ),
        Entry(
            "0.49.3", "1 Nov",
            new = listOf("The person who runs the app is now shown as \"App owner\" next to their own designation (for example \"GM · App owner\"), and everyone else shows their designation"),
            fixed = listOf("A person with the app-owner role is no longer labelled \"Owner\" when the company's real owners are above them"),
        ),
        Entry(
            "0.49.2", "1 Nov",
            new = listOf("Your Google Calendar meetings for today are shared with the team account, for the website's Day calendar (only the calendars you switched on)"),
            fixed = emptyList(),
        ),
        Entry(
            "0.49.1", "1 Nov",
            new = listOf(
                "Signing out of the team saves everything online and clears the phone; signing in again, here or on a new phone, brings it all back (call recordings stay on the phone)",
                "Your manager sees \"Signed out\" when you sign out of the team, until you sign in again",
            ),
            fixed = listOf(
                "Joining the team again after being removed or after a waiting request now works",
                "Signing out of the team shows you as offline straight away",
            ),
        ),
        Entry(
            "0.49.0", "1 Nov",
            new = listOf(
                "Join the team with your official Gmail: you are placed in your seat and get the access your designation gives",
                "Buttons the owner switched off for you are hidden",
                "Documents: your job description, SOPs and work rules, with Read and Need training",
                "Nightly backup also goes to the team account when the phone is charging",
            ),
            fixed = listOf("Someone removed from the team is signed out of it on the phone"),
        ),
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
