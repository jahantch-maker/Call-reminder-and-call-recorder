package com.callbackreminder.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val AskBg = Color(0xFF6B4A12)
private val AskFg = Color(0xFFFFC93C)
private val StuckBg = Color(0xFF5C1A22)
private val StuckFg = Color(0xFFFF8A8A)

/** One line of the Timeline: an automatic history line or a message, in time order. */
private sealed class TimelineItem(val at: Long) {
    class Line(at: Long, val text: String) : TimelineItem(at)
    class Msg(val m: TeamMsg) : TimelineItem(m.at)
}

/**
 * A team task's Timeline as a conversation: grey history lines, typed messages, voice notes and photos, with ❓ Ask
 * and ⚠ Stuck for the person doing it. Everyone on the task sees it; managers above read it.
 */
@Composable
fun TeamTimelineScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val taskId by TeamUi.timelineOf.collectAsState()
    val tasks by TeamRepo.tasks.collectAsState()
    val me = TeamRepo.uid
    val t = tasks.firstOrNull { it.id == taskId }
    var msgs by remember(taskId) { mutableStateOf<List<TeamMsg>?>(null) }
    DisposableEffect(taskId) {
        val reg = taskId?.let { id -> TeamRepo.watchMsgs(id) { msgs = it } }
        onDispose { reg?.remove(); TeamMedia.stopPlaying() }
    }
    LaunchedEffect(t?.lastMsgAt) { t?.let { TeamSync.markRead(ctx, it.id, maxOf(it.lastMsgAt, 1L)) } }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(if (t != null && t.toUid == me) "My task" else "Follow-up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (t == null) {
            Text("This task isn't in your team list any more.", modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            TeamChipsRow(
                buildList {
                    add(RowChip(if (t.toUid == me) "↘ ${t.fromFirst}" else "👤 ${t.toFirst}" + if (t.passedTo.isNotBlank()) " → ${t.passedTo}" else "", ChipTone.BLUE))
                    if (t.stuck) add(RowChip("⚠ Stuck ${Fmt.timeOnly(t.stuckAt)}", ChipTone.RED))
                    if (t.asks) add(RowChip(if (t.fromUid == me) "❓ Needs your answer" else "❓ Asked ${Fmt.timeOnly(t.askAt)}", ChipTone.ORANGE))
                    if (t.deadline > 0L && t.open) add(RowChip("Deadline ${TeamUi.shortDate(t.deadline)}", ChipTone.MUTED))
                    if (t.photoProof && t.open) add(RowChip("📷 Photo proof on Done", ChipTone.MUTED))
                }
            )
            Text("TIMELINE · CONVERSATION", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
        val items = remember(t.events, msgs) {
            (t.events.map { TimelineItem.Line(it.at, it.text) } + msgs.orEmpty().map { TimelineItem.Msg(it) }).sortedBy { it.at }
        }
        val list = rememberLazyListState()
        LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1) }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { if (it is TimelineItem.Msg) "m" + it.m.id else "l${it.at}${(it as TimelineItem.Line).text.hashCode()}" }) { item ->
                when (item) {
                    is TimelineItem.Line -> Text(
                        item.text + " · " + Fmt.timeOnly(item.at), fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    is TimelineItem.Msg -> TeamBubble(item.m, mine = item.m.fromUid == me)
                }
            }
            if (msgs == null) item { Text("Loading…", color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            item {
                Text("Photos and voice notes are kept ${Team.MEDIA_DAYS} days after the task closes. Text stays.",
                    fontSize = 11.sp, color = cs.outline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        val canTalk = me == t.fromUid || me == t.toUid
        if (canTalk) TeamComposer(t, isDoer = me == t.toUid)
        else Text("Only ${t.fromFirst} and ${t.toFirst} write here. You can read it.", fontSize = 12.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(16.dp))
    }
}

@androidx.compose.foundation.layout.ExperimentalLayoutApi
@Composable
private fun TeamChipsFlow(chips: List<RowChip>) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { c -> TeamSmallChip(c) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TeamChipsRow(chips: List<RowChip>) = TeamChipsFlow(chips)

@Composable
internal fun TeamSmallChip(c: RowChip) = SmallChip(c)

/** One message: yours on the right, theirs on the left; ❓ Ask amber, ⚠ Stuck red; voice with ▶, photos tap to enlarge. */
@Composable
private fun TeamBubble(m: TeamMsg, mine: Boolean) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val playing by TeamMedia.playing.collectAsState()
    var big by remember { mutableStateOf(false) }
    val (bg, label) = when (m.tag) {
        Team.ASK -> AskBg to "❓ ${m.fromFirst} asks"
        Team.STUCK -> StuckBg to "⚠ ${m.fromFirst} is stuck"
        Team.PROOF -> Color(0xFF1E5A3A) to "📷 ${m.fromFirst} · photo proof"
        Team.BRIEF -> (if (mine) cs.primaryContainer else cs.surface) to "${m.fromFirst} · with the task"
        else -> (if (mine) cs.primaryContainer else cs.surface) to (if (mine) null else m.fromFirst)
    }
    val fg = when (m.tag) { Team.ASK -> Color(0xFFFFF1C8); Team.STUCK -> Color(0xFFFFE1E1); Team.PROOF -> Color(0xFFDDFBEA); else -> if (mine) cs.onPrimaryContainer else cs.onSurface }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                label?.let {
                    Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = when (m.tag) {
                        Team.ASK -> AskFg; Team.STUCK -> StuckFg; else -> cs.primary
                    })
                }
                when {
                    m.kind == Team.VOICE && m.data != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        val on = playing == m.id
                        IconButton(onClick = { TeamMedia.play(ctx, m.id, m.data) }, modifier = Modifier.size(36.dp).clip(CircleShape).background(cs.primary)) {
                            Icon(if (on) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = if (on) "Stop" else "Play", tint = cs.onPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("▍▌▍▎▌▍▌▎▍▌▍▎", color = fg.copy(alpha = 0.7f), fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(TeamMedia.duration(m.durMs), color = fg, fontSize = 13.sp)
                    }
                    m.kind == Team.PHOTO && m.data != null -> {
                        val bmp = remember(m.id) { TeamMedia.bitmap(m.data)?.asImageBitmap() }
                        if (bmp != null) Image(
                            bmp, contentDescription = "Photo", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(220.dp, 160.dp).clip(RoundedCornerShape(10.dp)).clickable { big = true }
                        )
                    }
                    m.kind != Team.TEXT -> Text("(removed after ${Team.MEDIA_DAYS} days)", fontSize = 12.sp, color = fg.copy(alpha = 0.7f))
                }
                if (m.text.isNotBlank()) Text(m.text, color = fg, fontSize = 15.sp)
                Text(Fmt.timeOnly(m.at) + when (m.kind) { Team.VOICE -> " · voice"; Team.PHOTO -> " · photo"; else -> "" },
                    fontSize = 11.sp, color = fg.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.End))
            }
        }
    }
    if (big && m.data != null) {
        val bmp = remember(m.id) { TeamMedia.bitmap(m.data)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { big = false },
            text = { if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { big = false }) { Text("Close") } },
        )
    }
}

