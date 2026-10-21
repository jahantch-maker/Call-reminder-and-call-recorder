package com.callbackreminder.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A follow-up with no time yet: saved with No time (or Remove time), or task-only with nothing set. It keeps
 * everything the follow-up had, so setting a time later brings back its call and texts, history included.
 */
data class ToScheduleItem(
    val id: Long,
    val name: String,
    val number: String,
    val note: String,
    val createdAt: Long,
    val callOn: Boolean = true,
    val textOn: Boolean = false,
    val message: String = "",
    val tracked: Boolean = true,
    /** The text times as they were (they move with the new time, like New time). */
    val textTimes: List<Long> = emptyList(),
    val burstCount: Int = 1,
    val burstGapMin: Int = 0,
    val repeat: Repeat? = null,
    /** The due time before Remove time (0 = never had one). */
    val oldDue: Long = 0L,
    val autopilot: Boolean = false,
    val starred: Boolean = false,
    val linkId: Long = 0L,
    /** The texts' task number, kept so his "Done <number>" still matches. */
    val code: String = "",
    val callEvents: List<CallEvent> = emptyList(),
    val textLog: List<SmsLogEntry> = emptyList(),
) {
    val title: String
        get() = name.ifBlank { number }.ifBlank { "No person yet" }

    /** No person, or neither a call nor texts: it needs Edit before it can get a time. */
    val needsDetails: Boolean
        get() = number.isBlank() || (!callOn && !textOn)

    val burst: BurstTemplate?
        get() = if (burstCount > 1) BurstTemplate(burstCount, burstGapMin) else null

    /** The bold line: the task, else "Call back Ali". */
    val headline: String
        get() = note.ifBlank { message }.ifBlank { "Call back $title" }
}

/** The To schedule list, kept on the phone like the other lists. */
object ToScheduleStore {
    private lateinit var appCtx: Context
    private val _items = MutableStateFlow<List<ToScheduleItem>>(emptyList())
    val items: StateFlow<List<ToScheduleItem>> = _items.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        _items.value = load()
    }

    private fun prefs() = appCtx.getSharedPreferences("to_schedule", Context.MODE_PRIVATE)

    private val rows = Rows<ToScheduleItem>("to_schedule", { it.id }, ::toJson, ::fromJson)

    private fun load(): List<ToScheduleItem> = rows.load(appCtx, prefs())

    /** Finished items older than [Db.ARCHIVE_DAYS] days, kept for search. */
    fun archived(): List<ToScheduleItem> = rows.archived(appCtx)

    private fun fromJson(o: JSONObject): ToScheduleItem = run {
        ToScheduleItem(
            id = o.getLong("id"),
            name = o.optString("name"),
            number = o.optString("number"),
            note = o.optString("note"),
            createdAt = o.optLong("createdAt"),
            callOn = o.optBoolean("callOn", true),
            textOn = o.optBoolean("textOn", false),
            message = o.optString("message"),
            tracked = o.optBoolean("tracked", true),
            textTimes = o.optJSONArray("textTimes")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
            burstCount = o.optInt("burstCount", 1).coerceAtLeast(1),
            burstGapMin = o.optInt("burstGap"),
            repeat = Repeat.fromJson(o.optJSONObject("repeat")),
            oldDue = o.optLong("oldDue"),
            autopilot = o.optBoolean("autopilot"),
            starred = o.optBoolean("starred"),
            linkId = o.optLong("linkId"),
            code = o.optString("code").replace("-", ""),
            callEvents = o.optJSONArray("callEvents")?.let { a ->
                (0 until a.length()).map { j ->
                    val e = a.getJSONObject(j)
                    CallEvent(e.optLong("at"), e.optString("what"), e.optLong("due"), e.optString("note"))
                }
            } ?: emptyList(),
            textLog = o.optJSONArray("textLog")?.let { a ->
                (0 until a.length()).mapNotNull { j ->
                    val l = a.getJSONObject(j)
                    val status = SmsLogEntry.Status.values().firstOrNull { it.code == l.optString("s") }
                        ?: return@mapNotNull null
                    SmsLogEntry(l.getLong("at"), status, l.optString("d"))
                }
            } ?: emptyList(),
        )
    }

    private fun save(list: List<ToScheduleItem>) {
        rows.save(appCtx, list)
        _items.value = list
    }

    private fun toJson(t: ToScheduleItem): JSONObject {
        return JSONObject()
            .put("id", t.id)
            .put("name", t.name)
            .put("number", t.number)
            .put("note", t.note)
            .put("createdAt", t.createdAt)
            .put("callOn", t.callOn)
            .put("textOn", t.textOn)
            .put("message", t.message)
            .put("tracked", t.tracked)
            .put("textTimes", JSONArray().apply { t.textTimes.forEach { put(it) } })
            .put("burstCount", t.burstCount)
            .put("burstGap", t.burstGapMin)
            .put("repeat", t.repeat?.toJson())
            .put("oldDue", t.oldDue)
            .put("autopilot", t.autopilot)
            .put("starred", t.starred)
            .put("linkId", t.linkId)
            .put("code", t.code)
            .put("callEvents", JSONArray().apply {
                t.callEvents.forEach { e ->
                    put(JSONObject().put("at", e.at).put("what", e.what).put("due", e.due).put("note", e.note))
                }
            })
            .put("textLog", JSONArray().apply {
                t.textLog.forEach { l -> put(JSONObject().put("at", l.at).put("s", l.status.code).put("d", l.detail)) }
            })
    }

    fun get(id: Long): ToScheduleItem? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(name: String, number: String, note: String): ToScheduleItem =
        put(ToScheduleItem(0L, name, number, note, 0L))

    /** Saves [t] as a new item (id 0), or over the item with its id. */
    @Synchronized
    fun put(t: ToScheduleItem): ToScheduleItem {
        val id = if (t.id > 0L) t.id else prefs().getLong("nextId", 1L).also { prefs().edit().putLong("nextId", it + 1).apply() }
        val item = t.copy(
            id = id,
            name = t.name.trim(),
            number = t.number.trim(),
            note = t.note.trim(),
            createdAt = if (t.createdAt > 0L) t.createdAt else System.currentTimeMillis(),
        )
        val list = _items.value
        save(if (list.any { it.id == id }) list.map { if (it.id == id) item else it } else list + item)
        if (item.number.isNotBlank()) RecentPeople.record(appCtx, item.name, item.number)
        return item
    }

    @Synchronized
    fun remove(id: Long) {
        save(_items.value.filter { it.id != id })
    }
}

