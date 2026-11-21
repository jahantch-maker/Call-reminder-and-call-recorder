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
            "0.56.3", "22 Nov",
            new = emptyList(),
            fixed = listOf(
                "Timeline: a message you send now shows its ticks as soon as it reaches the server, not only after your next message",
                "Timeline: a message stuck on the clock now sends by itself after a few seconds, without closing the app",
            ),
        ),
        Entry(
            "0.56.2", "21 Nov",
            new = emptyList(),
            fixed = listOf(
                "Plot files: opening a new file now creates only the first step, not a later step that another step opens",
                "Restoring a backup no longer brings back an old unanswered \"Why not done\" window",
            ),
        ),
        Entry(
            "0.56.1", "21 Nov",
            new = listOf(
                "Plot files: a step that is waiting for the plot owner reminds you once every working day, with a Call button",
                "Plot files: when you are CC on a step (start, done or late), it shows in Follow-ups under PLOT FILES · CC, read-only, with a notification. Tap it to open the step's Timeline",
            ),
            fixed = listOf(
                "Plot files: staff now see the checklist on their plot step without opening Settings first",
            ),
        ),
        Entry(
            "0.56.0", "21 Nov",
            new = listOf(
                "Plot files: New plot file in Settings, right under your account (for the Front Desk and the app owner). It checks the plot number online, needs a 13-digit CNIC and 11-digit phone numbers, and gives Step 1 to the Front Desk",
                "Plot files: the app owner can save a test file and choose which roles get their real task; the rest come to the owner's phone",
                "Plot files: each plot step is a sand-coloured task with its checklist. Tap a line to tick it, tap ⓘ to see how to do it in English or Urdu. Done opens only when every line is ticked, and the next step goes to the next person by itself",
                "Plot files: Return with a reason, Waiting for plot owner, and Call / WhatsApp that always shows who you are calling first. Every call is written on the task's Timeline",
                "My tasks: a Plot files tab, shown when you have plot steps",
            ),
            fixed = emptyList(),
        ),
        Entry(
            "0.55.1", "14 Nov",
            new = listOf(
                "Person page: the four counts at the top (Pending, Overdue, Delayed, Done) are gone; that report is on the web app",
            ),
            fixed = listOf(
                "Send list now and the automatic daily lists now include every pending task of the person: late ones first, then tasks with no time yet, then the scheduled ones",
                "A team member's daily list now covers all of their numbers, like their person page",
            ),
        ),
        Entry(
            "0.55.0", "14 Nov",
            new = listOf(
                "Person page: tasks with no time yet now show under their person, in a No time yet group. Pending is ordered Late, No time yet, Scheduled, with small chips to show one group",
                "Follow-ups filtered to one person: that person's No time yet tasks show on top",
                "Urgent task with no deadline: a reminder to both people every 2 working hours until a deadline is set (change it in Settings > Team). If it was opened, one text goes after 2 reminders",
                "The message bar in every Timeline and the Help chat now looks the same as WhatsApp: one slim row with @ and the camera inside the box",
            ),
            fixed = listOf(
                "Pick a name after @: the cursor now goes after the name, the name shows in blue, and one delete key press removes all of it",
                "The message bar no longer disappears or leaves a gap above the keyboard when the @ list opens",
            ),
        ),
        Entry(
            "0.54.0", "11 Nov",
            new = listOf(
                "@ in a Timeline now works like WhatsApp: a small list of team members slides up above the message box and the chat stays visible",
                "The list narrows as you type after the @; tap a name to put it in your message, then type @ again to add more people",
                "Sending the message asks everyone tagged to help; they see the task's whole history and can reply",
                "Photo editor: pinch with two fingers to zoom in and move around, double-tap to fit the photo again, or use the + and − buttons; your marks stay in place",
            ),
            fixed = emptyList(),
        ),
        Entry(
            "0.53.1", "11 Nov",
            new = listOf(
                "Photo editor: line thickness and colour are now two slim sliders under the picture, so they no longer cover the drawing area",
            ),
            fixed = listOf(
                "Photo editor: the tool bar and Done no longer hide under the phone's navigation buttons",
                "Photo editor: the thickness slider now makes the line thicker when you move it towards Thick",
            ),
        ),
        Entry(
            "0.53.0", "11 Nov",
            new = listOf(
                "Ask a team member for help on any task: in its Timeline type @ (or tap the @ button) and pick a person",
                "The helper sees the task's whole history (private notes stay on your phone) and can reply, but not change the task",
                "New 🤝 Helping list in My tasks: tasks you were asked to help with and tasks you asked help for",
                "When the owner wrote last and you have not answered, it shows red on Home like Ask and Stuck",
                "The helper presses ✓ Solved and leaves with a line on the thread; an @mention brings them back",
                "Marking the task Done ends the help for everyone",
            ),
            fixed = emptyList(),
        ),
        Entry(
            "0.52.0", "11 Nov",
            new = listOf(
                "Photo editor: every photo you take or pick opens in a WhatsApp-style editor before it is used",
                "Draw with Pen, Arrow, Line, Circle, Box and Mark (highlighter), add a measurement with Size, or type text (Bold, Box)",
                "Colour slider on the right, thickness slider on the left; tap a mark to select it, drag the dots to resize, drag it to move, drag it to the bin to delete",
                "Hold an end dot of an arrow, line or measurement and a magnifier shows the exact spot under your finger",
                "Crop and rotate with Free, Square, 4:3 and 16:9",
                "Pick several photos from the gallery at once where photos are added to a task or note",
            ),
            fixed = emptyList(),
        ),
        Entry(
            "0.51.0", "10 Nov",
            new = listOf(
                "After saving a new task or follow-up, its window shrinks into a slim \"✓ Saved · View · Edit\" bar, also over other apps after a call or from a widget",
                "Review session: ◀ Prev and Next ▶ beside Skip, to go back to tasks you already handled (done ones can be reopened)",
                "Review session: the card on top is now the real task card, with its banner, urgent colour, chips and ticks; tap it to open its Timeline",
                "Review session: Move to Follow-ups and Remove time on the card",
                "Review session: one person's tasks come together inside each part, with \"1 of 3\" on the card",
            ),
            fixed = listOf(
                "Editing an overdue task no longer makes it ring straight away",
                "A task sent back as \"Not done yet\" is no longer marked Done again by itself",
                "Only tasks that are really late come into Review",
                "No signal no longer shows \"Something went wrong\"; a real problem shows at most once a day",
                "Answering a deadline request clears \"Extended N×\" from Needs you",
                "A follow-up can no longer have two open calls; old doubles are joined into one",
                "A new task you tap no longer jumps away in the list",
                "Search and Show in list now scroll to the task and outline it, also tasks with no time",
            ),
        ),
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