/**
 * The message box: type and ➤, or hold 🎤 for a voice note (up to 2 min), 📷 for a photo with a caption.
 * The person doing the task also gets ❓ Ask and ⚠ Stuck: tap one, then type or hold 🎤.
 */
@Composable
private fun TeamComposer(t: TeamTask, isDoer: Boolean) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var text by remember(t.id) { mutableStateOf("") }
    var tag by remember(t.id) { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val pickPhoto = rememberTeamPhotoPicker { photo = it }
    val other = if (isDoer) t.fromFirst else t.toFirst

    fun sendText() {
        if (text.isBlank()) return
        if (TeamRepo.send(t, Team.TEXT, text, tag)) { text = ""; tag = "" }
        else Toast.makeText(ctx, "Couldn't send: check Settings › Team", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        if (tag.isNotEmpty() || recording) {
            Row(
                Modifier.fillMaxWidth().background(if (tag == Team.STUCK) StuckBg else if (tag == Team.ASK) AskBg else cs.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var secs by remember { mutableLongStateOf(0L) }
                LaunchedEffect(recording) { while (recording) { secs = TeamMedia.elapsedMs() / 1000; delay(250) } }
                Text(
                    when {
                        recording -> "🔴 ${TeamMedia.duration(secs * 1000)} · release to send · max 2:00"
                        tag == Team.STUCK -> "⚠ Stuck · type it or hold 🎤"
                        else -> "❓ Ask · type it or hold 🎤"
                    },
                    color = if (tag == Team.STUCK) StuckFg else if (tag == Team.ASK) AskFg else cs.onSurface,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                if (!recording) TextButton(onClick = { tag = "" }) { Text("✕ cancel", color = cs.onSurface) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Message $other…") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = cs.surfaceVariant, unfocusedContainerColor = cs.surfaceVariant,
                ),
            )
            IconButton(onClick = pickPhoto) { Icon(Icons.Rounded.PhotoCamera, contentDescription = "Photo", tint = cs.onSurfaceVariant) }
            if (isDoer && t.open) {
                TeamTagButton("❓ Ask", AskFg, tag == Team.ASK) { tag = if (tag == Team.ASK) "" else Team.ASK }
                Spacer(Modifier.width(4.dp))
                TeamTagButton("⚠ Stuck", StuckFg, tag == Team.STUCK) { tag = if (tag == Team.STUCK) "" else Team.STUCK }
                Spacer(Modifier.width(4.dp))
            }
            if (text.isNotBlank()) {
                IconButton(onClick = { sendText() }, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3DD6B5))) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color(0xFF053D2E))
                }
            } else {
                TeamHoldToRecord(
                    onState = { recording = it },
                    onRecorded = { bytes, dur ->
                        TeamRepo.send(t, Team.VOICE, "", tag, bytes, dur)
                        tag = ""
                    },
                )
            }
        }
    }
    photo?.let { bytes ->
        TeamPhotoCaptionDialog(bytes, title = "Send a photo", button = "Send", onDismiss = { photo = null }) { caption ->
            TeamRepo.send(t, Team.PHOTO, caption, tag, bytes)
            tag = ""
            photo = null
        }
    }
}

