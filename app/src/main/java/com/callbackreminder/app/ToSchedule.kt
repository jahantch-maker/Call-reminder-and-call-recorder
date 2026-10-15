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

/** A task saved with "Schedule later" after a call: who and what, with no time and no type yet. */
data class ToScheduleItem(
    val id: Long,
    val name: String,
    val number: String,
    val note: String,
    val createdAt: Long,
) {
    val title: String
        get() = name.ifBlank { number }.ifBlank { "Unknown caller" }
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

    private fun load(): List<ToScheduleItem> {
        val raw = prefs().getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ToScheduleItem(
                    id = o.getLong("id"),
                    name = o.optString("name"),
                    number = o.optString("number"),
                    note = o.optString("note"),
                    createdAt = o.optLong("createdAt"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<ToScheduleItem>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("number", t.number)
                    .put("note", t.note)
                    .put("createdAt", t.createdAt)
            )
        }
        prefs().edit().putString("list", arr.toString()).apply()
        _items.value = list
    }

    @Synchronized
    fun add(name: String, number: String, note: String) {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        save(_items.value + ToScheduleItem(id, name.trim(), number.trim(), note.trim(), System.currentTimeMillis()))
        RecentPeople.record(appCtx, name.trim(), number.trim())
    }

    @Synchronized
    fun remove(id: Long) {
        save(_items.value.filter { it.id != id })
    }

    /** The task became a call-back reminder: it leaves this list. */
    fun toCall(t: ToScheduleItem, dueAt: Long) {
        ReminderStore.add(
            name = t.name,
            number = t.number,
            note = t.note,
            dueAt = dueAt,
            createdAs = "Created from To schedule",
            earlier = listOf(CallEvent(t.createdAt, "Saved for later after a call")),
        )
        remove(t.id)
    }

    /** The task became a scheduled text: it leaves this list. */
    fun toText(
        t: ToScheduleItem,
        name: String, number: String, message: String, dueAt: Long, repeat: Repeat?, burst: BurstTemplate?, tracked: Boolean,
    ) {
        SmsStore.add(
            name, number, message, dueAt, repeat, burst, tracked,
            history = listOf(SmsLogEntry(t.createdAt, SmsLogEntry.Status.CALL_EVENT, "Saved for later after a call")),
        )
        remove(t.id)
    }
}

/** The To schedule page: tasks saved after calls, each waiting for a call-back time or a text. */
@Composable
fun ToScheduleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by ToScheduleStore.items.collectAsState()
    var callFor by remember { mutableStateOf<ToScheduleItem?>(null) }
    var textFor by remember { mutableStateOf<ToScheduleItem?>(null) }

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
                        if (items.isNotEmpty()) {
                            Text(
                                "${items.size}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Text(
                        "Saved after calls. Pick Call back or Text to give each one a time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing waiting. After a call, type a note and tap Schedule later to save a task here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp)
                    )
                }
            }
            items(items.sortedByDescending { it.createdAt }, key = { it.id }) { t ->
                ToScheduleCard(
                    t = t,
                    onCall = { callFor = t },
                    onText = { textFor = t },
                    onDelete = {
                        ToScheduleStore.remove(t.id)
                        Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    callFor?.let { t ->
        CallBackTimeSheet(
            t = t,
            onPicked = { ms ->
                ToScheduleStore.toCall(t, ms)
                callFor = null
                Toast.makeText(ctx, "Reminder set · ${Fmt.dueLabel(ms)}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { callFor = null }
        )
    }

    textFor?.let { t ->
        SmsReminderDialog(
            prefill = TextPrefill(name = t.name, number = t.number, message = t.note, heading = "Schedule a text"),
            onDismiss = { textFor = null },
            onSave = { name, number, message, dueAt, repeat, burst, tracked ->
                ToScheduleStore.toText(t, name, number, message, dueAt, repeat, burst, tracked)
                textFor = null
                Toast.makeText(ctx, "Text scheduled · ${Fmt.dueLabel(dueAt)}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun ToScheduleCard(t: ToScheduleItem, onCall: () -> Unit, onText: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(text = t.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (t.name.isNotBlank() && t.number.isNotBlank()) {
                        Text(
                            t.number,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(t.note, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.PendingActions,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Saved ${Fmt.dueLabel(t.createdAt)} · no time yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCall,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Call back", maxLines = 1)
                }
                Button(
                    onClick = onText,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(Icons.Rounded.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Text", maxLines = 1)
                }
            }
        }
    }
}

/** The quick times for turning a To schedule task into a call-back reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallBackTimeSheet(t: ToScheduleItem, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(text = t.title, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "From To schedule · saved ${Fmt.dueLabel(t.createdAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(t.note, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Remind me to call back",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            TimePickerPanel { onPicked(it) }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showPicker = true }) {
                    Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick date & time")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }

    if (showPicker) {
        DateTimePickerFlow(
            initialMs = QuickTime.OnDay(1, 10).toOption().whenMs,
            onPicked = { showPicker = false; onPicked(it) },
            onDismiss = { showPicker = false }
        )
    }
}
