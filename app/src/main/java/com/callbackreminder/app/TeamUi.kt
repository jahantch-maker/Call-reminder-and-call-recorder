package com.callbackreminder.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Team screens and the small team parts of other screens (chips, Needs you lines, the For box). */
object TeamUi {
    /** A page to open: [SETTINGS], [DAY] (with [dayOf]), or the Follow-ups / My tasks tab. AppRoot opens it and clears this. */
    val request = MutableStateFlow<String?>(null)
    const val SETTINGS = "settings"
    const val FOLLOW_UPS = "followups"
    const val MY_TASKS = "mytasks"
    const val DAY = "day"
    const val TIMELINE = "timeline"

    /** Which team task the Timeline page shows. */
    val timelineOf = MutableStateFlow<String?>(null)

    fun openTimeline(id: String) {
        timelineOf.value = id
        request.value = TIMELINE
    }

    /** Home's "❓ n questions waiting ›": Follow-ups, scrolled to this card. */
    fun showGiven(c: Context, t: TeamTask) {
        val link = TeamLinks.map(c, "given")[t.id]?.toLongOrNull()
        if (link != null) FollowUpsUi.showEntry.value = "link-$link"
        request.value = FOLLOW_UPS
    }

    /** Whose day the day page shows. */
    val dayOf = MutableStateFlow<String?>(null)

