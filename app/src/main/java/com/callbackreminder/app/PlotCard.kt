package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Plot files: one step of a plot file on the phone (locked mockups P2 v3, P3, D and section H of the locks). It is the
 * same TaskCard as every task, in the sand tint, with its checklist under it when opened. Nothing of the SOP is in
 * this code: lines, people, forms and texts come from the published SOP (PlotFiles.sop). The next step is opened by
 * the cloud (functions/) when this one is Done.
 */
object PlotUi {
    /** What a CC person sees for this step in Follow-ups: "Started", "Late" or "Done" (24 hours), or null when it is not for them. */
    fun ccStatus(t: TeamTask, me: String, now: Long): String? {
        if (!t.plot || t.toUid == me || t.fromUid == me) return null
        val ev = t.cc[me]?.split(",") ?: return null
        return when {
            t.doneAt > 0L && (t.status == Team.CHECKED || t.status == Team.DONE) -> "Done".takeIf { "done" in ev && now - t.doneAt < 24 * 3600_000L }
            !t.open -> null
            t.waitAt == 0L && t.due in 1..now && "late" in ev -> "Late"
            "start" in ev -> "Started"
            else -> null
        }
    }

    /** The line's own step and option a "Shown only if" points at: "6.1 · 6b" -> ("6.1", "6", "6b"). */
    private fun ref(t: String): Triple<String, String, String>? {
        val m = Regex("""^(\d+\.\d+)\s*[·.\-]\s*(\d+)(\w*)$""").find(t.trim()) ?: return null
        return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[2] + m.groupValues[3])
    }

    /** What was picked on a Pick one line: this step's ticks, or an earlier step's (copied on by the cloud). */
    fun picked(t: TeamTask, step: String, line: String): String =
        if (step == t.step) t.ticks[line]?.value.orEmpty() else t.picks["$step·$line"].orEmpty()

    /** The lines shown now: a "Shown only if" line only when its option was picked. */
    fun shown(t: TeamTask, s: PlotFiles.Step): List<PlotFiles.Line> = s.lines.filter { l ->
        if (l.onlyIf.isBlank()) true else ref(l.onlyIf)?.let { (st, line, opt) -> picked(t, st, line) == opt } ?: true
    }

    /** The lines that count: ticks, pick ones and number boxes (an option is the answer to its pick one). */
    fun counted(t: TeamTask, s: PlotFiles.Step) = shown(t, s).filter { it.type != "Option" }

    fun tickedCount(t: TeamTask, s: PlotFiles.Step) = counted(t, s).count { l ->
        t.ticks[l.no]?.let { if (l.type == "Tick") true else it.value.isNotBlank() } == true
    }

    /** Why Done can't be tapped yet, or null (all lines ticked, or the sheet allows Done without them). */
    fun doneBlocked(t: TeamTask, s: PlotFiles.Step?): String? {
        if (s == null || s.doneWithoutAll) return null
        val n = counted(t, s).size
        val done = tickedCount(t, s)
        return if (done < n) "Tick all checklist lines first ($done of $n ticked)" else null
    }

    /** For MyTaskStore.setDone: a plot step's tick is blocked until its checklist is ticked. */
    fun blockedFor(c: Context, myTaskId: Long): String? {
        if (!TeamRepo.activeNow) return null
        val t = TeamUi.taskOfMyTask(c, myTaskId, TeamRepo.tasks.value)?.takeIf { it.plot && it.open } ?: return null
        if (t.waitAt > 0L) return "Waiting for plot owner: tap it again when he comes, then Done"
        return doneBlocked(t, PlotFiles.sop.value?.step(t.step))
    }

    /** N6 "Thu 27 Nov" or "Thu 27 Nov, 4:00 PM". */
    fun comes(at: Long, timed: Boolean): String {
        val z = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        return z.format(java.time.format.DateTimeFormatter.ofPattern(if (timed) "EEE d MMM, h:mm a" else "EEE d MMM", java.util.Locale.ENGLISH))
    }

    /** N7 Duty time waited: "5h", "2h 10m", "40m". */
    fun hm(ms: Long): String {
        val m = ms / 60_000L
        return if (m < 60) "${m}m" else "${m / 60}h" + if (m % 60 > 0) " ${m % 60}m" else ""
    }

    /** "Waiting for plot owner" on this step now: a button on the task, or after a line / an option the sheet names. */
    fun canWait(t: TeamTask, s: PlotFiles.Step): Boolean {
        if (!s.canPause) return false
        val p = s.pauseStarts
        Regex("""\((\d+\.\d+\s*·\s*\w+)\)""").find(p)?.let { m -> ref(m.groupValues[1])?.let { (st, line, opt) -> return picked(t, st, line) == opt } }
        Regex("""option\s+(\d+)(\w+)""", RegexOption.IGNORE_CASE).find(p)?.let { m -> return t.ticks[m.groupValues[1]]?.value == m.groupValues[1] + m.groupValues[2] }
        Regex("""after line\s+(\w+)""", RegexOption.IGNORE_CASE).find(p)?.let { m -> return t.ticks[m.groupValues[1]] != null }
        return p.contains("button", ignoreCase = true) || p.contains("created", ignoreCase = true)
    }

    /** The ready WhatsApp message with the plot owner's name and plot number put in. */
    fun message(t: TeamTask, raw: String): String =
        Regex("""[\[{<]([^\]}>]{1,40})[\]}>]""").replace(raw) { m ->
            val k = m.groupValues[1].lowercase()
            when {
                "plot" in k && ("no" in k || "number" in k) -> t.plotNo
                "name" in k || "owner" in k -> t.ownerName
                else -> m.value
            }
        }

    /** One person in the Call / WhatsApp picker. */
    data class Who(val name: String, val role: String, val number: String)

    /** The people a step's Call or WhatsApp list names: the plot owner from his file, staff from Team by designation. */
    fun people(t: TeamTask, roles: List<String>, whatsApp: Boolean): List<Who> = roles.flatMap { r ->
        if (r.equals("Plot owner", ignoreCase = true)) listOfNotNull(
            Who(t.ownerName.ifBlank { "Plot owner" }, "Plot owner", t.ownerWa).takeIf { t.ownerWa.isNotBlank() },
            Who(t.ownerName.ifBlank { "Plot owner" }, "Plot owner · 2nd SIM", t.ownerSim2).takeIf { !whatsApp && t.ownerSim2.isNotBlank() },
        )
        else TeamRepo.members.value.filter { it.active && it.does(r) && it.uid != TeamRepo.uid }
            .map { Who(it.first, r, if (whatsApp) it.waNumber else it.phone) }.filter { it.number.isNotBlank() }
    }

    fun nice(number: String): String = number.filter(Char::isDigit).let { if (it.length == 11) it.take(4) + " " + it.drop(4) else number }
}

