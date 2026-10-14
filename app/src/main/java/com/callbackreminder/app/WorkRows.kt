package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Tasks of any kind as one block of option C rows (Home's Next up, the person page, My day).
 * Tapping a row opens it on its own page; the round button calls, or sends a text after asking.
 */
@Composable
internal fun WorkBlock(list: List<WorkItem>, now: Long, showPerson: Boolean, onOpen: (WorkItem) -> Unit) {
    val ctx = LocalContext.current
    var sendFor by remember { mutableStateOf<SmsReminder?>(null) }
    Column {
        list.forEachIndexed { i, w ->
            WorkRow(w, rowPos(i, list.size), now, showPerson, onClick = { onOpen(w) }, onSend = { sendFor = it })
        }
    }
    sendFor?.let { r ->
        AlertDialog(
            onDismissRequest = { sendFor = null },
            title = { Text("Send now?") },
            text = { Text("Sends “${r.message}” to ${r.title} now.") },
            confirmButton = {
                TextButton(onClick = {
                    sendFor = null
                    if (!r.isBurst && SmsSender.InFlight.busy(ctx, r.id, System.currentTimeMillis())) {
                        Toast.makeText(ctx, "Already sending this message", Toast.LENGTH_SHORT).show()
                    } else {
                        SmsSender.sendDue(ctx, r.id, manual = true)
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sendFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WorkRow(
    w: WorkItem,
    pos: RowPos,
    now: Long,
    showPerson: Boolean,
    onClick: () -> Unit,
    onSend: (SmsReminder) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val call = if (w.kind == WorkItem.Kind.CALL) ReminderStore.get(w.id) else null
    val text = if (w.kind == WorkItem.Kind.TEXT) SmsStore.get(w.id) else null

    var title = w.title
    var style = TitleStyle.NORMAL
    val chips = mutableListOf<RowChip>()
    var timeTop = Fmt.timeOnly(w.at)
    var timeSub = RowFmt.timeSub(w.at, now)
    var action: (@Composable () -> Unit)? = null

    if (w.overdue && !w.inReview) chips += RowChip(RowFmt.late(w.at, now), ChipTone.RED)

    when {
        call != null -> {
            title = call.headline()
            if (call.note.isBlank()) style = TitleStyle.NO_TASK
            if (call.review) {
                timeTop = "Done"
                timeSub = RowFmt.doneSub(call.doneAt)
                chips += if (call.note.isBlank()) RowChip("No task written", ChipTone.ORANGE) else RowChip("Done, review it", ChipTone.GREEN)
            }
            call.repeatLabel?.takeIf { !call.done }?.let { chips += RowChip(it, ChipTone.MUTED, Icons.Rounded.Repeat) }
            if (call.delays.isNotEmpty()) chips += RowChip("Delayed ${call.delays.size}×", ChipTone.ORANGE, Icons.Rounded.History)
            if (call.note.isBlank() && !call.done) chips += RowChip("Add task", ChipTone.ORANGE, Icons.Rounded.Add)
            action = {
                RoundAction(Icons.Rounded.Call, "Call", cs.primary, cs.onPrimary, call.number.isNotBlank()) {
                    if (call.review || call.dueAt <= System.currentTimeMillis()) FollowUp.mark(ctx, call)
                    dial(ctx, call.number)
                }
            }
        }
        text != null -> {
            title = text.message
            val review = text.review
            if (text.tracked && text.code.isNotEmpty()) chips += RowChip(text.code, ChipTone.BLUE, Icons.Rounded.Tag)
            if (!text.tracked) chips += RowChip("Tracking off", ChipTone.MUTED)
            if (review != null) {
                timeTop = when (review) {
                    SmsReminder.Review.REPLIED -> "Replied"
                    SmsReminder.Review.NOT_SENT -> "Failed"
                    else -> "Sent"
                }
                timeSub = RowFmt.doneSub(text.reviewAt)
                chips += when (review) {
                    SmsReminder.Review.REPLIED -> RowChip("Replied “${text.doneReply}”", ChipTone.GREEN, Icons.Rounded.Check)
                    SmsReminder.Review.NOT_SENT -> RowChip("Not sent", ChipTone.RED)
                    else -> RowChip(if (text.tracked) "No reply" else "Sent", if (text.tracked) ChipTone.ORANGE else ChipTone.MUTED)
                }
                action = { RoundAction(Icons.Rounded.Check, "Open to review", cs.secondaryContainer, cs.onSecondaryContainer, onClick = onClick) }
            } else {
                if (text.retryAt > 0L) {
                    timeTop = Fmt.timeOnly(text.retryAt)
                    timeSub = "retrying"
                    chips += RowChip("Retry ${text.attempts + 1} of ${SmsSender.MAX_RETRIES + 1}", ChipTone.RED)
                } else if (text.isBurst && text.burstIndex > 0) {
                    timeSub = "next text"
                }
                if (text.isBurst && text.burstIndex > 0) {
                    chips += RowChip("${text.burstIndex} of ${text.burstCount} sent", ChipTone.BLUE, Icons.Rounded.Sms)
                }
                text.repeatLabel?.let { chips += RowChip(it, ChipTone.MUTED, Icons.Rounded.Repeat) }
                action = { RoundAction(Icons.AutoMirrored.Rounded.Send, "Send now", cs.secondary, cs.onSecondary) { onSend(text) } }
            }
        }
        w.kind == WorkItem.Kind.MY_TASK -> chips += RowChip("My task", ChipTone.MUTED, Icons.Rounded.TaskAlt)
        w.kind == WorkItem.Kind.DOSSIER -> chips += RowChip("Dossier", ChipTone.MUTED, Icons.Rounded.Checklist)
    }

    val hasPerson = w.personName.isNotBlank() || w.number.isNotBlank()
    TaskRow(
        pos = pos,
        timeTop = timeTop,
        timeSub = timeSub,
        timeRed = w.overdue && !w.inReview,
        title = title,
        titleStyle = style,
        person = if (showPerson && hasPerson) w.personName.ifBlank { w.number } else null,
        personDetail = w.number.takeIf { w.personName.isNotBlank() },
        chips = chips,
        expanded = false,
        onClick = onClick,
        action = action,
    )
}
