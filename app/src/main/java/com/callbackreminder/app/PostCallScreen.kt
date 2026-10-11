package com.callbackreminder.app

import android.provider.CallLog
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shows the popup for the first call in the queue, then the next one, until none are left. */
@Composable
fun PostCallScreen(queue: SnapshotStateList<CallInfo>, onFinished: () -> Unit) {
    var handled by remember { mutableIntStateOf(0) }
    val current = queue.firstOrNull()

    LaunchedEffect(current == null) {
        if (current == null) onFinished()
    }
    if (current == null) return

    val total = handled + queue.size
    val positionLabel = if (total > 1) "Call ${handled + 1} of $total" else null
    val onNext = {
        if (queue.isNotEmpty()) queue.removeAt(0)
        handled = handled + 1
    }
    val task = current.followUpId.takeIf { it > 0 }?.let { ReminderStore.get(it) }?.takeIf { !it.done }
    key(handled, task?.id) {
        if (task != null) {
            FollowUpSheet(
                call = current,
                task = task,
                positionLabel = positionLabel,
                onNext = onNext,
                // A new task came up in the same call: ask about it next, with the same person.
                onNewTask = { if (queue.isNotEmpty()) queue[0] = current.copy(followUpId = -1L) },
            )
        } else {
            PostCallSheet(call = current, positionLabel = positionLabel, onNext = onNext)
        }
    }
}

/** The dimmed backdrop (tap to skip) and the sheet that slides up from the bottom. */
@Composable
internal fun PopupSheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Slides up from the bottom when it appears (and again for the next call in the queue).
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically(tween(170)) { it } + fadeIn(tween(115)),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.height(14.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun PostCallSheet(call: CallInfo, positionLabel: String?, onNext: () -> Unit) {
    val ctx = LocalContext.current

    var typedName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    val callerName = call.name.takeIf { it.isNotBlank() }
    val callerNumber = call.number.takeIf { it.isNotBlank() }
    val title = callerName ?: callerNumber ?: "Unknown caller"
    val needsName = callerName == null && callerNumber == null

    val baseLabel = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming call"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "Missed call"
        else -> "Call"
    }
    val typeLabel = if (call.viaWhatsApp) "WhatsApp ${baseLabel.lowercase()}" else baseLabel
    val subtitle = buildString {
        append(typeLabel)
        if (callerName != null && callerNumber != null) append(" · ").append(callerNumber)
        if (call.durationSec > 0) append(" · ").append(Fmt.duration(call.durationSec))
    }

    fun schedule(whenMs: Long) {
        val name = callerName ?: typedName
        ReminderStore.add(
            name = name,
            number = callerNumber ?: "",
            note = note,
            dueAt = whenMs
        )
        Toast.makeText(ctx, "Reminder set · ${Fmt.dueLabel(whenMs)}", Toast.LENGTH_SHORT).show()
        onNext()
    }

    val options = remember { QuickTimes.options(ctx) }

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
            Avatar(text = callerName ?: callerNumber ?: "?", size = 48.dp)
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

        Spacer(Modifier.height(16.dp))

        if (needsName) {
            OutlinedTextField(
                value = typedName,
                onValueChange = { typedName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Who was this? (optional)") },
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("What did they promise? (optional)") },
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = "Remind me to call back",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(3).forEach { rowOptions ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowOptions.forEach { o ->
                        OptionTile(
                            title = o.title,
                            subtitle = o.subtitle,
                            modifier = Modifier.weight(1f)
                        ) { schedule(o.whenMs) }
                    }
                    // Keeps tiles the same width when the last row isn't full.
                    repeat(3 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showPicker = true }) {
                Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick date & time")
            }
            TextButton(onClick = onNext) { Text("Skip") }
        }
    }

    if (showPicker) {
        DateTimePickerFlow(
            initialMs = QuickTime.OnDay(1, 10).toOption().whenMs, // Tomorrow 10 am
            onPicked = { showPicker = false; schedule(it) },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
internal fun OptionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