/** A plot step in My tasks: the sand TaskCard with its chips; opened, the checklist, the bar and the links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlotTaskCard(
    t: MyTask, tt: TeamTask, now: Long, listName: String?, highlighted: Boolean, finishing: Boolean, pos: RowPos?,
    expanded: Boolean, onToggle: () -> Unit, onTick: () -> Unit, onStar: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val sand = CardColours.PALETTE.first { it.name == "Sand" }
    val sop by PlotFiles.sop.collectAsState()
    val s = sop?.step(tt.step)
    val waiting = tt.waitAt > 0L
    val done = t.done || finishing
    val tryDone = {
        val why = PlotUi.blockedFor(ctx, t.id)
        if (why != null) Toast.makeText(ctx, why, Toast.LENGTH_SHORT).show() else onTick()
    }
    TaskCard(
        title = tt.title,
        done = done,
        onTick = tryDone,
        time = if (t.done) "Done " + Fmt.dueLabel(t.doneAt) else tt.due.takeIf { it > 0L }?.let { Fmt.dueLabel(it) },
        late = !waiting && tt.due in 1..now,
        person = listOf("Plot ${tt.plotNo}", tt.ownerName).filter { it.isNotBlank() && it != "Plot " }.joinToString(" · "),
        chips = listOfNotNull(
            s?.let { RowChip("${PlotUi.tickedCount(tt, it)}/${PlotUi.counted(tt, it).size}", ChipTone.MUTED) },
            RowChip(if (tt.toUid == tt.fromUid || tt.role.isBlank()) "TEST" else "TEST · ${tt.role}", ChipTone.ORANGE).takeIf { tt.test },
        ),
        sub = listOfNotNull(
            "Stage ${tt.step.substringBefore('.')}",
            "Step ${tt.step.substringAfter('.')}",
            tt.fromRole.takeIf { it.isNotBlank() }?.let { "from $it" },
        ).joinToString(" · "),
        starred = t.starred,
        onStar = if (t.done) null else onStar,
        noTimeChip = false,
        banner = if (waiting && !t.done) CardBanner("Waiting for plot owner · since ${TeamUi.shortWhen(tt.waitAt)}" +
            if (tt.waitUntil > 0L) " · comes ${PlotUi.comes(tt.waitUntil, tt.waitTimed)}" else "", ChipTone.ORANGE) else null,
        tint = sand.fill(dark).takeIf { !t.done },
        panelFromEdge = true,
        expanded = expanded,
        onClick = onToggle,
        highlighted = highlighted,
        pos = pos,
    ) {
        if (s == null) {
            Text("Reading the SOP… needs internet the first time.", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            return@TaskCard
        }
        PlotPanel(tt, s, sop!!, open = tt.open && !t.done, onDone = tryDone)
    }
}

/** The opened plot step: checklist (tap = tick, ⓘ = how to do it), N of M, Next, If something goes wrong, the bar, links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlotPanel(t: TeamTask, s: PlotFiles.Step, sop: PlotFiles.Sop, open: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val me = TeamRepo.me.collectAsState().value
    var help by remember { mutableStateOf<PlotFiles.Line?>(null) }
    var picker by remember { mutableStateOf<Boolean?>(null) }   // true = WhatsApp, false = Call
    var returning by remember { mutableStateOf(false) }
    var startWait by remember { mutableStateOf(false) }
    val shown = PlotUi.shown(t, s)
    // N10 Photos added to 📷 lines (Timeline photo messages that name their line).
    var msgs by remember(t.id) { mutableStateOf<List<TeamMsg>>(emptyList()) }
    if (shown.any { it.photo }) DisposableEffect(t.id) {
        val reg = TeamRepo.watchMsgs(t.id) { all -> msgs = all.filter { it.kind == Team.PHOTO && it.checkLine.isNotEmpty() && it.data != null && !it.isDeleted } }
        onDispose { reg?.remove() }
    }
    var photoLine by remember { mutableStateOf<PlotFiles.Line?>(null) }
    val addPhoto = rememberTeamPhotoPicker(multi = true) { bytes ->
        photoLine?.let { l -> TeamRepo.send(t, Team.PHOTO, "Line ${l.no} · ${l.en}".take(120), tag = Team.PROOF, data = bytes, checkLine = l.no) }
    }
    fun tick(l: PlotFiles.Line, value: String?) {
        if (!open) return
        if (l.photo && value != null && msgs.none { it.checkLine == l.no }) {
            Toast.makeText(ctx, "Add a photo first, then tick this line", Toast.LENGTH_SHORT).show()
            return
        }
        val f: Map<String, Any> = if (value == null) mapOf("ticks.${l.no}" to com.google.firebase.firestore.FieldValue.delete())
        else mapOf("ticks.${l.no}" to mapOf("at" to System.currentTimeMillis(), "name" to (me?.name ?: ""), "value" to value))
        TeamRepo.plotUpdate(t, f, null)
    }
    shown.forEachIndexed { i, l ->
        if (i > 0 && l.type != "Option") HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        val mark = t.ticks[l.no]
        when (l.type) {
            "Option" -> {
                val pickNo = l.no.takeWhile(Char::isDigit)
                val on = t.ticks[pickNo]?.value == l.no
                PlotLineRow(l, open, onHelp = { help = l }, onClick = { tick(PlotFiles.Line(pickNo, "Pick one", "", "", "", "", ""), l.no) }) {
                    RadioButton(selected = on, onClick = if (open) ({ tick(PlotFiles.Line(pickNo, "Pick one", "", "", "", "", ""), l.no) }) else null)
                }
            }
            "Pick one" -> Text(l.en, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            "Number box" -> {
                var v by remember(t.id, l.no, mark?.value) { mutableStateOf(mark?.value.orEmpty()) }
                PlotLineRow(l, open, onHelp = { help = l }, onClick = null) { Spacer(Modifier.width(4.dp)) }
                OutlinedTextField(
                    value = v, onValueChange = { x -> v = x.filter { it.isDigit() || it == '.' }.take(12) },
                    enabled = open, singleLine = true, shape = RoundedCornerShape(12.dp), label = { Text("Number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { tick(l, v.takeIf { it.isNotBlank() }) }),
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 6.dp)
                        .onFocusLeft { if (v != mark?.value.orEmpty()) tick(l, v.takeIf { it.isNotBlank() }) },
                )
            }
            else -> {
                val photos = if (l.photo) msgs.filter { it.checkLine == l.no } else emptyList()
                PlotLineRow(l, open, onHelp = { help = l }, onClick = { tick(l, if (mark == null) "" else null) }) {
                    // N10 A 📷 line stays faded until it has a photo.
                    Checkbox(checked = mark != null, onCheckedChange = if (open) ({ tick(l, if (it) "" else null) }) else null,
                        modifier = if (l.photo && photos.isEmpty() && mark == null) Modifier.alpha(0.4f) else Modifier)
                }
                if (l.photo) PlotLinePhotos(t, photos, open, onAdd = { photoLine = l; addPhoto() }) { m ->
                    TeamRepo.deleteMsgId(t, m.id)
                    // The last photo gone: the line is not proved any more.
                    if (photos.size == 1 && mark != null) tick(l, null)
                }
            }
        }
    }
    val total = PlotUi.counted(t, s).size
    Text("${PlotUi.tickedCount(t, s)} of $total ticked", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    // The same grey detail lines as every opened card.
    s.next.mapNotNull { sop.step(it) }.forEach { n ->
        Text(boldLead("Next: ", "${n.id} · ${n.role} · ${n.title}"), fontSize = 12.5.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
    }
    if (s.wrongShort.isNotBlank()) Text(boldLead("If something goes wrong: ", s.wrongShort), fontSize = 12.5.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
    if (open) {
        Spacer(Modifier.height(6.dp))
        val waiting = t.waitAt > 0L
        val canWait = PlotUi.canWait(t, s)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarSlot(Icons.Rounded.Check, "Done", Tones.done.pair, if (waiting || PlotUi.doneBlocked(t, s) != null) null else onDone, true, Modifier.weight(1f))
            if (s.canReturn) BarSlot(Icons.AutoMirrored.Rounded.Undo, "Return", Tones.late.pair, { returning = true }, true, Modifier.weight(1f))
            if (canWait || waiting) BarSlot(
                null, if (!waiting) "Waiting for plot owner" else if (s.pauseStarts.contains("Receive", true)) "Receive" else "Plot owner came",
                chipColors(ChipTone.ORANGE), { if (waiting) PlotActions.stopWaiting(ctx, t) else startWait = true }, true, Modifier.weight(1f), lines = 2,
            )
            if (s.call.isNotEmpty()) BarSlot(Icons.Rounded.Call, "Call", Tones.call.pair, { picker = false }, true, Modifier.weight(1f))
            if (s.wa.isNotEmpty()) BarSlot(null, "WhatsApp", WhatsAppGreen to WhatsAppInk, { picker = true }, true, Modifier.weight(1f))
        }
    }
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PlotLink(TeamUi.timelineLabel(t)) { TeamUi.openTimeline(t.id) }
        s.forms.forEach { code ->
            val f = sop.forms.firstOrNull { it.code == code }
            if (f != null && f.link.startsWith("https://")) PlotLink("$code form") {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
    help?.let { l -> PlotLineHelpSheet(t.step, l) { help = null } }
    picker?.let { wa -> PlotWhoSheet(t, s, wa) { picker = null } }
    if (startWait) PlotWaitDialog(onDismiss = { startWait = false }) { r, until, timed ->
        startWait = false
        PlotActions.startWaiting(ctx, t, r, until, timed)
    }
    if (returning) TeamReasonDialog(
        title = "Return to ${s.returnTo}",
        body = "Write what is wrong. The step goes back to the ${s.returnTo}.",
        quick = emptyList(), button = "Return", onDismiss = { returning = false },
    ) { r, extras -> returning = false; PlotActions.returnStep(t, s, r.trim()); sendExtras(t, extras) }
}

private fun boldLead(lead: String, rest: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }
    append(rest)
}

/** A focus-lost hook for the number box. */
@Composable
private fun Modifier.onFocusLeft(block: () -> Unit): Modifier {
    var had by remember { mutableStateOf(false) }
    return this.onFocusChanged { if (had && !it.isFocused) block(); had = it.isFocused }
}