    fun openDay(uid: String) {
        dayOf.value = uid
        request.value = DAY
    }

    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    fun shortDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)

    /** A promise or date in a chip: "Sat 4 pm" this week, else "2 Nov 4 pm". */
    fun shortWhen(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val day = if (z.toLocalDate().isBefore(java.time.LocalDate.now().plusDays(6))) "EEE" else "d MMM"
        val time = if (z.minute == 0) "h a" else "h:mm a"
        return z.format(DateTimeFormatter.ofPattern("$day $time", Locale.ENGLISH)).replace("AM", "am").replace("PM", "pm")
    }

    /** The team task behind one of your My tasks (his side). */
    fun taskOfMyTask(c: Context, myTaskId: Long, tasks: List<TeamTask>): TeamTask? =
        TeamLinks.teamTaskOfMyTask(c, myTaskId)?.let { id -> tasks.firstOrNull { it.id == id } }

    /** The team task behind a follow-up: one you gave (your side) or passed down (his side). */
    fun taskOfLink(c: Context, linkId: Long, tasks: List<TeamTask>): TeamTask? {
        if (linkId == 0L) return null
        val id = TeamLinks.givenOfLink(c, linkId) ?: TeamLinks.passedOfLink(c, linkId) ?: return null
        return tasks.firstOrNull { it.id == id }
    }

    /** "You → Basharat → Ilyas" on your side, "Jahanzaib → You → Ilyas" on his: who has it now. */
    fun chain(t: TeamTask, me: String?): String? {
        if (t.passedTo.isBlank()) return null
        val from = if (t.fromUid == me) "You" else t.fromFirst
        val to = if (t.toUid == me) "You" else t.toFirst
        return "$from → $to → ${t.passedTo}"
    }

    /** Your side: the deadline (or Deadline pending) and where it stands. Sent / Seen are the ticks, waiting talk the banner. */
    fun giverChips(t: TeamTask): List<RowChip> = buildList {
        chain(t, TeamRepo.uid)?.let { add(RowChip("👤 $it", ChipTone.BLUE)) }
        when {
            t.status == Team.REOPENED -> {
                add(RowChip("↩ Not done yet", ChipTone.RED))
                add(if (t.due > 0L) RowChip("📅 Deadline ${shortWhen(t.due)}", ChipTone.ORANGE) else hisDeadlineChip(t))
            }
            t.open && t.due > 0L -> add(RowChip("📅 Deadline ${shortWhen(t.due)}", ChipTone.ORANGE))
            t.open -> add(hisDeadlineChip(t))
            t.status == Team.DONE -> add(RowChip("✅ Done, check it", ChipTone.GREEN))
            t.status == Team.CHECKED -> add(RowChip("Received ✓", ChipTone.GREEN))
            t.status == Team.CANCELLED -> add(RowChip("Cancelled", ChipTone.MUTED))
            t.status == Team.RETURNED -> add(RowChip("Returned", ChipTone.MUTED))
        }
        if (t.extCount > 0 && t.open) add(RowChip("Extended ${t.extCount}×", ChipTone.MUTED))
        if (t.photoProof && t.open) add(RowChip("📷 Photo proof", ChipTone.MUTED))
    }

    /** Your side, his date not picked yet: "⏳ His deadline? · Latest Thu". */
    private fun hisDeadlineChip(t: TeamTask) =
        RowChip("⏳ His deadline?" + if (t.deadline > 0L) " · Latest ${shortWhen(t.deadline)}" else "", ChipTone.ORANGE)

    /** His side: ↘ who gave it (and when), his deadline (or Pick your deadline · Latest Thu), photo proof. */
    fun receiverChips(t: TeamTask): List<RowChip> = buildList {
        add(RowChip(chain(t, TeamRepo.uid)?.let { "↘ $it" } ?: "↘ ${t.fromFirst} · ${Fmt.timeOnly(t.createdAt)}", ChipTone.BLUE))
        if (t.open) add(
            if (t.due > 0L) RowChip("📅 Deadline ${shortWhen(t.due)}" + if (t.deadlineLocked) " 🔒" else "", ChipTone.ORANGE)
            else RowChip("📅 Pick your deadline" + if (t.deadline > 0L) " · Latest ${shortWhen(t.deadline)}" else "", ChipTone.ORANGE)
        )
        if (t.open && t.stuck) add(RowChip("⚠ Stuck, waiting", ChipTone.MUTED))
        else if (t.open && t.asks) add(RowChip("❓ Asked, waiting", ChipTone.MUTED))
        if (t.asking) add(RowChip("📅 Asked for ${shortWhen(t.moreDue)}", ChipTone.MUTED))
        if (t.extCount > 0 && t.open) add(RowChip("Extended ${t.extCount}×", ChipTone.MUTED))
        if (t.photoProof && t.open) add(RowChip("📷 Photo proof", ChipTone.MUTED))
        if (t.status == Team.DONE) add(RowChip("Waiting for ${t.fromFirst}'s check", ChipTone.MUTED))
    }

    /**
     * The thin line on top of the card: ⚠ stuck (red), 📅 a deadline request (purple), ❓ a question (orange) stay
     * until answered; 💬 new messages and 📥 New from (blue) go once opened. Tap opens the Timeline.
     */
    fun banner(c: Context, t: TeamTask, me: String?): CardBanner? {
        val open = { openTimeline(t.id) }
        val giver = t.fromUid == me
        val n = TeamSync.unreadCount(c, t, me)
        return when {
            giver && t.stuck -> CardBanner("⚠ ${t.toFirst} is stuck" + t.stuckText.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), ChipTone.RED, open, "Answer")
            giver && t.asking -> CardBanner("📅 ${t.toFirst} asks to extend to ${shortWhen(t.moreDue)}", ChipTone.PURPLE, open, "Answer")
            giver && t.asks -> CardBanner("❓ ${t.toFirst} asked a question", ChipTone.ORANGE, open, "Answer")
            !giver && t.status == Team.REOPENED -> CardBanner("↩ Not done yet" + (if (t.reason.isNotBlank()) " · ${t.reason}" else "") +
                if (t.due == 0L) " · pick a new deadline" else "", ChipTone.RED, open)
            !giver && t.toUid == me && t.open && TeamSync.isNew(c, t.id) -> CardBanner("📥 New from ${t.fromFirst}", ChipTone.BLUE, open)
            n > 0 -> CardBanner("💬 $n new on Timeline", ChipTone.BLUE, open)
            else -> null
        }
    }

    /**
     * Grey early warnings on a task you gave (Needs you › Team): not opened yet after 2 working hours (urgent) or one
     * working day, or extended 2× (urgent) / 3× and more.
     */
    fun earlyWarning(c: Context, t: TeamTask, now: Long = System.currentTimeMillis()): String? {
        if (!t.open || t.fromUid != TeamRepo.uid) return null
        if (!Ticks.seen(t)) {
            val m = TeamRules.minutesSince(c, t.createdAt, now)
            if (m >= if (t.urgent) 120L else TeamRules.dayMin(c)) return "👁 Not seen by ${t.toFirst} · ${m / 60} h"
        }
        if (t.extCount >= if (t.urgent) 2 else 3) return "📅 Extended ${t.extCount}× by ${t.toFirst}"
        return null
    }

    /** "Timeline · 📷 2 · 🎤 1": the link with what it holds. */
    fun timelineLabel(t: TeamTask): String =
        "Timeline" + (if (t.nPhoto > 0) " · 📷 ${t.nPhoto}" else "") + if (t.nVoice > 0) " · 🎤 ${t.nVoice}" else ""

    /** One line in Needs you: what it says and how many; [first] is the card it opens to. */
    data class Need(
        val text: String, val count: Int, val tone: ChipTone, val mine: Boolean = false, val first: TeamTask? = null,
        /** [FollowUpsUi.STUCK], [FollowUpsUi.EXTEND] or [FollowUpsUi.ASK]: opens WAITING ON YOU with only these. */
        val kind: Int? = null,
    )

    /** A Needs you line: only one waiting opens its Timeline, more open Follow-ups with only that kind. */
    fun openNeed(c: Context, n: Need) {
        val first = n.first
        when {
            n.mine -> request.value = MY_TASKS
            n.kind != null && n.count == 1 && first != null -> openTimeline(first.id)
            n.kind != null -> {
                FollowUpsUi.needKind.value = n.kind
                request.value = FOLLOW_UPS
            }
            first != null -> showGiven(c, first)
            else -> request.value = FOLLOW_UPS
        }
    }

    fun needs(c: Context, tasks: List<TeamTask>, me: String?): List<Need> {
        if (me == null) return emptyList()
        val now = System.currentTimeMillis()
        val given = tasks.filter { it.fromUid == me }
        val noDateMin = TeamRules.noDateMin(c)
        return buildList {
            given.filter { it.open && it.stuck }.sortedBy { it.stuckAt }.takeIf { it.isNotEmpty() }?.let {
                add(Need("⚠ ${it.size} stuck · " + it.map { t -> t.toFirst }.distinct().joinToString(", ").take(40), it.size, ChipTone.RED, first = it.first(), kind = FollowUpsUi.STUCK))
            }
            given.filter { it.open && it.asks && !it.stuck && !it.asking }.sortedBy { it.askAt }.takeIf { it.isNotEmpty() }?.let {
                add(Need("❓ ${it.size} question${if (it.size == 1) "" else "s"} waiting · " + it.map { t -> t.toFirst }.distinct().joinToString(", ").take(40),
                    it.size, ChipTone.ORANGE, first = it.first(), kind = FollowUpsUi.ASK))
            }
            given.filter { it.asking }.sortedBy { it.moreAt }.takeIf { it.isNotEmpty() }?.let {
                add(Need("📅 ${it.size} deadline request${if (it.size == 1) "" else "s"} · " + it.map { t -> t.toFirst }.distinct().joinToString(", ").take(40),
                    it.size, ChipTone.PURPLE, first = it.first(), kind = FollowUpsUi.EXTEND))
            }
            given.filter { it.open && it.due == 0L && !it.asks && !it.stuck && TeamRules.minutesSince(c, it.createdAt, now) >= noDateMin }.takeIf { it.isNotEmpty() }?.let {
                val oldest = it.minOf { t -> t.createdAt }
                val h = TeamRules.minutesSince(c, oldest, now) / 60
                add(Need("📅 ${it.size} deadline pending · " + it.map { t -> t.toFirst }.distinct().joinToString(", ") + " · ${h} h", it.size, ChipTone.ORANGE))
            }
            tasks.filter { it.toUid == me && it.open && it.hisDate && it.due == 0L }.takeIf { it.isNotEmpty() }?.let {
                add(Need("📅 ${it.size} task${if (it.size == 1) "" else "s"} need your deadline", it.size, ChipTone.ORANGE, mine = true))
            }
            tasks.filter { it.toUid == me && it.status == Team.REOPENED }.takeIf { it.isNotEmpty() }?.let {
                add(Need("↩ ${it.size} sent back: not done yet", it.size, ChipTone.RED, mine = true))
            }
        }
    }

    fun shareCode(c: Context, team: TeamInfo) {
        val text = "Join ${team.name} on Callback Reminder: Settings › Team › Join, code ${team.code}"
        val wa = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.whatsapp")
        try {
            c.startActivity(wa.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share code")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * v0.44 "🟢 Active now" / "Last active 12 min ago": the app open on his phone (not the phone itself), so it never
     * promises more than it knows. Shown on his person page, the Timeline top and the Team page; everyone sees everyone.
     */
    fun lastSeenLabel(m: TeamMember): String {
        if (m.onlineNow()) return "🟢 Active now"
        if (m.lastSeen <= 0L) return "Not opened yet"
        val at = TeamPresence.lastAt(m.uid).takeIf { it > 0L } ?: m.lastSeen
        val min = (System.currentTimeMillis() - at) / 60_000L
        return when {
            min < 1L -> "Last active just now"
            min < 60L -> "Last active $min min ago"
            Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now() -> "Last active today ${Fmt.timeOnly(at)}"
            else -> "Last active ${Fmt.dueLabel(at)}"
        }
    }
}

// ---------------------------------------------------------------- small pieces

/** The one small heading (grey caps), like every chip row's. */
@Composable
private fun TeamHeading(text: String) = ChipHeading(text.uppercase(), modifier = Modifier.padding(start = 4.dp, top = 4.dp))

@Composable
private fun TeamTag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun TeamChipView(c: RowChip) = SmallChip(c)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamChips(chips: List<RowChip>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { TeamChipView(it) }
    }
}

@Composable
private fun TeamCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun OnlineDot(on: Boolean) {
    Box(Modifier.size(9.dp).background(if (on) Color(0xFF2FBF7F) else MaterialTheme.colorScheme.outline, CircleShape))
}

/** The minutes chips of Date rules (1h 2h 4h 8h 1 day). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinuteChips(choices: List<Int>, selected: Int, label: (Int) -> String = TeamRules::label, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { m ->
            FilterChip(selected = m == selected, onClick = { onPick(m) }, label = { Text(label(m)) })
        }
    }
}

// ---------------------------------------------------------------- Settings › Team

@Composable
fun TeamSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by TeamRepo.user.collectAsState()
    val joined by TeamRepo.joined.collectAsState()
    val team by TeamRepo.team.collectAsState()
    val me by TeamRepo.me.collectAsState()
    val members by TeamRepo.members.collectAsState()
    val tasks by TeamRepo.tasks.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun go(block: suspend () -> String?) {
        busy = true
        msg = null
        scope.launch {
            val r = runCatching { block() }.getOrElse { it.message ?: "Something went wrong" }
            busy = false
            msg = r
        }
    }

    SettingsPage(title = "Team", onBack = onBack) {
        val m = me
        val t = team
        when {
            user == null -> SectionCard(
                title = "Team",
                subtitle = "Give tasks to your staff on their own phones: they pick a date, you see Seen and Done. Sign in once with Google.",
            ) {
                Button(onClick = {
                    ctx.findActivity()?.let { act -> go { TeamRepo.signIn(act) } }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Signing in…" else "Sign in with Google") }
            }
            joined == null -> TeamStart(busy = busy, go = { go(it) })
            m == null -> SectionCard(title = "Connecting…", subtitle = "Needs mobile data or Wi-Fi the first time.") {
                TextButton(onClick = { go { TeamRepo.leave() } }) { Text("Cancel") }
            }
            m.pending -> SectionCard(title = "Join a team", subtitle = "Waiting for approval") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓  Signed in with Google · ${user?.email.orEmpty()}")
                    Text("✓  Asked to join" + (t?.name?.let { " $it" } ?: ""))
                    Text("⏳  Waiting for your manager to approve. You'll see the team here once he does.")
                    TextButton(onClick = { go { TeamRepo.leave() } }) { Text("Cancel request", color = MaterialTheme.colorScheme.error) }
                }
            }
            t != null -> TeamActive(t, m, members, tasks, busy, go = { go(it) })
        }
        msg?.let { Text(it, color = if (it.startsWith("Moved")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        if (user != null) {
            TextButton(onClick = { TeamRepo.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out on this phone") }
        }
    }
}

/** Not in a team yet: one "Ask to join Rafiq Builders" (no code); join with a code only if it can't be found. */
@Composable
private fun TeamStart(busy: Boolean, go: (suspend () -> String?) -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    var joinName by remember { mutableStateOf(TeamRepo.user.value?.displayName.orEmpty()) }
    var home by remember { mutableStateOf<Pair<String, String>?>(null) }
    var looked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { home = TeamRepo.findHomeTeam(); looked = true }
    val h = home
    SectionCard(
        title = if (h != null) "Join ${h.second}" else "Join a team",
        subtitle = if (h != null) "Your manager approves you, then tasks come to your phone." else if (looked) "Ask your manager for the 6-letter code." else "Looking for your team…",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (h == null && looked) OutlinedTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) }, label = { Text("Team code") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            OutlinedTextField(joinName, { joinName = it }, label = { Text("Your name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("No phone number to type: your manager links your numbers when he approves you.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "Before you join: your manager will see your work tasks and follow-ups. Tap 🔒 Private on any task to keep it " +
                        "private (he sees only how many).",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp)
                )
            }
            Button(onClick = {
                when {
                    joinName.isBlank() -> Toast.makeText(ctx, "Add your name", Toast.LENGTH_SHORT).show()
                    h != null -> go { TeamRepo.joinTeam(h.first, joinName, "") }
                    else -> go { TeamRepo.join(code, joinName, "") }
                }
            }, enabled = !busy && looked && (h != null || code.length == 6), modifier = Modifier.fillMaxWidth()) {
                Text(if (h != null) "Ask to join ${h.second}" else "Ask to join")
            }
        }
    }
}

/**
 * In the team. The Owner (and Admins) see Waiting for you, the chart with Edit and the date rules; a Member sees
 * only his place in the team and Leave. Date rules show to anyone with people under him.
 */
