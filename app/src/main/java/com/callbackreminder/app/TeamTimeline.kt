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
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
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

/** ❓ Ask, ⚠ Stuck and 📷 proof bubbles: the shared amber / red / green tones, right by day and by night. */
private val AskBg: Color @Composable get() = Tones.amber.bg
private val AskFg: Color @Composable get() = Tones.amber.fg
private val StuckBg: Color @Composable get() = Tones.red.bg
private val StuckFg: Color @Composable get() = Tones.red.fg
private val ProofBg: Color @Composable get() = Tones.green.bg
private val ProofFg: Color @Composable get() = Tones.green.fg

/** One line of the Timeline: an automatic history line or a message, in time order. */
private sealed class TimelineItem(val at: Long) {
    class Line(at: Long, val text: String) : TimelineItem(at)
    class Msg(val m: TeamMsg) : TimelineItem(m.at)
}

/**
 * A team task's Timeline as a conversation: grey history lines, typed messages, voice notes and photos (yours on
 * the right with WhatsApp ticks, theirs on the left), with ❓ Ask, ⚠ Stuck and 📅 Extend deadline for the person
 * doing it. Everyone on the task sees it; managers above read it.
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
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selected by remember(taskId) { mutableStateOf<Set<String>>(emptySet()) }
    var replyTo by remember(taskId) { mutableStateOf<TeamMsg?>(null) }
    var infoOf by remember(taskId) { mutableStateOf<TeamMsg?>(null) }
    var taskFrom by remember(taskId) { mutableStateOf<String?>(null) }
    DisposableEffect(taskId) {
        val reg = taskId?.let { id -> TeamRepo.watchMsgs(id) { msgs = it } }
        onDispose { reg?.remove(); TeamMedia.stopPlaying() }
    }
    LaunchedEffect(t?.lastMsgAt, t?.nBy) {
        t?.let {
            TeamSync.markRead(ctx, it.id, maxOf(it.lastMsgAt, 1L))
            if (it.toUid == me) TeamSync.markOpened(ctx, it.id)
        }
    }
    // What you now see goes blue for the sender.
    LaunchedEffect(msgs) { val id = taskId; val list = msgs; if (id != null && list != null && TeamRepo.foreground) TeamRepo.markMsgsSeen(id, list) }

    Box(Modifier.fillMaxSize().background(cs.background)) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // ← a small TIMELINE label; the task itself is the big heading. A long press selects messages: this bar
        // then takes the place of the label.
        val picked = msgs.orEmpty().filter { it.id in selected }
        if (picked.isNotEmpty()) TimelineSelectionBar(
            count = picked.size,
            actions = buildList {
                val one = picked.singleOrNull()
                val live = picked.filter { !it.isDeleted }
                if (one != null && !one.isDeleted && t != null && (me == t.fromUid || me == t.toUid)) add(SelAction("↩", "Reply") { replyTo = one; selected = emptySet() })
                if (live.any { it.text.isNotBlank() }) add(SelAction("⧉", "Copy") {
                    copyText(ctx, live.filter { it.text.isNotBlank() }.joinToString("\n") { it.text })
                    selected = emptySet()
                })
                if (one != null && !one.isDeleted) add(SelAction("⇪", "Share") { shareMsg(ctx, one); selected = emptySet() })
                if (picked.all { Team.canUndo(it, me) }) add(SelAction("↶", "Undo") {
                    picked.forEach { m -> t?.let { TeamRepo.deleteMsg(it, m) } }
                    selected = emptySet()
                })
                if (one != null && one.fromUid == me && !one.isDeleted) add(SelAction("ⓘ", "Info") { infoOf = one; selected = emptySet() })
                if (one != null && !one.isDeleted && one.text.isNotBlank()) add(SelAction("＋", "Task") { taskFrom = one.text; selected = emptySet() })
            },
            onClose = { selected = emptySet() },
        ) else Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = cs.onBackground) }
            Text("TIMELINE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
        }
        if (t == null) {
            Text("This task isn't in your team list any more.", modifier = Modifier.padding(16.dp), color = cs.onBackground)
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = cs.onBackground)
            TeamChipsRow(
                buildList {
                    add(RowChip(TeamUi.chain(t, me)?.let { (if (t.toUid == me) "↘ " else "👤 ") + it }
                        ?: if (t.toUid == me) "↘ ${t.fromFirst} → You" else "👤 You → ${t.toFirst}", ChipTone.BLUE))
                    if (t.open) add(
                        if (t.due > 0L) RowChip("📅 Deadline ${TeamUi.shortWhen(t.due)}" + if (t.deadlineLocked) " 🔒" else "", ChipTone.ORANGE)
                        else RowChip((if (t.toUid == me) "📅 Pick your deadline" else "⏳ His deadline?") + if (t.deadline > 0L) " · Latest ${TeamUi.shortWhen(t.deadline)}" else "", ChipTone.ORANGE)
                    )
                    if (t.extCount > 0 && t.open) add(RowChip("Extended ${t.extCount}×", ChipTone.MUTED))
                    if (t.photoProof && t.open) add(RowChip("📷 Photo proof", ChipTone.MUTED))
                }
            )
            // v0.44: the other person's 🟢 Active now / Last active, like WhatsApp's line under the name.
            val members by TeamRepo.members.collectAsState()
            members.firstOrNull { it.uid == (if (t.toUid == me) t.fromUid else t.toUid) }?.let { m ->
                Text("${m.first} · ${TeamUi.lastSeenLabel(m)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        val rows = remember(t.events, msgs) {
            // One "First seen by …" line per person (it used to come back on every visit), real dates in old lines.
            val seen = HashSet<String>()
            val lines = t.events.filter { e -> !(e.text.startsWith("Seen by ") || e.text.startsWith("First seen by ")) || seen.add(e.text.removePrefix("First ").replaceFirstChar { c -> c.uppercase() }) }
                .map { TimelineItem.Line(it.at, TimelineDates.unfreeze(TeamRepo.withoutWrongOutside(it.text.let { x -> if (x.startsWith("Seen by ")) "First s" + x.substring(1) else x }), it.at)) }
            withDayChips((lines + msgs.orEmpty().map { TimelineItem.Msg(it) }).sortedBy { it.at }) { it.at }
        }
        val items = rows
        val photos = remember(msgs) { msgs.orEmpty().filter { it.kind == Team.PHOTO && it.data != null } }
        var viewing by remember { mutableStateOf<Int?>(null) }
        val list = rememberLazyListState()
        LaunchedEffect(items.size) { if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = {
                when (it) {
                    is DayChip -> "d${TimelineDates.day(it.at)}"
                    is TimelineItem.Msg -> "m" + it.m.id
                    else -> "l${(it as TimelineItem.Line).at}${it.text.hashCode()}"
                }
            }) { item ->
                when (item) {
                    is DayChip -> DayChipView(item.label)
                    is TimelineItem.Line -> Text(
                        item.text + " · " + Fmt.timeOnly(item.at), fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    is TimelineItem.Msg -> TeamBubble(t, item.m, mine = item.m.fromUid == me,
                        selected = item.m.id in selected, selecting = selected.isNotEmpty(),
                        onToggle = { selected = if (item.m.id in selected) selected - item.m.id else selected + item.m.id },
                        onPhoto = { viewing = photos.indexOfFirst { p -> p.id == item.m.id }.coerceAtLeast(0) })
                }
            }
            if (msgs == null) item { Text("Loading…", color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            item {
                Text("Photos and voice notes are kept ${Team.MEDIA_DAYS} days after the task closes. Text stays.",
                    fontSize = 11.sp, color = cs.outline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        FloatingDayChip(list, items, Modifier.align(Alignment.TopCenter))
        }
        // 📅 A deadline request waits: the giver approves or keeps the date; he can take it back.
        if (t.asking) TeamExtendRequest(t, me, onAnswered = { msg, before ->
            scope.offerUndoFor(snack, msg, 10L) { TeamRepo.undoDeadline(before) }
        })
        val canTalk = me == t.fromUid || me == t.toUid
        if (canTalk) TeamComposer(t, isDoer = me == t.toUid, replyTo = replyTo, onReplyDone = { replyTo = null },
            onSent = { id -> id?.let { mid -> scope.offerUndoFor(snack, "Sent", 5L) { TeamRepo.deleteMsgId(t, mid) } } },
            onExtended = { msg, before ->
                scope.offerUndoFor(snack, msg, 10L) { TeamRepo.undoDeadline(before) }
            })
        else Text("Only ${t.fromFirst} and ${t.toFirst} write here. You can read it.", fontSize = 12.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(16.dp))
        infoOf?.let { m -> TeamMsgInfo(t, m) { infoOf = null } }
        taskFrom?.let { txt -> FollowUpDialog(onDismiss = { taskFrom = null }, initialTask = txt) }
        viewing?.let { start ->
            PhotoViewer(photos.mapNotNull { it.data }, start, onDismiss = { viewing = null }) { i -> photos.getOrNull(i)?.let { TeamRepo.markPlayed(t.id, it) } }
        }
    }
    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 120.dp))
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

/** The people a message goes to: the giver and the person doing it, but not its sender. */
private fun recipients(t: TeamTask, m: TeamMsg): List<String> = listOf(t.fromUid, t.toUid).filter { it.isNotEmpty() && it != m.fromUid }.distinct()

