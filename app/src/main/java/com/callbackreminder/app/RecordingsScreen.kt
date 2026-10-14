package com.callbackreminder.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
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
    onBack: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val items by RecordingStore.items.collectAsState()
    val player by RecordingPlayer.state.collectAsState()
    val speed by RecordingPlayer.speed.collectAsState()
    val status = remember(resumeKey) { SetupStatus.read(ctx) }

    var renameFor by remember { mutableStateOf<Recording?>(null) }
    var deleteFor by remember { mutableStateOf<Recording?>(null) }
    var noteFor by remember { mutableStateOf<Recording?>(null) }
    // Long-press a recording to start selecting; the list then shows a selection bar on top.
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    // Move the playback bar while a recording plays; stop playing when leaving the screen.
    LaunchedEffect(Unit) {
        RecordingPlayer.loadSpeed(ctx)
        while (true) {
            delay(250L)
            RecordingPlayer.tick()
        }
    }
    DisposableEffect(Unit) { onDispose { RecordingPlayer.stop() } }

    var query by ListFilters.recQuery
    var dateFilter by ListFilters.recDate
    var lengthFilter by ListFilters.recLength
    var sort by ListFilters.recSort
    var starredOnly by ListFilters.recStarred
    val filtering = query.isNotBlank() || dateFilter.active || lengthFilter != LengthFilter.ANY || starredOnly
    val q = query.trim()
    val sorted = items
        .filter { r ->
            (q.isEmpty() || r.displayName.contains(q, ignoreCase = true) ||
                r.name.contains(q, ignoreCase = true) || numberMatches(r.number, q) ||
                r.note.contains(q, ignoreCase = true)) &&
                dateFilter.matches(r.startedAt) &&
                (lengthFilter.range?.contains(r.durationSec) ?: true) &&
                (!starredOnly || r.starred)
        }
        .let { list ->
            when (sort) {
                RecordingSort.NEWEST -> list.sortedByDescending { it.startedAt }
                RecordingSort.OLDEST -> list.sortedBy { it.startedAt }
                RecordingSort.NAME_AZ -> list.sortedBy { it.displayName.lowercase() }
                RecordingSort.NAME_ZA -> list.sortedByDescending { it.displayName.lowercase() }
                RecordingSort.LONGEST -> list.sortedByDescending { it.durationSec }
                RecordingSort.SHORTEST -> list.sortedBy { it.durationSec }
            }
        }
        // Pinned recordings stay on top, in the chosen order among themselves.
        .sortedByDescending { it.pinned }

    // Recordings deleted elsewhere (auto-delete) drop out of the selection.
    LaunchedEffect(items) {
        val liveIds = items.map { it.id }.toSet()
        if (selected.any { it !in liveIds }) selected = selected.intersect(liveIds)
    }
    BackHandler(enabled = selecting) { selected = emptySet() }

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
                if (selecting) {
                    val chosen = items.filter { it.id in selected }
                    SelectionBar(
                        count = selected.size,
                        allStarred = chosen.isNotEmpty() && chosen.all { it.starred },
                        allPinned = chosen.isNotEmpty() && chosen.all { it.pinned },
                        onClose = { selected = emptySet() },
                        onSelectAll = { selected = sorted.map { it.id }.toSet() },
                        onStar = { RecordingStore.setStarred(selected, !chosen.all { it.starred }) },
                        onPin = { RecordingStore.setPinned(selected, !chosen.all { it.pinned }) },
                        onDelete = { confirmBulkDelete = true },
                    )
                    return@item
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Column(Modifier.padding(start = if (onBack == null) 4.dp else 0.dp)) {
                        Text(
                            "Recordings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (filtering) "${sorted.size} of ${items.size} shown"
                            else "${items.size} saved · also in your Files app under Recordings / Call Recorder",
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

            if (items.isNotEmpty()) {
                item(key = "filters") {
                    RecordingFilters(
                        query = query,
                        onQuery = { query = it },
                        date = dateFilter,
                        onDate = { dateFilter = it },
                        length = lengthFilter,
                        onLength = { lengthFilter = it },
                        sort = sort,
                        onSort = { sort = it },
                        starredOnly = starredOnly,
                        onStarredOnly = { starredOnly = it },
                    )
                }
            }

            if (sorted.isEmpty() && items.isNotEmpty()) {
                item(key = "no-match") {
                    Text(
                        "No recordings match these filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            if (items.isEmpty()) {
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
                        selecting = selecting,
                        isSelected = r.id in selected,
                        onToggleSelect = {
                            selected = if (r.id in selected) selected - r.id else selected + r.id
                        },
                        onStar = { RecordingStore.setStarred(setOf(r.id), !r.starred) },
                        onPin = { RecordingStore.setPinned(setOf(r.id), !r.pinned) },
                        onNote = { noteFor = r },
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
        val startText = remember(r.id) { r.title.ifBlank { r.displayName } }
        var text by remember(r.id) { mutableStateOf(startText) }
        val cancel = rememberDiscardGuard(changed = { text != startText }, onClose = { renameFor = null })
        AlertDialog(
            onDismissRequest = cancel,
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            title = { Text("Rename recording") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    trailingIcon = voiceMic(text) { text = it },
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
            dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
        )
    }

    noteFor?.let { r ->
        var text by remember(r.id) { mutableStateOf(r.note) }
        val cancel = rememberDiscardGuard(changed = { text != r.note }, onClose = { noteFor = null })
        AlertDialog(
            onDismissRequest = cancel,
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            title = { Text("Note") },
            text = {
                Column {
                    Text(
                        "${r.displayName} · ${Fmt.dueLabel(r.startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        trailingIcon = voiceMic(text) { text = it },
                        placeholder = { Text("What was this call about?") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    RecordingStore.setNote(r.id, text)
                    noteFor = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } }
        )
    }

    if (confirmBulkDelete) {
        val chosen = items.filter { it.id in selected }
        val starredCount = chosen.count { it.starred }
        val toDelete = chosen.size - starredCount
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = {
                Text(
                    if (toDelete == 0) "Nothing to delete"
                    else if (toDelete == 1) "Delete 1 recording?"
                    else "Delete $toDelete recordings?"
                )
            },
            text = {
                Text(
                    when {
                        toDelete == 0 -> "All the selected recordings are starred, so they are kept. " +
                            "Unstar a recording first to delete it."
                        starredCount > 0 -> "The audio files are deleted from your phone. " +
                            "$starredCount starred ${if (starredCount == 1) "recording is" else "recordings are"} kept."
                        else -> "The audio files are deleted from your phone."
                    }
                )
            },
            confirmButton = {
                if (toDelete > 0) {
                    TextButton(onClick = {
                        val kept = RecordingStore.deleteUnstarred(ctx, selected)
                        confirmBulkDelete = false
                        selected = emptySet()
                        if (kept > 0) Toast.makeText(ctx, "$kept starred kept", Toast.LENGTH_SHORT).show()
                    }) { Text("Delete") }
                } else {
                    TextButton(onClick = { confirmBulkDelete = false }) { Text("OK") }
                }
            },
            dismissButton = if (toDelete > 0) {
                { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } }
            } else null
        )
    }

    deleteFor?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(if (r.starred) "Delete this starred recording?" else "Delete this recording?") },
            text = {
                Text(
                    (if (r.starred) "This recording is starred. " else "") +
                        "${r.displayName} · ${clock(r.durationSec)}. The audio file is deleted from your phone."
                )
            },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    r: Recording,
    selecting: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onStar: () -> Unit,
    onPin: () -> Unit,
    onNote: () -> Unit,
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
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.combinedClickable(
            onClick = { if (selecting) onToggleSelect() },
            onLongClick = onToggleSelect,
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                } else {
                    Avatar(text = r.displayName, size = 44.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (r.pinned) {
                            Icon(
                                Icons.Rounded.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            r.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (r.starred) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Rounded.Star,
                                contentDescription = "Starred",
                                tint = Color(0xFFF5B400),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
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
                if (r.finished && !selecting) {
                    FilledIconButton(onClick = onPlay) {
                        Icon(
                            if (isCurrent && player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isCurrent && player.playing) "Pause" else "Play"
                        )
                    }
                }
                if (!selecting) Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (r.starred) "Unstar" else "Star") },
                            leadingIcon = {
                                Icon(
                                    if (r.starred) Icons.Rounded.StarBorder else Icons.Rounded.Star,
                                    contentDescription = null
                                )
                            },
                            onClick = { menu = false; onStar() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (r.pinned) "Unpin" else "Pin to top") },
                            leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                            onClick = { menu = false; onPin() }
                        )
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

            // Notes stay small: a "+ Add note" link, or the note itself in up to two lines.
            val noteClick = Modifier.clickable { if (selecting) onToggleSelect() else onNote() }
            if (r.note.isBlank()) {
                if (r.finished && !selecting) {
                    Text(
                        "+ Add note",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 56.dp, top = 4.dp).then(noteClick).padding(vertical = 2.dp)
                    )
                }
            } else {
                Text(
                    r.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 56.dp, top = 4.dp).fillMaxWidth().then(noteClick)
                        .padding(vertical = 2.dp)
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

/** A typed number matches by its digits, so "0300 123" finds "+92 300 1234567" too (leading 0 ignored). */
private fun numberMatches(number: String, query: String): Boolean {
    if (query.any { it.isLetter() }) return false
    val q = query.filter { it.isDigit() }.trimStart('0')
    return q.isNotEmpty() && number.filter { it.isDigit() }.contains(q)
}

/** Search by name or number, then Date, Length and Sort chips. */
@Composable
private fun RecordingFilters(
    query: String,
    onQuery: (String) -> Unit,
    date: DateFilter,
    onDate: (DateFilter) -> Unit,
    length: LengthFilter,
    onLength: (LengthFilter) -> Unit,
    sort: RecordingSort,
    onSort: (RecordingSort) -> Unit,
    starredOnly: Boolean,
    onStarredOnly: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Search name, number or note") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } }
            } else voiceMic(query, onQuery),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = starredOnly,
                onClick = { onStarredOnly(!starredOnly) },
                label = { Text("Starred") },
                leadingIcon = {
                    Icon(
                        if (starredOnly) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            DateFilterChip("Date", date, ListFilters.CREATED_PRESETS, onDate)
            ChoiceChip(
                label = if (length == LengthFilter.ANY) "Length" else "Length: ${length.label}",
                selected = length != LengthFilter.ANY,
                options = LengthFilter.entries.map { it.label },
                current = length.ordinal,
                onPick = { onLength(LengthFilter.entries[it]) },
            )
            ChoiceChip(
                label = "Sort: ${sort.label}",
                selected = sort != RecordingSort.NEWEST,
                options = RecordingSort.entries.map { it.label },
                current = sort.ordinal,
                onPick = { onSort(RecordingSort.entries[it]) },
            )
            if (date.active || length != LengthFilter.ANY || query.isNotEmpty() || starredOnly) {
                ClearChip { onQuery(""); onDate(DateFilter()); onLength(LengthFilter.ANY); onStarredOnly(false) }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, options: List<String>, current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o) },
                    trailingIcon = if (i == current) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else null,
                    onClick = { open = false; onPick(i) }
                )
            }
        }
    }
}

/** Shown instead of the title while recordings are selected. */
@Composable
private fun SelectionBar(
    count: Int,
    allStarred: Boolean,
    allPinned: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onStar: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Cancel selection") }
        Text(
            "$count selected",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, contentDescription = "Select all") }
        IconButton(onClick = onStar) {
            Icon(
                if (allStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (allStarred) "Unstar" else "Star"
            )
        }
        IconButton(onClick = onPin) {
            Icon(
                Icons.Rounded.PushPin,
                contentDescription = if (allPinned) "Unpin" else "Pin to top",
                tint = if (allPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
    }
}
