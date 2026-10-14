package com.callbackreminder.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- Home + (Google Keep style)

/** One choice in the Home ＋ menu. */
class DialOption(val label: String, val icon: ImageVector, val tint: Color, val onClick: () -> Unit)

/**
 * The Home ＋: tap it and it turns into ×, the screen dims and the labelled choices pop up one after
 * another, nearest your thumb first. Tap one, × or anywhere outside to close. [options] are listed
 * from the bottom up.
 */
@Composable
fun BoxScope.SpeedDial(open: Boolean, onOpenChange: (Boolean) -> Unit, options: List<DialOption>) {
    val scrim by animateFloatAsState(if (open) 0.45f else 0f, tween(180), label = "scrim")
    val turn by animateFloatAsState(if (open) 135f else 0f, tween(220), label = "turn")
    BackHandler(enabled = open) { onOpenChange(false) }
    if (scrim > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpenChange(false) }
        )
    }
    Column(
        Modifier.align(Alignment.BottomEnd).padding(16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        options.withIndex().reversed().forEach { (i, o) ->
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(140, delayMillis = i * 45)) + scaleIn(tween(160, delayMillis = i * 45), initialScale = 0.6f),
                exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.8f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null
                    ) { onOpenChange(false); o.onClick() }
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                        Text(
                            o.label,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(o.icon, contentDescription = null, tint = o.tint)
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { onOpenChange(!open) },
            containerColor = if (open) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.primary,
            contentColor = if (open) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(),
        ) {
            // Only the sign turns, so the button keeps its shape.
            Icon(Icons.Rounded.Add, contentDescription = if (open) "Close" else "New", modifier = Modifier.rotate(turn))
        }
    }
}

// ---------------------------------------------------------------- Search

/** The bar at the top of Home. Tapping it opens Search; the 🎤 fills it by voice first. */
@Composable
fun HomeSearchBar(onOpen: (String) -> Unit) {
    Surface(
        onClick = { onOpen("") },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                "Search tasks, people, settings",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp)
            )
            voiceMic("") { onOpen(it) }?.invoke()
        }
    }
}

object SearchUi {
    /** What the search box starts with (from Home's 🎤). */
    val initial = mutableStateOf("")
}

/** What Search looks in. Each can be switched off from Search's own ⚙. */
enum class SearchSource(val key: String, val label: String, val about: String) {
    TASK_TEXT("task", "Task text", "Call tasks, text messages, My tasks and Dossier names"),
    NAMES("names", "Names", "People's names on tasks and recordings"),
    NUMBERS("numbers", "Numbers", "Phone numbers"),
    NOTES("notes", "Notes", "Notes on My tasks, Dossier and recordings"),
    WHY_NOT("why", "Why not done", "Reasons saved in a task's history"),
    RECORDINGS("recordings", "Recordings", "Show recordings in results"),
    SETTINGS("settings", "Settings", "Show settings pages in results"),
}

object SearchPrefs {
    private fun p(c: Context) = c.getSharedPreferences("search", Context.MODE_PRIVATE)
    fun on(c: Context, s: SearchSource) = p(c).getBoolean(s.key, true)
    fun set(c: Context, s: SearchSource, on: Boolean) = p(c).edit().putBoolean(s.key, on).apply()
}

/** A settings page Search can find: its words, and the key MainActivity opens it by. */
class SettingEntry(val title: String, val words: String, val key: String)

val SETTINGS_INDEX = listOf(
    SettingEntry("Weekly off day", "off day friday sunday holiday weekend leave", "off_days"),
    SettingEntry("Setup", "permissions allow phone call log contacts battery overlay", "setup"),
    SettingEntry("After-call popup", "popup outgoing incoming missed whatsapp caller card after call", "popup"),
    SettingEntry("Quick times", "quick times snooze minutes hours tomorrow", "quick_times"),
    SettingEntry("Call recording", "record recording recorder banking switch off", "recording"),
    SettingEntry("Reminder style & ringtone", "reminder style ringtone sound full screen banner ping tone", "reminder"),
    SettingEntry("Text messages", "sms text burst template tracking number reply", "texts"),
    SettingEntry("Review reminder", "review reminder 1 pm 6 pm to review", "review"),
    SettingEntry("Dossier", "dossier lock 3 am separate reminder", "dossier"),
    SettingEntry("Morning summary", "morning summary 8 am day", "morning"),
    SettingEntry("Notifications", "notifications notification silent sound off alerts", "notifications"),
    SettingEntry("Daily list", "daily list sms people send follow-up list", "daily_list"),
    SettingEntry("Backup", "backup restore share folder", "backup"),
    SettingEntry("Voice typing", "voice typing mic microphone urdu english speak", "voice"),
)

private class Hit(val title: String, val sub: String, val done: Boolean, val icon: ImageVector, val open: () -> Unit)

