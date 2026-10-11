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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.PhoneDisabled
import androidx.compose.material.icons.rounded.Schedule
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
    var reason by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    val options = remember { QuickTimes.options(ctx) }

    val title = call.name.ifBlank { task.title }
    val subtitle = buildString {
        append("Follow-up call")
        if (call.number.isNotBlank() && call.name.isNotBlank()) append(" · ").append(call.number)
        if (answered) append(" · ").append(Fmt.duration(call.durationSec))
    }

    fun finish(message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        if (newTaskOn) onNewTask() else onNext()
    }

    fun moveTo(whenMs: Long) {
        ReminderStore.postpone(task.id, whenMs, reason)
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(text = title, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

        // The task this call was about.
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
                    task.note.ifBlank { "Call back ${task.title}" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
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
                ReminderStore.markDone(task.id)
                finish("Task done")
            }
            OutcomeButton(
                icon = Icons.Rounded.TaskAlt,
                label = "Done, review it",
                selected = false,
                modifier = Modifier.weight(1f)
            ) {
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.chunked(3).forEach { rowOptions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowOptions.forEach { o ->
                            OptionTile(
                                title = o.title,
                                subtitle = o.subtitle,
                                modifier = Modifier.weight(1f)
                            ) { moveTo(o.whenMs) }
                        }
                        repeat(3 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
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