/**
 * WhatsApp's ticks on one of your messages: 🕓 not sent yet, ✓ sent, ✓✓ on their phone, blue ✓✓ seen. A photo goes
 * blue once opened full screen; a voice note's ticks go blue when seen and its 🎙 when played (see [playedByAll]).
 */
private fun tickState(t: TeamTask, m: TeamMsg): Pair<Int, Boolean> {
    if (m.pending) return 0 to false
    val to = recipients(t, m)
    if (to.isEmpty()) return 2 to true
    val seen = if (m.kind == Team.PHOTO) to.all { t.playedBy(m, it) } else to.all { t.saw(m, it) || t.playedBy(m, it) }
    val delivered = to.all { (t.msgDel[it] ?: 0L) >= m.at || t.saw(m, it) }
    return (if (delivered || seen) 2 else 1) to seen
}

/** Everyone it went to has played this voice note (its 🎙 goes blue). */
private fun playedByAll(t: TeamTask, m: TeamMsg): Boolean = recipients(t, m).all { t.playedBy(m, it) }

/**
 * One message: yours on the right with time and ticks, theirs on the left; ❓ Ask amber, ⚠ Stuck red, 📷 proof
 * green; voice with ▶ (🎙 goes blue once played), photos open full screen. Long-press yours for Info per person.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TeamBubble(
    t: TeamTask, m: TeamMsg, mine: Boolean, selected: Boolean, selecting: Boolean, onToggle: () -> Unit, onPhoto: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val (bg, label) = when (m.tag) {
        Team.ASK -> AskBg to "❓ ${m.fromFirst} asks"
        Team.STUCK -> StuckBg to "⚠ ${m.fromFirst} is stuck"
        Team.PROOF -> ProofBg to "📷 ${m.fromFirst} · photo proof"
        Team.BRIEF -> (if (mine) cs.primaryContainer else cs.surface) to "${m.fromFirst} · with the task"
        else -> (if (mine) cs.primaryContainer else cs.surface) to (if (mine) null else m.fromFirst)
    }
    val fg = when (m.tag) { Team.ASK -> AskFg; Team.STUCK -> StuckFg; Team.PROOF -> ProofFg; else -> if (mine) cs.onPrimaryContainer else cs.onSurface }
    val (ticks, blue) = if (mine) tickState(t, m) else 0 to false
    // The whole row answers a long press (and, once something is selected, a tap): not only the bubble.
    Box(
        Modifier.fillMaxWidth()
            .background(if (selected) cs.primary.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onToggle),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp), color = bg,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                label?.let {
                    Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = when (m.tag) {
                        Team.ASK, Team.STUCK, Team.PROOF -> fg; else -> cs.primary
                    })
                }
                if (m.isDeleted) {
                    Text("🚫 This message was deleted", fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = fg.copy(alpha = 0.7f))
                    Text(Fmt.timeOnly(m.at), fontSize = 11.sp, color = fg.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.End))
                    return@Column
                }
                when {
                    m.kind == Team.VOICE && m.data != null -> VoicePlayer(
                        m.id, m.data, m.durMs, m.wave, fg, modifier = Modifier.widthIn(min = 230.dp),
                        onPlay = { TeamRepo.markPlayed(t.id, m) },
                    ) {
                        // The bubble's time and ticks share the voice note's time line.
                        Text(Fmt.timeOnly(m.at), fontSize = 11.sp, color = fg.copy(alpha = 0.7f))
                        if (mine) {
                            Icon(Icons.Rounded.Mic, contentDescription = if (playedByAll(t, m)) "Played" else "Not played yet",
                                tint = if (playedByAll(t, m)) Ticks.BLUE else Ticks.GREY, modifier = Modifier.padding(start = 3.dp).size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            if (ticks == 0) Text("🕓", fontSize = 10.sp) else TickMarks(ticks == 2, if (blue) Ticks.BLUE else Ticks.GREY)
                        }
                    }
                    m.kind == Team.PHOTO && m.data != null -> {
                        val bmp = remember(m.id) { TeamMedia.bitmap(m.data)?.asImageBitmap() }
                        if (bmp != null) Image(
                            bmp, contentDescription = "Photo", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(220.dp, 160.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = if (selecting) onToggle else onPhoto)
                        )
                    }
                    m.kind != Team.TEXT -> Text("(removed after ${Team.MEDIA_DAYS} days)", fontSize = 12.sp, color = fg.copy(alpha = 0.7f))
                }
                if (m.text.isNotBlank()) {
                    // "↩ Name: quoted text" on the first line is a reply: shown as a small quote above the message.
                    val quoted = m.text.startsWith("↩ ") && m.text.contains('\n')
                    if (quoted) {
                        Text(
                            m.text.substringBefore('\n').removePrefix("↩ "), fontSize = 12.sp, color = fg.copy(alpha = 0.75f), maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = 0.10f)).padding(6.dp),
                        )
                    }
                    Text(if (quoted) m.text.substringAfter('\n') else m.text, color = fg, fontSize = 15.sp)
                }
                if (!(m.kind == Team.VOICE && m.data != null)) Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(Fmt.timeOnly(m.at), fontSize = 11.sp, color = fg.copy(alpha = 0.7f))
                    if (mine) {
                        if (ticks == 0) Text("🕓", fontSize = 10.sp)
                        else TickMarks(ticks == 2, if (blue) Ticks.BLUE else Ticks.GREY)
                    }
                }
            }
        }
        // Once something is selected every tap on the row picks or drops it, whatever is under the finger.
        if (selecting) Box(Modifier.matchParentSize().clickable(onClick = onToggle))
    }
}

/** WhatsApp's Message info: when it was sent and, per person, delivered / seen / played. */
@Composable
private fun TeamMsgInfo(t: TeamTask, m: TeamMsg, onDismiss: () -> Unit) {
    run {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Message info", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sent · ${Fmt.dueLabel(m.at)}", fontSize = 14.sp)
                    recipients(t, m).forEach { uid ->
                        val name = TeamRepo.member(uid)?.first ?: if (uid == t.fromUid) t.fromFirst else t.toFirst
                        val seenAt = m.seenBy[uid] ?: if (t.saw(m, uid)) -1L else 0L
                        val playedAt = m.playedBy[uid] ?: if (t.playedBy(m, uid)) -1L else 0L
                        val got = (t.msgDel[uid] ?: 0L) >= m.at || seenAt != 0L
                        Column {
                            Text("👤 $name", fontWeight = FontWeight.Bold)
                            Text(
                                listOfNotNull(
                                    if (got) "✓✓ Delivered" else "✓ Not on his phone yet",
                                    seenAt.takeIf { it != 0L }?.let { "Seen" + if (it > 0L) " " + Fmt.dueLabel(it) else "" },
                                    playedAt.takeIf { it != 0L }?.let { (if (m.kind == Team.VOICE) "Played" else "Opened") + if (it > 0L) " " + Fmt.dueLabel(it) else "" },
                                ).joinToString(" · "),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onDismiss() }) { Text("OK") } },
        )
    }
}

