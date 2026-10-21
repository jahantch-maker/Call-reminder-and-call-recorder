package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.PhoneDisabled
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The after-call popup for a call you made from a due reminder (Call back). It first settles the
 * task the call was about: Done, Done but review it, or a new time (with why it is not done yet).
 * If "New task discussed" is on, the normal popup follows to add the new task for the same person.
 */
@Composable
fun FollowUpSheet(
    call: CallInfo,
    task: Reminder,
    positionLabel: String?,
    onNext: () -> Unit,
    onNewTask: () -> Unit,
) {
    val ctx = LocalContext.current
    val answered = call.durationSec > 0
    // When they did not pick up, the only sensible step is a new time to call again.
    var newTime by remember { mutableStateOf(!answered) }
    var newTaskOn by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf(call.prefillNote) }
    var showPicker by remember { mutableStateOf(false) }

    val title = call.name.ifBlank { task.title }
    val subtitle = buildString {
        append("Follow-up call")
        if (call.number.isNotBlank() && call.name.isNotBlank()) append(" · ").append(call.number)
        val round = CallAgain.roundLabel(call)
        if (round != null) append(" · ").append(round)
        else if (answered) append(" · ").append(Fmt.duration(call.durationSec))
    }

    // Each outcome below first notes the call itself in the task's History.
    fun logCall() = ReminderStore.logEvent(
        task.id,
        if (answered) "Called · ${Fmt.duration(call.durationSec)}" else "Called · didn't pick up"
    )

    fun finish(message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        if (newTaskOn) onNewTask() else onNext()
    }

    fun moveTo(whenMs: Long) {
        logCall()
        val before = ReminderStore.get(task.id) ?: task
        ReminderStore.postpone(task.id, whenMs, reason)
        // A new due time: the follow-up's texts move with it.
        FollowUps.onNewTime(ctx, before, whenMs, moveTexts = true)
        finish("Task moved · ${Fmt.dueLabel(whenMs)}")
    }

    PopupSheetFrame(onDismiss = onNext) {
        if (positionLabel != null) {
            Text(
                text = positionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        }

        // Task first: the task is the big title, the person a small line under it.
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.note.ifBlank { "Call back ${task.title}" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(text = title, size = 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$title · $subtitle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.Close, contentDescription = "Skip")
            }
        }

        if (!answered) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.PhoneDisabled,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Didn't pick up. Set a time to call again.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // When the task was due.
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "TASK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        append("Was due ").append(Fmt.dueLabel(task.dueAt))
                        val n = task.delays.size
                        if (n > 0) append(" · delayed ").append(if (n == 1) "once" else "$n times")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("New task discussed", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "After this, add a new reminder for ${task.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = newTaskOn, onCheckedChange = { newTaskOn = it })
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "How did this task go?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutcomeButton(
                icon = Icons.Rounded.Check,
                label = "Done",
                selected = false,
                modifier = Modifier.weight(1f)
            ) {
                logCall()
                ReminderStore.markDone(task.id)
                finish("Task done")
            }
            OutcomeButton(
                icon = Icons.Rounded.TaskAlt,
                label = "Done, review it",
                selected = false,
                modifier = Modifier.weight(1f)
            ) {
                logCall()
                ReminderStore.markDone(task.id, review = true)
                finish("Sent to To review")
            }
            OutcomeButton(
                icon = Icons.Rounded.Schedule,
                label = "New time",
                selected = newTime,
                modifier = Modifier.weight(1f)
            ) { newTime = true }
        }

        if (newTime) {
            Spacer(Modifier.height(14.dp))
            // Only asked when a task that came due is moved again: kept as the task's history.
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                trailingIcon = voiceMic(reason) { reason = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Why not done? (optional)") },
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (answered) "New time for this task" else "Call again",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ShortTimeRow(selected = null, plusRow = true, dialogTitle = "New time", onPick = { moveTo(it) })
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { showPicker = true }) {
                Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick date & time")
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNext) { Text("Skip") }
            }
        }

        if (call.number.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            CallAgainButton {
                logCall()
                CallAgain.dialAgain(ctx, call, title, reason)
                onNext()
            }
        }
    }

    if (showPicker) {
        DateTimePickerFlow(
            initialMs = QuickTime.OnDay(1, 10).toOption().whenMs, // Tomorrow 10 am
            onPicked = { showPicker = false; moveTo(it) },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun OutcomeButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/** Which outcome the text follow-up popup is showing. */
private enum class TextOutcome { NONE, CALL_LATER }

/**
 * The after-call popup for a call you made with Call now on a text in To review. It settles the text
 * task: Done, Not done (text again, in the text's own Edit window), or Not done (call later, which
 * moves it to Calls). If they didn't pick up, the text stays in To review with "Called · no answer".
 */
@Composable
fun TextFollowUpSheet(
    call: CallInfo,
    task: SmsReminder,
    positionLabel: String?,
    onNext: () -> Unit,
    onNewTask: () -> Unit,
) {
    val ctx = LocalContext.current
    val answered = call.durationSec > 0
    var outcome by remember { mutableStateOf(TextOutcome.NONE) }
    var newTaskOn by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf(call.prefillNote) }
    var showPicker by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    val title = call.name.ifBlank { task.title }
    val length = if (answered) Fmt.duration(call.durationSec) else "no answer"
    val subtitle = buildString {
        append("Call about a text")
        if (call.number.isNotBlank() && call.name.isNotBlank()) append(" · ").append(call.number)
        append(" · ").append(CallAgain.roundLabel(call) ?: length)
    }

    // The call goes in the text's History once, whatever is chosen (or if the popup is just closed).
    var logged by remember { mutableStateOf(false) }
    fun logCall(extra: String = "") {
        if (logged) return
        logged = true
        val detail = if (extra.isBlank()) length else "$length · $extra"
        SmsStore.addLog(task.id, SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.CALLED, detail))
    }

    fun finish(message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        if (newTaskOn) onNewTask() else onNext()
    }

    fun close() {
        logCall()
        onNext()
    }

    fun callLater(whenMs: Long) {
        logCall(reason.trim().takeIf { it.isNotEmpty() }?.let { "not done: $it" }.orEmpty())
        val extra = listOf(CallEvent(System.currentTimeMillis(), "Not done, call later", note = reason.trim()))
        TextToCall.move(ctx, task, task.name, task.number, task.message, whenMs, extra = extra)
        finish("Moved to Calls · ${Fmt.dueLabel(whenMs)}")
    }

    PopupSheetFrame(onDismiss = { close() }) {
        if (positionLabel != null) {
            Text(
                text = positionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        }

        // Task first: the task is the big title, the person a small line under it.
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.message,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(text = title, size = 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$title · $subtitle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { close() }) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
        }

        if (!answered) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.PhoneDisabled,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Didn't pick up. The text stays in To review, marked \"Called · no answer\".",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // The text task this call was about.
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (task.code.isNotEmpty()) "TEXT TASK · ${task.code}" else "TEXT TASK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (task.reviewAt > 0L) {
                    Text(
                        "In To review since ${Fmt.dueLabel(task.reviewAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (answered) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("New task discussed", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "After this, add a new reminder for $title",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = newTaskOn, onCheckedChange = { newTaskOn = it })
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "How did this task go?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutcomeButton(icon = Icons.Rounded.Check, label = "Done", selected = false, modifier = Modifier.weight(1f)) {
                    logCall()
                    SmsStore.markDone(task.id)
                    finish("Task done")
                }
                OutcomeButton(
                    icon = Icons.Rounded.Sms,
                    label = "Not done, text again",
                    selected = false,
                    modifier = Modifier.weight(1f)
                ) {
                    logCall(reason.trim().takeIf { it.isNotEmpty() }?.let { "not done: $it" }.orEmpty())
                    SmsStore.noteNotDone(task.id)
                    showEdit = true
                }
                OutcomeButton(
                    icon = Icons.Rounded.Call,
                    label = "Not done, call later",
                    selected = outcome == TextOutcome.CALL_LATER,
                    modifier = Modifier.weight(1f)
                ) { outcome = TextOutcome.CALL_LATER }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                trailingIcon = voiceMic(reason) { reason = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Why not done? (optional)") },
                shape = RoundedCornerShape(14.dp)
            )

            if (outcome == TextOutcome.CALL_LATER) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Call again at",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ShortTimeRow(selected = null, plusRow = true, dialogTitle = "Call again at", onPick = { callLater(it) })
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showPicker = true }) {
                    Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick date & time")
                }
            }
        }

        if (call.number.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            CallAgainButton {
                logCall()
                CallAgain.dialAgain(ctx, call, title, reason)
                onNext()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { close() }) { Text(if (answered) "Skip" else "Keep in To review") }
        }
    }

    if (showPicker) {
        DateTimePickerFlow(
            initialMs = QuickTime.OnDay(1, 10).toOption().whenMs, // Tomorrow 10 am
            onPicked = { showPicker = false; callLater(it) },
            onDismiss = { showPicker = false }
        )
    }

    if (showEdit) {
        // "Not done, text again": the same follow-up editor as everywhere; it stays in To review until saved.
        val link = remember { FollowUps.ensureLink(ctx, null, SmsStore.get(task.id) ?: task) }
        FollowUpDialog(
            onDismiss = {
                showEdit = false
                val now = SmsStore.get(task.id)
                finish(if (now != null && now.active) "Text scheduled again · ${Fmt.dueLabel(now.alarmAt)}" else "Still in To review")
            },
            linkId = link,
        )
    }
}