@Composable
private fun TeamTagButton(label: String, color: Color, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = if (on) color.copy(alpha = 0.25f) else Color.Transparent, border = BorderStroke(1.dp, color),
    ) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** 🎤 hold to record, release to send (like WhatsApp). Asks for the microphone the first time. */
@Composable
internal fun TeamHoldToRecord(onState: (Boolean) -> Unit, onRecorded: (ByteArray, Long) -> Unit, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val ctx = LocalContext.current
    var active by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Toast.makeText(ctx, "Now hold 🎤 to record", Toast.LENGTH_SHORT).show()
    }
    Box(
        Modifier.size(if (active) size + 12.dp else size).clip(CircleShape)
            .background(if (active) Color(0xFFFF5A5A) else Color(0xFF3DD6B5))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    if (!TeamMedia.startRecording(ctx)) {
                        Toast.makeText(ctx, "The microphone is busy", Toast.LENGTH_SHORT).show()
                        return@detectTapGestures
                    }
                    active = true
                    onState(true)
                    val released = tryAwaitRelease()
                    active = false
                    onState(false)
                    if (!released) { TeamMedia.cancelRecording(); return@detectTapGestures }
                    val r = TeamMedia.stopRecording()
                    if (r == null) Toast.makeText(ctx, "Hold 🎤 while you speak", Toast.LENGTH_SHORT).show()
                    else onRecorded(r.first, r.second)
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = "Hold to record", tint = if (active) Color.White else Color(0xFF053D2E))
    }
}

/** Camera or gallery; the photo comes back shrunk to about 150 KB. Returns what opens the choice. */
@Composable
internal fun rememberTeamPhotoPicker(onPhoto: (ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var choose by remember { mutableStateOf(false) }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) busy = camUri
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) busy = uri
    }
    busy?.let { uri ->
        LaunchedEffect(uri) {
            val bytes = withContext(Dispatchers.IO) { TeamMedia.shrink(ctx, uri) }
            busy = null
            if (bytes != null) onPhoto(bytes) else Toast.makeText(ctx, "Couldn't read that photo", Toast.LENGTH_SHORT).show()
        }
    }
    if (choose) {
        AlertDialog(
            onDismissRequest = { choose = false },
            title = { Text("Add a photo") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        choose = false
                        runCatching {
                            val u = TeamMedia.cameraUri(ctx)
                            camUri = u
                            camera.launch(u)
                        }.onFailure { Toast.makeText(ctx, "No camera app found", Toast.LENGTH_SHORT).show() }
                    }, modifier = Modifier.weight(1f).height(72.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                            Text("Camera")
                        }
                    }
                    OutlinedButton(onClick = {
                        choose = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, modifier = Modifier.weight(1f).height(72.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Image, contentDescription = null)
                            Text("Gallery")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = false }) { Text("Cancel") } },
        )
    }
    return { choose = true }
}