/**
 * Universal search: one box for calls, texts, My tasks, Dossier, people, recordings and settings.
 * Results come under a heading each (top 3, "See all"), pending first and done greyed below.
 * Its ⚙ chooses what it looks in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenCalls: () -> Unit,
    onOpenTexts: () -> Unit,
    onOpenMyTasks: () -> Unit,
    onOpenPerson: (String) -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenSetting: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf(SearchUi.initial.value) }
    LaunchedEffect(Unit) { SearchUi.initial.value = "" }
    var showSettings by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    val recordings by RecordingStore.items.collectAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(200L)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    val on = remember(refresh) { SearchSource.values().associateWith { SearchPrefs.on(ctx, it) } }
    val q = query.trim().lowercase()
    val digits = q.filter { it.isDigit() }
    fun has(text: String) = q.isNotEmpty() && text.lowercase().contains(q)
    fun hasNumber(number: String) = digits.length >= 3 && number.filter { it.isDigit() }.contains(digits)
    fun name(n: String) = on.getValue(SearchSource.NAMES) && has(n)
    fun num(n: String) = on.getValue(SearchSource.NUMBERS) && hasNumber(n)
    fun task(t: String) = on.getValue(SearchSource.TASK_TEXT) && has(t)
    fun note(t: String) = on.getValue(SearchSource.NOTES) && has(t)
    fun why(t: String) = on.getValue(SearchSource.WHY_NOT) && has(t)

    val sections: List<Pair<String, List<Hit>>> = if (q.isEmpty()) emptyList() else listOf(
        "Settings" to if (!on.getValue(SearchSource.SETTINGS)) emptyList() else SETTINGS_INDEX
            .filter { has(it.title) || has(it.words) }
            .map { s -> Hit(s.title, "Settings › ${s.title}", false, Icons.Rounded.Settings) { onOpenSetting(s.key) } },
        "Calls" to calls.filter { r ->
            task(r.note) || name(r.name) || num(r.number) || r.delays.any { why(it.reason) }
        }.map { r ->
            val done = r.done && !r.review
            Hit(r.headline(), listOf(r.title, if (r.done) "done ${Fmt.dueLabel(r.doneAt)}" else Fmt.dueLabel(r.dueAt)).joinToString(" · "), done, Icons.Rounded.Call) {
                CallsUi.showRequest.value = r.id
                onOpenCalls()
            }
        },
        "Texts" to texts.filter { t -> task(t.message) || task(t.code) || name(t.name) || num(t.number) }.map { t ->
            Hit(t.message, listOf(t.title, if (t.done) "done" else Fmt.dueLabel(t.alarmAt)).joinToString(" · "), t.done, Icons.Rounded.Sms) {
                SmsUi.showRequest.value = t.id
                onOpenTexts()
            }
        },
        "My tasks" to myTasks.filter { m -> task(m.title) || note(m.note) || name(m.name) || num(m.number) }.map { m ->
            Hit(
                m.title,
                listOfNotNull(MyTaskStore.listName(m.listId), if (m.timed) Fmt.dueLabel(m.dueAt) else "no time yet", m.personTitle.takeIf { m.hasPerson })
                    .joinToString(" · "),
                m.done,
                Icons.Rounded.TaskAlt
            ) {
                MyTasksUi.showTask.value = m.id
                onOpenMyTasks()
            }
        },
        "Dossier" to dossier.filter { d ->
            task(d.title) || note(d.note) || records.values.any { it.taskId == d.id && why(it.reason) }
        }.map { d ->
            Hit(d.title, "${d.frequency} · ${d.timeLabel}", false, Icons.Rounded.Checklist) {
                MyTasksUi.showDossier.value = d.id
                onOpenMyTasks()
            }
        },
        "People" to Work.people(System.currentTimeMillis(), calls, texts, myTasks, all = true)
            .filter { p -> name(p.name) || num(p.number) }
            .map { p -> Hit(p.title, Work.line(p, System.currentTimeMillis()), p.pending == 0, Icons.Rounded.Person) { onOpenPerson(p.key) } },
        "Recordings" to if (!on.getValue(SearchSource.RECORDINGS)) emptyList() else recordings.filter { r ->
            name(r.name) || name(r.title) || num(r.number) || note(r.note)
        }.sortedByDescending { it.startedAt }.map { r ->
            Hit(r.title.ifBlank { r.displayName }, listOf(Fmt.dueLabel(r.startedAt), r.note.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "), false, Icons.Rounded.Mic) {
                ListFilters.recQuery.value = r.number.ifBlank { r.name }
                onOpenRecordings()
            }
        },
    ).map { (h, list) -> h to (list.filter { !it.done } + list.filter { it.done }) }.filter { it.second.isNotEmpty() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search everything") },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } }
                } else voiceMic(query) { query = it },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "What search looks in") }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (q.isEmpty()) {
                item(key = "hint") {
                    Text(
                        "Type a word, name or number. Search looks in tasks, people, notes, recordings and settings.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else if (sections.isEmpty()) {
                item(key = "none") {
                    Text("Nothing found for “${query.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            sections.forEach { (heading, hits) ->
                item(key = "s-$heading") {
                    val all = heading in expanded
                    Column {
                        Row(Modifier.padding(top = 8.dp, start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(heading.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text("${hits.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TaskGroup {
                            (if (all) hits else hits.take(3)).forEachIndexed { i, h ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp))
                                HitRow(h)
                            }
                            if (hits.size > 3) {
                                HorizontalDivider()
                                TextButton(
                                    onClick = { expanded = if (all) expanded - heading else expanded + heading },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (all) "Show less" else "See all (${hits.size})") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("Search looks in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                SearchSource.values().forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, fontWeight = FontWeight.SemiBold)
                            Text(s.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = on.getValue(s), onCheckedChange = { SearchPrefs.set(ctx, s, it); refresh++ })
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(h: Hit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = h.open).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).background(if (h.done) cs.surfaceVariant else cs.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(h.icon, contentDescription = null, tint = if (h.done) cs.outline else cs.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                h.title,
                fontWeight = FontWeight.SemiBold,
                color = if (h.done) cs.outline else cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (h.sub.isNotBlank()) {
                Text(h.sub, style = MaterialTheme.typography.bodySmall, color = if (h.done) cs.outline else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