@Composable
private fun TeamActive(
    t: TeamInfo, me: TeamMember, members: List<TeamMember>, tasks: List<TeamTask>, busy: Boolean,
    go: (suspend () -> String?) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val admin = me.role == Team.OWNER || me.role == Team.ADMIN
    val active = members.filter { it.active }
    val waiting = members.filter { it.pending }
    val hasReports = active.any { me.uid in it.managers && it.uid != me.uid }
    var approving by remember { mutableStateOf<TeamMember?>(null) }
    var editing by remember { mutableStateOf<TeamMember?>(null) }
    LaunchedEffect(me.role, members.size) { if (me.role == Team.OWNER) runCatching { TeamRepo.adminsToMembers() } }

    TeamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TeamTag("You · ${me.roleLabel}")
        }
        val boss = active.firstOrNull { it.uid == me.reportsTo }
        Text(
            if (admin) "${active.size} member${if (active.size == 1) "" else "s"}. Nobody joins until ${if (me.role == Team.OWNER) "you approve" else "the owner approves"}."
            else "Tasks from your manager come to My tasks." + (boss?.let { " Your Dossier is set by ${active.firstOrNull { m -> m.role == Team.OWNER }?.first ?: it.first}." } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
        )
    }

    if (admin && waiting.isNotEmpty()) {
        TeamHeading("Waiting for you · ${waiting.size}")
        waiting.forEach { w ->
            TeamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(w.name.ifBlank { w.email }, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.name.ifBlank { "New member" }, fontWeight = FontWeight.Bold)
                        Text("${w.phone} · ${Fmt.dueLabel(w.joinedAt)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { go { TeamRepo.decline(w) } }, enabled = !busy, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text("Decline", color = cs.error)
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { approving = w }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Approve") }
                }
            }
        }
    }

    @Composable
    fun Node(m: TeamMember, depth: Int, byBoss: Map<String, List<TeamMember>>, only: Set<String>?) {
        val canSee = me.uid in m.managers
        val openFromMe = tasks.count { it.toUid == m.uid && it.fromUid == me.uid && it.open }
        Row(
            Modifier.fillMaxWidth().padding(start = (depth * 18).dp)
                .clickable(enabled = canSee) { TeamUi.openDay(m.uid) }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(m.name, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.first, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    OnlineDot(m.onlineNow())
                }
                val who = when {
                    m.uid == me.uid -> "you"
                    m.uid == me.reportsTo -> "your manager"
                    else -> TeamUi.lastSeenLabel(m).replaceFirstChar { it.lowercase() }.removePrefix("🟢 ")
                }
                Text(m.roleLabel + " · " + who + (if (openFromMe > 0) " · $openFromMe open from you" else ""), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            if (admin && m.uid != me.uid && m.role != Team.OWNER) TextButton(onClick = { editing = m }) { Text("Edit") }
            if (canSee) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "His day", tint = cs.onSurfaceVariant)
        }
        byBoss[m.uid].orEmpty().filter { only == null || it.uid in only }.forEach { Node(it, depth + 1, byBoss, only) }
    }

    val byBoss = active.groupBy { it.reportsTo }
    val ids = active.map { it.uid }.toSet()
    if (admin) {
        TeamHeading("Team chart")
        TeamCard {
            active.filter { it.reportsTo.isEmpty() || it.reportsTo !in ids }.forEach { Node(it, 0, byBoss, null) }
        }
    } else {
        // His place: the people above him, him, and anyone under him.
        TeamHeading("Your place in the team")
        val only = (me.managers + me.uid + active.filter { me.uid in it.managers }.map { it.uid }).toSet()
        TeamCard {
            active.filter { it.uid in only && (it.reportsTo.isEmpty() || it.reportsTo !in only) }.forEach { Node(it, 0, byBoss, only) }
        }
    }

    // Everyone can give tasks, so everyone has these rules for the tasks they give.
    run {
        TeamHeading("Date rules")
        var noDate by remember { mutableIntStateOf(TeamRules.noDateMin(ctx)) }
        var urgent by remember { mutableIntStateOf(TeamRules.urgentMin(ctx)) }
        var sms by remember { mutableIntStateOf(TeamRules.smsMin(ctx)) }
        var working by remember { mutableStateOf(TeamRules.workingOnly(ctx)) }
        var locked by remember { mutableStateOf(TeamRules.deadlineLocked(ctx)) }
        var freeExt by remember { mutableIntStateOf(TeamRules.freeExt(ctx)) }
        var rule by remember { mutableIntStateOf(-1) }
        var reasons by remember { mutableStateOf(TeamRules.notDoneReasons(ctx)) }
        var editReasons by remember { mutableStateOf(false) }
        TeamCard {
            RuleRow("No deadline picked · remind me", TeamRules.label(noDate)) { rule = 0 }
            RuleRow("Urgent, no deadline · notify me", TeamRules.label(urgent)) { rule = 1 }
            RuleRow("Not seen · text him", if (sms == 0) "Off" else TeamRules.label(sms)) { rule = 2 }
            RuleRow("Working hours only", if (working) "On" else "Off") { working = !working; TeamRules.setWorkingOnly(ctx, working) }
            RuleRow("Deadline locked", if (locked) "On" else "Off") { locked = !locked; TeamRules.setDeadlineLocked(ctx, locked) }
            RuleRow("Extend without asking", TeamRules.extLabel(freeExt)) { rule = 3 }
            RuleRow("Not done reasons", "${reasons.size}") { editReasons = true }
            Text("New tasks follow these. Locked: once he sets his deadline, he can only ask to extend it.",
                fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        if (editReasons) NotDoneReasonsDialog(reasons, onDismiss = { editReasons = false }) { list ->
            TeamRules.setNotDoneReasons(ctx, list)
            reasons = TeamRules.notDoneReasons(ctx)
            editReasons = false
        }
        if (rule >= 0) {
            val (title, choices, sel) = when (rule) {
                0 -> Triple("No deadline picked · remind me after", TeamRules.NO_DATE_CHOICES, noDate)
                1 -> Triple("Urgent, no deadline · notify me after", TeamRules.URGENT_CHOICES, urgent)
                2 -> Triple("Not seen · text him after", TeamRules.SMS_CHOICES, sms)
                else -> Triple("Extend without asking", TeamRules.FREE_EXT_CHOICES, freeExt)
            }
            AlertDialog(
                onDismissRequest = { rule = -1 },
                title = { Text(title, fontSize = 17.sp) },
                text = {
                    MinuteChips(choices, sel, label = if (rule == 3) TeamRules::extLabel else TeamRules::label) { m ->
                        when (rule) {
                            0 -> { noDate = m; TeamRules.setNoDateMin(ctx, m) }
                            1 -> { urgent = m; TeamRules.setUrgentMin(ctx, m) }
                            2 -> { sms = m; TeamRules.setSmsMin(ctx, m) }
                            else -> { freeExt = m; TeamRules.setFreeExt(ctx, m) }
                        }
                        rule = -1
                    }
                },
                confirmButton = { TextButton(onClick = { rule = -1 }) { Text("Close") } },
            )
        }
    }

    if (me.role != Team.OWNER) {
        TextButton(onClick = { go { TeamRepo.leave() } }, modifier = Modifier.fillMaxWidth()) { Text("Leave the team", color = cs.error) }
    }

    approving?.let { w ->
        TeamApproveDialog(w, me, active, onDismiss = { approving = null }) { nums, boss, role ->
            approving = null
            go { TeamRepo.approve(ctx, w, nums, boss, role) }
        }
    }
    editing?.let { m ->
        TeamChartDialog(m, active, tasks, onDismiss = { editing = null },
            onSave = { boss, role, nums, own, set -> editing = null; go { TeamRepo.setChart(ctx, m, boss, role, nums, own, set) } },
            onRemove = { to -> editing = null; go { TeamRepo.remove(ctx, m, to) } })
    }
}

/** Settings › Team › Not done reasons: the WHY chips of ↩ Not done yet. Add, rename, move up, delete. */
@Composable
private fun NotDoneReasonsDialog(start: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val list = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(start) } }
    var adding by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Not done reasons") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("The quick WHY chips when you send a task back.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(r, { list[i] = it }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                        TextButton(onClick = { if (i > 0) { val x = list.removeAt(i); list.add(i - 1, x) } }, enabled = i > 0) { Text("↑") }
                        TextButton(onClick = { list.removeAt(i) }, enabled = list.size > 1) { Text("✕", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(adding, { adding = it }, placeholder = { Text("New reason") }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                    TextButton(onClick = { if (adding.isNotBlank()) { list.add(adding.trim()); adding = "" } }) { Text("Add") }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(list.toList() + listOfNotNull(adding.trim().takeIf { it.isNotEmpty() })) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One ACCESS switch in Team › edit person. */
@Composable
private fun AccessSwitch(label: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** A slim date-rule row: name, value on the right. */
@Composable
private fun RuleRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Reports to (radio list) and role chips, shared by Approve and Edit. */
@Composable
private fun TeamChartFields(
    m: TeamMember, active: List<TeamMember>, boss: String, onBoss: (String) -> Unit, role: String, onRole: (String) -> Unit,
) {
    Text("Reports to", fontWeight = FontWeight.Bold)
    active.filter { it.uid != m.uid && m.uid !in it.managers }.forEach { b ->
        Row(Modifier.fillMaxWidth().clickable { onBoss(b.uid) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = boss == b.uid, onClick = { onBoss(b.uid) })
            Text(b.name + " · " + b.roleLabel)
        }
    }
    Text("Role", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Team.ADMIN, Team.MEMBER).forEach { r ->
            FilterChip(selected = role == r, onClick = { onRole(r) }, label = { Text(r.replaceFirstChar { it.uppercase() }) })
        }
    }
}

/** Approve: who he reports to, his role, then his NUMBERS from your contacts (his open follow-ups under all of them move). */
@Composable
private fun TeamApproveDialog(
    w: TeamMember, me: TeamMember, active: List<TeamMember>, onDismiss: () -> Unit, onApprove: (MemberNumbers, String, String) -> Unit,
) {
    var nums by remember { mutableStateOf(MemberNumbers.of(w)) }
    var boss by remember { mutableStateOf(me.uid) }
    var role by remember { mutableStateOf(Team.MEMBER) }
    val first = Names.first(w.name) ?: w.name.ifBlank { "him" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${w.name.ifBlank { w.email }} wants to join") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (w.email.isNotBlank()) Text(w.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TeamChartFields(w, active, boss, { boss = it }, role, { role = it })
                TeamNumbersEditor(w.name, nums) { nums = it }
            }
        },
        confirmButton = {
            Button(onClick = { onApprove(nums, boss, role) }, enabled = nums.main.isNotBlank(), modifier = Modifier.height(48.dp)) {
                Text(if (nums.main.isBlank()) "Pick a number first" else "Approve $first", maxLines = 1)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** His numbers as picked: the ticked ones, ⭐ [main] (calls and SMS) and 🟢 [wa] (WhatsApp). */
data class MemberNumbers(val rows: List<Row>, val main: String, val wa: String) {
    data class Row(val number: String, val from: String, val ticked: Boolean = true, val onWa: Boolean = false)

    val ticked: List<String> get() = rows.filter { it.ticked }.map { it.number }

    companion object {
        fun of(m: TeamMember) = MemberNumbers(
            m.numbers.map { Row(it, "") }, m.phone, m.wa.takeIf { it.isNotBlank() && it != m.phone }.orEmpty(),
        )
    }

    /** Adds numbers (a contact's, or typed); the first one ticked becomes ⭐ Main, a WhatsApp one 🟢 WA. */
    fun plus(new: List<Row>): MemberNumbers {
        val add = new.filter { n -> rows.none { SmsStore.sameNumber(it.number, n.number) } }
        val all = rows + add
        val m = main.ifBlank { all.firstOrNull { it.ticked }?.number.orEmpty() }
        val w = wa.ifBlank { all.firstOrNull { it.ticked && it.onWa && it.number != m }?.number.orEmpty() }
        return MemberNumbers(all, m, w)
    }

    fun tick(n: String, on: Boolean): MemberNumbers {
        val r = rows.map { if (it.number == n) it.copy(ticked = on) else it }
        val left = r.filter { it.ticked }.map { it.number }
        return MemberNumbers(r, if (main in left) main else left.firstOrNull().orEmpty(), if (wa in left) wa else "")
    }
}

/**
 * NUMBERS: contacts named like him are suggested; 📇 Pick from contacts, ＋ Type number. Each number has a tick,
 * ⭐ Main (calls and SMS) and 🟢 WA (WhatsApp, found by itself when the contact shows it). A green line says what moves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamNumbersEditor(name: String, nums: MemberNumbers, editing: Boolean = false, onChange: (MemberNumbers) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val suggested = remember(name) { WaNumbers.contactsNamed(ctx, Names.first(name) ?: name) }
    fun addContact(contact: String, number: String) {
        val found = WaNumbers.lookup(ctx, number).options
        val rows = found.ifEmpty { listOf(WaOption(number, "", false)) }.map { MemberNumbers.Row(it.number, contact.ifBlank { it.label }, onWa = it.onWhatsApp) }
        onChange(nums.plus(rows))
    }
    val pick = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) addContact(c.getString(0).orEmpty(), c.getString(1).orEmpty())
            }
        }
    }
    ChipHeading(if (nums.rows.isEmpty()) "NUMBERS · FROM YOUR CONTACTS" else "NUMBERS · TAP ⭐ / 🟢 TO CHOOSE", warn = nums.main.isBlank())
    val unused = suggested.filter { (_, n) -> nums.rows.none { SmsStore.sameNumber(it.number, n) } }
    if (unused.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        unused.forEach { (c, n) -> SmallChip(RowChip("📇 $c", ChipTone.BLUE, onClick = { addContact(c, n) })) }
    }
    nums.rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = r.ticked, onCheckedChange = { onChange(nums.tick(r.number, it)) })
            Column(Modifier.weight(1f)) {
                Text(r.number, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.from.isNotBlank()) Text("📇 ${r.from}", fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val isMain = r.number == nums.main
            val isWa = r.number == (nums.wa.ifBlank { nums.main })
            SmallChip(RowChip("⭐ Main", if (isMain) ChipTone.ORANGE else ChipTone.MUTED,
                onClick = { if (r.ticked) onChange(nums.copy(main = r.number, wa = if (nums.wa == r.number) "" else nums.wa)) }))
            Spacer(Modifier.width(4.dp))
            SmallChip(RowChip("🟢 WA", if (isWa) ChipTone.GREEN else ChipTone.MUTED,
                onClick = { if (r.ticked) onChange(nums.copy(wa = if (r.number == nums.main) "" else r.number)) }))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CardLinkText(if (nums.rows.isEmpty()) "📇 Pick from contacts" else "＋ Add another contact") {
            pick.launch(android.content.Intent(android.content.Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }
        CardLinkText("＋ Type number") { typing = true }
    }
    if (typing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(typed, { typed = it }, label = { Text("Number") }, singleLine = true, modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
        TextButton(onClick = {
            if (typed.isNotBlank()) addContact(ContactLookup.nameFor(ctx, typed.trim()), typed.trim())
            typed = ""; typing = false
        }) { Text("Add") }
    }
    val ticked = nums.ticked
    // Ones already in his app (moved before) don't count again.
    val count = remember(ticked) {
        val taken = TeamLinks.map(ctx, "given").values.toSet()
        if (ticked.isEmpty()) 0 else ReminderStore.items.value.count { r ->
            !r.done && !r.review && r.repeat == null && r.linkId.toString() !in taken && ticked.any { SmsStore.sameNumber(it, r.number) }
        } + ToScheduleStore.items.value.count { s -> s.linkId.toString() !in taken && ticked.any { SmsStore.sameNumber(it, s.number) } }
    }
    // Your phone book decides his name: your contact for his ⭐ Main number, else the name he typed.
    val saved = remember(nums.main) { ContactLookup.nameFor(ctx, nums.main).trim() }
    val shows = saved.ifBlank { name }
    if (nums.main.isNotBlank() || count > 0) Surface(shape = RoundedCornerShape(12.dp), color = Tones.green.bg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (nums.main.isNotBlank()) Text(
                if (saved.isNotBlank()) "Shows as $shows on your phone (your contact for ⭐ Main)" else "Shows as $shows · not in your contacts",
                color = Tones.green.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (count > 0) Text(
                "${if (editing) "Save moves" else "Moves"} $count open follow-up${if (count == 1) "" else "s"}${if (ticked.size > 1) " (${if (ticked.size == 2) "both" else "all ${ticked.size}"} numbers)" else ""} to ${Names.first(shows) ?: "his"}'s app",
                color = Tones.green.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (nums.main.isNotBlank() && saved.isBlank()) CardLinkText("＋ Save to contacts") {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
                        .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name)
                        .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, nums.main))
                }
            }
        }
    }
}

@Composable
private fun TeamChartDialog(
    m: TeamMember, active: List<TeamMember>, tasks: List<TeamTask>, onDismiss: () -> Unit,
    onSave: (String, String, MemberNumbers, Boolean, Boolean) -> Unit,
    onRemove: (TeamMember?) -> Unit,
) {
    var nums by remember { mutableStateOf(MemberNumbers.of(m)) }
    var ownDossier by remember { mutableStateOf(m.ownDossier) }
    var setDossier by remember { mutableStateOf(m.setDossier) }
    var boss by remember { mutableStateOf(m.reportsTo) }
    var role by remember { mutableStateOf(m.role) }
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(m.name)
                if (m.typed.isNotBlank() && m.typed != m.name) Text("Typed when joining: ${m.typed}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamChartFields(m, active, boss, { boss = it }, role, { role = it })
                // What he may add to Dossiers; once added, blocks never change and only you delete them.
                ChipHeading("ACCESS")
                AccessSwitch("✏ Adds his own Dossier", null, ownDossier) { ownDossier = it }
                val under = remember(active) { active.filter { m.uid in it.managers }.joinToString(", ") { it.first } }
                AccessSwitch("✏ Sets Dossier of people under him", if (under.isBlank()) "Nobody under him yet" else "Now: $under", setDossier) { setDossier = it }
                TeamNumbersEditor(m.typed.ifBlank { m.name }, nums, editing = true) { nums = it }
                TextButton(onClick = { confirmRemove = true }) { Text("Remove member", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { onSave(boss, role, nums, ownDossier, setDossier) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmRemove) {
        // Who takes his open tasks from you: someone else (same task, Timeline and deadline), or back to you.
        val open = tasks.count { it.toUid == m.uid && it.fromUid == TeamRepo.uid && it.open }
        val others = remember(active) { TeamRepo.belowMe().filter { it.uid != m.uid } }
        var to by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${m.first}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("His access ends now. His own tasks stay on his phone.")
                    if (open > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("Give his $open open task${if (open == 1) "" else "s"} to someone else, or back to you?", fontWeight = FontWeight.Bold)
                        others.forEach { o ->
                            Row(Modifier.fillMaxWidth().clickable { to = o.uid }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = to == o.uid, onClick = { to = o.uid })
                                Text("👤 ${o.name}")
                            }
                        }
                        Row(Modifier.fillMaxWidth().clickable { to = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = to == null, onClick = { to = null })
                            Text("↩ Back to me (my To schedule)")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { confirmRemove = false; onRemove(others.firstOrNull { it.uid == to }) }, modifier = Modifier.height(48.dp)) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** ✓ Received · ↩ Not done · ✓ Done, ➜ next part, in the card's own bar style: on a Done card. */
@Composable
fun TeamCheckButtons(t: TeamTask) {
    var notYet by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf(false) }
    if (t.doneAt > 0L) Text("${t.toFirst}: Done ${Fmt.dueLabel(t.doneAt)}" + if (t.passedTo.isNotBlank()) " · by ${t.passedTo}" else "",
        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BarSlot(Icons.Rounded.Check, "Received", Tones.done.pair, { TeamRepo.check(t) }, true, Modifier.weight(1f))
        BarSlot(Icons.Rounded.Schedule, "Not done", Tones.late.pair, { notYet = true }, true, Modifier.weight(1f))
        BarSlot(null, NEXT_PART_LABEL, Tones.call.pair, { next = true }, true, Modifier.weight(1f), lines = 2)
    }
    if (notYet) TeamNotDoneSheet(t) { notYet = false }
    if (next) TeamNextPartSheet(t) { next = false }
}

/** The third check button, on two short lines so the bar keeps its one row. */
const val NEXT_PART_LABEL = "✓ Done,\n➜ next part"

/**
 * v0.44 ✓ Done, ➜ next part: his part is received with thanks (it stays done in his history) and the SAME task goes
 * on. Me on top (it comes to My tasks, you pick the time), then the same 4 buttons as giving a task. Someone on the
 * team gets this task with its Timeline; someone outside the app becomes a normal follow-up on your side.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TeamNextPartSheet(t: TeamTask, onSent: () -> Unit = {}, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var me by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    var left by remember { mutableStateOf("") }
    var myTime by remember { mutableStateOf(false) }
    var myPick by remember { mutableStateOf(false) }
    var outside by remember { mutableStateOf<Pair<String, String>?>(null) }
    val member = picked?.let { (_, num) -> TeamRepo.memberByNumber(num) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarMissing()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("➜ Who does the next part?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(t.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Surface(
                onClick = { me = true; picked = null },
                shape = RoundedCornerShape(14.dp),
                color = if (me) cs.primaryContainer else cs.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Me", fontWeight = FontWeight.Bold)
                        Text("Goes to My tasks, you pick the time", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                    RadioButton(selected = me, onClick = { me = true; picked = null })
                }
            }
            PersonPicker(prompt = null) { n, num -> me = false; picked = n to num }
            picked?.let { (n, num) ->
                Text(
                    if (member != null && member.uid == t.toUid) "${member.first} did this part: pick someone else"
                    else if (member != null) "👥 ${member.first} is on the app: this task and its Timeline go to him"
                    else "👤 ${n.ifBlank { num }} is not on the app: it becomes your follow-up with him",
                    fontSize = 12.sp, color = cs.primary
                )
            }
            OutlinedTextField(left, { left = it }, placeholder = { Text("What's left? (optional)") }, trailingIcon = voiceMic(left) { left = it },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), maxLines = 3)
            Text("✓ ${t.toFirst} gets \"Received, thanks\" · his part stays in his history", fontSize = 12.sp, color = Tones.done.pair.second)
            Button(
                onClick = {
                    val p = picked
                    when {
                        me || p == null -> myTime = true
                        member != null -> {
                            TeamNext.toMember(ctx, t, member, left)
                            onSent(); onDismiss()
                        }
                        else -> {
                            TeamNext.received(ctx, t, left, p.first.ifBlank { p.second })
                            outside = p
                        }
                    }
                },
                enabled = me || (picked != null && member?.uid != t.toUid),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Send next part", fontWeight = FontWeight.Bold) }
        }
    }
    if (myTime) SnoozeSheet(
        heading = "Your time",
        about = t.title,
        onPick = { ms -> myTime = false; TeamNext.toMe(ctx, t, left, ms); onSent(); onDismiss() },
        onPickTime = { myTime = false; myPick = true },
        onDismiss = { myTime = false },
        plusRow = true,
    )
    if (myPick) DateTimePickerFlow(
        initialMs = System.currentTimeMillis() + 3_600_000L,
        onPicked = { ms -> myPick = false; TeamNext.toMe(ctx, t, left, ms); onSent(); onDismiss() },
        onDismiss = { myPick = false },
    )
    outside?.let { (n, num) ->
        FollowUpDialog(
            onDismiss = { outside = null; onSent(); onDismiss() },
            initialName = n, initialNumber = num,
            initialTask = listOf(t.title, left.trim()).filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }
}

/** What ✓ Done, ➜ next part does. */
object TeamNext {
    /** Next part with you: a My task (no time = To schedule), whose Timeline link opens this task's Timeline. */
    fun toMe(c: Context, t: TeamTask, left: String, at: Long) {
        TeamRepo.nextPart(t, null, left)
        checkedHere(c, t)
        val mine = MyTaskStore.add(title = t.title, note = left.trim(), dueAt = at, name = "", number = "")
        val m = TeamLinks.map(c, "nextMine")
        m[mine.id.toString()] = t.id
        TeamLinks.putMap(c, "nextMine", m)
    }

    /** Next part with someone on the team: the same task, his part received; your follow-up waits for the new person. */
    fun toMember(c: Context, t: TeamTask, to: TeamMember, left: String) {
        TeamRepo.nextPart(t, to, left)
        val link = TeamLinks.map(c, "given")[t.id]?.toLongOrNull() ?: return
        val at = if (t.hisDate || t.due == 0L) TeamSync.waitAt(c, t.deadline) else t.due
        ReminderStore.items.value.filter { it.linkId == link && it.done && it.review }.forEach { ReminderStore.reschedule(it.id, at, "Next part with ${to.first}:") }
    }

    /** Next part with someone outside the app: his part is received; the follow-up is made in the window that opens. */
    fun received(c: Context, t: TeamTask, left: String, who: String) {
        TeamRepo.nextPart(t, null, left, outsider = who)
        checkedHere(c, t)
    }

    /** Your "Done, check it" call-back for it is checked at once (the sync would do it a moment later). */
    private fun checkedHere(c: Context, t: TeamTask) {
        val link = TeamLinks.map(c, "given")[t.id]?.toLongOrNull() ?: return
        ReminderStore.items.value.filter { it.linkId == link && it.done && it.review }.forEach { ReminderStore.reviewed(it.id) }
    }

    /** The team task a My task continues (✓ Done, ➜ next part with you), if any. */
    fun teamOfMine(c: Context, myTaskId: Long): String? = TeamLinks.map(c, "nextMine")[myTaskId.toString()]
}

/**
 * Not done yet: the New time sheet with WHY (a quick reason is needed; words or a voice note are extra) and NEW
 * DEADLINE: 👤 He picks (his task goes back to his To schedule with a red line) or 🕒 I pick (WHEN / IN / RECENT).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TeamNotDoneSheet(t: TeamTask, onSent: () -> Unit = {}, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var why by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<Pair<ByteArray, Long>?>(null) }
    var recording by remember { mutableStateOf(false) }
    var iPick by remember { mutableStateOf(false) }
    var due by remember { mutableStateOf<Long?>(null) }
    val quick = remember { TeamRules.notDoneReasons(ctx) }
    val ready = why != null && (!iPick || due != null)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarMissing()),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("↩ Not done yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(t.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ChipHeading("WHY *", warn = why == null)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quick.forEach { q -> FilterChip(selected = why == q, onClick = { why = if (why == q) null else q }, label = { Text(q) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(more, { more = it }, placeholder = { Text("Add details (optional)") }, trailingIcon = voiceMic(more) { more = it },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), maxLines = 3)
                TeamHoldToRecord(onState = { recording = it }, onRecorded = { b, d -> voice = b to d }, size = 44.dp)
            }
            if (recording || voice != null) Text(
                if (recording) "🔴 Recording…" else "🎤 Voice note ${TeamMedia.duration(voice!!.second)} · goes on the Timeline",
                fontSize = 12.sp, color = if (recording) Color(0xFFFF5A5A) else cs.onSurfaceVariant,
                modifier = if (!recording) Modifier.clickable { voice = null } else Modifier,
            )
            ChipHeading("NEW DEADLINE", modifier = Modifier.padding(top = 4.dp))
            TeamWhenBox(myTime = iPick, onChange = { iPick = it })
            androidx.compose.animation.AnimatedVisibility(
                visible = iPick,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(240)) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeOut(),
            ) {
                ShortTimeRow(
                    selected = due,
                    onClear = if (due != null) ({ due = null }) else null,
                    dialogTitle = "New deadline",
                    plusRow = true,
                    compact = true,
                    leading = {
                        ChipHeading("WHEN", modifier = Modifier.align(Alignment.CenterVertically))
                        if (due == null) Text("Pick a time", fontSize = 13.sp, color = cs.outline, modifier = Modifier.align(Alignment.CenterVertically))
                    },
                    onPick = { due = it },
                )
            }
            if (!iPick) Text("${t.toFirst}'s task goes back to his To schedule; he picks a new deadline.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Button(
                onClick = {
                    val reason = listOfNotNull(why, more.trim().takeIf { it.isNotEmpty() }).joinToString(" · ")
                    TeamRepo.notDoneYet(t, reason, if (iPick) due ?: 0L else 0L)
                    voice?.let { (b, d) -> TeamRepo.send(t, Team.VOICE, "", data = b, durMs = d) }
                    TeamSync.giverNotDone(ctx, t, if (iPick) due ?: 0L else 0L)
                    onSent()
                    onDismiss()
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("↩ Send back to ${t.toFirst}", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeamReasonDialog(
    title: String, body: String, quick: List<String>, button: String, onDismiss: () -> Unit,
    top: (@Composable () -> Unit)? = null, ready: Boolean = true, onSend: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                top?.invoke()
                if (quick.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    quick.forEach { q -> FilterChip(selected = reason == q, onClick = { reason = q }, label = { Text(q) }) }
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason *") }, trailingIcon = voiceMic(reason) { reason = it },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), maxLines = 3)
            }
        },
        confirmButton = { Button(onClick = { onSend(reason) }, enabled = ready && reason.isNotBlank()) { Text(button) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * His opened card for a task given to him: the photos and voice notes that came with it, then ✓ Done (📷 when a
 * photo is asked for) · 📅 Deadline (to the giver, up to Latest; greyed once set when locked) · ⏰ My reminder (his
 * phone only), and the links Timeline · Move to Follow-ups. No Edit or Delete: the task is the giver's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamReceiverPanel(t: TeamTask, myTaskId: Long?, onDone: () -> Unit, onReminder: (() -> Unit)?, onMove: (() -> Unit)?) {
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    var donePhoto by remember { mutableStateOf(false) }
    LaunchedEffect(t.id) { TeamSync.markOpened(ctx, t.id) }
    TeamBriefStrip(t)
    if (t.open) {
        Spacer(Modifier.height(6.dp))
        TeamHisBar(
            photo = t.photoProof,
            onDone = { if (t.photoProof) donePhoto = true else onDone() },
            onDeadline = if (t.hisDate && !t.deadlineLocked) ({ pick = true }) else null,
            onReminder = onReminder,
        )
    }
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CardLinkText(TeamUi.timelineLabel(t)) { TeamUi.openTimeline(t.id) }
        if (t.open && onMove != null) CardLinkText("Move to Follow-ups", onMove)
        if (t.asking) CardLinkText("Cancel request") { TeamRepo.cancelMore(t) }
    }
    if (donePhoto) TeamDoneWithPhoto(t, myTaskId) { donePhoto = false }
    if (pick) {
        DateTimePickerFlow(
            initialMs = t.due.takeIf { it > 0L } ?: defaultCallTime(ctx, System.currentTimeMillis()),
            onPicked = { ms ->
                pick = false
                val end = if (t.deadline > 0L) TeamUi.endOfDayOf(t.deadline) else Long.MAX_VALUE
                if (ms > end) {
                    Toast.makeText(ctx, "After Latest (${TeamUi.shortWhen(t.deadline)}). Ask ${t.fromFirst} in the Timeline.", Toast.LENGTH_LONG).show()
                } else {
                    TeamRepo.setHisDate(t, ms)
                    // The card stays open: he sets ⏰ My reminder himself (it leaves To schedule only with both).
                    myTaskId?.let { id -> MyTaskStore.get(id)?.takeIf { it.timed && it.dueAt == t.due }?.let { MyTaskStore.setDue(id, ms) } }
                    if (t.locked) Toast.makeText(ctx, "Deadline set · from now on only 📅 Extend deadline on the Timeline", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { pick = false },
        )
    }
}

/** ✓ Done · 📅 Deadline · ⏰ My reminder, the same BarSlot buttons as every card's bar. */
@Composable
fun TeamHisBar(photo: Boolean, onDone: (() -> Unit)?, onDeadline: (() -> Unit)?, onReminder: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BarSlot(if (photo) Icons.Rounded.PhotoCamera else Icons.Rounded.Check, "Done", Tones.done.pair, onDone, true, Modifier.weight(1f))
        BarSlot(Icons.Rounded.Event, "Deadline", Tones.date.pair, onDeadline, true, Modifier.weight(1f))
        BarSlot(Icons.Rounded.Alarm, "My reminder", chipColors(ChipTone.ORANGE), onReminder, true, Modifier.weight(1f))
    }
}

/** A small link under an opened card: the same as CardPanel's (13sp, blue). */
@Composable
private fun CardLinkText(label: String, onClick: () -> Unit) {
    Text(
        label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 5.dp)
    )
}

/** The photos and voice notes given with the task: the first photo (＋N more), tap for all; ▶ plays the voice. */
@Composable
fun TeamBriefStrip(t: TeamTask) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var msgs by remember(t.id) { mutableStateOf<List<TeamMsg>>(emptyList()) }
    DisposableEffect(t.id) {
        val reg = TeamRepo.watchMsgs(t.id) { all -> msgs = all.filter { it.tag == Team.BRIEF && it.data != null } }
        onDispose { reg?.remove() }
    }
    val photos = msgs.filter { it.kind == Team.PHOTO }
    val voice = msgs.firstOrNull { it.kind == Team.VOICE }
    if (photos.isEmpty() && voice == null) return
    val playing by TeamMedia.playing.collectAsState()
    var gallery by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.firstOrNull()?.let { first ->
                val bmp = remember(first.id) { first.data?.let { TeamMedia.bitmap(it)?.asImageBitmap() } }
                Box {
                    if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { gallery = true })
                    if (photos.size > 1) Text(
                        "+${photos.size - 1}", color = cs.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp)).background(cs.primary).padding(horizontal = 4.dp)
                    )
                }
            }
            voice?.let { v ->
                VoicePlayer(v.id, v.data, v.durMs, v.wave, cs.onSurfaceVariant, modifier = Modifier.weight(1f), button = 34.dp,
                    onPlay = { TeamRepo.markPlayed(t.id, v) })
            }
        }
    }
    if (gallery) {
        PhotoViewer(photos.mapNotNull { it.data }, onDismiss = { gallery = false }) { i -> photos.getOrNull(i)?.let { TeamRepo.markPlayed(t.id, it) } }
    }
}