/** One checklist line: its box (tap anywhere on the line), the text, and a small yellow ⓘ for how to do it. */
@Composable
private fun PlotLineRow(l: PlotFiles.Line, open: Boolean, onHelp: () -> Unit, onClick: (() -> Unit)?, box: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (open && onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        box()
        Text(l.en, fontSize = 13.sp, lineHeight = 17.sp, color = cs.onSurface, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (l.detail.isNotBlank() || l.detailUr.isNotBlank()) Icon(
            Icons.Rounded.Info, contentDescription = "How to do this line", tint = Tones.amber.fg,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onHelp).padding(8.dp).size(18.dp),
        )
    }
}

/**
 * N10 Under a 📷 line: "Photo needed" + 📷 Add photo, or the photos (44dp, like the task brief) with a small × each
 * (only the person who added it, only until Done), + for more, and "N photos · name · time". Tap opens full screen.
 */
@Composable
private fun PlotLinePhotos(t: TeamTask, photos: List<TeamMsg>, open: Boolean, onAdd: () -> Unit, onRemove: (TeamMsg) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var viewAt by remember { mutableStateOf<Int?>(null) }
    if (photos.isEmpty()) {
        Column(Modifier.padding(start = 40.dp, bottom = 4.dp)) {
            Text("📷 Photo needed before you can tick this line", fontSize = 12.5.sp, color = Tones.amber.fg)
            if (open) PlotLink("📷 Add photo", onAdd)
        }
        return
    }
    val me = TeamRepo.me.collectAsState().value?.uid
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = Modifier.padding(start = 40.dp, bottom = 6.dp)) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEachIndexed { i, m ->
                val bmp = remember(m.id) { m.data?.let { TeamMedia.bitmap(it)?.asImageBitmap() } }
                Box {
                    if (bmp != null) Image(bmp, contentDescription = "Photo ${i + 1}", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { viewAt = i })
                    if (open && m.fromUid == me) Box(
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                            .clickable { onRemove(m) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(12.dp)) }
                }
            }
            if (open) Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, cs.outline, RoundedCornerShape(10.dp)).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add another photo", tint = cs.onSurfaceVariant) }
            val last = photos.last()
            Text("${photos.size} photo" + (if (photos.size == 1) "" else "s") + "\n${last.fromFirst} · ${TeamUi.shortWhen(last.at)}",
                fontSize = 11.5.sp, lineHeight = 14.sp, color = cs.onSurfaceVariant)
        }
    }
    viewAt?.let { start ->
        PhotoViewer(photos.mapNotNull { it.data }, start = start, onDismiss = { viewAt = null }) { i -> photos.getOrNull(i)?.let { TeamRepo.markPlayed(t.id, it) } }
    }
}

