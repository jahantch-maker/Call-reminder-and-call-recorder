package com.callbackreminder.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** v0.48 One "why" picked for a slip: a moved date, an extension request or a Done after the date. */
data class LateReason(val at: Long, val reason: String, val kind: String, val outside: Boolean, val note: String = "")

/**
 * v0.48 The team's late reasons. Only the owner changes them, in the meeting web app (Settings › Late reasons); the
 * phones read them from teams/{team}/config/reasons and keep a copy. "Outside" reasons (material, payment, client,
 * weather) show grey in the meeting and don't count against the person.
 */
object LateReasons {
    val OWN_DEFAULT = listOf("Forgot", "Too much work", "Waiting on staff", "Date was too tight", "Priority changed")
    val OUTSIDE_DEFAULT = listOf("Material late", "Payment held", "Client / vendor delay", "Weather")
    const val OTHER = "Other"

    private val _own = MutableStateFlow(OWN_DEFAULT)
    private val _outside = MutableStateFlow(OUTSIDE_DEFAULT)
    val own: StateFlow<List<String>> = _own.asStateFlow()
    val outside: StateFlow<List<String>> = _outside.asStateFlow()

    private fun p(c: Context) = c.getSharedPreferences("late_reasons", Context.MODE_PRIVATE)

    fun load(c: Context) {
        p(c).getString("own", null)?.let { s -> lines(s).takeIf { it.isNotEmpty() }?.let { _own.value = it } }
        p(c).getString("outside", null)?.let { s -> lines(s).takeIf { it.isNotEmpty() }?.let { _outside.value = it } }
    }

    private fun lines(s: String) = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Reads the owner's list once (app start, team becomes active). */
    fun refresh(c: Context) {
        val t = TeamRepo.teamId ?: return
        if (!TeamRepo.activeNow) return
        runCatching {
            FirebaseFirestore.getInstance().collection("teams").document(t).collection("config").document("reasons").get()
                .addOnSuccessListener { d ->
                    if (!d.exists()) return@addOnSuccessListener
                    @Suppress("UNCHECKED_CAST")
                    fun names(key: String): List<String> = (d.get(key) as? List<*>)?.mapNotNull { e ->
                        when (e) {
                            is String -> e
                            is Map<*, *> -> (e["name"] as? String)?.takeIf { e["retired"] != true }
                            else -> null
                        }
                    }?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    val o = names("own")
                    val x = names("outside")
                    if (o.isNotEmpty()) _own.value = o
                    if (x.isNotEmpty()) _outside.value = x
                    p(c).edit().putString("own", _own.value.joinToString("\n")).putString("outside", _outside.value.joinToString("\n")).apply()
                }
        }
    }

    fun isOutside(reason: String) = reason in _outside.value
}

/**
 * v0.48 The one-tap "why" after a slip. The slip itself is saved at once (a date moved, a request sent, Done ticked);
 * then this sheet asks one reason, from the owner's list. It can't be skipped. Slips made from a notification wait
 * here until the app opens. Only while the team is on, and never for 🔒 Private tasks.
 */
object ReasonAsk {
    const val MOVE = "move"
    const val EXT = "ext"
    const val LATE = "late"

    data class Ask(val key: String, val kind: String, val title: String, val sub: String, val at: Long)

    private lateinit var appCtx: Context
    private val _pending = MutableStateFlow<List<Ask>>(emptyList())
    val pending: StateFlow<List<Ask>> = _pending.asStateFlow()

