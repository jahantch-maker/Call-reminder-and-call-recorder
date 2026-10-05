package com.callbackreminder.app

import android.provider.CallLog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PostCallScreen(kind: String, test: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current

    var info by remember {
        mutableStateOf<CallInfo?>(
            if (test) CallInfo("0300 1234567", "Test Caller", CallLog.Calls.OUTGOING_TYPE, 125L, System.currentTimeMillis())
            else null
        )
    }
    var lookupDone by remember { mutableStateOf(test) }
    var typedName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }

    // The phone writes the call into its log a moment after hang-up, so we retry briefly.
    LaunchedEffect(Unit) {
        if (!test) {
            var tries = 0
            while (info == null && tries < 6) {
                delay(if (tries == 0) 600L else 700L)
                info = withContext(Dispatchers.IO) { CallLookup.latest(ctx) }
                tries++
            }
            lookupDone = true
        }
    }

    val known = info
    val callerName = known?.name?.takeIf { it.isNotBlank() }
    val callerNumber = known?.number?.takeIf { it.isNotBlank() }
    val title = callerName ?: callerNumber ?: when {
        !lookupDone -> "Looking up the call…"
        else -> "Unknown caller"
    }
    val needsName = lookupDone && callerName == null && callerNumber == null

    val typeLabel = when (known?.type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming call"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "Missed call"
        else -> when (kind) {
            "incoming" -> "Incoming call"
            "missed" -> "Missed call"
            else -> "Outgoing call"
        }
    }
    val subtitle = buildString {
        append(typeLabel)
        if (callerName != null && callerNumber != null) append(" · ").append(callerNumber)
        val d = known?.durationSec ?: 0L
        if (d > 0) append(" · ").append(Fmt.duration(d))
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
        onClose()
    }

    val noRipple = remember { MutableInteractionSource() }
    val options = remember { quickOptions() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = noRipple, indication = null) { onClose() },
        contentAlignment = Alignment.BottomCenter
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
                    IconButton(onClick = onClose) {
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

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    options.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { o ->
                                OptionTile(
                                    title = o.title,
                                    subtitle = o.subtitle,
                                    modifier = Modifier.weight(1f)
                                ) { schedule(o.whenMs) }
                            }
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
                    TextButton(onClick = onClose) { Text("Skip") }
                }
            }
        }
    }

    if (showPicker) {
        DateTimePickerFlow(
            initialMs = options[2].whenMs,
            onPicked = { showPicker = false; schedule(it) },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun OptionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