@Composable
private fun PlotLink(label: String, onClick: () -> Unit) {
    Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 5.dp))
}

private val Nastaliq = FontFamily(Font(R.font.noto_nastaliq_urdu))

/** ⓘ: how to do one line, English | اردو (Noto Nastaliq), in a sheet that slides up (mockup P3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlotLineHelpSheet(step: String, l: PlotFiles.Line, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var urdu by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarMissing())) {
            BoxTabs(listOf("English", "اردو"), if (urdu) 1 else 0, onSelect = { urdu = it == 1 })
            Spacer(Modifier.height(12.dp))
            Text("$step · Line ${l.no} · How to do this", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            val text = if (urdu) l.detailUr.ifBlank { l.detail } else l.detail.ifBlank { l.detailUr }
            if (urdu) {
                Text(l.ur.ifBlank { l.en }, fontFamily = Nastaliq, fontSize = 17.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl), textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(text, fontFamily = Nastaliq, fontSize = 15.sp, lineHeight = 34.sp,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl), textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                Text(l.en, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(text, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            PlotSheetButton("Close", onDismiss)
        }
    }
}

@Composable
private fun PlotSheetButton(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/** Call who? / WhatsApp who?: always this list first on a plot step, even for one person (mockup D). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlotWhoSheet(t: TeamTask, s: PlotFiles.Step, whatsApp: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val people = remember(t.id, whatsApp) { PlotUi.people(t, if (whatsApp) s.wa else s.call, whatsApp) }
    val msg = PlotUi.message(t, s.waMsg)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp + navBarMissing()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (whatsApp) "WhatsApp who?" else "Call who?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (people.isEmpty()) Text("Nobody on this list has a number yet.", fontSize = 13.sp, color = cs.onSurfaceVariant)
            people.forEach { p ->
                val owner = p.role.startsWith("Plot owner")
                Surface(
                    onClick = {
                        onDismiss()
                        if (whatsApp) WhatsApp.open(ctx, p.number, if (owner) msg else "") else dial(ctx, p.number)
                        PlotActions.logContact(t, whatsApp, p)
                    },
                    shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((if (whatsApp) "💬 " else "📞 ") + "${p.name} · ${p.role}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2)
                            Text(PlotUi.nice(p.number), fontSize = 13.sp, color = cs.onSurfaceVariant)
                        }
                        if (whatsApp) Text(
                            if (owner && msg.isNotBlank()) "“" + msg.take(90) + (if (msg.length > 90) "…”" else "”") else "No ready message",
                            fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 2,
                        )
                    }
                }
            }
            PlotSheetButton("Cancel", onDismiss)
        }
    }
}

/**
 * N6 "Waiting for plot owner": the reason (typed or 🎤, required), then optional "When will he come?" date and an
 * optional time. Same look as the app's other reason windows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlotWaitDialog(onDismiss: () -> Unit, onStart: (String, Long, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val z = java.time.ZoneId.systemDefault()
    var reason by remember { mutableStateOf("") }
    var day by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var time by remember { mutableStateOf<java.time.LocalTime?>(null) }
    var pickDay by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Waiting for plot owner") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Write why. Your time stops while waiting and is given back when he comes.")
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason *") }, trailingIcon = voiceMic(reason) { reason = it },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), maxLines = 3)
                ChipHeading("WHEN WILL HE COME? (OPTIONAL)", modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = day != null, onClick = { pickDay = true },
                        label = { Text(day?.let { "📅 " + PlotUi.comes(it.atStartOfDay(z).toInstant().toEpochMilli(), false) } ?: "📅 Pick date") },
                        trailingIcon = if (day != null) ({ Icon(Icons.Rounded.Close, "Remove date", Modifier.size(16.dp).clickable { day = null; time = null }) }) else null)
                    if (day != null) FilterChip(selected = time != null, onClick = { pickTime = true },
                        label = { Text(time?.let { "🕒 " + it.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)) } ?: "🕒 Add time (optional)") },
                        trailingIcon = if (time != null) ({ Icon(Icons.Rounded.Close, "Remove time", Modifier.size(16.dp).clickable { time = null }) }) else null)
                }
                if (day != null) Text(
                    if (time == null) "You get a reminder at the start of your duty that day." else "You get a reminder at that time.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val d = day
                val until = when {
                    d == null -> 0L
                    time != null -> d.atTime(time).atZone(z).toInstant().toEpochMilli()
                    else -> d.atStartOfDay(z).toInstant().toEpochMilli()
                }
                onStart(reason.trim(), until, d != null && time != null)
            }, enabled = reason.isNotBlank()) { Text("Start waiting") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickDay) DateTimeDialog(
        initialMs = (day ?: java.time.LocalDate.now(z)).atTime(9, 0).atZone(z).toInstant().toEpochMilli(),
        onDone = { ms, _ -> day = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDate(); pickDay = false },
        onDismiss = { pickDay = false },
    )
    if (pickTime) ClockDialog(
        initial = time ?: java.time.LocalTime.of(11, 0),
        is24h = android.text.format.DateFormat.is24HourFormat(ctx),
        onPicked = { time = it; pickTime = false },
        onDismiss = { pickTime = false },
    )
}

/** What the plot step's buttons write. Every one leaves a line on the task's Timeline. */
object PlotActions {
    fun logContact(t: TeamTask, whatsApp: Boolean, p: PlotUi.Who) {
        val me = TeamRepo.me.value?.first ?: ""
        TeamRepo.plotUpdate(t, emptyMap(), (if (whatsApp) "💬 $me opened WhatsApp for " else "📞 $me called ") + "${p.name} (${p.role})")
    }

