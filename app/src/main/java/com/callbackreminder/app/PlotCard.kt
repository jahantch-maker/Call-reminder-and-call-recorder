package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
        else TeamRepo.members.value.filter { it.active && it.designation == r && it.uid != TeamRepo.uid }
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
        banner = if (waiting && !t.done) CardBanner("Waiting for plot owner · since ${TeamUi.shortWhen(tt.waitAt)}", ChipTone.ORANGE) else null,
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
    val shown = PlotUi.shown(t, s)
    fun tick(l: PlotFiles.Line, value: String?) {
        if (!open) return
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
            else -> PlotLineRow(l, open, onHelp = { help = l }, onClick = { tick(l, if (mark == null) "" else null) }) {
                Checkbox(checked = mark != null, onCheckedChange = if (open) ({ tick(l, if (it) "" else null) }) else null)
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
                chipColors(ChipTone.ORANGE), { if (waiting) PlotActions.stopWaiting(ctx, t) else PlotActions.startWaiting(t) }, true, Modifier.weight(1f), lines = 2,
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
    if (returning) TeamReasonDialog(
        title = "Return to ${s.returnTo}",
        body = "Write what is wrong. The step goes back to the ${s.returnTo}.",
        quick = emptyList(), button = "Return", onDismiss = { returning = false },
    ) { r -> returning = false; PlotActions.returnStep(t, s, r.trim()) }
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
            BoxTabs(listOf("English", "اردو"), if (urdu) 1 else 0) { urdu = it == 1 }
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

/** What the plot step's buttons write. Every one leaves a line on the task's Timeline. */
object PlotActions {
    fun logContact(t: TeamTask, whatsApp: Boolean, p: PlotUi.Who) {
        val me = TeamRepo.me.value?.first ?: ""
        TeamRepo.plotUpdate(t, emptyMap(), (if (whatsApp) "💬 $me opened WhatsApp for " else "📞 $me called ") + "${p.name} (${p.role})")
    }

    fun startWaiting(t: TeamTask) {
        TeamRepo.plotUpdate(t, mapOf("waitAt" to System.currentTimeMillis()), "⏸ Waiting for plot owner")
    }

    /** He came: the timer runs again and the due time moves later by the working days waited. */
    fun stopWaiting(c: Context, t: TeamTask) {
        val now = System.currentTimeMillis()
        val z = java.time.ZoneId.systemDefault()
        var d = java.time.Instant.ofEpochMilli(t.waitAt).atZone(z).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        var days = 0L
        while (d.isBefore(today)) { d = d.plusDays(1); if (!OffDays.isOff(d)) days++ }
        val due = if (t.due > 0L && days > 0L) {
            var x = java.time.Instant.ofEpochMilli(t.due).atZone(z)
            var n = days
            while (n > 0) { x = x.plusDays(1); if (!OffDays.isOff(x.toLocalDate())) n-- }
            x.toInstant().toEpochMilli()
        } else t.due
        // A step created paused (2.1) starts its time now.
        val fresh = t.seenAt == 0L && t.due > 0L && t.waitAt <= t.createdAt + 60_000L
        val newDue = if (fresh) PlotFiles.dueOf(c, t.timer, t.days, now) else due
        TeamRepo.plotUpdate(t, mapOf("waitAt" to 0L, "due" to newDue, "deadline" to newDue), "▶ Plot owner came" + if (newDue != t.due && newDue > 0L) " · due ${TeamUi.shortWhen(newDue)}" else "")
    }

    /** Return with a reason: this step closes as returned; the cloud gives the step before it back to [PlotFiles.Step.returnTo]. */
    fun returnStep(t: TeamTask, s: PlotFiles.Step, reason: String) {
        TeamRepo.plotUpdate(t, mapOf("status" to Team.RETURNED, "reason" to reason, "returnTo" to s.returnTo), "↩ Returned to ${s.returnTo} · “$reason”")
    }
}