    private fun p() = appCtx.getSharedPreferences("reason_asks", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (::appCtx.isInitialized) return
        appCtx = c.applicationContext
        _pending.value = runCatching {
            val a = JSONArray(p().getString("list", "[]"))
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { o -> Ask(o.optString("k"), o.optString("kind"), o.optString("t"), o.optString("s"), o.optLong("at")) }
            }
        }.getOrElse { emptyList() }
        LateReasons.load(appCtx)
    }

    private fun save(list: List<Ask>) {
        _pending.value = list
        p().edit().putString("list", JSONArray().apply {
            list.forEach { put(JSONObject().put("k", it.key).put("kind", it.kind).put("t", it.title).put("s", it.sub).put("at", it.at)) }
        }.toString()).apply()
    }

    /** Asks why (once per task and kind until answered). */
    @Synchronized
    fun ask(key: String, kind: String, title: String, sub: String) {
        if (!::appCtx.isInitialized || !TeamRepo.activeNow) return
        val list = _pending.value
        if (list.any { it.key == key && it.kind == kind }) return
        save(list + Ask(key, kind, title, sub, System.currentTimeMillis()))
    }

    /** An own My task slipped (not 🔒 Private, not a task given by the team: those ask on the team side). */
    fun askMyTask(c: Context, t: MyTask, kind: String) {
        if (TeamLinks.isPrivate(c, t.id) || TeamLinks.teamTaskOfMyTask(c, t.id) != null) return
        val sub = when (kind) {
            MOVE -> "${t.title} · moved to ${Fmt.fullDate(t.dueAt)}"
            else -> "${t.title} · was due ${Fmt.fullDate(OnTime.promise(t))}"
        }
        ask("my:${t.id}", kind, title(kind, OnTime.promise(t), t.doneAt), sub)
    }

    fun askTeamTask(t: TeamTask, kind: String, newDue: Long = 0L) {
        if (t.toUid != TeamRepo.uid) return
        val sub = when (kind) {
            MOVE, EXT -> "${t.title} · to ${TeamUi.shortWhen(newDue)}"
            else -> "${t.title} · was due ${TeamUi.shortWhen(OnTime.promise(t))}"
        }
        ask("team:${t.id}", kind, title(kind, OnTime.promise(t), System.currentTimeMillis()), sub)
    }

    private fun title(kind: String, promise: Long, doneAt: Long) = when (kind) {
        MOVE -> "Why is it moving?"
        EXT -> "Why more time?"
        else -> OnTime.label(promise, true, doneAt, 0, "moved")?.removePrefix("⏱ ")?.let { "Done · $it" } ?: "Why late?"
    }

    @Synchronized
    fun answer(a: Ask, reason: String, note: String) {
        save(_pending.value.filterNot { it.key == a.key && it.kind == a.kind })
        val r = LateReason(System.currentTimeMillis(), reason, a.kind, LateReasons.isOutside(reason), note.trim())
        when {
            a.key.startsWith("my:") -> a.key.removePrefix("my:").toLongOrNull()?.let { MyTaskStore.addReason(it, r) }
            a.key.startsWith("team:") -> TeamRepo.addLateReason(a.key.removePrefix("team:"), r)
        }
    }
}

/** The sheet itself: shown over any screen while a "why" waits. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReasonAskHost() {
    val pending by ReasonAsk.pending.collectAsState()
    val a = pending.firstOrNull() ?: return
    val own by LateReasons.own.collectAsState()
    val outside by LateReasons.outside.collectAsState()
    val cs = MaterialTheme.colorScheme
    var why by remember(a.key, a.kind) { mutableStateOf<String?>(null) }
    var note by remember(a.key, a.kind) { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    val ready = why != null && (why != LateReasons.OTHER || note.isNotBlank())
    ModalBottomSheet(onDismissRequest = {}, sheetState = state) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarMissing()),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(a.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(a.sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ChipHeading("WHY", warn = why == null, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                own.forEach { q -> FilterChip(selected = why == q, onClick = { why = q }, label = { Text(q) }) }
            }
            ChipHeading("OUTSIDE · NOT COUNTED AGAINST YOU", modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                outside.forEach { q -> FilterChip(selected = why == q, onClick = { why = q }, label = { Text(q, color = if (why == q) cs.onSurface else cs.outline) }) }
                FilterChip(selected = why == LateReasons.OTHER, onClick = { why = LateReasons.OTHER }, label = { Text("✎ Other") })
            }
            if (why == LateReasons.OTHER) OutlinedTextField(
                note, { note = it }, placeholder = { Text("A few words") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            Button(
                onClick = { why?.let { ReasonAsk.answer(a, it, note) } },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (ready) "Save" else "Pick a reason to save") }
        }
    }
}

/**
 * v0.48 Told once, when the team is on: own tasks (not 🔒 Private) are shared with the owner for team meetings.
 */
@Composable
fun MeetingShareNotice() {
    val ctx = LocalContext.current
    val me by TeamRepo.me.collectAsState()
    val team by TeamRepo.team.collectAsState()
    val p = remember { ctx.getSharedPreferences("own_share", Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(p.getBoolean("notice_seen", false)) }
    if (seen || me?.active != true || team == null || me?.role == Team.OWNER) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Shared for team meetings") },
        text = { Text("Your tasks (except 🔒 Private) are now shared with the owner for team meetings.", fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = { p.edit().putBoolean("notice_seen", true).apply(); seen = true }) { Text("OK") } },
    )
}
