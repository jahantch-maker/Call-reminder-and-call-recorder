package com.callbackreminder.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

private fun clock(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
fun RecordingsScreen(
    resumeKey: Int,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val items by RecordingStore.items.collectAsState()
    val player by RecordingPlayer.state.collectAsState()
    val speed by RecordingPlayer.speed.collectAsState()
    val status = remember(resumeKey) { SetupStatus.read(ctx) }

    var renameFor by remember { mutableStateOf<Recording?>(null) }
    var deleteFor by remember { mutableStateOf<Recording?>(null) }

    // Move the playback bar while a recording plays; stop playing when leaving the screen.
    LaunchedEffect(Unit) {
        RecordingPlayer.loadSpeed(ctx)
        while (true) {
            delay(250L)
            RecordingPlayer.tick()
        }
    }
    DisposableEffect(Unit) { onDispose { RecordingPlayer.stop() } }

    val sorted = items.sortedByDescending { it.startedAt }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column {
                        Text(
                            "Recordings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${sorted.size} saved · also in your Files app under Recordings / Call Recorder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!status.recordingReady) {
                item(key = "setup") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Finish recording setup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    "Allow the Microphone.".takeIf { !status.microphone },
                                    "Switch on the Accessibility service, so Android keeps the microphone working during calls."
                                        .takeIf { !status.accessibility },
                                ).joinToString(" "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenSettings) { Text("Open settings") }
                        }
                    }
                }
            }

            if (sorted.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Calls are recorded automatically once recording is set up.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(sorted, key = { it.id }) { r ->
                Box(Modifier.animateItem()) {
                    RecordingCard(
                        r = r,
                        player = player,
                        speed = speed,
                        onSpeed = { RecordingPlayer.setSpeed(ctx, it) },
                        onPlay = {
                            val err = RecordingPlayer.toggle(ctx, r)
                            if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
                        },
                        onSeek = { RecordingPlayer.seek(it) },
                        onRename = { renameFor = r },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/mp4"
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(r.uri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                ctx.startActivity(
                                    Intent.createChooser(send, "Share recording")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Could not share this recording.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { deleteFor = r },
                    )
                }
            }
        }
    }

    renameFor?.let { r ->
        var text by remember(r.id) { mutableStateOf(r.title.ifBlank { r.displayName }) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename recording") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RecordingStore.rename(r.id, text)
                    renameFor = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } }
        )
    }

    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete this recording?") },
            text = { Text("${r.displayName} · ${clock(r.durationSec)}. The audio file is deleted from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    RecordingStore.delete(ctx, r.id)
                    deleteFor = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RecordingCard(
    r: Recording,
    player: RecordingPlayer.State,
    speed: Float,
    onSpeed: (Float) -> Unit,
    onPlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCurrent = player.id == r.id
    var menu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(text = r.displayName, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOf(
                            r.typeLabel,
                            Fmt.dueLabel(r.startedAt),
                            if (r.finished) clock(r.durationSec) else "recording…",
                            sizeText(r.sizeBytes),
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (r.finished) {
                    FilledIconButton(onClick = onPlay) {
                        Icon(
                            if (isCurrent && player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isCurrent && player.playing) "Pause" else "Play"
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = { menu = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            onClick = { menu = false; onShare() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }

            if (r.silent) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "No sound was captured on this call. The next call may use a different source " +
                        "(Settings → Call recording).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (isCurrent && player.durationMs > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        clock(player.positionMs / 1000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = player.positionMs.toFloat().coerceIn(0f, player.durationMs.toFloat()),
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..player.durationMs.toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(
                        clock(player.durationMs / 1000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Speed",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(1f to "1x", 1.25f to "1.25x", 1.5f to "1.5x").forEach { (value, label) ->
                        FilterChip(
                            selected = speed == value,
                            onClick = { onSpeed(value) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}
