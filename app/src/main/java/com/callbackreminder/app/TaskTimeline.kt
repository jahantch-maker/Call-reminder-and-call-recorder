package com.callbackreminder.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
data class TaskNote(val at: Long, val kind: String, val text: String = "", val file: String = "", val durMs: Long = 0L)

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
            TaskNote(o.optLong("at"), o.optString("k"), o.optString("t"), o.optString("f"), o.optLong("d"))
        }
    }.getOrDefault(emptyList())

    fun add(c: Context, key: String, n: TaskNote, bytes: ByteArray? = null) {
        val note = if (bytes != null) {
            val f = File(dir(c), "${System.currentTimeMillis()}.${if (n.kind == PHOTO) "jpg" else "m4a"}")
            f.writeBytes(bytes)
            n.copy(file = f.name)
        } else n
        val a = JSONArray()
        (list(c, key) + note).forEach {
            a.put(JSONObject().put("at", it.at).put("k", it.kind).put("t", it.text).put("f", it.file).put("d", it.durMs))
        }
        p(c).edit().putString(key, a.toString()).apply()
    }

    fun bytes(c: Context, n: TaskNote): ByteArray? = runCatching { File(dir(c), n.file).readBytes() }.getOrNull()
}

/** A line of a task's history: what happened, when, and an optional quote. */
data class TimelineLine(val at: Long, val text: String, val note: String = "", val bad: Boolean = false)

/**
 * The one full-window Timeline for your own tasks: a small TIMELINE label, the task as the big heading with its
 * chips, everything that happened (newest first), and your private notes with "Add a note…" (text, 🎤, 📷).
 * A task given to or from the team opens the team Timeline instead (see the callers).
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val playing by TeamMedia.playing.collectAsState()
    val pickPhoto = rememberTeamPhotoPicker { b ->
        TaskNotes.add(ctx, noteKey, TaskNote(System.currentTimeMillis(), TaskNotes.PHOTO), b)
        tick++
    }
    Dialog(
        onDismissRequest = { TeamMedia.stopPlaying(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                // History and notes together, newest first.
                val all = remember(lines, notes) {
                    (lines.map { it.at to (it as Any) } + notes.map { it.at to (it as Any) }).sortedByDescending { it.first }.map { it.second }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    if (all.isEmpty()) item {
                        Text("Nothing yet. From now on, every ring, snooze, call and move is listed here.",
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    items(all) { x ->
                        when (x) {
                            is TimelineLine -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(x.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (x.bad) cs.error else cs.onSurface)
                                Text(Fmt.dueLabel(x.at), fontSize = 12.sp, color = cs.onSurfaceVariant)
                                if (x.note.isNotBlank()) Text("“${x.note}”", fontStyle = FontStyle.Italic, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            is TaskNote -> NoteBubble(x, playing == "note-${x.at}", onPlay = {
                                TaskNotes.bytes(ctx, x)?.let { TeamMedia.play(ctx, "note-${x.at}", it) }
                            }, onPhoto = { viewing = TaskNotes.bytes(ctx, x) })
                        }
                        HorizontalDivider(color = cs.surfaceVariant)
                    }
                }
                Text(if (recording) "🔴 Recording… release to add" else "🔒 Notes stay on this phone only",
                    fontSize = 12.sp, color = if (recording) cs.error else cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(draft, { draft = it }, placeholder = { Text("Add a note…") }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp), maxLines = 4, trailingIcon = voiceMic(draft) { draft = it })
                    if (draft.isNotBlank()) IconButton(onClick = {
                        TaskNotes.add(ctx, noteKey, TaskNote(System.currentTimeMillis(), TaskNotes.TEXT, draft.trim()))
                        draft = ""
                        tick++
                    }) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Add note", tint = cs.primary) }
                    else {
                        IconButton(onClick = pickPhoto) { Icon(Icons.Rounded.PhotoCamera, contentDescription = "Add a photo", tint = cs.primary) }
                        TeamHoldToRecord(onState = { recording = it }, onRecorded = { b, d ->
                            TaskNotes.add(ctx, noteKey, TaskNote(System.currentTimeMillis(), TaskNotes.VOICE, durMs = d), b)
                            tick++
                        }, size = 44.dp)
                    }
                }
            }
        }
    }
    viewing?.let { b -> PhotoViewer(listOf(b), onDismiss = { viewing = null }) }
}

@Composable
private fun NoteBubble(n: TaskNote, playing: Boolean, onPlay: () -> Unit, onPhoto: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
            when (n.kind) {
                TaskNotes.VOICE -> Row(Modifier.clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (playing) "⏸" else "▶", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("🎤 Voice note · ${TeamMedia.duration(n.durMs)}", fontWeight = FontWeight.SemiBold)
                }
                TaskNotes.PHOTO -> {
                    val bmp = remember(n.file) { TaskNotes.bytes(ctx, n)?.let { TeamMedia.bitmap(it) }?.asImageBitmap() }
                    if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(180.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onPhoto))
                    else Text("📷 Photo", modifier = Modifier.padding(12.dp))
                }
                else -> Text(n.text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Text("📝 Your note · ${Fmt.dueLabel(n.at)}", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
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