/**
 * The To schedule page: everything waiting with no time. Follow-ups (from No time, Remove time or a quick save)
 * and My tasks with no time yet, each with Set time.
 */
@Composable
fun ToScheduleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by ToScheduleStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val mine = myTasks.filter { !it.done && !it.timed }.sortedByDescending { it.createdAt }
    var setFor by remember { mutableStateOf<ToScheduleItem?>(null) }
    var editFor by remember { mutableStateOf<ToScheduleItem?>(null) }
    var setTaskFor by remember { mutableStateOf<MyTask?>(null) }
    var moveTaskFor by remember { mutableStateOf<MyTask?>(null) }
    val total = items.size + mine.size

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            "To schedule",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (total > 0) {
                            Text(
                                "$total",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Text(
                        "No time set yet. Give each one a time, or open it to add what is missing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
            if (total == 0) {
                item(key = "empty") {
                    Text(
                        "Nothing waiting. A follow-up or task saved with No time shows here until it gets a time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp)
                    )
                }
            }
            if (items.isNotEmpty()) item(key = "h-fu") { DayHeader("Follow-ups", "${items.size}", MaterialTheme.colorScheme.primary) }
            items(items.sortedByDescending { it.createdAt }, key = { "fu-${it.id}" }) { t ->
                ToScheduleCard(
                    title = t.headline,
                    person = t.title.takeIf { t.number.isNotBlank() || t.name.isNotBlank() },
                    number = t.number.takeIf { t.name.isNotBlank() && it.isNotBlank() },
                    info = buildList {
                        add("Saved ${Fmt.dueLabel(t.createdAt)}")
                        if (t.needsDetails) add("Needs details")
                        else {
                            if (t.callOn) add("📞 call")
                            if (t.textOn) add("💬 texts paused")
                        }
                    }.joinToString(" · "),
                    warn = t.needsDetails,
                    primaryLabel = if (t.needsDetails) "Add details" else "Set time",
                    onPrimary = { if (t.needsDetails) editFor = t else setFor = t },
                    secondaryLabel = "Edit",
                    onSecondary = { editFor = t },
                    menu = listOf(
                        "Move to My tasks" to {
                            FollowUps.moveToMyTasks(ctx, t)
                            Toast.makeText(ctx, "Moved to My tasks", Toast.LENGTH_SHORT).show()
                        },
                        "Delete" to {
                            ToScheduleStore.remove(t.id)
                            Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                        },
                    ),
                )
            }
            if (mine.isNotEmpty()) item(key = "h-my") { DayHeader("My tasks", "${mine.size}", MaterialTheme.colorScheme.secondary) }
            items(mine, key = { "my-${it.id}" }) { t ->
                ToScheduleCard(
                    title = t.title,
                    person = t.personTitle.takeIf { t.hasPerson },
                    number = null,
                    info = "My task · saved ${Fmt.dueLabel(t.createdAt)}",
                    warn = false,
                    primaryLabel = "Set time",
                    onPrimary = { setTaskFor = t },
                    secondaryLabel = "Move to Follow-ups",
                    onSecondary = { moveTaskFor = t },
                    menu = listOf(
                        "Delete" to {
                            MyTaskStore.delete(t.id)
                            Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                        },
                    ),
                )
            }
        }
    }

    setFor?.let { t ->
        SetTimeSheet(
            heading = "Set time",
            about = t.headline,
            onPick = { ms ->
                setFor = null
                FollowUps.setTime(ctx, t, ms)
                Toast.makeText(ctx, "Set · ${Fmt.dueLabel(ms)}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { setFor = null },
        )
    }
    editFor?.let { t -> FollowUpDialog(onDismiss = { editFor = null }, item = t) }
    setTaskFor?.let { t ->
        SetTimeSheet(
            heading = "Set time",
            about = t.title,
            onPick = { ms ->
                setTaskFor = null
                MyTaskStore.setDue(t.id, ms)
                Toast.makeText(ctx, "Set · ${Fmt.dueLabel(ms)}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { setTaskFor = null },
        )
    }
    moveTaskFor?.let { t -> FollowUpDialog(onDismiss = { moveTaskFor = null }, fromMyTask = t) }
}

@Composable
private fun ToScheduleCard(
    title: String,
    person: String?,
    number: String?,
    info: String,
    warn: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    menu: List<Pair<String, () -> Unit>>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (person != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(text = person, size = 20.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                person + (number?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Column {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        menu.forEach { (label, action) ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (label == "Delete") cs.error else cs.onSurface) },
                                onClick = { menuOpen = false; action() }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.PendingActions,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (warn) cs.error else cs.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(info, style = MaterialTheme.typography.bodySmall, color = if (warn) cs.error else cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrimary, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(primaryLabel, maxLines = 1)
                }
                OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(secondaryLabel, maxLines = 1)
                }
            }
        }
    }
}