    /** N6 Waiting starts with a reason, and the day (and time) he said he would come when he gave one. */
    fun startWaiting(c: Context, t: TeamTask, reason: String, until: Long, timed: Boolean) {
        TeamRepo.plotUpdate(t, mapOf("waitAt" to System.currentTimeMillis(), "waitReason" to reason, "waitUntil" to until, "waitTimed" to timed),
            "⏸ Waiting for plot owner · $reason" + if (until > 0L) " · comes ${PlotUi.comes(until, timed)}" else "")
        TaskAlarms.scheduleComes(c, t.copy(waitAt = 1L, waitReason = reason, waitUntil = until, waitTimed = timed))
    }

    /**
     * He came: the timer runs again and (N7) the due time moves later by exactly the duty time waited (his timetable;
     * nights, off days and holidays not counted). N8 the wait is kept in the step's list.
     */
    fun stopWaiting(c: Context, t: TeamTask) {
        val now = System.currentTimeMillis()
        val dutyMs = TeamRules.minutesSince(c, t.waitAt, now) * 60_000L
        // A step created paused (2.1) starts its time now.
        val fresh = t.seenAt == 0L && t.due > 0L && t.waitAt <= t.createdAt + 60_000L
        val newDue = if (fresh) PlotFiles.dueOf(c, t.timer, t.days, t.hours, now) else if (t.due > 0L) TeamRules.addDuty(c, t.due, dutyMs) else t.due
        val wait = mapOf("from" to t.waitAt, "to" to now, "dutyMs" to dutyMs, "reason" to t.waitReason, "comesAt" to t.waitUntil)
        TeamRepo.plotUpdate(t, mapOf("waitAt" to 0L, "waitReason" to "", "waitUntil" to 0L, "waitTimed" to false, "due" to newDue, "deadline" to newDue,
            "waits" to com.google.firebase.firestore.FieldValue.arrayUnion(wait)),
            "▶ Plot owner came · waited ${PlotUi.hm(dutyMs)}" + if (newDue != t.due && newDue > 0L) " · due ${TeamUi.shortWhen(newDue)}" else "")
        TaskAlarms.cancelComes(c, t.id)
    }

