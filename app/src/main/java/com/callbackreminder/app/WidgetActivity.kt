package com.callbackreminder.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What a widget button opens: a call or a text straight away (Android only lets an app start calls and
 * other apps from a screen of its own), or a small popup over the home screen (Snooze, a calendar card's
 * quick actions, a new follow-up or task, the 🎤). It closes itself when the popup is done.
 */
class WidgetActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "widget_mode"
        const val EXTRA_ID = "widget_item"
        const val EXTRA_EXTRA = "widget_extra"
        const val EXTRA_WIDGET = "widget_id"
        const val EXTRA_AT = "widget_at"

        /** Call a follow-up's person (id = call). */
        const val MODE_CALL = "call"
        /** Call the person of a text follow-up (id = text). */
        const val MODE_CALL_NUMBER_OF_TEXT = "call_text"
        /** Open Messages for a follow-up (id = text or -1, extra = call). */
        const val MODE_TEXT = "text"
        /** Open WhatsApp for a follow-up (id = call or -1, extra = text). */
        const val MODE_WHATSAPP = "whatsapp"
        /** +30m / +1h / +3h / Tomorrow for a call (id = call). */
        const val MODE_SNOOZE_FOLLOW_UP = "snooze"
        /** A calendar card: Call / Text / WhatsApp / Done / Snooze / Open (id = call or text). */
        const val MODE_CALL_ACTIONS = "call_actions"
        const val MODE_TEXT_ACTIONS = "text_actions"
        /** A My task card: Done / Snooze / New time / Open (id = task). */
        const val MODE_TASK_ACTIONS = "task_actions"
        const val MODE_NEW_FOLLOW_UP = "new_follow_up"
        /** extra = list (or -1), at = the free gap's start (or -1). */
        const val MODE_NEW_TASK = "new_task"
        const val MODE_VOICE = "voice"
    }

    private sealed interface Screen {
        data object None : Screen
        data class Snooze(val isCall: Boolean, val id: Long, val title: String) : Screen
        data class PickTime(val isCall: Boolean, val isTask: Boolean, val id: Long, val from: Long) : Screen
        data class CallActions(val r: Reminder) : Screen
        data class TextActions(val t: SmsReminder) : Screen
        data class TaskActions(val t: MyTask) : Screen
        data class NewFollowUp(val text: String) : Screen
        data class NewTask(val text: String, val listId: Long, val at: Long) : Screen
        data class AddAs(val text: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.None)

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        } else ""
        if (said.isBlank()) finish() else screen = Screen.AddAs(said.replaceFirstChar { it.uppercase() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WidgetData.ensure(this)
        if (savedInstanceState == null && !start(intent)) {
            finish()
            return
        }
        setContent {
            CallbackTheme {
                Popups()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!start(intent)) finish()
    }

    /** Runs a tap. False when nothing needs to stay on screen. */
    private fun start(i: Intent): Boolean {
        val id = i.getLongExtra(EXTRA_ID, -1L)
        val extra = i.getLongExtra(EXTRA_EXTRA, -1L)
        val at = i.getLongExtra(EXTRA_AT, -1L)
        when (i.getStringExtra(EXTRA_MODE)) {
            MODE_CALL -> ReminderStore.get(id)?.let { callNow(it) }
            MODE_CALL_NUMBER_OF_TEXT -> SmsStore.get(id)?.let { callText(it) }
            MODE_TEXT -> text(SmsStore.get(id), ReminderStore.get(extra))
            MODE_WHATSAPP -> whatsApp(ReminderStore.get(id), SmsStore.get(extra))
            MODE_SNOOZE_FOLLOW_UP -> {
                val r = ReminderStore.get(id) ?: return false
                screen = Screen.Snooze(true, r.id, r.note.ifBlank { "Call ${r.name.ifBlank { r.number }}" })
                return true
            }
            MODE_CALL_ACTIONS -> {
                screen = Screen.CallActions(ReminderStore.get(id) ?: return false)
                return true
            }
            MODE_TEXT_ACTIONS -> {
                screen = Screen.TextActions(SmsStore.get(id) ?: return false)
                return true
            }
            MODE_TASK_ACTIONS -> {
                screen = Screen.TaskActions(MyTaskStore.get(id) ?: return false)
                return true
            }
            MODE_NEW_FOLLOW_UP -> {
                screen = Screen.NewFollowUp("")
                return true
            }
            MODE_NEW_TASK -> {
                screen = Screen.NewTask("", extra, at)
                return true
            }
            MODE_VOICE -> {
                return try {
                    voice.launch(VoiceTyping.intent(this))
                    true
                } catch (e: Exception) {
                    Toast.makeText(this, "Voice typing isn't available on this phone", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
        return false
    }

    // ---- Straight actions ----

    private fun callNow(r: Reminder) {
        if (r.review || !r.done) FollowUp.mark(this, r)
        dial(this, r.number)
    }

    private fun callText(t: SmsReminder) {
        if (t.review != null) FollowUp.markText(this, t)
        dial(this, t.number)
    }

    private fun text(t: SmsReminder?, r: Reminder?) {
        try {
            when {
                t != null -> SmsSender.openComposer(this, t)
                r != null -> startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", r.number, null))
                        .putExtra("sms_body", r.note)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No messages app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun whatsApp(r: Reminder?, t: SmsReminder?) {
        when {
            r != null -> WhatsApp.forCall(this, r)
            t != null -> WhatsApp.forText(this, t)
        }
    }

    private fun snooze(isCall: Boolean, isTask: Boolean, id: Long, ms: Long) {
        when {
            isTask -> MyTaskStore.setDue(id, ms)
            isCall -> ReminderStore.reschedule(id, ms, "Snoozed to")
            else -> SmsStore.reschedule(id, ms)
        }
        Toast.makeText(this, (if (isTask) "Moved to " else "Snoozed to ") + Fmt.dueLabel(ms), Toast.LENGTH_SHORT).show()
        close()
    }

    private fun done(call: Reminder?, text: SmsReminder?) {
        call?.let { ReminderStore.markDone(it.id) }
        text?.let { SmsStore.markDone(it.id) }
        Toast.makeText(this, "Done ✓", Toast.LENGTH_SHORT).show()
        close()
    }

    private fun openApp(open: String, task: Long = -1L) {
        try {
            startActivity(WidgetIntents.app(this, open, task))
        } catch (e: Exception) {
            // Nothing else to do from here.
        }
        finish()
    }

    private fun close() {
        WidgetSync.refresh(this)
        finish()
    }

    // ---- Popups ----

    @Composable
    private fun Popups() {
        when (val s = screen) {
            Screen.None -> Unit
            is Screen.Snooze -> SnoozeSheet(
                heading = "Snooze",
                about = s.title,
                onPick = { ms -> snooze(s.isCall, false, s.id, ms) },
                onPickTime = {
                    val from = if (s.isCall) ReminderStore.get(s.id)?.dueAt else SmsStore.get(s.id)?.dueAt
                    screen = Screen.PickTime(s.isCall, false, s.id, from ?: System.currentTimeMillis())
                },
                onDismiss = { finish() },
                plusRow = true,
            )
            is Screen.PickTime -> DateTimePickerFlow(
                initialMs = s.from.coerceAtLeast(System.currentTimeMillis()),
                onPicked = { ms -> snooze(s.isCall, s.isTask, s.id, ms) },
                onDismiss = { finish() },
            )
            is Screen.CallActions -> {
                val r = s.r
                FollowUpSheet(
                    title = r.note.ifBlank { "Call back" },
                    sub = "📞 Call · ${Fmt.dueLabel(r.dueAt)}" + r.name.ifBlank { r.number }.let { if (it.isNotBlank()) " · $it" else "" },
                    late = !r.done && r.dueAt < System.currentTimeMillis(),
                    hasNumber = r.number.isNotBlank(),
                    onCall = { callNow(r); finish() },
                    onText = { text(FollowUps.text(r.linkId)?.takeIf { r.linkId != 0L }, r); finish() },
                    onWhatsApp = { whatsApp(r, null); finish() },
                    onDone = { done(r, null) },
                    onSnooze = { screen = Screen.Snooze(true, r.id, r.note.ifBlank { "Call back" }) },
                    onOpen = { openApp(MainActivity.OPEN_FOLLOW_UPS) },
                )
            }
            is Screen.TextActions -> {
                val t = s.t
                FollowUpSheet(
                    title = t.message,
                    sub = "💬 Text · ${Fmt.dueLabel(t.dueAt)}" + t.name.ifBlank { t.number }.let { if (it.isNotBlank()) " · $it" else "" },
                    late = !t.done && t.dueAt < System.currentTimeMillis(),
                    hasNumber = t.number.isNotBlank(),
                    onCall = { callText(t); finish() },
                    onText = { text(t, null); finish() },
                    onWhatsApp = { whatsApp(null, t); finish() },
                    onDone = { done(null, t) },
                    onSnooze = { screen = Screen.Snooze(false, t.id, t.message) },
                    onOpen = { openApp(MainActivity.OPEN_FOLLOW_UPS) },
                )
            }
            is Screen.TaskActions -> TaskSheet(s.t)
            is Screen.NewFollowUp -> FollowUpDialog(onDismiss = { close() }, initialTask = s.text)
            is Screen.NewTask -> MyTaskDialog(
                existing = null,
                onDismiss = { close() },
                onSwitchKind = { screen = Screen.NewFollowUp(s.text) },
                listId = if (s.listId >= 0L) s.listId else MyTaskStore.UNSORTED,
                initialDue = if (s.at > 0L) s.at else 0L,
                initialTitle = s.text,
            )
            is Screen.AddAs -> AddAsSheet(s.text)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FollowUpSheet(
        title: String,
        sub: String,
        late: Boolean,
        hasNumber: Boolean,
        onCall: () -> Unit,
        onText: () -> Unit,
        onWhatsApp: () -> Unit,
        onDone: () -> Unit,
        onSnooze: () -> Unit,
        onOpen: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        ModalBottomSheet(onDismissRequest = { finish() }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp + navBarMissing()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(sub, color = if (late) cs.error else cs.onSurfaceVariant, fontSize = 13.sp)
                if (hasNumber) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCall, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Call")
                        }
                        OutlinedButton(onClick = onText, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("💬 Text") }
                        WhatsAppAction(size = 48.dp, onClick = onWhatsApp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("✓ Done") }
                    OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("⏰ Snooze") }
                    OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Open") }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TaskSheet(t: MyTask) {
        val cs = MaterialTheme.colorScheme
        ModalBottomSheet(onDismissRequest = { finish() }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp + navBarMissing()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (t.timed) Fmt.dueLabel(t.dueAt) else "No time") + if (t.name.isNotBlank()) " · ${t.name}" else "",
                    color = if (!t.done && t.timed && t.dueAt < System.currentTimeMillis()) cs.error else cs.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            MyTaskStore.setDone(t.id, !t.done)
                            Toast.makeText(this@WidgetActivity, if (t.done) "Not done" else "Done ✓", Toast.LENGTH_SHORT).show()
                            close()
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (t.done) "Not done" else "✓ Done") }
                    if (t.hasPerson) {
                        OutlinedButton(onClick = { dial(this@WidgetActivity, t.number); finish() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text("📞 Call")
                        }
                    }
                }
                if (!t.done) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { screen = Screen.PickTime(false, true, t.id, if (t.timed) t.dueAt + 30 * 60_000L else System.currentTimeMillis()) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("New time") }
                        OutlinedButton(
                            onClick = { snooze(false, true, t.id, (if (t.timed) maxOf(t.dueAt, System.currentTimeMillis()) else System.currentTimeMillis()) + 30 * 60_000L) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("⏰ +30m") }
                        OutlinedButton(onClick = { openApp(MainActivity.OPEN_MY_TASKS, t.id) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text("Open")
                        }
                    }
                }
            }
        }
    }

    /** After the 🎤: is what you said a follow-up or one of your own tasks? */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AddAsSheet(said: String) {
        ModalBottomSheet(onDismissRequest = { finish() }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp + navBarMissing()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("“$said”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text("Add it as", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { screen = Screen.NewFollowUp(said) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text("+ Follow-up")
                    }
                    OutlinedButton(onClick = { screen = Screen.NewTask(said, -1L, -1L) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text("+ Task")
                    }
                }
            }
        }
    }
}