/**
 * 📅 The open deadline request, above the message box: the giver gets ✓ Approve · Keep date (Undo for 10 s); he
 * sees it waiting with Cancel request.
 */
@Composable
private fun TeamExtendRequest(t: TeamTask, me: String?, onAnswered: (String, TeamTask) -> Unit) {
    val purple = Tones.purple
    Surface(color = purple.bg, contentColor = purple.fg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (t.fromUid == me) "📅 ${t.toFirst} asks to extend · " else "📅 You asked · ") +
                    "${TeamUi.shortWhen(t.due)} → ${TeamUi.shortWhen(t.moreDue)}" + if (t.moreReason.isNotBlank()) " · “${t.moreReason}”" else "",
                fontWeight = FontWeight.Bold, fontSize = 13.sp, color = purple.fg,
            )
            if (t.fromUid == me) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BarSlot(Icons.Rounded.Check, "Approve", Tones.done.pair, {
                    val before = t
                    TeamRepo.answerMoreTime(t, true)
                    onAnswered("Approved · ${TeamUi.shortWhen(t.moreDue)}", before)
                }, true, Modifier.weight(1f))
                BarSlot(Icons.Rounded.Close, "Keep date", chipColors(ChipTone.ORANGE), {
                    val before = t
                    TeamRepo.answerMoreTime(t, false)
                    onAnswered("Kept ${TeamUi.shortWhen(t.due)}", before)
                }, true, Modifier.weight(1f))
            } else Text(
                "Cancel request", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.clickable { TeamRepo.cancelMore(t) }.padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * The message bar, two rows. Top: ❓ Ask · ⚠ Stuck · 📅 Extend deadline · 📷 for the person doing it (📷 only for
 * the giver). Bottom: type and ➤, or hold 🎤 for a voice note (up to 2 min). Tap ❓ or ⚠, then type or hold 🎤.
 */
@Composable
private fun TeamComposer(
    t: TeamTask, isDoer: Boolean, replyTo: TeamMsg?, onReplyDone: () -> Unit, onSent: (String?) -> Unit,
    onExtended: (String, TeamTask) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var text by remember(t.id) { mutableStateOf("") }
    var tag by remember(t.id) { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var extendPick by remember { mutableStateOf(false) }
    var extendTo by remember { mutableStateOf<Long?>(null) }
    val pickPhoto = rememberTeamPhotoPicker { photo = it }
    val other = if (isDoer) t.fromFirst else t.toFirst

    // A reply starts with its quote ("↩ Name: text"), which the bubble shows as a small quote above the message.
    fun quote(): String = replyTo?.let { r ->
        val said = when { r.text.isNotBlank() -> r.text.substringAfterLast('\n'); r.kind == Team.VOICE -> "🎤 Voice note"; else -> "📷 Photo" }
        "↩ ${r.fromFirst}: ${said.take(80)}\n"
    }.orEmpty()

    fun sendText() {
        if (text.isBlank()) return
        if (TeamRepo.send(t, Team.TEXT, quote() + text, tag)) { text = ""; tag = ""; onReplyDone(); onSent(TeamRepo.lastSentId) }
        else Toast.makeText(ctx, "Couldn't send: check Settings › Team", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↩ Replying to ${r.fromFirst}: " + r.text.ifBlank { if (r.kind == Team.VOICE) "🎤 Voice note" else "📷 Photo" }.substringAfterLast('\n'),
                    fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onReplyDone, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp)) }
            }
        }
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
                        recording -> "🔴 ${TeamMedia.duration(secs * 1000)} · max 2:00"
                        tag == Team.STUCK -> "⚠ Stuck · type it or tap 🎤"
                        else -> "❓ Ask · type it or tap 🎤"
                    },
                    color = if (tag == Team.STUCK) StuckFg else if (tag == Team.ASK) AskFg else cs.onSurface,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                if (!recording) TextButton(onClick = { tag = "" }) { Text("✕ cancel", color = cs.onSurface) }
            }
        }
        // Top row: what you can do with it.
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (isDoer && t.open) {
                TeamTagButton("❓ Ask", AskFg, tag == Team.ASK) { tag = if (tag == Team.ASK) "" else Team.ASK }
                TeamTagButton("⚠ Stuck", StuckFg, tag == Team.STUCK) { tag = if (tag == Team.STUCK) "" else Team.STUCK }
                TeamTagButton("📅 Extend deadline", Tones.purple.fg, false, enabled = t.due > 0L && !t.asking) { extendPick = true }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = pickPhoto) { Icon(Icons.Rounded.PhotoCamera, contentDescription = "Photo", tint = cs.onSurfaceVariant) }
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
            Spacer(Modifier.width(6.dp))
            if (text.isNotBlank()) {
                IconButton(onClick = { sendText() }, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3DD6B5))) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color(0xFF053D2E))
                }
            } else {
                TeamHoldToRecord(
                    onState = { recording = it },
                    onRecorded = { bytes, dur ->
                        if (TeamRepo.send(t, Team.VOICE, "", tag, bytes, dur)) onSent(TeamRepo.lastSentId)
                        tag = ""
                    },
                )
            }
        }
    }
    photo?.let { bytes ->
        TeamPhotoCaptionDialog(bytes, title = "Send a photo", button = "Send", onDismiss = { photo = null }) { caption ->
            if (TeamRepo.send(t, Team.PHOTO, caption, tag, bytes)) onSent(TeamRepo.lastSentId)
            tag = ""
            photo = null
        }
    }
    // 📅 Extend deadline: the same time grid (no IN row), then why.
    if (extendPick) {
        TimePickerDialog(
            title = "Extend deadline",
            selected = null,
            onPick = { ms -> extendPick = false; extendTo = ms },
            onDismiss = { extendPick = false },
        )
    }
    extendTo?.let { ms ->
        val free = t.extCount < t.freeExt
        TeamReasonDialog(
            title = "📅 Extend deadline",
            body = "${TeamUi.shortWhen(t.due)} → ${TeamUi.shortWhen(ms)}" + if (free) "\nMoves at once (${t.freeExt - t.extCount} free left)." else "\n${t.fromFirst} approves or keeps the date.",
            quick = listOf("Waiting for material", "Vendor late", "More work than planned", "Site not ready"),
            button = if (free) "Extend" else "Ask ${t.fromFirst}",
            onDismiss = { extendTo = null },
        ) { reason ->
            extendTo = null
            if (ms <= t.due) {
                Toast.makeText(ctx, "Pick a time after ${TeamUi.shortWhen(t.due)}", Toast.LENGTH_SHORT).show()
                return@TeamReasonDialog
            }
            val before = t
            val moved = TeamRepo.extend(t, ms, reason)
            onExtended(if (moved) "Deadline · ${TeamUi.shortWhen(ms)}" else "Asked ${t.fromFirst} · ${TeamUi.shortWhen(ms)}", before)
        }
    }
}

@Composable
private fun TeamTagButton(label: String, color: Color, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (enabled) color else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
        color = if (on) c.copy(alpha = 0.25f) else Color.Transparent, border = BorderStroke(1.dp, c),
    ) {
        Text(label, color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
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
            myTaskId?.let { MyTaskStore.setDone(it, true, force = true) }
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
        Text(if (recording) "Recording…" else "tap 🎤", fontSize = 12.sp,
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