    /** Return with a reason: this step closes as returned; the cloud gives the step before it back to [PlotFiles.Step.returnTo]. */
    fun returnStep(t: TeamTask, s: PlotFiles.Step, reason: String) {
        TeamRepo.plotUpdate(t, mapOf("status" to Team.RETURNED, "reason" to reason, "returnTo" to s.returnTo), "↩ Returned to ${s.returnTo} · “$reason”")
    }
}

/** A plot step you are CC on (SOP Notify columns): read-only row in Follow-ups, tap opens the step's Timeline. */
@Composable
fun PlotCcCard(t: TeamTask, status: String, now: Long, pos: RowPos?) {
    val dark = LocalDarkTheme.current
    val sand = CardColours.PALETTE.first { it.name == "Sand" }
    TaskCard(
        title = t.title,
        done = false,
        onTick = {},
        showTick = false,
        time = if (status == "Done") "Done " + Fmt.dueLabel(t.doneAt) else t.due.takeIf { it > 0L }?.let { Fmt.dueLabel(it) },
        late = status == "Late",
        person = listOf("Plot ${t.plotNo}", t.toName).filter { it.isNotBlank() && it != "Plot " }.joinToString(" · "),
        chips = listOf(RowChip(status, when (status) { "Late" -> ChipTone.RED; "Done" -> ChipTone.GREEN; else -> ChipTone.BLUE })),
        sub = listOf("Stage ${t.step.substringBefore('.')}", "Step ${t.step.substringAfter('.')}", t.role).filter { it.isNotBlank() }.joinToString(" · "),
        noTimeChip = false,
        tint = sand.fill(dark),
        onClick = { TeamUi.openTimeline(t.id) },
        pos = pos,
    )
}
