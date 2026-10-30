package com.callbackreminder.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One private note on your own task: typed text, a voice note or a photo. Kept on this phone only. */
data class TaskNote(val at: Long, val kind: String, val text: String = "", val file: String = "", val durMs: Long = 0L, val wave: String = "")

/** Private notes per task ("l:<link>" for a follow-up, "m:<id>" for a My task), with their voice and photo files. */
object TaskNotes {
    const val TEXT = "text"
    const val VOICE = "voice"
    const val PHOTO = "photo"

    private fun p(c: Context) = c.getSharedPreferences("task_notes", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "task_notes").apply { mkdirs() }

    fun list(c: Context, key: String): List<TaskNote> = runCatching {
        val a = JSONArray(p(c).getString(key, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TaskNote(o.optLong("at"), o.optString("k"), o.optString("t"), o.optString("f"), o.optLong("d"), o.optString("w"))
        }
    }.getOrDefault(emptyList())

    fun add(c: Context, key: String, n: TaskNote, bytes: ByteArray? = null) {
        val note = if (bytes != null) {
            val f = File(dir(c), "${System.currentTimeMillis()}.${if (n.kind == PHOTO) "jpg" else "aac"}")
            f.writeBytes(bytes)
            n.copy(file = f.name)
        } else n
        val a = JSONArray()
        (list(c, key) + note).forEach {
            a.put(JSONObject().put("at", it.at).put("k", it.kind).put("t", it.text).put("f", it.file).put("d", it.durMs).put("w", it.wave))
        }
        p(c).edit().putString(key, a.toString()).apply()
    }

    fun bytes(c: Context, n: TaskNote): ByteArray? = runCatching { File(dir(c), n.file).readBytes() }.getOrNull()

    /** Takes one note away completely (its voice or photo file too). */
    fun remove(c: Context, key: String, at: Long) {
        val all = list(c, key)
        all.filter { it.at == at && it.file.isNotEmpty() }.forEach { runCatching { File(dir(c), it.file).delete() } }
        val a = JSONArray()
        all.filter { it.at != at }.forEach {
            a.put(JSONObject().put("at", it.at).put("k", it.kind).put("t", it.text).put("f", it.file).put("d", it.durMs).put("w", it.wave))
        }
        p(c).edit().putString(key, a.toString()).apply()
    }
}

/** A line of a task's history: what happened, when, and an optional quote. */
data class TimelineLine(val at: Long, val text: String, val note: String = "", val bad: Boolean = false)

/**
 * The Timeline of a task that stays with you, drawn like the team Timeline: the task as the big heading with its
 * chips, then a conversation oldest first under day chips (Today, Yesterday, the date): grey history lines in the
 * middle, your private notes (text, voice, photo) on the right with their time. A long press selects notes
 * (Copy, Share, Undo). "Add a note…" stays on this phone only. A task given to or from the team opens the team
 * Timeline instead (see the callers).
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TaskTimelineWindow(
    noteKey: String,
    title: String,
    chips: List<RowChip>,
    lines: List<TimelineLine>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    val notes = remember(noteKey, tick) { TaskNotes.list(ctx, noteKey) }
    var draft by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ByteArray?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun added(n: TaskNote) {
        tick++
        scope.offerUndo(snack, "Added") { TaskNotes.remove(ctx, noteKey, n.at); tick++ }
    }
    val pickPhoto = rememberTeamPhotoPicker { b ->
        val n = TaskNote(System.currentTimeMillis(), TaskNotes.PHOTO)
        TaskNotes.add(ctx, noteKey, n, b)
        added(n)
    }
    Dialog(
        onDismissRequest = { TeamMedia.stopPlaying(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
            // safeBottomPadding keeps the bar above the phone's buttons and the keyboard inside this window too.
            Column(Modifier.fillMaxSize().statusBarsPadding().safeBottomPadding(0.dp)) {
                val picked = notes.filter { it.at in selected }
                if (picked.isNotEmpty()) TimelineSelectionBar(
                    count = picked.size,
                    actions = buildList {
                        if (picked.any { it.kind == TaskNotes.TEXT }) add(SelAction("⧉", "Copy") {
                            copyText(ctx, picked.filter { it.kind == TaskNotes.TEXT }.joinToString("\n") { it.text })
                            selected = emptySet()
                        })
                        picked.singleOrNull()?.let { n ->
                            add(SelAction("⇪", "Share") {
                                val b = TaskNotes.bytes(ctx, n)
                                shareMsg(ctx, TeamMsg(id = "n${n.at}", at = n.at, fromUid = "", fromName = "", kind = when (n.kind) {
                                    TaskNotes.VOICE -> Team.VOICE; TaskNotes.PHOTO -> Team.PHOTO; else -> Team.TEXT
                                }, tag = "", text = n.text, data = b, durMs = n.durMs))
                                selected = emptySet()
                            })
                        }
                        // Private notes have no trace: Undo takes them away completely.
                        add(SelAction("↶", "Undo") { picked.forEach { TaskNotes.remove(ctx, noteKey, it.at) }; selected = emptySet(); tick++ })
                    },
                    onClose = { selected = emptySet() },
                ) else Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { TeamMedia.stopPlaying(); onDismiss() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Text("TIMELINE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                    if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chips.forEach { SmallChip(it) }
                    }
                }
                // History and notes together, oldest first, with a day chip before each day.
                val rows = remember(lines, notes) {
                    withDayChips((lines.map { it as Any } + notes.map { it as Any }).sortedBy { if (it is TimelineLine) it.at else (it as TaskNote).at }) {
                        if (it is TimelineLine) it.at else (it as TaskNote).at
                    }
                }
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = list, modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (rows.isEmpty()) item {
                            Text("Nothing yet. From now on, every ring, snooze, call and move is listed here.",
                                color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                        }
                        items(rows, key = {
                            when (it) {
                                is DayChip -> "d${TimelineDates.day(it.at)}"
                                is TaskNote -> "n${it.at}"
                                else -> "l${(it as TimelineLine).at}${it.text.hashCode()}"
                            }
                        }) { x ->
                            when (x) {
                                is DayChip -> DayChipView(x.label)
                                is TimelineLine -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(TimelineDates.unfreeze(x.text, x.at) + " · " + Fmt.timeOnly(x.at), fontSize = 12.5.sp,
                                        color = if (x.bad) cs.error else cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    if (x.note.isNotBlank()) Text("“${x.note}”", fontStyle = FontStyle.Italic, fontSize = 13.sp, color = cs.onSurfaceVariant)
                                }
                                is TaskNote -> NoteRow(x, x.at in selected, selected.isNotEmpty(),
                                    onToggle = { selected = if (x.at in selected) selected - x.at else selected + x.at },
                                    onPhoto = { viewing = TaskNotes.bytes(ctx, x) })
                            }
                        }
                    }
                    FloatingDayChip(list, rows, Modifier.align(Alignment.TopCenter))
                }
                Text(if (recording) "🔴 Recording…" else "🔒 Notes stay on this phone only",
                    fontSize = 12.sp, color = if (recording) cs.error else cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                androidx.compose.material3.SnackbarHost(snack, Modifier.padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(draft, { draft = it }, placeholder = { Text("Add a note…") }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp), maxLines = 4, trailingIcon = voiceMic(draft) { draft = it })
                    if (draft.isNotBlank()) IconButton(onClick = {
                        val n = TaskNote(System.currentTimeMillis(), TaskNotes.TEXT, draft.trim())
                        TaskNotes.add(ctx, noteKey, n)
                        draft = ""
                        added(n)
                    }) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Add note", tint = cs.primary) }
                    else {
                        IconButton(onClick = pickPhoto) { Icon(Icons.Rounded.PhotoCamera, contentDescription = "Add a photo", tint = cs.primary) }
                        TeamHoldToRecord(onState = { recording = it }, onRecorded = { b, d ->
                            val n = TaskNote(System.currentTimeMillis(), TaskNotes.VOICE, durMs = d, wave = TeamMedia.waveFor(d))
                            TaskNotes.add(ctx, noteKey, n, b)
                            added(n)
                        }, size = 44.dp)
                    }
                }
            }
        }
    }
    viewing?.let { b -> PhotoViewer(listOf(b), onDismiss = { viewing = null }) }
}

/** One private note on the right like a sent message: the long press (anywhere on the row) selects it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(n: TaskNote, selected: Boolean, selecting: Boolean, onToggle: () -> Unit, onPhoto: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().background(if (selected) cs.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onToggle),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = cs.primaryContainer, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                when (n.kind) {
                    TaskNotes.VOICE -> {
                        val bytes = remember(n.file) { TaskNotes.bytes(ctx, n) }
                        VoicePlayer("note-${n.at}", bytes, n.durMs, n.wave, cs.onPrimaryContainer, modifier = Modifier.widthIn(min = 230.dp)) {
                            Text(Fmt.timeOnly(n.at), fontSize = 11.sp, color = cs.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    }
                    TaskNotes.PHOTO -> {
                        val bmp = remember(n.file) { TaskNotes.bytes(ctx, n)?.let { TeamMedia.bitmap(it) }?.asImageBitmap() }
                        if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(220.dp, 160.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = if (selecting) onToggle else onPhoto))
                        else Text("📷 Photo", modifier = Modifier.padding(4.dp))
                    }
                    else -> Text(n.text, fontSize = 15.sp, color = cs.onPrimaryContainer)
                }
                if (n.kind != TaskNotes.VOICE) Text(Fmt.timeOnly(n.at), fontSize = 11.sp, color = cs.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End))
            }
        }
        if (selecting) Box(Modifier.matchParentSize().clickable(onClick = onToggle))
    }
}

/** The Timeline of a follow-up (its call and its texts together), or the team Timeline when it is a team task. */
@Composable
fun FollowUpTimeline(call: Reminder?, text: SmsReminder?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val link = call?.linkId?.takeIf { it != 0L } ?: text?.linkId?.takeIf { it != 0L } ?: 0L
    val tasks by TeamRepo.tasks.collectAsState()
    val team = remember(link, tasks) { if (TeamRepo.activeNow) TeamUi.taskOfLink(ctx, link, tasks) else null }
    if (team != null) {
        LaunchedEffect(team.id) { TeamUi.openTimeline(team.id); onDismiss() }
        return
    }
    val c = call ?: link.takeIf { it != 0L }?.let { l -> ReminderStore.items.value.lastOrNull { it.linkId == l } }
    val t = text ?: link.takeIf { it != 0L }?.let { FollowUps.text(it) }
    val lines = (c?.historyLines().orEmpty().map { TimelineLine(it.at, it.label, it.note, it.what.contains("didn't pick up")) } +
        t?.log.orEmpty().map { e ->
            TimelineLine(e.at, if (e.status == SmsLogEntry.Status.CALL_EVENT) e.detail else "💬 " + e.status.label,
                if (e.status == SmsLogEntry.Status.CALL_EVENT) "" else e.detail,
                e.status == SmsLogEntry.Status.FAILED || e.status == SmsLogEntry.Status.RETRYING)
        }).distinctBy { it.at to it.text }
    val person = c?.title ?: t?.title.orEmpty()
    val chips = listOfNotNull(
        c?.takeIf { !it.done }?.let { RowChip("📞 " + Fmt.dueLabel(it.dueAt), ChipTone.MUTED) },
        person.takeIf { it.isNotBlank() }?.let { RowChip("👤 " + (Names.first(it) ?: it), ChipTone.BLUE) },
        (c?.code?.takeIf { it.isNotEmpty() } ?: t?.code?.takeIf { it.isNotEmpty() })?.let { RowChip("#$it", ChipTone.BLUE) },
    )
    val title = c?.note?.takeIf { it.isNotBlank() } ?: t?.message?.takeIf { it.isNotBlank() } ?: c?.headline() ?: person
    val key = if (link != 0L) "l:$link" else c?.let { "c:${it.id}" } ?: "t:${t?.id}"
    TaskTimelineWindow(key, title, chips, lines, onDismiss)
}

/** A My task's Timeline, or the team Timeline when it came from the team. */
@Composable
fun MyTaskTimeline(t: MyTask, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val tasks by TeamRepo.tasks.collectAsState()
    val team = remember(t.id, tasks) { if (TeamRepo.activeNow) TeamUi.taskOfMyTask(ctx, t.id, tasks) else null }
    if (team != null) {
        LaunchedEffect(team.id) { TeamUi.openTimeline(team.id); onDismiss() }
        return
    }
    // Tasks from before v0.31 have no lines yet: at least when they were made.
    val lines = t.events.ifEmpty { listOf(TaskEvent(t.createdAt, "Created")) }.map { TimelineLine(it.at, it.text) }
    val chips = listOfNotNull(
        RowChip(if (t.done) "Done " + Fmt.dueLabel(t.doneAt) else if (t.timed) "☑ " + Fmt.dueLabel(t.dueAt) else "⏱ No time yet", ChipTone.MUTED),
        t.personTitle.takeIf { t.hasPerson }?.let { RowChip("👤 " + (Names.first(it) ?: it), ChipTone.BLUE) },
    )
    TaskTimelineWindow("m:${t.id}", t.title, chips, lines, onDismiss)
}

/** A follow-up waiting in To schedule: its history so far (kept from its call and texts), or the team Timeline. */
@Composable
fun ScheduledTimeline(s: ToScheduleItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val tasks by TeamRepo.tasks.collectAsState()
    val team = remember(s.linkId, tasks) { if (TeamRepo.activeNow) TeamUi.taskOfLink(ctx, s.linkId, tasks) else null }
    if (team != null) {
        LaunchedEffect(team.id) { TeamUi.openTimeline(team.id); onDismiss() }
        return
    }
    val lines = (listOf(TimelineLine(s.createdAt, "Saved, no time yet")) +
        s.callEvents.map { TimelineLine(it.at, it.label, it.note) } +
        s.textLog.map { TimelineLine(it.at, "💬 " + it.status.label, it.detail) })
    val chips = listOfNotNull(
        RowChip("⏱ No time yet", ChipTone.MUTED),
        s.title.takeIf { s.number.isNotBlank() || s.name.isNotBlank() }?.let { RowChip("👤 " + (Names.first(it) ?: it), ChipTone.BLUE) },
    )
    TaskTimelineWindow(if (s.linkId != 0L) "l:${s.linkId}" else "s:${s.id}", s.headline, chips, lines, onDismiss)
}