/** The photo with a caption box before it goes (Send, or Send Done for photo proof). */
@Composable
internal fun TeamPhotoCaptionDialog(
    bytes: ByteArray, title: String, button: String, body: String? = null, onRetake: (() -> Unit)? = null,
    onDismiss: () -> Unit, onSend: (String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    val bmp = remember(bytes) { TeamMedia.bitmap(bytes)?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                body?.let { Text(it) }
                if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)))
                Text("Preview from this phone · goes up when data is on", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                onRetake?.let { TextButton(onClick = it) { Text("📷 Retake / Gallery") } }
                OutlinedTextField(caption, { caption = it }, label = { Text("Caption") }, trailingIcon = voiceMic(caption) { caption = it },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), maxLines = 3)
            }
        },
        confirmButton = { Button(onClick = { onSend(caption) }) { Text(button) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * ✅ Done with photo: for a task given with 📷 Photo proof needed. The photo goes on the Timeline, then Done.
 */
@Composable
internal fun TeamDoneWithPhoto(t: TeamTask, myTaskId: Long?, onDismiss: () -> Unit) {
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val pick = rememberTeamPhotoPicker { photo = it }
    LaunchedEffect(Unit) { pick() }
    val bytes = photo
    if (bytes == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("📷 Photo proof needed") },
            text = { Text("${t.fromFirst} asked for a photo with “${t.title}”. Take one or pick it from the gallery.") },
            confirmButton = { Button(onClick = pick) { Text("Add photo") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    } else {
        TeamPhotoCaptionDialog(
            bytes, title = "📷 Photo proof needed", button = "✓ Send Done",
            body = "${t.fromFirst} asked for a photo with ${t.title}.", onRetake = { photo = null; pick() },
            onDismiss = onDismiss,
        ) { caption ->
            TeamRepo.send(t, Team.PHOTO, caption, Team.PROOF, bytes)
            val sent = TeamRepo.tasks.value.firstOrNull { it.id == t.id } ?: t
            TeamRepo.markDone(sent)
            myTaskId?.let { MyTaskStore.setDone(it, true) }
            onDismiss()
        }
    }
}

/** A photo or voice note added in New follow-up, sent as the task's brief on the Timeline once it is given. */
data class TeamAttach(val kind: String, val data: ByteArray, val durMs: Long = 0L)

/** ATTACH in FOR <NAME>: 📷 photo, hold 🎤 for a voice note; what is added shows as small chips with ✕. */
@Composable
internal fun TeamAttachRow(attach: List<TeamAttach>, onAttach: (List<TeamAttach>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var recording by remember { mutableStateOf(false) }
    val pick = rememberTeamPhotoPicker { onAttach(attach + TeamAttach(Team.PHOTO, it)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipHeading("ATTACH", modifier = Modifier.align(Alignment.CenterVertically))
        TextButton(onClick = pick, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("📷 Photo") }
        TeamHoldToRecord(onState = { recording = it }, onRecorded = { b, d -> onAttach(attach + TeamAttach(Team.VOICE, b, d)) }, size = 34.dp)
        Text(if (recording) "Recording… release to add" else "hold 🎤", fontSize = 12.sp,
            color = if (recording) Color(0xFFFF5A5A) else cs.onSurfaceVariant)
    }
    if (attach.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        attach.forEachIndexed { i, a ->
            Surface(shape = RoundedCornerShape(10.dp), color = Color.Transparent, border = BorderStroke(1.dp, cs.outline)) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (a.kind == Team.VOICE) "🎤 ${TeamMedia.duration(a.durMs)}" else "📷 Photo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { onAttach(attach.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/**
 * 📷 photo proof for a team Dossier block: shown over any screen when such a block is ticked. The photo goes to
 * the Owner, then the tick goes through (its time is the tick time).
 */
@Composable
fun TeamDossierProofHost() {
    val ctx = LocalContext.current
    val ask by TeamDossier.asking.collectAsState()
    val (id, date) = ask ?: return
    val t = DossierStore.get(id)
    if (t == null) {
        LaunchedEffect(ask) { TeamDossier.asking.value = null }
        return
    }
    var photo by remember(ask) { mutableStateOf<ByteArray?>(null) }
    val pick = rememberTeamPhotoPicker { photo = it }
    val close = { TeamDossier.asking.value = null }
    val bytes = photo
    if (bytes == null) {
        AlertDialog(
            onDismissRequest = close,
            title = { Text("📷 ${t.title} · photo proof") },
            text = { Text("${TeamDossier.ownerFirst()} asked for a photo to tick ${t.title}. Take one or pick it from the gallery.") },
            confirmButton = { Button(onClick = pick) { Text("Add photo") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
    } else {
        TeamPhotoCaptionDialog(
            bytes, title = "📷 ${t.title} · photo proof", button = "✓ Tick",
            body = "${TeamDossier.ownerFirst()} asked for a photo to tick ${t.title}.", onRetake = { photo = null; pick() },
            onDismiss = close,
        ) { caption ->
            if (TeamDossier.sendProof(ctx, id, date, TeamDossier.PHOTO, bytes, caption)) {
                if (!DossierStore.tick(id, date)) Toast.makeText(ctx, "This day is locked in History", Toast.LENGTH_SHORT).show()
                TaskAlarms.cancelDossierNotification(ctx, id)
            } else Toast.makeText(ctx, "Couldn't send the photo", Toast.LENGTH_SHORT).show()
            close()
        }
    }
}
