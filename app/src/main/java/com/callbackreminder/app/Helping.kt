package com.callbackreminder.app

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One person asked to help on a task. [solvedAt] 0 = still helping. */
data class HelpHelper(val uid: String, val name: String, val solvedAt: Long)

/**
 * v0.53 A task with people helping on it (@mention on the Timeline). Lives in teams/{t}/helps/{id}: the owner who asked,
 * the helpers, a copy of the task's history lines (never the private notes) and the talk in its own msgs.
 */
data class HelpThread(
    val id: String,
    val key: String,
    val ownerUid: String,
    val ownerName: String,
    val title: String,
    val helpers: List<HelpHelper>,
    val history: List<Pair<Long, String>>,
    val createdAt: Long,
    val lastMsgAt: Long,
    val lastMsgBy: String,
    val lastMsgText: String,
    val ownerDone: Boolean,
) {
    fun helper(uid: String?): HelpHelper? = helpers.firstOrNull { it.uid == uid }
    val allSolved: Boolean get() = helpers.isNotEmpty() && helpers.all { it.solvedAt > 0L }
    val ownerFirst: String get() = TeamRepo.member(ownerUid)?.first ?: Names.first(ownerName) ?: ownerName
}

data class HelpMsg(val id: String, val at: Long, val fromUid: String, val fromName: String, val text: String, val line: Boolean)

/** Which task a Timeline belongs to: "m:<id>" (My task), "l:<link>" (follow-up), "t:<id>" (team task). */
data class HelpRef(val key: String, val title: String)

object HelpUi {
    /** The help thread to show on top of everything (opened from a notification, Needs you or the Helping list). */
    val openThread = MutableStateFlow<String?>(null)
    /** Asks My tasks to jump to its 🤝 Helping list. */
    val showList = MutableStateFlow(false)
}

object HelpRepo {
    private val _threads = MutableStateFlow<List<HelpThread>>(emptyList())
    val threads: StateFlow<List<HelpThread>> = _threads.asStateFlow()

    /** The newest message of each thread that you have looked at (so the owner's "💬 new" can go). */
    private val _seen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val seen: StateFlow<Map<String, Long>> = _seen.asStateFlow()

    private val told = HashMap<String, Long>()
    private var first = true
    private var started = false
    private val finishing = HashSet<String>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("help_seen", Context.MODE_PRIVATE)

    fun idFor(ownerUid: String, key: String) = ownerUid + "_" + key.replace(':', '-')

    private fun db() = FirebaseFirestore.getInstance()

    // ------------------------------------------------------------ reading

