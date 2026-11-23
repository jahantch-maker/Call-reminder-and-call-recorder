package com.callbackreminder.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneId

/*
 * Plot files (Rafiq Builders): a file for each plot, from possession to completion. The steps, roles and time allowed
 * come only from the published SOP (teams/{t}/config/plotsop, imported on the web from the master Google Sheet).
 * The web watches the files; the phone opens them (New plot file, in Settings) and does the steps.
 *
 * Data (the same shape the web reads, see web/plotfiles.js):
 *   plotfiles/{id}: plotNo, size (marla), name (plot owner), cnic (13 digits), wa (11 digits), sim2 (11 digits or ""),
 *                   address, attorney, test, status ("open"), openedAt, openedByName, edits []
 *   tasks/{id}: one normal team task per step, plus plot: true, plotId, plotNo, step, role, test, ticks {}, waitAt 0.
 */
object PlotFiles {
    /** One checklist line: Tick, Pick one (its Option lines follow it), Option or Number box. */
    data class Line(val no: String, val type: String, val en: String, val ur: String, val onlyIf: String, val detail: String, val detailUr: String)

    data class Step(
        val id: String, val stage: String, val role: String, val title: String, val timer: String, val days: Int?,
        val hours: Int? = null,
        val next: List<String> = emptyList(), val recv: String = "", val returnTo: String = "",
        val canPause: Boolean = false, val pauseStarts: String = "", val reminder: String = "", val doneWithoutAll: Boolean = false,
        val call: List<String> = emptyList(), val wa: List<String> = emptyList(), val waMsg: String = "",
        val forms: List<String> = emptyList(), val wrongShort: String = "", val wrongShortUr: String = "",
        val lines: List<Line> = emptyList(),
    ) {
        /** "1.8" -> 8: the step's number inside its stage. */
        val noInStage: String get() = id.substringAfter('.')
        val canReturn: Boolean get() = recv.contains("Return", ignoreCase = true) && returnTo.isNotBlank()
    }

    data class Form(val code: String, val name: String, val link: String)

    data class Sop(
        val roles: List<String>, val steps: List<Step>, val opened: Set<String>,
        val stages: Map<String, String> = emptyMap(), val forms: List<Form> = emptyList(),
    ) {
        /** The steps no Flow row opens: they start when a new plot file is saved (Step 1.1). */
        val first: List<Step> get() = steps.filter { it.id !in opened }
        fun step(id: String): Step? = steps.firstOrNull { it.id == id }
    }

    private val _sop = MutableStateFlow<Sop?>(null)
    /** The published SOP, once read (null = not read yet, or none published). */
    val sop: StateFlow<Sop?> = _sop.asStateFlow()

    private fun teamDoc() = TeamRepo.teamId?.let { FirebaseFirestore.getInstance().collection("teams").document(it) }