// ---------------------------------------------------------------- his day

@Composable
fun TeamDayScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val uid by TeamUi.dayOf.collectAsState()
    val members by TeamRepo.members.collectAsState()
    val me by TeamRepo.me.collectAsState()
    val m = members.firstOrNull { it.uid == uid }
    var day by remember(uid) { mutableStateOf<Map<String, Any?>?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    DisposableEffect(uid) {
        val reg = uid?.let { u -> TeamRepo.watchDay(u) { day = it } }
        onDispose { reg?.remove() }
    }
    PullRefresh(Modifier.fillMaxSize()) {
    SettingsPage(title = "${m?.first ?: "Member"}'s day", onBack = onBack) {
        if (m == null) {
            Text("Not in your team any more.")
            return@SettingsPage
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.name, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                val synced = (day?.get("updatedAt") as? Number)?.toLong() ?: 0L
                Text(m.roleLabel + " · " + if (synced > 0L) "last synced ${Fmt.dueLabel(synced)}" else "not synced yet", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            if (m.phone.isNotBlank()) {
                IconButton(onClick = { dial(ctx, m.phone) }) { Icon(Icons.Rounded.Call, contentDescription = "Call", tint = cs.primary) }
                TextButton(onClick = { WhatsApp.open(ctx, m.waNumber, "") }) { Text("WA", color = Color(0xFF25D366), fontWeight = FontWeight.ExtraBold) }
            }
        }
        @Suppress("UNCHECKED_CAST")
        fun list(key: String): List<Map<String, Any?>> = (day?.get(key) as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
        val tasks = list("tasks")
        val fus = list("followUps")
        val dossier = list("dossier")
        val ticked = dossier.count { ((it["doneAt"] as? Number)?.toLong() ?: 0L) > 0L }
        BoxTabs(
            labels = listOf("Tasks · ${tasks.count { it["done"] != true }}", "Follow-ups · ${fus.count { it["done"] != true }}", "Dossier · $ticked/${dossier.size}"),
            selected = tab, onSelect = { tab = it },
        )
        val now = System.currentTimeMillis()
        val myFirst = me?.first
        fun num(v: Any?) = (v as? Number)?.toLong() ?: 0L
        val rows: List<Map<String, Any?>> = when (tab) { 0 -> tasks; 1 -> fus; else -> dossier }
        if (tab == 2) TeamDossierTab(m, if (day == null) null else dossier, list("reports").mapNotNull { DayReport.of(it) })
        else if (day == null) Text("${m.first}'s phone hasn't sent his day yet. It does when he opens the app.", color = cs.onSurfaceVariant)
        else if (rows.isEmpty()) Text("Nothing for today.", color = cs.onSurfaceVariant)
        else TaskGroup {
            Column {
                rows.forEachIndexed { i, r ->
                    val done = if (tab == 2) num(r["doneAt"]) > 0L else r["done"] == true
                    val due = num(r["due"])
                    val chips = buildList {
                        val from = r["from"] as? String
                        if (!from.isNullOrBlank()) add(RowChip(if (from == myFirst) "↘ from you" else "↘ $from", ChipTone.BLUE))
                        val reason = r["reason"] as? String
                        if (tab == 2 && !done && !reason.isNullOrBlank()) add(RowChip("Why: $reason", ChipTone.ORANGE))
                    }
                    TaskCard(
                        title = r["title"] as? String ?: "",
                        done = done,
                        onTick = {},
                        tickEnabled = false,
                        time = when {
                            tab == 2 && done -> "✓ " + Fmt.timeOnly(num(r["doneAt"]))
                            tab == 2 -> r["time"] as? String
                            done -> "Done " + Fmt.dueLabel(num(r["doneAt"]))
                            due > 0L -> Fmt.dueLabel(due)
                            else -> null
                        },
                        late = !done && due in 1 until now,
                        timeIcons = if (tab == 1) TimeIcons.CALL else TimeIcons.NONE,
                        person = (r["name"] as? String)?.let { Names.first(it) ?: it },
                        chips = chips,
                        onClick = {},
                        pos = rowPos(i, rows.size),
                    )
                }
            }
        }
        val priv = num(day?.get("privateCount")).toInt()
        if (tab == 0 && priv > 0) {
            Surface(shape = RoundedCornerShape(14.dp), color = cs.surface, modifier = Modifier.fillMaxWidth()) {
                Text("🔒 $priv private task${if (priv == 1) "" else "s"} · hidden", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
}

// ---------------------------------------------------------------- his Dossier (Phase 3)

/**
 * Dossier tab of his day: the blocks you set (Owner: tap to edit, ＋ Add block), with today's tick times, late
 * ticks in orange, 📷 photos, reasons and voice notes from his phone. [today] is null until his phone sent his day.
 */
@Composable
private fun TeamDossierTab(m: TeamMember, today: List<Map<String, Any?>>?, reports: List<DayReport> = emptyList()) {
    val cs = MaterialTheme.colorScheme
    val team by TeamRepo.team.collectAsState()
    val meNow by TeamRepo.me.collectAsState()
    val owner = team?.ownerUid == TeamRepo.uid && m.role != Team.OWNER
    // Who may add: the Owner, a senior you switched on, or he himself when switched on. Only the Owner deletes.
    val canAdd = remember(team, meNow, m) { TeamDossier.canAdd(m) }
    var blocks by remember(m.uid) { mutableStateOf<List<TeamBlock>?>(null) }
    var deleting by remember { mutableStateOf<TeamBlock?>(null) }
    var adding by remember { mutableStateOf(false) }
    val pending by TeamDossier.pending.collectAsState()
    var open by remember { mutableStateOf<Pair<TeamBlock?, Map<String, Any?>?>?>(null) }
    DisposableEffect(m.uid) {
        val reg = TeamDossier.watch(m.uid) { blocks = it }
        onDispose { reg?.remove() }
    }
    fun num(v: Any?) = (v as? Number)?.toLong() ?: 0L
    val rows = today.orEmpty()
    val byBlock = rows.filter { !(it["blockId"] as? String).isNullOrBlank() }.associateBy { it["blockId"] as String }
    val list = blocks.orEmpty()
    // Today's blocks first (from his phone), then the ones not on today, then his own dossier tasks.
    val todayIds = byBlock.keys
    val ordered = list.filter { it.id in todayIds } + list.filter { it.id !in todayIds }
    val own = rows.filter { (it["blockId"] as? String).isNullOrBlank() }
    val now = System.currentTimeMillis()

    if (today == null) Text("${m.first}'s phone hasn't sent his day yet. It does when he opens the app.", color = cs.onSurfaceVariant)
    if (ordered.isEmpty() && own.isEmpty() && today != null) {
        Text(if (owner) "No Dossier set for ${m.first} yet." else "Nothing for today.", color = cs.onSurfaceVariant)
    }
    if (ordered.isNotEmpty() || own.isNotEmpty()) TaskGroup {
        Column {
            val all: List<Pair<TeamBlock?, Map<String, Any?>?>> = ordered.map { it to byBlock[it.id] } + own.map { null to it }
            all.forEachIndexed { i, (b, r) ->
                val doneAt = num(r?.get("doneAt"))
                val done = doneAt > 0L
                val due = num(r?.get("due"))
                val end = if (b != null && due > 0L) due + (if (b.endMinute > b.minute) b.endMinute - b.minute else 30) * 60_000L else due
                val late = done && end > 0L && doneAt > end
                val reason = r?.get("reason") as? String
                val chips = buildList {
                    if (b?.proof == true) add(RowChip(if (r?.get("photo") == true) "📷 photo ›" else "📷 proof", if (r?.get("photo") == true) ChipTone.BLUE else ChipTone.MUTED))
                    if (late) add(RowChip("late " + Fmt.timeOnly(doneAt), ChipTone.ORANGE))
                    if (!done && !reason.isNullOrBlank()) add(RowChip("💬 $reason", ChipTone.ORANGE))
                    if (r?.get("voice") == true) add(RowChip("🎤 voice ›", ChipTone.BLUE))
                    if (r != null && !done && reason.isNullOrBlank() && due in 1 until now) add(RowChip("No reason yet", ChipTone.MUTED))
                    if (r == null) add(RowChip("Not today", ChipTone.MUTED))
                    // Who added it: blocks never change, so this stays true.
                    b?.byFirst?.takeIf { it.isNotBlank() }?.let { add(RowChip("✏ " + if (b.by == TeamRepo.uid) "You" else it, ChipTone.MUTED)) }
                }
                TaskCard(
                    title = b?.title ?: (r?.get("title") as? String ?: ""),
                    done = done,
                    onTick = {},
                    tickEnabled = false,
                    time = when {
                        done && !late -> "✓ " + Fmt.timeOnly(doneAt)
                        done -> "✓ " + Fmt.timeOnly(doneAt)
                        b != null -> b.rangeLabel
                        else -> r?.get("time") as? String
                    },
                    late = !done && due in 1 until now,
                    sub = b?.frequency ?: (r?.get("frequency") as? String),
                    chips = chips,
                    onClick = { open = b to r },
                    pos = rowPos(i, all.size),
                )
            }
        }
    }
    pending?.takeIf { it.member.uid == m.uid }?.let { p ->
        Surface(shape = RoundedCornerShape(12.dp), color = cs.inverseSurface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Block added · ${p.block.title}", color = cs.inverseOnSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { TeamDossier.undo() }) { Text("UNDO", color = cs.inversePrimary, fontWeight = FontWeight.Bold) }
            }
        }
    }
    if (canAdd) {
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("＋ Add block for ${if (m.uid == TeamRepo.uid) "you" else m.first}", fontWeight = FontWeight.Bold)
        }
        Text(
            if (owner) "Blocks can't be moved or changed once added. Only you can delete one."
            else "Blocks can't be moved, changed or deleted once added. Only ${TeamDossier.ownerFirst()} can delete one.",
            fontSize = 12.sp, color = cs.onSurfaceVariant
        )
    }

    open?.let { (b, r) ->
        TeamBlockDayDialog(m, b, r, reports, canEdit = owner && b != null, onEdit = { open = null; deleting = b }) { open = null }
    }
    val cur = blocks.orEmpty()
    if (adding) {
        DossierTaskDialog(
            existing = null, onDismiss = { adding = false }, onSwitchKind = null,
            team = TeamBlockEdit(if (m.uid == TeamRepo.uid) "you" else m.first, null, onSave = { nb -> TeamDossier.addLater(m, cur, nb) }, onDelete = null,
                others = cur + listOfNotNull(pending?.takeIf { it.member.uid == m.uid }?.block)),
        )
    }
    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete for ${m.first}?") },
            text = { Text("“${b.title}” leaves ${m.first}'s Dossier from now on. Its past ticks stay in History.") },
            confirmButton = {
                TextButton(onClick = { TeamDossier.save(m, cur.filter { it.id != b.id }); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** One block on his day: tick time, the 📷 photo, his reason and voice note; Edit for the Owner. */
@Composable
private fun TeamBlockDayDialog(
    m: TeamMember, b: TeamBlock?, r: Map<String, Any?>?, reports: List<DayReport>, canEdit: Boolean, onEdit: () -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val date = r?.get("date") as? String
    val blockId = b?.id
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var caption by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<ByteArray?>(null) }
    var voiceMs by remember { mutableLongStateOf(0L) }
    val playing by TeamMedia.playing.collectAsState()
    LaunchedEffect(blockId, date) {
        if (blockId == null || date == null) return@LaunchedEffect
        if (r?.get("photo") == true) TeamDossier.fetchProof(m.uid, blockId, date, TeamDossier.PHOTO) { d, c, _ -> photo = d; caption = c }
        if (r?.get("voice") == true) TeamDossier.fetchProof(m.uid, blockId, date, TeamDossier.VOICE) { d, _, ms -> voice = d; voiceMs = ms }
    }
    val doneAt = (r?.get("doneAt") as? Number)?.toLong() ?: 0L
    val reason = r?.get("reason") as? String
    AlertDialog(
        onDismissRequest = { TeamMedia.stopPlaying(); onDismiss() },
        title = { Text(b?.title ?: (r?.get("title") as? String).orEmpty()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((b?.rangeLabel ?: (r?.get("time") as? String).orEmpty()) + " · " + (b?.frequency ?: (r?.get("frequency") as? String).orEmpty()),
                    fontSize = 13.sp, color = cs.onSurfaceVariant)
                Text(
                    when {
                        r == null -> "Not on today."
                        doneAt > 0L -> "✓ Ticked at ${Fmt.timeOnly(doneAt)}"
                        !reason.isNullOrBlank() -> "Not done: “$reason”"
                        else -> "Not ticked yet · no reason yet"
                    },
                    fontWeight = FontWeight.SemiBold
                )
                if (b?.proof == true) Text("📷 Photo proof on tick", fontSize = 13.sp, color = cs.onSurfaceVariant)
                // 📋 Tick after evening review: this week's score, then each day's report (orange: no evening review).
                if (b?.afterReview == true || r?.get("afterReview") == true) {
                    val byDate = reports.associateBy { it.date }
                    val (rev, days) = DayClose.weekScore(byDate, { d -> b?.toTask()?.copy(startDate = "2000-01-01")?.isOn(d) ?: true })
                    WeekScore(rev, days)
                    val todayDate = r?.get("date") as? String
                    val lines = (reports.filter { it.date != todayDate } +
                        listOfNotNull(todayDate?.takeIf { doneAt > 0L }?.let { byDate[it] ?: DayReport(it) })).sortedByDescending { it.date }
                    lines.forEach { x ->
                        Text("${TeamUi.shortDate(java.time.LocalDate.parse(x.date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())} · ${x.line}",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (x.reviewed) cs.onSurface else ReviewOrange)
                    }
                }
                if (r?.get("photo") == true) {
                    val bmp = remember(photo) { photo?.let { TeamMedia.bitmap(it)?.asImageBitmap() } }
                    var big by remember { mutableStateOf(false) }
                    if (bmp != null) Image(bmp, contentDescription = "Photo proof", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).clickable { big = true })
                    else Text("Loading photo…", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    if (big) photo?.let { PhotoViewer(listOf(it), onDismiss = { big = false }) }
                    if (caption.isNotBlank()) Text("“$caption”", fontSize = 13.sp)
                }
                if (r?.get("voice") == true) {
                    val v = voice
                    VoicePlayer("dossier-$blockId-$date", v, voiceMs, "", cs.onSurface)
                }
            }
        },
        confirmButton = { TextButton(onClick = { TeamMedia.stopPlaying(); onDismiss() }) { Text("Close") } },
        dismissButton = { if (canEdit) TextButton(onClick = onEdit) { Text("Delete block", color = MaterialTheme.colorScheme.error) } },
    )
}

// ---------------------------------------------------------------- Home: Needs you lines

/**
 * The team lines inside the Needs you card on Home (only while something waits), as slim lines like "🕘 2 waiting
 * for a time ›": ⚠ stuck, ❓ questions waiting, 📅 deadline requests open Follow-ups at the first such card.
 */
@Composable
fun TeamNeedsLines(mineOnly: Boolean = false) {
    val ctx = LocalContext.current
    val tasks by TeamRepo.tasks.collectAsState()
    val me by TeamRepo.me.collectAsState()
    if (me?.active != true) return
    val needs = remember(tasks, me) { TeamUi.needs(ctx, tasks, me?.uid).filter { !mineOnly || it.mine } }
    needs.forEach { n ->
        val fg = when (n.tone) {
            ChipTone.RED -> MaterialTheme.colorScheme.error
            ChipTone.PURPLE -> Tones.date.fg
            else -> NoTimeFg
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { TeamUi.openNeed(ctx, n) }.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(n.text, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("›", color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

// ---------------------------------------------------------------- picking a team member

/** 👥 Team in the person picker: everyone in the team, the people below you first. */
@Composable
fun TeamPickDialog(onDismiss: () -> Unit, onPick: (TeamMember) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val members by TeamRepo.members.collectAsState()
    val tasks by TeamRepo.tasks.collectAsState()
    val team by TeamRepo.team.collectAsState()
    val list = remember(members) { TeamRepo.canGiveTo() }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("👥 Team")
                Text("${team?.name.orEmpty()} · people you can give tasks to", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (list.size > 6) OutlinedTextField(q, { q = it }, placeholder = { Text("Search team") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (list.isEmpty()) Text("Nobody else in the team yet.")
                list.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }.forEach { m ->
                    val open = tasks.count { it.toUid == m.uid && it.fromUid == TeamRepo.uid && it.open }
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Avatar(m.name, size = 36.dp)
                            Box(Modifier.align(Alignment.BottomEnd)) { OnlineDot(m.onlineNow()) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.first, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                TeamTag(m.roleLabel.uppercase())
                            }
                            Text(TeamUi.lastSeenLabel(m), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        if (open > 0) Text("$open open\nfrom you", fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * FOR <NAME> in New follow-up, for a team member: an optional deadline and Urgent (Urgent also stars it).
 */
@Composable
fun TeamForBox(
    member: TeamMember, deadline: Long, onDeadline: (Long) -> Unit, urgent: Boolean, onUrgent: (Boolean) -> Unit,
    photoProof: Boolean = false, onPhotoProof: (Boolean) -> Unit = {},
    attach: List<TeamAttach> = emptyList(), onAttach: (List<TeamAttach>) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var pick by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = cs.secondaryContainer.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("FOR ${member.first.uppercase()}", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipHeading("LATEST", modifier = Modifier.align(Alignment.CenterVertically))
                if (deadline > 0L) {
                    Surface(shape = RoundedCornerShape(10.dp), color = Color.Transparent, border = BorderStroke(1.dp, cs.outline)) {
                        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📅 ${Fmt.dueLabel(deadline)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { onDeadline(0L) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear latest", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                } else Text("None", fontSize = 13.sp, color = cs.outline)
                TextButton(onClick = { pick = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("📅 Pick") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔴 Urgent", fontWeight = FontWeight.Bold, color = if (urgent) cs.error else cs.onSurface)
                    Text("Red card · reminder ${TeamRules.label(TeamRules.urgentMin(LocalContext.current))} if no deadline · auto ⭐", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = urgent, onCheckedChange = onUrgent)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📷 Photo proof needed", fontWeight = FontWeight.Bold)
                    Text("${member.first} can only mark it Done with a photo", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = photoProof, onCheckedChange = onPhotoProof)
            }
            TeamAttachRow(attach, onAttach)
        }
    }
    if (pick) {
        DateTimePickerFlow(
            initialMs = deadline.takeIf { it > 0L } ?: TeamUi.endOfDayOf(System.currentTimeMillis() + 2 * 24 * 3_600_000L).minus(6 * 3_600_000L - 1),
            onPicked = { onDeadline(it); pick = false },
            onDismiss = { pick = false },
        )
    }
}

internal fun TeamUi.endOfDayOf(ms: Long): Long {
    val z = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(z).toLocalDate()
    return d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
}

/** 👤 He picks | 🕒 I pick: who sets the deadline (the Tasks | Dossier style box), everywhere. */
@Composable
fun TeamWhenBox(myTime: Boolean, onChange: (Boolean) -> Unit) {
    BoxTabs(labels = listOf("👤 He picks", "🕒 I pick"), selected = if (myTime) 1 else 0, onSelect = { onChange(it == 1) })
}

/** In his Move to Follow-ups window: FOR <GIVER> · DEADLINE, his deadline for the giver (up to Latest; locked once set). */
@Composable
fun TeamPromiseBox(t: TeamTask, promise: Long, onPromise: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), color = cs.primary.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipHeading("FOR ${t.fromFirst.uppercase()}")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipHeading("DEADLINE", modifier = Modifier.align(Alignment.CenterVertically))
                if ((!t.hisDate || t.deadlineLocked) && t.due > 0L) {
                    Text("📅 ${Fmt.dueLabel(t.due)}" + if (!t.hisDate) " · set by ${t.fromFirst}" else " 🔒", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    if (promise > 0L) Text(
                        "📅 ${Fmt.dueLabel(promise)}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.5.dp, cs.onSurface.copy(alpha = 0.8f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Text(
                        "📅 Pick", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.5.dp, cs.primary.copy(alpha = 0.7f), RoundedCornerShape(9.dp))
                            .clickable { open = true }.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
            if (t.deadline > 0L) Text("Latest ${Fmt.dueLabel(t.deadline)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
    }
    if (open) {
        val ctx = LocalContext.current
        DateTimePickerFlow(
            initialMs = promise.takeIf { it > 0L } ?: defaultCallTime(ctx, System.currentTimeMillis()),
            onPicked = { ms ->
                open = false
                val end = if (t.deadline > 0L) TeamUi.endOfDayOf(t.deadline) else Long.MAX_VALUE
                if (ms > end) Toast.makeText(ctx, "After Latest (${TeamUi.shortWhen(t.deadline)}). Ask ${t.fromFirst} in the Timeline.", Toast.LENGTH_LONG).show()
                else onPromise(ms)
            },
            onDismiss = { open = false },
        )
    }
}

// ---------------------------------------------------------------- deleting a task you gave

/**
 * Every delete of a follow-up that is a task you gave goes through here: one line to confirm, "Delete for you
 * and Basharat?", then it goes from his phone too. Your other follow-ups delete at once.
 */
object TeamDelete {
    val ask = MutableStateFlow<Pair<TeamTask, () -> Unit>?>(null)

    /** The open task you gave behind a follow-up link, if any. */
    fun givenOpen(c: Context, link: Long): TeamTask? {
        if (link == 0L || !TeamRepo.activeNow) return null
        val id = TeamLinks.givenOfLink(c, link) ?: return null
        return TeamRepo.tasks.value.firstOrNull { it.id == id && it.open && it.fromUid == TeamRepo.uid }
    }

    /** Runs [delete] now, or after "Delete for you and X?" when [link] is a task you gave. */
    fun guard(c: Context, link: Long, delete: () -> Unit) {
        val t = givenOpen(c, link)
        if (t == null) delete() else ask.value = t to delete
    }

    /** You ticked it done yourself (To schedule, review): it closes for him too, as received. */
    fun closeGiven(c: Context, link: Long) {
        givenOpen(c, link)?.let { TeamRepo.check(it) }
    }
}

@Composable
fun TeamDeleteHost() {
    val ask by TeamDelete.ask.collectAsState()
    val (t, delete) = ask ?: return
    val close = { TeamDelete.ask.value = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Delete for you and ${t.toFirst}?") },
        text = { Text("“${t.title}” goes from ${t.toFirst}'s phone too.") },
        confirmButton = {
            TextButton(onClick = { close(); TeamRepo.cancel(t); delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = close) { Text("Keep") } },
    )
}