    private fun parse(d: DocumentSnapshot): HelpThread? {
        val owner = d.getString("ownerUid") ?: return null
        val helpers = (d.get("helpers") as? List<*>).orEmpty().mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            val uid = m["uid"] as? String ?: return@mapNotNull null
            HelpHelper(uid, m["name"] as? String ?: "", (m["solvedAt"] as? Number)?.toLong() ?: 0L)
        }
        val history = (d.get("history") as? List<*>).orEmpty().mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            val text = m["text"] as? String ?: return@mapNotNull null
            ((m["at"] as? Number)?.toLong() ?: 0L) to text
        }
        return HelpThread(
            id = d.id, key = d.getString("key").orEmpty(), ownerUid = owner, ownerName = d.getString("ownerName").orEmpty(),
            title = d.getString("title").orEmpty(), helpers = helpers, history = history,
            createdAt = d.getLong("createdAt") ?: 0L, lastMsgAt = d.getLong("lastMsgAt") ?: 0L,
            lastMsgBy = d.getString("lastMsgBy").orEmpty(), lastMsgText = d.getString("lastMsgText").orEmpty(),
            ownerDone = d.getBoolean("ownerDone") ?: false,
        )
    }

    /** The threads changed (from TeamRepo's listener): keep them, tell about new messages, close the ones whose task is done. */
    fun onSnapshot(c: Context, docs: List<DocumentSnapshot>) {
        val me = TeamRepo.uid ?: return
        val list = docs.mapNotNull { parse(it) }
        if (_seen.value.isEmpty()) {
            val p = prefs(c)
            _seen.value = p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        }
        _threads.value = list
        for (t in list) {
            val prev = told[t.id]
            told[t.id] = t.lastMsgAt
            if (first || prev == null && t.lastMsgAt == 0L) continue
            if (t.lastMsgAt <= (prev ?: 0L) || t.lastMsgBy == me || t.lastMsgBy.isEmpty()) continue
            if (HelpUi.openThread.value == t.id) continue
            val mineAsHelper = t.helper(me)
            if (t.ownerUid != me && mineAsHelper == null) continue
            val who = if (t.lastMsgBy == t.ownerUid) t.ownerFirst else TeamRepo.member(t.lastMsgBy)?.first ?: "Someone"
            TeamSync.notify(
                c, "help:${t.id}", "🤝 $who · ${t.title}".take(80), t.lastMsgText, mine = null,
                taskId = "help:${t.id}", kind = NotifKind.MESSAGES,
            )
        }
        first = false
        if (!started) {
            started = true
            TeamRepo.scope.launch { MyTaskStore.items.collect { sync() } }
            TeamRepo.scope.launch { TeamRepo.tasks.collect { sync() } }
        }
        sync()
    }

    fun markSeen(c: Context, t: HelpThread) {
        if ((_seen.value[t.id] ?: 0L) >= t.lastMsgAt) return
        _seen.value = _seen.value + (t.id to t.lastMsgAt)
        prefs(c).edit().putLong(t.id, t.lastMsgAt).apply()
    }

    fun listenMsgs(id: String, onChange: (List<HelpMsg>) -> Unit): ListenerRegistration? {
        val col = TeamRepo.helpsRef() ?: return null
        return col.document(id).collection("msgs").orderBy("at", Query.Direction.ASCENDING).addSnapshotListener { s, e ->
            if (e != null || s == null) return@addSnapshotListener
            onChange(s.documents.map { d ->
                HelpMsg(
                    d.id, d.getLong("at") ?: 0L, d.getString("fromUid").orEmpty(), d.getString("fromName").orEmpty(),
                    d.getString("text").orEmpty(), d.getBoolean("line") ?: false,
                )
            })
        }
    }

    // ------------------------------------------------------------ who needs what

    fun isActive(t: HelpThread, me: String): Boolean = when {
        t.ownerUid == me -> !t.ownerDone && !t.allSolved
        else -> t.helper(me)?.let { it.solvedAt == 0L } == true && !t.ownerDone
    }

    /** You are a helper and the owner wrote last: red on Home, until you answer. */
    fun waitingForMe(me: String): List<HelpThread> =
        _threads.value.filter { it.ownerUid != me && isActive(it, me) && it.lastMsgBy == it.ownerUid }.sortedBy { it.lastMsgAt }

    /** You asked, and a helper wrote since you last looked. */
    fun newForOwner(me: String): List<HelpThread> =
        _threads.value.filter {
            it.ownerUid == me && isActive(it, me) && it.lastMsgBy != me && it.lastMsgBy.isNotEmpty() && it.lastMsgAt > (_seen.value[it.id] ?: 0L)
        }.sortedByDescending { it.lastMsgAt }

    fun forKey(me: String?, key: String): HelpThread? = me?.let { u -> _threads.value.firstOrNull { it.id == idFor(u, key) } }

    // ------------------------------------------------------------ writing

    private fun helperMaps(list: List<HelpHelper>) = list.map { mapOf("uid" to it.uid, "name" to it.name, "solvedAt" to it.solvedAt) }

    /** @mention: [who] helps on the task [ref] (asked again if he had left). Returns the thread id. */
    fun mention(ref: HelpRef, who: TeamMember, history: List<Pair<Long, String>>): String? {
        val col = TeamRepo.helpsRef() ?: return null
        val me = TeamRepo.me.value ?: return null
        val id = idFor(me.uid, ref.key)
        val now = System.currentTimeMillis()
        val cur = _threads.value.firstOrNull { it.id == id }
        val again = cur?.helper(who.uid)?.let { it.solvedAt > 0L } == true
        val helpers = cur?.helpers.orEmpty().filter { it.uid != who.uid } + HelpHelper(who.uid, who.name, 0L)
        val viewers = (listOf(me.uid) + helpers.map { it.uid }).distinct()
        val line = if (again) "🔁 ${who.first} was asked again" else "🤝 ${who.first} was asked to help"
        val b = db().batch()
        b.set(
            col.document(id),
            mapOf(
                "ownerUid" to me.uid, "ownerName" to me.name, "key" to ref.key, "title" to ref.title,
                "helpers" to helperMaps(helpers), "viewers" to viewers,
                "history" to history.takeLast(60).map { mapOf("at" to it.first, "text" to it.second.take(300)) },
                "createdAt" to (cur?.createdAt ?: now), "updatedAt" to now, "ownerDone" to false,
                "lastMsgAt" to now, "lastMsgBy" to me.uid, "lastMsgText" to line,
            ),
            SetOptions.merge(),
        )
        b.set(col.document(id).collection("msgs").document(), mapOf("at" to now, "fromUid" to me.uid, "fromName" to me.name, "text" to line, "line" to true))
        b.commit()
        return id
    }

    /** One message that tags several people: all become helpers (asked again if they had left) and the message goes to the thread. */
    fun mentionAll(ref: HelpRef, who: List<TeamMember>, history: List<Pair<Long, String>>, text: String): String? {
        val col = TeamRepo.helpsRef() ?: return null
        val me = TeamRepo.me.value ?: return null
        if (who.isEmpty()) return null
        val id = idFor(me.uid, ref.key)
        val now = System.currentTimeMillis()
        val cur = _threads.value.firstOrNull { it.id == id }
        val ids = who.map { it.uid }.toSet()
        val helpers = cur?.helpers.orEmpty().filter { it.uid !in ids } + who.map { HelpHelper(it.uid, it.name, 0L) }
        val viewers = (listOf(me.uid) + helpers.map { it.uid }).distinct()
        val asked = who.joinToString(", ") { it.first }
        val again = who.any { w -> cur?.helper(w.uid)?.let { it.solvedAt > 0L } == true }
        val line = (if (again) "🔁 " else "🤝 ") + asked + (if (who.size > 1) " were" else " was") + (if (again) " asked again" else " asked to help")
        val body = text.trim()
        val b = db().batch()
        b.set(
            col.document(id),
            mapOf(
                "ownerUid" to me.uid, "ownerName" to me.name, "key" to ref.key, "title" to ref.title,
                "helpers" to helperMaps(helpers), "viewers" to viewers,
                "history" to history.takeLast(60).map { mapOf("at" to it.first, "text" to it.second.take(300)) },
                "createdAt" to (cur?.createdAt ?: now), "updatedAt" to now, "ownerDone" to false,
                "lastMsgAt" to now + 1, "lastMsgBy" to me.uid, "lastMsgText" to body.take(120),
            ),
            SetOptions.merge(),
        )
        b.set(col.document(id).collection("msgs").document(), mapOf("at" to now, "fromUid" to me.uid, "fromName" to me.name, "text" to line, "line" to true))
        b.set(col.document(id).collection("msgs").document(), mapOf("at" to now + 1, "fromUid" to me.uid, "fromName" to me.name, "text" to body, "line" to false))
        b.commit()
        return id
    }

    fun send(t: HelpThread, text: String): Boolean {
        val col = TeamRepo.helpsRef() ?: return false
        val me = TeamRepo.me.value ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        val now = System.currentTimeMillis()
        val b = db().batch()
        b.set(col.document(t.id).collection("msgs").document(), mapOf("at" to now, "fromUid" to me.uid, "fromName" to me.name, "text" to body, "line" to false))
        b.update(col.document(t.id), mapOf("lastMsgAt" to now, "lastMsgBy" to me.uid, "lastMsgText" to body.take(120), "updatedAt" to now))
        b.commit()
        return true
    }

    /** The helper presses Solved: he leaves; only a new @mention brings it back. */
    fun solve(t: HelpThread) {
        val col = TeamRepo.helpsRef() ?: return
        val me = TeamRepo.me.value ?: return
        val now = System.currentTimeMillis()
        val helpers = t.helpers.map { if (it.uid == me.uid) it.copy(solvedAt = now) else it }
        val line = "👋 ${me.first} solved this and left · @mention to ask again"
        val b = db().batch()
        b.set(col.document(t.id).collection("msgs").document(), mapOf("at" to now, "fromUid" to me.uid, "fromName" to me.name, "text" to line, "line" to true))
        b.update(
            col.document(t.id),
            mapOf("helpers" to helperMaps(helpers), "lastMsgAt" to now, "lastMsgBy" to me.uid, "lastMsgText" to line, "updatedAt" to now),
        )
        b.commit()
    }

    /** The task is done: every helper still on it is closed. */
    private fun finish(t: HelpThread) {
        val col = TeamRepo.helpsRef() ?: return
        val me = TeamRepo.me.value ?: return
        if (!finishing.add(t.id)) return
        val now = System.currentTimeMillis()
        val helpers = t.helpers.map { if (it.solvedAt == 0L) it.copy(solvedAt = now) else it }
        val line = "✓ Task done · helping ended"
        val b = db().batch()
        b.set(col.document(t.id).collection("msgs").document(), mapOf("at" to now, "fromUid" to me.uid, "fromName" to me.name, "text" to line, "line" to true))
        b.update(
            col.document(t.id),
            mapOf("helpers" to helperMaps(helpers), "ownerDone" to true, "lastMsgAt" to now, "lastMsgBy" to me.uid, "lastMsgText" to line, "updatedAt" to now),
        )
        b.commit()
    }

    private fun taskDone(key: String): Boolean = when {
        key.startsWith("m:") -> key.drop(2).toLongOrNull()?.let { MyTaskStore.get(it)?.done } == true
        key.startsWith("t:") -> TeamRepo.tasks.value.firstOrNull { it.id == key.drop(2) }?.let { it.status == Team.DONE || it.status == Team.CHECKED } == true
        else -> false
    }

    /** Marking the task Done closes all its helpers. */
    fun sync() {
        val me = TeamRepo.uid ?: return
        _threads.value.filter { it.ownerUid == me && !it.ownerDone && it.helpers.any { h -> h.solvedAt == 0L } && taskDone(it.key) }.forEach { finish(it) }
    }
}