    private fun str(v: Any?) = v?.toString().orEmpty()
    private fun strs(v: Any?) = (v as? List<*>).orEmpty().mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }

    /** Reads the published SOP again (kept for the session). Null when there is none (nothing can be opened then). */
    suspend fun loadSop(): Sop? {
        val d = runCatching { teamDoc()?.collection("config")?.document("plotsop")?.get()?.await() }.getOrNull()
        if (d == null || !d.exists()) return _sop.value
        fun list(k: String) = (d.get(k) as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val steps = list("steps").mapNotNull { m ->
            val id = str(m["id"]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Step(
                id = id, stage = str(m["stage"]), role = str(m["role"]), title = str(m["title"]), timer = str(m["timer"]),
                days = (m["days"] as? Number)?.toInt(), hours = (m["hours"] as? Number)?.toInt(), next = strs(m["next"]), recv = str(m["recv"]), returnTo = str(m["returnTo"]),
                canPause = m["canPause"] == true, pauseStarts = str(m["pauseStarts"]), reminder = str(m["reminder"]), doneWithoutAll = m["doneWithoutAll"] == true,
                call = strs(m["call"]), wa = strs(m["wa"]), waMsg = str(m["waMsg"]), forms = strs(m["forms"]),
                wrongShort = str(m["wrongShort"]), wrongShortUr = str(m["wrongShortUr"]),
                lines = (m["checklist"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { c ->
                    Line(str(c["no"]), str(c["type"]), str(c["en"]), str(c["ur"]), str(c["onlyIf"]), str(c["detail"]), str(c["detailUr"]))
                },
            )
        }
        val sop = Sop(
            roles = list("roles").mapNotNull { str(it["role"]).takeIf { r -> r.isNotBlank() } },
            steps = steps,
            // A step is "opened" by a Flow row, or by a pick-one option of another step ("Opens step"). The rest start the file.
            opened = (list("flow").map { str(it["to"]) } + list("steps").flatMap { st ->
                (st["checklist"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("opens")?.toString() }
            }).filter { it.isNotBlank() }.toSet(),
            stages = list("stages").associate { str(it["no"]) to str(it["name"]) },
            forms = list("forms").map { Form(str(it["code"]), str(it["name"]), str(it["link"])) },
        )
        _sop.value = sop
        return sop
    }

    /** The owner, and whoever does the first step (Front Desk), see "New plot file" in Settings. */
    fun canOpen(me: TeamMember?, team: TeamInfo?, sop: Sop?): Boolean =
        me != null && me.active && (team?.ownerUid == me.uid || (me.designation.isNotBlank() && sop?.first?.any { it.role == me.designation } == true))

    /** Who does a step: you when it is your designation, else the first active member with that designation. */
    fun doerOf(role: String, me: TeamMember, members: List<TeamMember>): TeamMember? =
        if (me.designation == role) me else members.firstOrNull { it.active && it.designation == role }

    /**
     * When a step is due (his rules, 23 Nov; written for everyone in the sheet's "Time rules" tab), from his own working
     * hours and weekly off day (his timetable on the website) and the company holidays. The clock starts when the step is made; outside working hours it starts when work starts
     * on the next working day.
     * Working hours (N in Hours): N hours later if that many are left today, else N hours after work starts next working day.
     * Working days (N in Days): the same clock time N working days later.
     * Only these two timer types exist (23 Nov: Same visit and Same day were removed). Anything else: closing time that day.
     */
    fun dueOf(c: Context, timer: String, days: Int?, hours: Int?, from: Long): Long {
        val z = ZoneId.systemDefault()
        fun win(d: java.time.LocalDate): Pair<Long, Long>? = when {
            OffDays.isOff(d) || Seats.isHoliday(c, d) -> null
            WorkHours.isSet(c) -> WorkHours.window(c, d)
            else -> CalendarData.at(d, 11 * 60) to CalendarData.at(d, 21 * 60)   // the office's 11 AM - 9 PM, as Team uses
        }
        fun nextWorkDay(d: java.time.LocalDate): java.time.LocalDate {
            var x = d.plusDays(1)
            var guard = 0
            while (win(x) == null && guard++ < 14) x = x.plusDays(1)
            return x
        }
        var day = Instant.ofEpochMilli(from).atZone(z).toLocalDate()
        var start = from
        val w = win(day)
        if (w == null || from >= w.second) {
            day = nextWorkDay(day)
            start = win(day)?.first ?: from
        } else if (from < w.first) start = w.first
        val end = win(day)?.second ?: start
        val h = hours ?: 0
        val n = days ?: 0
        return when {
            timer.contains("working hours", ignoreCase = true) && h > 0 -> {
                val need = h * 3_600_000L
                if (end - start >= need) start + need
                else win(nextWorkDay(day))?.let { (a, b) -> minOf(a + need, b) } ?: (start + need)
            }
            n > 0 -> {
                val clock = Instant.ofEpochMilli(start).atZone(z).toLocalTime()
                repeat(n) { day = nextWorkDay(day) }
                day.atTime(clock).atZone(z).toInstant().toEpochMilli()
            }
            else -> end
        }
    }

    /** The file already opened for this plot number (test files only clash with test files), or null. */
    data class Clash(val openedAt: Long, val stage: String?)

    suspend fun clash(plotNo: String, test: Boolean): Clash? {
        val t = teamDoc() ?: return null
        val no = plotNo.trim().lowercase()
        if (no.isEmpty()) return null
        val f = t.collection("plotfiles").get(Source.SERVER).await().documents.firstOrNull {
            (it.getBoolean("test") ?: false) == test && it.get("plotNo")?.toString()?.trim()?.lowercase() == no
        } ?: return null
        // The stage it is in now: the newest step still open.
        val open = runCatching {
            t.collection("tasks").whereEqualTo("plot", true).whereEqualTo("plotId", f.id).get(Source.SERVER).await().documents
                .filter { (it.getLong("doneAt") ?: 0L) == 0L }.mapNotNull { it.getString("step") }
        }.getOrDefault(emptyList())
        return Clash(f.getLong("openedAt") ?: 0L, open.minOrNull()?.substringBefore('.'))
    }

    data class NewFile(
        val plotNo: String, val size: Number, val name: String, val cnic: String, val wa: String, val sim2: String,
        val address: String, val attorney: String,
    )

    /**
     * Saves the file and gives its first step(s) to whoever does them, in one write. [testRoles] null = a real file;
     * for a test file, the ticked roles get the task on their own phone and the rest come to yours. Returns a problem or null.
     */
    suspend fun save(c: Context, f: NewFile, sop: Sop, testRoles: Set<String>?): String? {
        val t = teamDoc() ?: return "You are not in the team."
        val me = TeamRepo.me.value ?: return "You are not in the team."
        val members = TeamRepo.members.value
        val test = testRoles != null
        if (clash(f.plotNo, test) != null) return "Plot ${f.plotNo.trim()} already has a file."
        val first = sop.first
        if (first.isEmpty()) return "The SOP has no first step. Check the Flow tab of the sheet."
        val doers = first.map { s ->
            val to = if (testRoles != null && s.role !in testRoles) me else doerOf(s.role, me, members)
                ?: return "Nobody in the team has the designation ${s.role} yet. Add it on the web in Team first."
            s to to
        }
        val now = System.currentTimeMillis()
        val db = FirebaseFirestore.getInstance()
        val file = t.collection("plotfiles").document()
        val batch = db.batch()
        batch.set(
            file, mapOf(
                "plotNo" to f.plotNo.trim(), "size" to f.size, "name" to f.name.trim(), "cnic" to f.cnic, "wa" to f.wa,
                "sim2" to f.sim2, "address" to f.address.trim(), "attorney" to f.attorney.trim(), "test" to test,
                "status" to "open", "openedAt" to now, "openedBy" to me.uid, "openedByName" to me.name, "edits" to emptyList<Any>(),
            ) + (if (testRoles != null) mapOf("testRoles" to testRoles.toList()) else emptyMap()),
        )
        for ((s, to) in doers) {
            // A first guess from this phone; the doer's phone sets it again from his own working hours (TeamSync).
            val due = dueOf(c, s.timer, s.days, s.hours, now)
            val viewers = (listOf(me.uid, to.uid) + to.managers + me.managers).distinct()
            batch.set(
                t.collection("tasks").document(), mapOf(
                    "title" to s.title,
                    "fromUid" to me.uid, "fromName" to me.name,
                    "toUid" to to.uid, "toName" to to.name, "toPhone" to to.phone,
                    "viewers" to viewers,
                    "createdAt" to now, "updatedAt" to now,
                    "deadline" to due, "urgent" to false,
                    "dateBy" to Team.GIVER_DATE, "due" to due, "firstDue" to due, "status" to Team.DATED,
                    "seenAt" to 0L, "doneAt" to 0L, "checkedAt" to 0L,
                    "moreDue" to 0L, "moreReason" to "", "moreAt" to 0L,
                    "reason" to "", "passedTo" to "",
                    "askAt" to 0L, "stuckAt" to 0L, "stuckText" to "",
                    "lastMsgAt" to 0L, "lastMsgBy" to "", "lastMsgText" to "",
                    "photoProof" to false, "proofAt" to 0L, "deliveredAt" to 0L,
                    "locked" to true, "freeExt" to 0, "extCount" to 0,
                    "reminderAt" to 0L, "nPhoto" to 0, "nVoice" to 0, "nBy" to emptyMap<String, Long>(),
                    "events" to listOf(mapOf("at" to now, "text" to "Plot file opened by ${me.first}")),
                    "plot" to true, "plotId" to file.id, "plotNo" to f.plotNo.trim(), "step" to s.id, "role" to s.role,
                    "test" to test, "ticks" to emptyMap<String, Any>(), "waitAt" to 0L,
                    "timer" to s.timer, "days" to s.days, "hours" to s.hours, "dueSet" to false, "stage" to s.stage, "fromRole" to "",
                    "ownerName" to f.name.trim(), "ownerWa" to f.wa, "ownerSim2" to f.sim2,
                ),
            )
        }
        return runCatching { batch.commit().await(); null }.getOrElse { it.message ?: "Could not save. Check the internet and try again." }
    }
}

// ---------------------------------------------------------------- Settings row

/** Settings › PLOT FILES › New plot file: a sand row right under the account card (owner and Front Desk only). */
@Composable
fun PlotFilesSettingsGroup(onOpen: () -> Unit) {
    val me by TeamRepo.me.collectAsState()
    val team by TeamRepo.team.collectAsState()
    val sop by PlotFiles.sop.collectAsState()
    LaunchedEffect(me?.uid) { if (me?.active == true) PlotFiles.loadSop() }
    if (!PlotFiles.canOpen(me, team, sop)) return
    val dark = LocalDarkTheme.current
    val sand = CardColours.PALETTE.first { it.name == "Sand" }
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipHeading("PLOT FILES", modifier = Modifier.padding(start = 4.dp))
        Card(
            onClick = onOpen,
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = sand.fill(dark)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.border(1.dp, sand.edge(dark).copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(sand.edge(dark).copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = sand.ink(dark), modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("New plot file", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = sand.ink(dark))
                    Text("Open a file for a new plot owner", fontSize = 12.5.sp, color = sand.ink(dark).copy(alpha = 0.8f), maxLines = 1)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- New plot file screen (locked mockup C)

/** Shows digits with a separator before the given positions (CNIC 35202-1234567-1, phone 0300 1234567). */
private class Grouped(private val cuts: List<Int>, private val sep: Char) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val out = buildString { s.forEachIndexed { i, ch -> if (i in cuts) append(sep); append(ch) } }
        return TransformedText(AnnotatedString(out), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + cuts.count { it < offset }
            override fun transformedToOriginal(offset: Int): Int {
                var o = offset
                cuts.filter { it < s.length }.forEachIndexed { k, cut -> if (cut + k < offset) o-- }
                return o.coerceIn(0, s.length)
            }
        })
    }
}

private val CnicCuts = Grouped(listOf(5, 12), '-')
private val PhoneCuts = Grouped(listOf(4), ' ')

@Composable
private fun DigitsField(label: String, value: String, need: Int, optional: Boolean, what: String, format: VisualTransformation, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val n = value.length
    val mobile = what == "Phone numbers"   // a Pakistani mobile: starts 03 (locked 23 Nov)
    val notMobile = mobile && n >= 2 && !value.startsWith("03")
    val ok = n == need && !notMobile
    val bad = notMobile || n > need || (n in 1 until need && !focused)
    val cs = MaterialTheme.colorScheme
    val hint: (@Composable () -> Unit)? = if (optional) { { Text("If any") } } else null
    val count: (@Composable () -> Unit)? = if (n > 0) {
        { Text(if (ok) "$n/$need ✓" else "$n/$need", color = if (ok) TextGreen else if (bad) cs.error else cs.onSurfaceVariant, fontSize = 13.sp) }
    } else null
    val error: (@Composable () -> Unit)? = if (bad) { { Text(if (notMobile) "A mobile number starts with 03." else "$what must have $need digits. You typed $n.") } } else null
    OutlinedTextField(
        value = value,
        // Locked to [need] digits (23 Nov): no more can be typed; a pasted +92 3xx becomes 03xx.
        onValueChange = { v -> onChange(v.filter(Char::isDigit).let { d -> if (mobile && d.length == 12 && d.startsWith("923")) "0" + d.drop(2) else d }.take(need)) },
        label = { Text(if (optional) label else "$label *") },
        placeholder = hint,
        suffix = count,
        isError = bad,
        supportingText = error,
        visualTransformation = format,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    )
}

@Composable
private fun PlainField(label: String, value: String, required: Boolean, onChange: (String) -> Unit, placeholder: String? = null) {
    val hint: (@Composable () -> Unit)? = if (placeholder != null) { { Text(placeholder) } } else null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (required) "$label *" else label) },
        placeholder = hint,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NewPlotFileScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val me by TeamRepo.me.collectAsState()
    val team by TeamRepo.team.collectAsState()
    val members by TeamRepo.members.collectAsState()
    val sop by PlotFiles.sop.collectAsState()
    val isOwner = me != null && team?.ownerUid == me?.uid
    LaunchedEffect(Unit) { PlotFiles.loadSop() }

    var plotNo by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var cnic by remember { mutableStateOf("") }
    var wa by remember { mutableStateOf("") }
    var sim2 by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var attorney by remember { mutableStateOf("") }
    var aoOk by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf(setOf<String>()) }
    var clash by remember { mutableStateOf<PlotFiles.Clash?>(null) }
    var checking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // The plot number is checked online as it is typed (plot numbers are unique).
    LaunchedEffect(plotNo, test) {
        clash = null
        if (plotNo.isBlank()) return@LaunchedEffect
        checking = true
        delay(500)
        clash = runCatching { PlotFiles.clash(plotNo, test) }.getOrNull()
        checking = false
    }

    val sizeNum = size.toDoubleOrNull()?.takeIf { it > 0 }
    val ready = plotNo.isNotBlank() && clash == null && !checking && sizeNum != null && name.isNotBlank() && cnic.length == 13 &&
        wa.length == 11 && wa.startsWith("03") && (sim2.isEmpty() || (sim2.length == 11 && sim2.startsWith("03"))) && address.isNotBlank() && (test || aoOk) && sop != null && !busy

    val red = clash != null || (size.isNotEmpty() && sizeNum == null) || listOf(cnic to 13, wa to 11, sim2 to 11).any { (v, n) -> v.isNotEmpty() && v.length != n }

    fun save() {
        val s = sop ?: return
        busy = true
        problem = null
        scope.launch {
            val f = PlotFiles.NewFile(plotNo, if (sizeNum!! % 1.0 == 0.0) sizeNum.toLong() else sizeNum, name, cnic, wa, sim2, address, attorney)
            val r = runCatching { PlotFiles.save(ctx, f, s, if (test) ticked else null) }.getOrElse { it.message ?: "Could not save" }
            busy = false
            if (r == null) {
                android.widget.Toast.makeText(ctx, "Plot ${plotNo.trim()} file opened", android.widget.Toast.LENGTH_SHORT).show()
                onBack()
            } else problem = r
        }
    }

    Scaffold(
        containerColor = cs.background,
        bottomBar = {
            Column(Modifier.background(cs.background).safeBottomPadding()) {
                HorizontalDivider(color = cs.surfaceVariant)
                Button(
                    onClick = { save() }, enabled = ready,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (busy) "Saving…" else if (test) "Save test file" else "Save and open file", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Text(
                    problem ?: if (sop == null) "Reading the SOP… needs internet" else if (red) "Fix the red boxes to save" else "Needs internet · plot number is checked first",
                    color = if (problem != null) cs.error else cs.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The same header as every Settings page.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("New plot file", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                if (test) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "TEST", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TestAmber,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(TestAmber.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (isOwner) TestCard(test, { test = it }, sop?.roles.orEmpty(), ticked) { r, on -> ticked = if (on) ticked + r else ticked - r }

            val c = clash
            val clashText: (@Composable () -> Unit)? = if (c != null) {
                {
                    Text(
                        "⛔ Plot ${plotNo.trim()} already has a file (opened ${if (c.openedAt > 0L) TeamUi.shortWhen(c.openedAt) else "before"}" +
                            (c.stage?.let { ", now in Stage $it" } ?: "") + "). You cannot save. Check the plot number with the plot owner."
                    )
                }
            } else null
            OutlinedTextField(
                value = plotNo, onValueChange = { plotNo = it.take(20) },
                label = { Text("Plot number *") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                isError = c != null,
                supportingText = clashText,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = size, onValueChange = { v -> size = v.filter { it.isDigit() || it == '.' }.take(6) },
                label = { Text("Plot size (marla) *") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                suffix = { Text("marla", color = cs.onSurfaceVariant) },
                isError = size.isNotEmpty() && sizeNum == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            PlainField("Plot owner name", name, true, { name = it.take(60) })
            DigitsField("CNIC number", cnic, 13, false, "CNIC", CnicCuts) { cnic = it }
            DigitsField("WhatsApp number", wa, 11, false, "Phone numbers", PhoneCuts) { wa = it }
            DigitsField("Second SIM (if any)", sim2, 11, true, "Phone numbers", PhoneCuts) { sim2 = it }
            PlainField("Address", address, true, { address = it.take(150) })
            PlainField("Attorney name", attorney, false, { attorney = it.take(60) }, placeholder = "If any")
            if (!test) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { aoOk = !aoOk }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = aoOk, onCheckedChange = { aoOk = it })
                Text("Accounts Officer confirmed this is the real plot owner", fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val TestAmber = androidx.compose.ui.graphics.Color(0xFFF2A93B)

/** Your phone only: Test file switch, and which roles get their real task (the rest come to your phone). */
@Composable
private fun TestCard(on: Boolean, onSwitch: (Boolean) -> Unit, roles: List<String>, ticked: Set<String>, onTick: (String, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { onSwitch(!on) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Test file", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Only you see this switch", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = onSwitch)
        }
        if (on) {
            HorizontalDivider(color = cs.surfaceVariant)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    "Ticked roles get their real task on their own phone, tagged TEST. Unticked roles come to your phone, labelled with the role.",
                    fontSize = 13.sp, color = cs.onSurfaceVariant,
                )
                roles.forEach { r ->
                    val t = r in ticked
                    Row(Modifier.fillMaxWidth().clickable { onTick(r, !t) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = t, onCheckedChange = { onTick(r, it) })
                        Text(r, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(if (t) "their phone" else "your phone", fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
