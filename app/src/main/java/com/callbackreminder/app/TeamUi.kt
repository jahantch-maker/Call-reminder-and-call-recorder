package com.callbackreminder.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
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
    /** A page to open: [SETTINGS], [INBOX] or [DAY] (with [dayOf]). AppRoot opens it and clears this. */
    val request = MutableStateFlow<String?>(null)
    const val SETTINGS = "settings"
    const val INBOX = "inbox"
    const val DAY = "day"
    const val TIMELINE = "timeline"

    /** Which team task the Timeline page shows. */
    val timelineOf = MutableStateFlow<String?>(null)

    fun openTimeline(id: String) {
        timelineOf.value = id
        request.value = TIMELINE
    }

    /** Whose day the day page shows. */
    val dayOf = MutableStateFlow<String?>(null)

    fun openDay(uid: String) {
        dayOf.value = uid
        request.value = DAY
    }

    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    fun shortDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)

    /** The team task behind one of your My tasks (his side). */
    fun taskOfMyTask(c: Context, myTaskId: Long, tasks: List<TeamTask>): TeamTask? =
        TeamLinks.teamTaskOfMyTask(c, myTaskId)?.let { id -> tasks.firstOrNull { it.id == id } }

    /** The team task behind a follow-up: one you gave (your side) or passed down (his side). */
    fun taskOfLink(c: Context, linkId: Long, tasks: List<TeamTask>): TeamTask? {
        if (linkId == 0L) return null
        val id = TeamLinks.givenOfLink(c, linkId) ?: TeamLinks.passedOfLink(c, linkId) ?: return null
        return tasks.firstOrNull { it.id == id }
    }

    /** Your side: one status chip (Sent → Seen → his date → Done, check it), plus Urgent / asks / passed on. */
    fun giverChips(t: TeamTask): List<RowChip> = buildList {
        if (t.urgent && t.open) add(RowChip("🔴 Urgent", ChipTone.RED))
        if (t.open && t.stuck) add(RowChip("⚠ Stuck", ChipTone.RED))
        if (t.open && t.asks) add(RowChip("❓ Needs your answer", ChipTone.ORANGE))
        when {
            t.asking -> add(RowChip("⏳ asks till ${shortDate(t.moreDue)}", ChipTone.ORANGE))
            t.status == Team.SENT -> add(RowChip("✓ Sent ${Fmt.timeOnly(t.createdAt)}", ChipTone.MUTED))
            t.status == Team.SEEN -> add(RowChip("✓✓ Seen ${Fmt.timeOnly(t.seenAt)}", ChipTone.BLUE))
            t.status == Team.REOPENED -> add(RowChip("Not done yet", ChipTone.RED))
            t.status == Team.DATED && t.hisDate -> add(RowChip("📅 his date", ChipTone.ORANGE))
            t.status == Team.DATED -> add(RowChip("📌 fixed date", ChipTone.MUTED))
            t.status == Team.DONE -> add(RowChip("✅ Done, check it", ChipTone.GREEN))
            t.status == Team.CHECKED -> add(RowChip("Received ✓", ChipTone.GREEN))
            t.status == Team.CANCELLED -> add(RowChip("Cancelled", ChipTone.MUTED))
            t.status == Team.RETURNED -> add(RowChip("Returned", ChipTone.MUTED))
        }
        if (t.passedTo.isNotBlank()) add(RowChip("${t.toFirst} → ${t.passedTo}", ChipTone.MUTED))
    }

    /** His side: ↘ who gave it, Pick your date, the deadline. */
    fun receiverChips(t: TeamTask): List<RowChip> = buildList {
        if (t.urgent && t.open) add(RowChip("🔴 Urgent", ChipTone.RED))
        if (t.open && t.hisDate && t.due == 0L) add(RowChip("📅 Pick your date", ChipTone.ORANGE))
        add(RowChip("↘ ${t.fromFirst}", ChipTone.BLUE))
        if (t.status == Team.REOPENED) add(RowChip("Not done yet", ChipTone.RED))
        if (t.asking) add(RowChip("⏳ More time asked", ChipTone.ORANGE))
        if (t.open && t.stuck) add(RowChip("⚠ Stuck, waiting", ChipTone.RED))
        else if (t.open && t.asks) add(RowChip("❓ Asked, waiting", ChipTone.ORANGE))
        if (t.photoProof && t.open) add(RowChip("📷 Photo proof", ChipTone.MUTED))
        if (t.open && t.deadline > 0L) add(RowChip("Deadline ${shortDate(t.deadline)}", ChipTone.MUTED))
        if (t.status == Team.DONE) add(RowChip("Waiting for ${t.fromFirst}'s check", ChipTone.MUTED))
    }

    /** One line in Needs you: what it says and how many. */
    data class Need(val text: String, val count: Int, val tone: ChipTone)

    fun needs(c: Context, tasks: List<TeamTask>, me: String?): List<Need> {
        if (me == null) return emptyList()
        val now = System.currentTimeMillis()
        val given = tasks.filter { it.fromUid == me }
        val noDateMin = TeamRules.noDateMin(c)
        return buildList {
            given.filter { it.status == Team.DONE }.takeIf { it.isNotEmpty() }?.let {
                add(Need("✅ ${it.size} done, check it · " + it.joinToString(", ") { t -> t.toFirst }.take(40), it.size, ChipTone.GREEN))
            }
            given.filter { it.asking }.takeIf { it.isNotEmpty() }?.let {
                add(Need("⏳ ${it.size} asking for more time · " + it.joinToString(", ") { t -> t.toFirst }.take(40), it.size, ChipTone.ORANGE))
            }
            given.filter { it.open && it.stuck }.takeIf { it.isNotEmpty() }?.let {
                add(Need("⚠ ${it.size} stuck · " + it.map { t -> t.toFirst }.distinct().joinToString(", ").take(40), it.size, ChipTone.RED))
            }
            given.filter { it.open && it.asks && !it.stuck }.takeIf { it.isNotEmpty() }?.let {
                add(Need("❓ ${it.size} need your answer · " + it.map { t -> t.toFirst }.distinct().joinToString(", ").take(40), it.size, ChipTone.ORANGE))
            }
            given.filter { it.open && it.due == 0L && !it.asks && !it.stuck && TeamRules.minutesSince(c, it.createdAt, now) >= noDateMin }.takeIf { it.isNotEmpty() }?.let {
                val oldest = it.minOf { t -> t.createdAt }
                val h = TeamRules.minutesSince(c, oldest, now) / 60
                add(Need("📅 ${it.size} waiting for his date · " + it.map { t -> t.toFirst }.distinct().joinToString(", ") + " · ${h} h", it.size, ChipTone.ORANGE))
            }
            tasks.filter { it.toUid == me && it.open && it.hisDate && it.due == 0L }.takeIf { it.isNotEmpty() }?.let {
                add(Need("📅 ${it.size} task${if (it.size == 1) "" else "s"} need your date", it.size, ChipTone.ORANGE))
            }
            tasks.filter { it.toUid == me && it.status == Team.REOPENED }.takeIf { it.isNotEmpty() }?.let {
                add(Need("↩ ${it.size} sent back: not done yet", it.size, ChipTone.RED))
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

    fun lastSeenLabel(m: TeamMember): String = when {
        m.lastSeen <= 0L -> "Not opened yet"
        m.onlineToday() -> "Online today · ${Fmt.timeOnly(m.lastSeen)}"
        else -> "Last seen ${Fmt.dueLabel(m.lastSeen)}"
    }
}

// ---------------------------------------------------------------- small pieces

@Composable
private fun TeamHeading(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

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
private fun TeamChipView(c: RowChip) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (c.tone) {
        ChipTone.BLUE -> cs.primaryContainer to cs.onPrimaryContainer
        ChipTone.RED -> cs.errorContainer to cs.onErrorContainer
        ChipTone.GREEN -> Color(0xFF1E8E5A).copy(alpha = 0.22f) to Color(0xFF2FBF7F)
        ChipTone.ORANGE -> Color(0xFFF0AA5A).copy(alpha = 0.22f) to Color(0xFFE08A1E)
        ChipTone.MUTED -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

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
private fun MinuteChips(choices: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { m ->
            FilterChip(selected = m == selected, onClick = { onPick(m) }, label = { Text(TeamRules.label(m)) })
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
            SectionCard(title = "Signed in", subtitle = user?.email.orEmpty()) {
                TextButton(onClick = { TeamRepo.signOut() }) { Text("Sign out on this phone") }
            }
        }
    }
}

/** Not in a team yet: make one (you become its Owner), or join with the code. */
@Composable
private fun TeamStart(busy: Boolean, go: (suspend () -> String?) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var myPhone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var joinName by remember { mutableStateOf(TeamRepo.user.value?.displayName.orEmpty()) }
    SectionCard(title = "Make your team", subtitle = "You become its Owner. Share the code; nobody joins until you approve.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Team name") }, placeholder = { Text("e.g. Rafiq Builders") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            OutlinedTextField(myPhone, { myPhone = it }, label = { Text("Your mobile number") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
            Button(onClick = { go { TeamRepo.createTeam(name, myPhone) } }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Make team")
            }
        }
    }
    SectionCard(title = "Join a team", subtitle = "Ask your manager for the 6-letter code.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) }, label = { Text("Team code") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            OutlinedTextField(joinName, { joinName = it }, label = { Text("Your name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(myPhone, { myPhone = it }, label = { Text("Your mobile number") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "Before you join: your manager will see your work tasks and follow-ups. Tap 🔒 Private on any task to keep it " +
                        "private (he sees only how many). Call recordings and call logs never leave your phone.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp)
                )
            }
            Button(onClick = {
                if (myPhone.isBlank()) Toast.makeText(ctx, "Add your mobile number", Toast.LENGTH_SHORT).show()
                else go { TeamRepo.join(code, joinName, myPhone) }
            }, enabled = !busy && code.length == 6, modifier = Modifier.fillMaxWidth()) { Text("Ask to join") }
        }
    }
}

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
    var approving by remember { mutableStateOf<TeamMember?>(null) }
    var editing by remember { mutableStateOf<TeamMember?>(null) }

    TeamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TeamTag("You · ${me.roleLabel}")
        }
        Text("${active.size} member${if (active.size == 1) "" else "s"} · share the code with your staff. Nobody joins until ${if (admin) "you approve" else "the owner approves"}.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.code.toCharArray().joinToString(" "), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Button(onClick = { TeamUi.shareCode(ctx, t) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366), contentColor = Color(0xFF053D1E)),
                contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Share on WA", fontWeight = FontWeight.Bold) }
            FilledTonalButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Team code", t.code))
                Toast.makeText(ctx, "Code copied", Toast.LENGTH_SHORT).show()
            }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Copy") }
        }
    }

    FilledTonalButton(onClick = { TeamUi.request.value = TeamUi.INBOX }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Team tasks · ${tasks.count { it.open || it.status == Team.DONE }} open")
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
                        Text("${w.email} · ${w.phone} · ${Fmt.dueLabel(w.joinedAt)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { go { TeamRepo.decline(w) } }, enabled = !busy) { Text("Decline", color = cs.error) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { approving = w }, enabled = !busy) { Text("Approve") }
                }
            }
        }
    }

    TeamHeading("Team chart")
    TeamCard {
        val byBoss = active.groupBy { it.reportsTo }
        val ids = active.map { it.uid }.toSet()
        val roots = active.filter { it.reportsTo.isEmpty() || it.reportsTo !in ids }
        @Composable
        fun Node(m: TeamMember, depth: Int) {
            val canSee = me.uid in m.managers
            val openFromMe = tasks.count { it.toUid == m.uid && it.fromUid == me.uid && it.open }
            Row(
                Modifier.fillMaxWidth().padding(start = (depth * 18).dp)
                    .clickable(enabled = canSee) { TeamUi.openDay(m.uid) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(m.name, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.first, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        OnlineDot(m.onlineToday())
                    }
                    Text(m.roleLabel + (if (openFromMe > 0) " · $openFromMe open from you" else ""), fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                if (admin && m.uid != me.uid && m.role != Team.OWNER) TextButton(onClick = { editing = m }) { Text("Edit") }
                if (canSee) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "His day", tint = cs.onSurfaceVariant)
            }
            byBoss[m.uid].orEmpty().forEach { Node(it, depth + 1) }
        }
        roots.forEach { Node(it, 0) }
        Text(
            "Who sees whom comes from this chart: each person sees everyone below him, never sideways or up. " +
                "Roles: Owner, Admin (can edit the chart and approve), Member.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
        )
    }

    TeamHeading("Date rules")
    var noDate by remember { mutableIntStateOf(TeamRules.noDateMin(ctx)) }
    var urgent by remember { mutableIntStateOf(TeamRules.urgentMin(ctx)) }
    var sms by remember { mutableIntStateOf(TeamRules.smsMin(ctx)) }
    var working by remember { mutableStateOf(TeamRules.workingOnly(ctx)) }
    SectionCard(title = "No date picked · remind me after", subtitle = "Shows in Needs you on Home") {
        MinuteChips(TeamRules.NO_DATE_CHOICES, noDate) { noDate = it; TeamRules.setNoDateMin(ctx, it) }
    }
    SectionCard(title = "Urgent, no date · notify me after", subtitle = "Then you can set the date yourself") {
        MinuteChips(TeamRules.URGENT_CHOICES, urgent) { urgent = it; TeamRules.setUrgentMin(ctx, it) }
    }
    SectionCard(title = "Not seen · text him after", subtitle = "One SMS from your SIM: “New task from you… Please open the app.”") {
        MinuteChips(TeamRules.SMS_CHOICES, sms) { sms = it; TeamRules.setSmsMin(ctx, it) }
    }
    SectionCard(title = "Count only working hours", subtitle = WorkHours.label(ctx).let { if (WorkHours.isSet(ctx)) "$it, weekly off day skipped" else "11 AM - 9 PM, weekly off day skipped" }) {
        SwitchRow(label = if (working) "On" else "Off", checked = working) { working = it; TeamRules.setWorkingOnly(ctx, it) }
    }

    if (me.role != Team.OWNER) {
        TextButton(onClick = { go { TeamRepo.leave() } }) { Text("Leave the team", color = cs.error) }
    }

    approving?.let { w ->
        TeamApproveDialog(w, me, active, onDismiss = { approving = null }) { phone, boss, role ->
            approving = null
            go { TeamRepo.approve(ctx, w, phone, boss, role) }
        }
    }
    editing?.let { m ->
        TeamChartDialog(m, active, onDismiss = { editing = null },
            onSave = { boss, role, phone -> editing = null; go { TeamRepo.setChart(m, boss, role, phone) } },
            onRemove = { editing = null; go { TeamRepo.remove(ctx, m) } })
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

/** Approve: his saved number (so his open follow-ups move), who he reports to, his role. */
@Composable
private fun TeamApproveDialog(
    w: TeamMember, me: TeamMember, active: List<TeamMember>, onDismiss: () -> Unit, onApprove: (String, String, String) -> Unit,
) {
    var phone by remember { mutableStateOf(w.phone) }
    var picked by remember { mutableStateOf("") }
    var boss by remember { mutableStateOf(me.uid) }
    var role by remember { mutableStateOf(Team.MEMBER) }
    val count = remember(phone) {
        if (phone.isBlank()) 0 else ReminderStore.items.value.count { !it.done && !it.review && it.repeat == null && SmsStore.sameNumber(it.number, phone) } +
            ToScheduleStore.items.value.count { SmsStore.sameNumber(it.number, phone) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Approve ${w.name.ifBlank { w.email }}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Which saved contact is ${Names.first(w.name) ?: "he"}? Links him to your contact, so his history continues.",
                    style = MaterialTheme.typography.bodySmall)
                if (picked.isNotBlank()) Text("✓ $picked", fontWeight = FontWeight.Bold)
                PersonPicker(prompt = null) { n, num -> picked = n.ifBlank { num }; phone = num }
                OutlinedTextField(phone, { phone = it }, label = { Text("His mobile number") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                TeamChartFields(w, active, boss, { boss = it }, role, { role = it })
                if (count > 0) Text("His $count open follow-up${if (count == 1) "" else "s"} move to his app. The task and its time go to him; your old history stays on your phone.",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(onClick = { onApprove(phone, boss, role) }, enabled = phone.isNotBlank()) {
                Text(if (count > 0) "Approve and move $count" else "Approve")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TeamChartDialog(
    m: TeamMember, active: List<TeamMember>, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit, onRemove: () -> Unit,
) {
    var phone by remember { mutableStateOf(m.phone) }
    var boss by remember { mutableStateOf(m.reportsTo) }
    var role by remember { mutableStateOf(m.role) }
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                TeamChartFields(m, active, boss, { boss = it }, role, { role = it })
                TextButton(onClick = { confirmRemove = true }) { Text("Remove member", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { onSave(boss, role, phone) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${m.first}?") },
            text = { Text("His access ends now. Open tasks you gave him come back to your To schedule. His own tasks stay on his phone.") },
            confirmButton = { Button(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Team tasks (inbox)

@Composable
fun TeamInboxScreen(onBack: () -> Unit) {
    val tasks by TeamRepo.tasks.collectAsState()
    val me by TeamRepo.me.collectAsState()
    val uid = me?.uid
    SettingsPage(title = "Team tasks", onBack = onBack) {
        if (me?.active != true || uid == null) {
            Text("Join or make a team in Settings › Team first.")
            TextButton(onClick = { TeamUi.request.value = TeamUi.SETTINGS }) { Text("Open Settings › Team") }
            return@SettingsPage
        }
        val given = tasks.filter { it.fromUid == uid }
        val mine = tasks.filter { it.toUid == uid }
        val below = tasks.filter { it.fromUid != uid && it.toUid != uid && (it.open || it.status == Team.DONE) }
        fun section(title: String, list: List<TeamTask>, content: @Composable (TeamTask) -> Unit): (@Composable () -> Unit)? =
            if (list.isEmpty()) null else ({
                TeamHeading("$title · ${list.size}")
                list.forEach { content(it) }
            })
        val parts = listOfNotNull(
            section("Done, check it", given.filter { it.status == Team.DONE }) { TeamGivenCard(it) },
            section("Asking for more time", given.filter { it.asking }) { TeamGivenCard(it) },
            section("Given to you", mine.filter { it.open }.sortedBy { if (it.due == 0L) 0L else it.due }) { TeamMineCard(it) },
            section("Waiting for a date", given.filter { it.open && it.due == 0L && !it.asking }) { TeamGivenCard(it) },
            section("With a date", given.filter { it.open && it.due > 0L && !it.asking }.sortedBy { it.due }) { TeamGivenCard(it) },
            section("In your team", below.sortedByDescending { it.createdAt }.take(30)) { TeamViewCard(it) },
            section("Closed", (given + mine).filter { !it.open && it.status != Team.DONE }.sortedByDescending { it.events.lastOrNull()?.at ?: it.createdAt }.take(15)) { TeamViewCard(it) },
        )
        if (parts.isEmpty()) Text("No team tasks yet. Give one with New follow-up › 👥 Team.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        parts.forEach { it() }
    }
}

@Composable
private fun TeamTaskHead(t: TeamTask, sub: String, chips: List<RowChip>) {
    val ctx = LocalContext.current
    TeamSync.version.collectAsState().value
    val all = if (TeamSync.unread(ctx, t, TeamRepo.uid)) chips + RowChip("💬 new", ChipTone.BLUE) else chips
    Text(t.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (t.lastMsgText.isNotBlank()) Text("💬 ${t.lastMsgText}", fontSize = 12.5.sp, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    if (all.isNotEmpty()) TeamChips(all)
}

/** A task you gave: its status, and what you can do now. */
@Composable
private fun TeamGivenCard(t: TeamTask) {
    var setDate by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    TeamCard(onClick = { TeamUi.openTimeline(t.id) }) {
        TeamTaskHead(
            t,
            "👤 ${t.toFirst} · given ${Fmt.dueLabel(t.createdAt)}" + (if (t.due > 0L) " · date ${Fmt.dueLabel(t.due)}" else " · no date yet") +
                (if (t.deadline > 0L) " · deadline ${TeamUi.shortDate(t.deadline)}" else ""),
            TeamUi.giverChips(t),
        )
        when {
            t.status == Team.DONE -> TeamCheckButtons(t)
            t.asking -> {
                Text("Wants until ${Fmt.dueLabel(t.moreDue)} · “${t.moreReason}”", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { TeamRepo.answerMoreTime(t, true) }) { Text("Give more time") }
                    OutlinedButton(onClick = { TeamRepo.answerMoreTime(t, false) }) { Text("Keep the date") }
                }
            }
            t.open -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { setDate = true }) { Text(if (t.due == 0L) "Set date myself" else "Change date") }
                TextButton(onClick = { cancel = true }) { Text("Cancel task", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (setDate) {
        DateTimePickerFlow(
            initialMs = t.due.takeIf { it > 0L } ?: (System.currentTimeMillis() + 3_600_000L),
            onPicked = { TeamRepo.setGiverDate(t, it); setDate = false },
            onDismiss = { setDate = false },
        )
    }
    if (cancel) {
        AlertDialog(
            onDismissRequest = { cancel = false },
            title = { Text("Cancel this task?") },
            text = { Text("“${t.title}” leaves ${t.toFirst}'s app.") },
            confirmButton = { Button(onClick = { cancel = false; TeamRepo.cancel(t) }) { Text("Cancel task") } },
            dismissButton = { TextButton(onClick = { cancel = false }) { Text("Keep") } },
        )
    }
}

/** A task given to you: pick your date, ask for more time, Done. */
@Composable
private fun TeamMineCard(t: TeamTask) {
    TeamCard(onClick = { TeamUi.openTimeline(t.id) }) {
        TeamTaskHead(t, "↘ From ${t.fromFirst} · given ${Fmt.dueLabel(t.createdAt)}" + if (t.due > 0L) " · your date ${Fmt.dueLabel(t.due)}" else "",
            TeamUi.receiverChips(t))
        TeamReceiverButtons(t, myTaskId = null)
    }
}

@Composable
private fun TeamViewCard(t: TeamTask) {
    TeamCard(onClick = { TeamUi.openTimeline(t.id) }) {
        TeamTaskHead(t, "${t.fromFirst} → ${t.toFirst}" + if (t.due > 0L) " · ${Fmt.dueLabel(t.due)}" else " · no date", TeamUi.giverChips(t))
    }
}

/** Received, thanks ✓ / Not done yet (with a reason): on a Done card. */
@Composable
fun TeamCheckButtons(t: TeamTask) {
    var notYet by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    if (t.doneAt > 0L) Text("${t.toFirst}: Done ${Fmt.dueLabel(t.doneAt)}" + if (t.passedTo.isNotBlank()) " · by ${t.passedTo}" else "", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { TeamRepo.check(t) }, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3DD6B5), contentColor = Color(0xFF053D2E))) {
            Text("✓ Received, thanks", fontWeight = FontWeight.Bold, maxLines = 1)
        }
        OutlinedButton(onClick = { notYet = true }, modifier = Modifier.weight(1f)) {
            Text("Not done yet", color = cs.error, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
    if (notYet) {
        TeamReasonDialog(
            title = "Not done yet",
            body = "“${t.title}” goes back to ${t.toFirst}. His task reopens with the same date.",
            quick = listOf("Photo missing", "Incomplete", "Wrong site"),
            button = "Send back",
            onDismiss = { notYet = false },
        ) { reason -> notYet = false; TeamRepo.notDoneYet(t, reason) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamReasonDialog(
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
 * His buttons on a task given to him: 📅 Pick your date (or Change date) up to the deadline, Need more time
 * (a later date with a reason), and Done. [myTaskId] is his My task, kept in step.
 */
@Composable
fun TeamReceiverButtons(t: TeamTask, myTaskId: Long?) {
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var donePhoto by remember { mutableStateOf(false) }
    if (!t.open) return
    Text(
        "↘ From ${t.fromFirst} · given ${Fmt.dueLabel(t.createdAt)}" + (if (t.deadline > 0L) " · deadline ${Fmt.dueLabel(t.deadline)}" else "") +
            (if (!t.hisDate && t.due > 0L) " · date set by ${t.fromFirst}" else "") +
            (if (t.status == Team.REOPENED && t.reason.isNotBlank()) "\nNot done yet: “${t.reason}”" else ""),
        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.hisDate) TextButton(onClick = { pick = true }) { Text(if (t.due == 0L) "📅 Pick your date" else "Change date", fontWeight = FontWeight.Bold) }
        if (!t.asking) TextButton(onClick = { more = true }) { Text("Need more time") }
        if (myTaskId == null || t.photoProof) TextButton(onClick = { if (t.photoProof) donePhoto = true else TeamRepo.markDone(t) }) {
            Text(if (t.photoProof) "📷 Done with photo" else "Done ✓")
        }
        TextButton(onClick = { TeamUi.openTimeline(t.id) }) { Text("💬 Talk") }
    }
    if (donePhoto) TeamDoneWithPhoto(t, myTaskId) { donePhoto = false }
    if (pick) {
        DateTimePickerFlow(
            initialMs = t.due.takeIf { it > 0L } ?: defaultCallTime(ctx, System.currentTimeMillis()),
            onPicked = { ms ->
                pick = false
                val end = if (t.deadline > 0L) TeamUi.endOfDayOf(t.deadline) else Long.MAX_VALUE
                if (ms > end) {
                    Toast.makeText(ctx, "After ${t.fromFirst}'s deadline: use Need more time", Toast.LENGTH_LONG).show()
                } else {
                    TeamRepo.setHisDate(t, ms)
                    myTaskId?.let { MyTaskStore.setDue(it, ms) } ?: TeamSync.apply(ctx, TeamRepo.tasks.value, true)
                }
            },
            onDismiss = { pick = false },
        )
    }
    if (more) {
        var until by remember { mutableLongStateOf(0L) }
        var pickUntil by remember { mutableStateOf(false) }
        TeamReasonDialog(
            title = "Ask for more time",
            body = if (t.deadline > 0L || t.due > 0L) "${t.fromFirst}'s date is ${Fmt.dueLabel(maxOf(t.deadline, t.due))}. He gets your request and decides."
            else "${t.fromFirst} gets your request and decides.",
            quick = emptyList(),
            button = "Send request",
            ready = until > 0L,
            top = {
                OutlinedButton(onClick = { pickUntil = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (until > 0L) "I need until ${Fmt.dueLabel(until)}" else "📅 I need until…")
                }
            },
            onDismiss = { more = false },
        ) { reason -> more = false; TeamRepo.askMoreTime(t, until, reason) }
        if (pickUntil) {
            DateTimePickerFlow(
                initialMs = maxOf(t.deadline, t.due, System.currentTimeMillis()) + 24 * 3_600_000L,
                onPicked = { until = it; pickUntil = false },
                onDismiss = { pickUntil = false },
            )
        }
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
                TextButton(onClick = { WhatsApp.open(ctx, m.phone, "") }) { Text("WA", color = Color(0xFF25D366), fontWeight = FontWeight.ExtraBold) }
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
        if (tab == 2) TeamDossierTab(m, if (day == null) null else dossier)
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

// ---------------------------------------------------------------- his Dossier (Phase 3)

/**
 * Dossier tab of his day: the blocks you set (Owner: tap to edit, ＋ Add block), with today's tick times, late
 * ticks in orange, 📷 photos, reasons and voice notes from his phone. [today] is null until his phone sent his day.
 */
@Composable
private fun TeamDossierTab(m: TeamMember, today: List<Map<String, Any?>>?) {
    val cs = MaterialTheme.colorScheme
    val team by TeamRepo.team.collectAsState()
    val owner = team?.ownerUid == TeamRepo.uid && m.role != Team.OWNER
    var blocks by remember(m.uid) { mutableStateOf<List<TeamBlock>?>(null) }
    var edit by remember { mutableStateOf<TeamBlock?>(null) }
    var adding by remember { mutableStateOf(false) }
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
    if (owner) {
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("＋ Add block for ${m.first}", fontWeight = FontWeight.Bold)
        }
        Text("Only you can change ${m.first}'s Dossier. He ticks it on his phone.", fontSize = 12.sp, color = cs.onSurfaceVariant)
    }

    open?.let { (b, r) ->
        TeamBlockDayDialog(m, b, r, canEdit = owner && b != null, onEdit = { open = null; edit = b }) { open = null }
    }
    val cur = blocks.orEmpty()
    if (adding) {
        DossierTaskDialog(
            existing = null, onDismiss = { adding = false }, onSwitchKind = null,
            team = TeamBlockEdit(m.first, null, onSave = { nb -> TeamDossier.save(m, cur + nb) }, onDelete = null),
        )
    }
    edit?.let { b ->
        DossierTaskDialog(
            existing = b.toTask(), onDismiss = { edit = null }, onSwitchKind = null,
            team = TeamBlockEdit(
                m.first, b,
                onSave = { nb -> TeamDossier.save(m, cur.map { if (it.id == nb.id) nb else it }) },
                onDelete = { TeamDossier.save(m, cur.filter { it.id != b.id }) },
            ),
        )
    }
}

/** One block on his day: tick time, the 📷 photo, his reason and voice note; Edit for the Owner. */
@Composable
private fun TeamBlockDayDialog(
    m: TeamMember, b: TeamBlock?, r: Map<String, Any?>?, canEdit: Boolean, onEdit: () -> Unit, onDismiss: () -> Unit,
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
                if (r?.get("photo") == true) {
                    val bmp = remember(photo) { photo?.let { TeamMedia.bitmap(it)?.asImageBitmap() } }
                    if (bmp != null) Image(bmp, contentDescription = "Photo proof", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)))
                    else Text("Loading photo…", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    if (caption.isNotBlank()) Text("“$caption”", fontSize = 13.sp)
                }
                if (r?.get("voice") == true) {
                    val v = voice
                    OutlinedButton(onClick = { v?.let { TeamMedia.play(ctx, "dossier-$blockId-$date", it) } }, enabled = v != null) {
                        Text(if (playing == "dossier-$blockId-$date") "■ Stop" else "▶ Voice note ${if (voiceMs > 0) TeamMedia.duration(voiceMs) else ""}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { TeamMedia.stopPlaying(); onDismiss() }) { Text("Close") } },
        dismissButton = { if (canEdit) TextButton(onClick = onEdit) { Text("Edit block") } },
    )
}

// ---------------------------------------------------------------- Home: Needs you lines

/** The team lines inside Needs you on Home (only while something waits). */
@Composable
fun TeamNeedsLines() {
    val ctx = LocalContext.current
    val tasks by TeamRepo.tasks.collectAsState()
    val me by TeamRepo.me.collectAsState()
    if (me?.active != true) return
    val needs = remember(tasks, me) { TeamUi.needs(ctx, tasks, me?.uid) }
    needs.forEach { n ->
        val (bg, fg) = when (n.tone) {
            ChipTone.GREEN -> Color(0xFF1E8E5A).copy(alpha = 0.22f) to Color(0xFF2FBF7F)
            ChipTone.RED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            else -> Color(0xFFF0AA5A).copy(alpha = 0.25f) to Color(0xFFE08A1E)
        }
        Surface(
            onClick = { TeamUi.request.value = TeamUi.INBOX },
            shape = RoundedCornerShape(14.dp), color = bg, modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n.text, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = fg)
            }
        }
    }
}

// ---------------------------------------------------------------- picking a team member

/** 👥 Team in the person picker: only the people below you in the chart. */
@Composable
fun TeamPickDialog(onDismiss: () -> Unit, onPick: (TeamMember) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val members by TeamRepo.members.collectAsState()
    val tasks by TeamRepo.tasks.collectAsState()
    val team by TeamRepo.team.collectAsState()
    val list = remember(members) { TeamRepo.belowMe() }
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
                if (list.isEmpty()) Text("Nobody below you in the chart yet. The owner sets it in Settings › Team.")
                list.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }.forEach { m ->
                    val open = tasks.count { it.toUid == m.uid && it.fromUid == TeamRepo.uid && it.open }
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Avatar(m.name, size = 36.dp)
                            Box(Modifier.align(Alignment.BottomEnd)) { OnlineDot(m.onlineToday()) }
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
                ChipHeading("DEADLINE", modifier = Modifier.align(Alignment.CenterVertically))
                if (deadline > 0L) {
                    Surface(shape = RoundedCornerShape(10.dp), color = Color.Transparent, border = BorderStroke(1.dp, cs.outline)) {
                        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📅 ${Fmt.dueLabel(deadline)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { onDeadline(0L) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear deadline", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                } else Text("None", fontSize = 13.sp, color = cs.outline)
                TextButton(onClick = { pick = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("📅 Pick") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔴 Urgent", fontWeight = FontWeight.Bold, color = if (urgent) cs.error else cs.onSurface)
                    Text("Reminder ${TeamRules.label(TeamRules.urgentMin(LocalContext.current))} if no date · auto ⭐", fontSize = 12.sp, color = cs.onSurfaceVariant)
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

/** 👤 His date | 🕒 My time: the WHEN choice for a team member (the Tasks | Dossier style box). */
@Composable
fun TeamWhenBox(myTime: Boolean, onChange: (Boolean) -> Unit) {
    BoxTabs(labels = listOf("👤 His date", "🕒 My time"), selected = if (myTime) 1 else 0, onSelect = { onChange(it == 1) })
}