// ---------------------------------------------------------------- the @ list and the Timeline hook

/**
 * What a Timeline needs for @mentions. Typing @ in the message box shows a small list of team members above the box
 * ([MentionPanel]); tapping a name puts "@Name " in the message; more people can follow. Sending asks all tagged people.
 */
class HelpLauncher(val ref: HelpRef, var history: () -> List<Pair<Long, String>>) {
    /** The @word a member is called by: the first name, or the whole name without spaces when two share a first name. */
    fun tagOf(m: TeamMember, all: List<TeamMember>): String = memberTag(m, all)

    fun taggedIn(text: String, all: List<TeamMember>): List<TeamMember> {
        val me = TeamRepo.uid
        val words = Regex("(?:^|\\s)@(\\S+)").findAll(text).map { it.groupValues[1].trimEnd(',', '.', '!', '?', ';', ':') }.toList()
        return all.filter { it.active && it.uid != me && words.any { w -> w.equals(tagOf(it, all), ignoreCase = true) } }
    }

    fun hasTags(text: String): Boolean = taggedIn(text, TeamRepo.members.value).isNotEmpty()

    /** Asks every tagged person (they see the whole history) and sends [body] to the help thread. False when nobody is tagged. */
    fun sendTagged(ctx: Context, body: String): Boolean {
        val who = taggedIn(body, TeamRepo.members.value)
        if (who.isEmpty()) return false
        val id = HelpRepo.mentionAll(ref, who, history(), body)
        Toast.makeText(
            ctx, if (id == null) "Couldn't ask: check Settings › Team" else "Asked " + who.joinToString(", ") { it.first } + " to help", Toast.LENGTH_SHORT,
        ).show()
        return id != null
    }
}

@Composable
fun rememberHelpLauncher(ref: HelpRef?, history: () -> List<Pair<Long, String>>): HelpLauncher? {
    val active = TeamRepo.activeNow
    // The help thread opens in the main screen, so a Timeline shown over other apps (the ring screen) has no @.
    var host: Context? = LocalContext.current
    while (host is android.content.ContextWrapper && host !is MainActivity) host = host.baseContext
    if (ref == null || !active || host !is MainActivity) return null
    return remember(ref.key) { HelpLauncher(ref, history) }.also { it.history = history }
}

/** The small list of team members above the message box (WhatsApp style). Narrows as you type after the @. */
@Composable
fun MentionPanel(l: HelpLauncher?, value: TextFieldValue, onValue: (TextFieldValue) -> Unit) {
    if (l == null) return
    val members by TeamRepo.members.collectAsState()
    val threads by HelpRepo.threads.collectAsState()
    val q = mentionToken(value) ?: return
    val me = TeamRepo.uid ?: return
    val open = threads.firstOrNull { it.id == HelpRepo.idFor(me, l.ref.key) }?.helpers.orEmpty().filter { it.solvedAt == 0L }.map { it.uid }.toSet()
    val taggedUids = l.taggedIn(value.text, members).map { it.uid }.toSet()
    val list = members.filter {
        it.active && it.uid != me && it.uid !in open && it.uid !in taggedUids && l.tagOf(it, members).startsWith(q, ignoreCase = true)
    }
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant, shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            Text(
                "ASK WHO TO HELP?", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp),
            )
            if (list.isEmpty()) Text("Nobody matches @$q", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            list.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clickable { onValue(pickMention(value, l.tagOf(m, members))) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = cs.primary, modifier = Modifier.size(30.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(m.first.take(1).uppercase(), color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(m.name, fontSize = 15.sp)
                        if (m.designation.isNotBlank()) Text(m.designation, fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A slim "🤝 Helping: Sara, Ali ›" line under the task heading once someone was asked. */
@Composable
fun HelpStrip(l: HelpLauncher?) {
    if (l == null) return
    val threads by HelpRepo.threads.collectAsState()
    val me = TeamRepo.uid ?: return
    val t = threads.firstOrNull { it.id == HelpRepo.idFor(me, l.ref.key) } ?: return
    val cs = MaterialTheme.colorScheme
    val open = t.helpers.filter { it.solvedAt == 0L }
    val text = if (open.isNotEmpty()) "🤝 Helping: " + open.joinToString(", ") { Names.first(it.name) ?: it.name }
    else "🤝 Helped by " + t.helpers.joinToString(", ") { Names.first(it.name) ?: it.name } + " · done"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { HelpUi.openThread.value = t.id }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("›", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** A small "@" button for the composer: [onAt] adds the @ to the message. */
@Composable
fun HelpAtButton(l: HelpLauncher?, onAt: () -> Unit) {
    if (l == null) return
    IconButton(onClick = onAt, modifier = Modifier.size(40.dp)) {
        Text("@", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MemberPickDialog(exclude: Set<String>, onDismiss: () -> Unit, onPick: (TeamMember) -> Unit) {
    val members by TeamRepo.members.collectAsState()
    val me = TeamRepo.uid
    val list = members.filter { it.active && it.uid != me && it.uid !in exclude }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🤝 Ask who to help?") },
        text = {
            if (list.isEmpty()) Text("Nobody else to ask right now.")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(list, key = { it.uid }) { m ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 10.dp)) {
                        Text("@" + m.name, fontWeight = FontWeight.SemiBold)
                        if (m.designation.isNotBlank()) Text(m.designation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- the Helping list (My tasks)

/** The 🤝 Helping list: tasks you were asked to help with and tasks you asked help for. */
fun LazyListScope.helpingItems(
    me: String, threads: List<HelpThread>, seen: Map<String, Long>,
    completedOpen: Boolean, onToggleCompleted: () -> Unit,
) {
    val mine = threads.filter { it.ownerUid == me || it.helper(me) != null }
    val active = mine.filter { HelpRepo.isActive(it, me) }
    val done = mine.filter { !HelpRepo.isActive(it, me) }.sortedByDescending { it.lastMsgAt }
    fun attention(t: HelpThread) =
        if (t.ownerUid == me) t.lastMsgBy != me && t.lastMsgBy.isNotEmpty() && t.lastMsgAt > (seen[t.id] ?: 0L)
        else t.lastMsgBy == t.ownerUid
    val urgent = active.filter { attention(it) }.sortedByDescending { it.lastMsgAt }
    val rest = active.filter { !attention(it) }.sortedByDescending { it.lastMsgAt }
    if (mine.isEmpty()) {
        item(key = "help-empty") {
            Text(
                "Nobody is helping yet. In any task's Timeline, type @ and pick a team member to ask for help. Tasks someone asks you to help with show here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
            )
        }
        return
    }
    if (urgent.isNotEmpty()) item(key = "help-urgent") {
        Column {
            DayHeader("💬 ${urgent.size} new", "${urgent.size}", MaterialTheme.colorScheme.error)
            TaskGroup {
                urgent.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                    HelpRow(me, t, true)
                }
            }
        }
    }
    if (rest.isNotEmpty()) item(key = "help-rest") {
        Column {
            DayHeader("Helping", "${rest.size}", MaterialTheme.colorScheme.onSurfaceVariant)
            TaskGroup {
                rest.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                    HelpRow(me, t, false)
                }
            }
        }
    }
    if (done.isNotEmpty()) {
        item(key = "help-completed") { CompletedHeader(done.size, completedOpen, onToggleCompleted) }
        if (completedOpen) item(key = "help-completed-list") {
            TaskGroup {
                done.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                    HelpRow(me, t, false, finished = true)
                }
            }
        }
    }
}

@Composable
private fun HelpRow(me: String, t: HelpThread, red: Boolean, finished: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val mineOwner = t.ownerUid == me
    val who = if (mineOwner) "You asked " + t.helpers.joinToString(", ") { Names.first(it.name) ?: it.name }
    else "Asked by ${t.ownerFirst}"
    Column(
        Modifier.fillMaxWidth()
            .background(if (red) cs.errorContainer.copy(alpha = 0.35f) else Color.Transparent)
            .clickable { HelpUi.openThread.value = t.id }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            t.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (finished) cs.onSurfaceVariant else if (red) cs.error else cs.onSurface,
        )
        Text("🤝 $who", fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (t.lastMsgText.isNotBlank()) Text(
            t.lastMsgText, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- the help thread

private data class HelpRowItem(val at: Long, val text: String, val fromUid: String, val fromName: String, val kind: Int, val key: String)

/** The conversation about one task: its history on top (grey), then the talk. Full screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelpThreadWindow(id: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val threads by HelpRepo.threads.collectAsState()
    val t = threads.firstOrNull { it.id == id }
    if (t == null) {
        // Just asked: the thread arrives in a moment. If it never does, close.
        LaunchedEffect(id) { delay(4000L); onDismiss() }
        return
    }
    val me = TeamRepo.uid ?: ""
    val isOwner = t.ownerUid == me
    val myHelp = t.helper(me)
    val canWrite = !t.ownerDone && (isOwner || myHelp?.let { it.solvedAt == 0L } == true)
    var msgs by remember(id) { mutableStateOf<List<HelpMsg>>(emptyList()) }
    var draft by remember(id) { mutableStateOf(TextFieldValue()) }
    var picking by remember(id) { mutableStateOf(false) }
    val launcher = remember(t.key) { HelpLauncher(HelpRef(t.key, t.title)) { t.history } }
    DisposableEffect(id) {
        val reg = HelpRepo.listenMsgs(id) { msgs = it }
        onDispose { reg?.remove() }
    }
    LaunchedEffect(t.lastMsgAt) { HelpRepo.markSeen(ctx, t) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bg = cs.background.toArgb()
        (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
            SideEffect {
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().fitBetweenBars()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("🤝 HELP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (myHelp != null && myHelp.solvedAt == 0L && !t.ownerDone) {
                        TextButton(onClick = { HelpRepo.solve(t) }) { Text("✓ Solved", fontWeight = FontWeight.Bold) }
                    }
                    if (isOwner && !t.ownerDone) TextButton(onClick = { picking = true }) { Text("＋ Ask someone", fontWeight = FontWeight.Bold) }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallChip(RowChip("👤 ${t.ownerFirst}", ChipTone.BLUE))
                        t.helpers.forEach { h ->
                            val n = Names.first(h.name) ?: h.name
                            SmallChip(if (h.solvedAt > 0L) RowChip("✓ $n", ChipTone.GREEN) else RowChip("🤝 $n", ChipTone.ORANGE))
                        }
                    }
                    if (!isOwner) Text(
                        "You can read the task's history and reply. Press ✓ Solved when you are done.",
                        fontSize = 12.sp, color = cs.onSurfaceVariant,
                    )
                }
                val rows = remember(t.history, msgs) {
                    val all = t.history.map { HelpRowItem(it.first, it.second, "", "", 0, "h${it.first}${it.second.hashCode()}") } +
                        msgs.map { HelpRowItem(it.at, it.text, it.fromUid, it.fromName, if (it.line) 1 else 2, "m${it.id}") }
                    withDayChips(all.sortedBy { it.at }) { it.at }
                }
                val list = rememberLazyListState()
                LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = list, modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(rows, key = { if (it is DayChip) "d${TimelineDates.day(it.at)}" else (it as HelpRowItem).key }) { x ->
                            when (x) {
                                is DayChip -> DayChipView(x.label)
                                is HelpRowItem -> if (x.kind != 2) {
                                    Text(
                                        x.text + " · " + Fmt.timeOnly(x.at), fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                                    )
                                } else HelpBubble(x, mine = x.fromUid == me)
                            }
                        }
                    }
                    FloatingDayChip(list, rows, Modifier.align(Alignment.TopCenter))
                }
                if (canWrite) Column(Modifier.fillMaxWidth().background(cs.surface)) {
                    if (isOwner) MentionPanel(launcher, draft) { draft = it }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatPill(draft, { draft = it }, if (isOwner) "Message…" else "Reply…", Modifier.weight(1f)) {
                            if (isOwner) HelpAtButton(launcher) { draft = insertAtSign(draft) }
                        }
                        Spacer(Modifier.width(6.dp))
                        ChatSendButton(
                            onClick = {
                                val body = draft.text
                                if (isOwner && launcher.hasTags(body)) { launcher.sendTagged(ctx, body.trim()); draft = TextFieldValue() }
                                else if (HelpRepo.send(t, body)) draft = TextFieldValue()
                            },
                            enabled = draft.text.isNotBlank(),
                        )
                    }
                } else Text(
                    when {
                        t.ownerDone -> "The task is done. Helping has ended."
                        else -> "You left this task. Only a new @mention brings you back."
                    },
                    fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    if (picking) {
        val open = t.helpers.filter { it.solvedAt == 0L }.map { it.uid }.toSet()
        MemberPickDialog(exclude = open, onDismiss = { picking = false }) { m ->
            picking = false
            HelpRepo.mention(HelpRef(t.key, t.title), m, t.history)
        }
    }
}

@Composable
private fun HelpBubble(x: HelpRowItem, mine: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (mine) cs.primaryContainer else cs.surfaceVariant, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (!mine) Text(Names.first(x.fromName) ?: x.fromName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = cs.primary)
                Text(mentionStyled(x.text, rememberMentionTags(), mentionBlue()), fontSize = 15.sp, color = if (mine) cs.onPrimaryContainer else cs.onSurface)
                Text(
                    Fmt.timeOnly(x.at), fontSize = 11.sp, color = (if (mine) cs.onPrimaryContainer else cs.onSurface).copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** Shows the help thread asked for from anywhere (a notification, Needs you, the Helping list). Put once in the app. */
@Composable
fun HelpHost() {
    val id by HelpUi.openThread.collectAsState()
    id?.let { HelpThreadWindow(it) { HelpUi.openThread.value = null } }
}
