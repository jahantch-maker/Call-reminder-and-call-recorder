package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.rounded.ArrowDropDown
import kotlinx.coroutines.launch

/** What the Follow-ups tab shows: everything together, or (from Filter) only calls or only texts. */
object FollowUpsUi {
    const val ALL = 0
    const val CALLS = 1
    const val TEXTS = 2

    const val SORT_DUE = 0
    const val SORT_CREATED = 1
    const val SORT_PERSON = 2

    /** Another screen asked for a view (e.g. Home's Calls tile). */
    val view = MutableStateFlow<Int?>(null)

    /** Home's Needs you line: only the WAITING ON YOU cards of this kind ([STUCK], [EXTEND], [ASK]), with ✕ Show all. */
    val needKind = MutableStateFlow<Int?>(null)
    const val STUCK = 0
    const val EXTEND = 1
    const val ASK = 2

    /** "Show in Follow-ups ›": this follow-up (its row key) is scrolled to and outlined for a moment. */
    val showEntry = MutableStateFlow<String?>(null)
}

/**
 * The Follow-ups tab: every follow-up once, a call and its texts together, as the same card as My tasks. Open | Done
 * in the box at the top with ★, then "↕ Due time ▾" and "👤 Everyone ▾".
 */
@Composable
fun FollowUpsScreen(resumeKey: Int, onOpenTextSettings: () -> Unit, onOpenToSchedule: () -> Unit) {
    val ctx = LocalContext.current
    var done by rememberSaveable { mutableStateOf(false) }
    var personName by rememberSaveable { mutableStateOf<String?>(null) }
    var personNumbers by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var sort by rememberSaveable { mutableIntStateOf(FollowUpsUi.SORT_DUE) }
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    var reveal by remember { mutableStateOf<String?>(null) }
    var editLink by remember { mutableStateOf<Long?>(null) }
    val asked by FollowUpsUi.view.collectAsState()
    // Show (Calls only / Texts only) is gone: a request for one just opens the list.
    LaunchedEffect(asked) {
        asked ?: return@LaunchedEffect
        FollowUpsUi.view.value = null
    }
    /** Shows this follow-up: the filters that could hide it are cleared, Open or Done as it is, then scrolled to. */
    fun show(e: FuEntry?) {
        e ?: return
        personName = null
        personNumbers = emptyList()
        starredOnly = false
        done = !e.open
        reveal = e.key
    }
    fun everything() = entries(ReminderStore.items.value, SmsStore.items.value)
    // A request for a call or a text (a notification, search, Home) shows it here.
    val callShow by CallsUi.showRequest.collectAsState()
    val callTab by CallsUi.tabRequest.collectAsState()
    val textShow by SmsUi.showRequest.collectAsState()
    val textTab by SmsUi.tabRequest.collectAsState()
    val textEdit by SmsUi.editRequest.collectAsState()
    val entryShow by FollowUpsUi.showEntry.collectAsState()
    LaunchedEffect(callShow) {
        val id = callShow ?: return@LaunchedEffect
        CallsUi.showRequest.value = null
        show(everything().firstOrNull { it.call?.id == id })
    }
    LaunchedEffect(textShow) {
        val id = textShow ?: return@LaunchedEffect
        SmsUi.showRequest.value = null
        show(everything().firstOrNull { it.text?.id == id })
    }
    LaunchedEffect(callTab) {
        val t = callTab ?: return@LaunchedEffect
        CallsUi.tabRequest.value = null
        done = t == CallsUi.DONE
    }
    LaunchedEffect(textTab) {
        val t = textTab ?: return@LaunchedEffect
        SmsUi.tabRequest.value = null
        done = t == 2
    }
    LaunchedEffect(textEdit) {
        val id = textEdit ?: return@LaunchedEffect
        SmsUi.editRequest.value = null
        val t = SmsStore.get(id) ?: return@LaunchedEffect
        done = false
        editLink = FollowUps.ensureLink(ctx, null, t)
    }
    LaunchedEffect(entryShow) {
        val key = entryShow ?: return@LaunchedEffect
        FollowUpsUi.showEntry.value = null
        show(everything().firstOrNull { it.key == key })
    }

    FollowUpsAll(
        resumeKey = resumeKey,
        showDone = done,
        sort = sort,
        starredOnly = starredOnly,
        personName = personName,
        personNumbers = personNumbers,
        onPerson = { p -> personName = p?.name; personNumbers = p?.let { x -> listOf(x.number).filter { it.isNotBlank() } + TeamRepo.numbersOf(x.number) }?.distinct() ?: emptyList() },
        onDoneTab = { done = it },
        onSort = { sort = it },
        onStarred = { starredOnly = it },
        onOpenToSchedule = onOpenToSchedule,
        reveal = reveal,
        onRevealed = { reveal = null },
        editLink = editLink,
        onEditLink = { editLink = it },
    )
}

/** Whose follow-ups the list shows: a team member or anyone you follow up with (by number, else by name). */
internal data class PersonPick(val name: String, val number: String)

/** Open | Done in the box with ★, then one slim row: "↕ Due time ▾" and "👤 Everyone ▾". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpsBar(
    done: Boolean,
    openCount: Int,
    doneCount: Int,
    sort: Int,
    starredOnly: Boolean,
    person: String?,
    onDoneTab: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onStarred: (Boolean) -> Unit,
    onPickPerson: () -> Unit,
    onClearPerson: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val sorts = listOf(FollowUpsUi.SORT_DUE to "Due time", FollowUpsUi.SORT_CREATED to "Created", FollowUpsUi.SORT_PERSON to "Person")
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxTabs(listOf("Open · $openCount", "Done · $doneCount"), if (done) 1 else 0, { onDoneTab(it == 1) }, Modifier.weight(1f))
        StarToggle(starredOnly, onStarred)
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            SmallChip(RowChip("↕ ${sorts.firstOrNull { it.first == sort }?.second ?: "Due time"} ▾", ChipTone.MUTED, onClick = { menu = true }))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuHeading("SORT")
                sorts.forEach { (v, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = if (sort == v) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                        onClick = { menu = false; onSort(v) }
                    )
                }
            }
        }
        if (person == null) SmallChip(RowChip("👤 Everyone ▾", ChipTone.MUTED, onClick = onPickPerson))
        else SmallChip(RowChip("👤 ${Names.first(person) ?: person}  ✕", ChipTone.BLUE, onClick = onClearPerson))
    }
}

/** "Show tasks for": search, TEAM first, then OTHERS, each with their open follow-ups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonFilterSheet(entries: List<FuEntry>, onPick: (PersonPick) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val members by TeamRepo.members.collectAsState()
    var q by remember { mutableStateOf("") }
    val open = entries.filter { it.open && !it.hidden }
    val team = remember(members) { if (TeamRepo.activeNow) members.filter { it.uid != TeamRepo.uid && it.phone.isNotBlank() } else emptyList() }
    val teamPicks = team.map { PersonPick(it.name, it.phone) to it.numbers }
    val others = entries.map { PersonPick(it.person, it.number) }
        .distinctBy { it.number.filter(Char::isDigit).takeLast(9).ifBlank { "n:" + it.name.lowercase() } }
        .filter { p -> teamPicks.none { (_, nums) -> p.number.isNotBlank() && nums.any { SmsStore.sameNumber(it, p.number) } } }
        .sortedBy { it.name.lowercase() }
    fun count(nums: List<String>, name: String) = open.count { e -> matches(e, nums, name) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp + navBarMissing()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Show tasks for", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(q, { q = it }, placeholder = { Text("Search people") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            @Composable
            fun Line(p: PersonPick, n: Int) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(p) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.name.ifBlank { p.number }, size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(p.name.ifBlank { p.number }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (n > 0) Text("$n open", fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.Bold)
                }
            }
            fun hit(p: PersonPick) = q.isBlank() || p.name.contains(q, ignoreCase = true) || p.number.contains(q.trim())
            val t = teamPicks.filter { hit(it.first) }
            if (t.isNotEmpty()) {
                ChipHeading("TEAM", modifier = Modifier.padding(top = 6.dp))
                t.forEach { (p, nums) -> Line(p, count(nums, p.name)) }
            }
            val o = others.filter { hit(it) }
            if (o.isNotEmpty()) {
                ChipHeading("OTHERS", modifier = Modifier.padding(top = 6.dp))
                o.forEach { p -> Line(p, count(listOf(p.number).filter { it.isNotBlank() }, p.name)) }
            }
            if (t.isEmpty() && o.isEmpty()) Text("Nobody found", color = cs.onSurfaceVariant)
        }
    }
}

/** This follow-up is with this person: any of their numbers, else the same name when there is no number. */
internal fun matches(e: FuEntry, numbers: List<String>, name: String): Boolean =
    if (numbers.isNotEmpty()) e.number.isNotBlank() && numbers.any { SmsStore.sameNumber(it, e.number) }
    else e.number.isBlank() && e.person.equals(name, ignoreCase = true)

@Composable
private fun MenuHeading(text: String) =
    ChipHeading(text.uppercase(), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))


/** One follow-up in the list: a call, its texts, or both. */
internal class FuEntry(val call: Reminder?, val text: SmsReminder?) {
    val key: String = when {
        call?.linkId?.let { it != 0L } == true -> "link-${call.linkId}"
        text?.linkId?.let { it != 0L } == true -> "link-${text.linkId}"
        call != null -> "call-${call.id}"
        else -> "text-${text!!.id}"
    }
    val callPending: Boolean get() = call != null && !call.done
    val textActive: Boolean get() = text != null && text.active
    val callReview: Boolean get() = call != null && call.done && call.review
    val textReview: Boolean get() = text != null && !text.done && text.review != null
    val inReview: Boolean get() = callReview || textReview
    val open: Boolean get() = callPending || textActive || callReview || textReview
    val hidden: Boolean
        get() = !callPending && !textActive &&
            (!callReview || call!!.reviewLaterUntil > System.currentTimeMillis()) &&
            (!textReview || text!!.reviewLaterUntil > System.currentTimeMillis()) && open

    val name: String get() = call?.name ?: text!!.name
    val number: String get() = call?.number ?: text!!.number
    val person: String get() = call?.title ?: text!!.title
    val createdAt: Long get() = minOf(call?.createdAt ?: Long.MAX_VALUE, text?.createdAt ?: Long.MAX_VALUE)
    val finishedAt: Long get() = maxOf(call?.doneAt ?: 0L, text?.doneAt ?: 0L)
    val moved: Boolean get() = FollowUps.wasMoved(call, text)

    /** When it sorts: the call's due time, else the next text. */
    val at: Long
        get() = when {
            callPending -> call!!.dueAt
            textActive -> text!!.alarmAt
            textReview -> text!!.reviewAt
            else -> call?.doneAt ?: text?.doneAt ?: 0L
        }

    val title: String
        get() = when {
            call != null && call.note.isNotBlank() -> call.note
            text != null -> text.message
            else -> call!!.headline()
        }
}

internal fun entries(calls: List<Reminder>, texts: List<SmsReminder>): List<FuEntry> {
    val out = mutableListOf<FuEntry>()
    val links = (calls.map { it.linkId } + texts.map { it.linkId }).filter { it != 0L }.toSet()
    links.forEach { link ->
        val c = calls.filter { it.linkId == link }.let { l -> l.firstOrNull { !it.done || it.review } ?: l.maxByOrNull { it.id } }
        val t = texts.filter { it.linkId == link }.let { l -> l.firstOrNull { !it.done } ?: l.maxByOrNull { it.id } }
        out += FuEntry(c, t)
    }
    calls.filter { it.linkId == 0L }.forEach { out += FuEntry(it, null) }
    texts.filter { it.linkId == 0L }.forEach { out += FuEntry(null, it) }
    return out
}

@Composable
private fun FollowUpsAll(
    resumeKey: Int,
    showDone: Boolean,
    sort: Int,
    starredOnly: Boolean,
    personName: String?,
    personNumbers: List<String>,
    onPerson: (PersonPick?) -> Unit,
    onDoneTab: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onStarred: (Boolean) -> Unit,
    onOpenToSchedule: () -> Unit,
    reveal: String? = null,
    onRevealed: () -> Unit = {},
    editLink: Long?,
    onEditLink: (Long?) -> Unit,
) {
    val ctx = LocalContext.current
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val toSchedule by ToScheduleStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val stars by FuStars.keys.collectAsState()
    LaunchedEffect(Unit) { FuStars.load(ctx) }
    var showNew by remember { mutableStateOf(false) }
    var pickPerson by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val fu = rememberFuController(snack)
    // A text notification's "Not done, text again" opens its follow-up in the editor.
    LaunchedEffect(editLink) {
        val link = editLink ?: return@LaunchedEffect
        onEditLink(null)
        fu.editLink = link
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }

    fun starred(e: FuEntry): Boolean = e.call?.starred ?: (e.key in stars)
    val every = entries(calls, texts)
    val shown = every.filter {
        (!starredOnly || starred(it)) && (personName == null || matches(it, personNumbers, personName))
    }
    val all = shown.filter { it.open && !it.hidden }
    // WAITING ON YOU: tasks you gave where he is stuck, asks to extend or asked a question, pinned on top until answered.
    val teamTasks by TeamRepo.tasks.collectAsState()
    val needKind by FollowUpsUi.needKind.collectAsState()
    val kinds = remember(teamTasks, calls, texts) {
        if (!TeamRepo.activeNow) emptyMap() else every.mapNotNull { e ->
            val t = TeamUi.taskOfLink(ctx, e.call?.linkId?.takeIf { it != 0L } ?: e.text?.linkId ?: 0L, teamTasks)
                ?.takeIf { it.fromUid == TeamRepo.uid } ?: return@mapNotNull null
            when {
                t.open && t.stuck -> FollowUpsUi.STUCK
                t.asking -> FollowUpsUi.EXTEND
                t.open && t.asks -> FollowUpsUi.ASK
                else -> null
            }?.let { e.key to it }
        }.toMap()
    }
    val pinned = all.filter { it.key in kinds && (needKind == null || kinds[it.key] == needKind) }
        .sortedWith(compareBy({ kinds[it.key] }, { it.at }))
    val rest = if (needKind != null) emptyList() else all.filter { it.key !in kinds }
    val review = rest.filter { it.inReview }.sortedByDescending { it.at }
    val open = rest.filter { !it.inReview }.sortedBy { it.at }
    val overdue = open.filter { it.callPending && it.at <= now }
    val ahead = open.filter { !(it.callPending && it.at <= now) }
    val finished = shown.filter { !it.open }.sortedByDescending { it.finishedAt }
    val waiting = toSchedule.size
    val today = LocalDate.now(ZoneId.systemDefault())
    val cs = MaterialTheme.colorScheme

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Follow-up") },
                containerColor = cs.primary,
                contentColor = cs.onPrimary,
            )
        }
    ) { padding ->
        PullRefresh(Modifier.fillMaxSize()) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        var outlined by remember { mutableStateOf<String?>(null) }
        // Cleared only at the end: clearing it first restarted this effect and cancelled the scroll.
        LaunchedEffect(reveal) {
            val key = reveal ?: return@LaunchedEffect
            outlined = key
            listState.revealKey(key)
            kotlinx.coroutines.delay(3_000L)
            if (outlined == key) outlined = null
            onRevealed()
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
        ) {
            item(key = "header") {
                PageTitleRow(
                    "Follow-ups",
                    when {
                        overdue.isNotEmpty() && review.isNotEmpty() -> "${overdue.size} overdue · ${review.size} to review"
                        overdue.isNotEmpty() -> "${overdue.size} overdue"
                        review.isNotEmpty() -> "${review.size} to review"
                        open.isEmpty() -> "All caught up"
                        else -> "${open.size} open"
                    },
                    red = overdue.isNotEmpty() || review.isNotEmpty(),
                    noTime = waiting,
                    onNoTime = onOpenToSchedule,
                )
            }
            item(key = "switch") {
                FollowUpsBar(
                    done = showDone,
                    openCount = all.size,
                    doneCount = finished.size,
                    sort = sort,
                    starredOnly = starredOnly,
                    person = personName,
                    onDoneTab = onDoneTab,
                    onSort = onSort,
                    onStarred = onStarred,
                    onPickPerson = { pickPerson = true },
                    onClearPerson = { onPerson(null) },
                )
            }

            fun block(key: String, title: String, count: String, color: Color, list: List<FuEntry>) {
                if (list.isEmpty()) return
                item(key = "group-$key") { Box(Modifier.animateItem()) { DayHeader(title, count, color) } }
                itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                    Box(Modifier.animateItem()) { fu.Card(e, rowPos(i, list.size), now, starred(e), highlighted = outlined == e.key) }
                }
            }

            if (showDone) {
                if (finished.isEmpty()) {
                    item(key = "empty-done") {
                        EmptyPicture(Icons.Rounded.DoneAll, "No finished follow-ups yet", "Finished follow-ups show here, newest first.")
                    }
                }
                byDay(finished) { it.finishedAt }.forEach { (day, list) ->
                    block("done-$day", RowFmt.dayTitle(day), "${list.size} done", cs.onSurfaceVariant, list)
                }
            } else {
                if (needKind != null) {
                    item(key = "need-kind") {
                        Box(Modifier.padding(bottom = 6.dp)) {
                            SmallChip(RowChip("✕ Show all", ChipTone.BLUE, onClick = { FollowUpsUi.needKind.value = null }))
                        }
                    }
                }
                block("waiting", "WAITING ON YOU", "${pinned.size}", cs.error, pinned)
                if (all.isEmpty()) {
                    item(key = "empty") {
                        EmptyPicture(
                            Icons.Rounded.PhoneCallback,
                            if (starredOnly || personName != null) "Nothing here" else "Nothing to follow up",
                            "Remind yourself to call someone, text them, or both.",
                            button = "＋ Follow-up",
                            onButton = { showNew = true }
                        )
                    }
                }
                when (sort) {
                    FollowUpsUi.SORT_CREATED -> {
                        block("review", "Waiting for you", "${review.size}", cs.error, review)
                        block("created", "Newest first", "${open.size}", cs.primary, open.sortedByDescending { it.createdAt })
                    }
                    FollowUpsUi.SORT_PERSON -> {
                        block("review", "Waiting for you", "${review.size}", cs.error, review)
                        open.groupBy { it.person }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (p, list) ->
                            block("person-$p", p, "${list.size}", cs.primary, list.sortedBy { it.at })
                        }
                    }
                    else -> {
                        block("review", "Waiting for you", "${review.size}", cs.error, review)
                        block("overdue", "Overdue", "${overdue.size}", cs.error, overdue)
                        byDay(ahead) { it.at }.forEach { (day, list) ->
                            block("day-$day", RowFmt.dayTitle(day), if (day == today) "${list.size} left" else "${list.size}",
                                if (day == today) cs.primary else cs.onSurfaceVariant, list)
                        }
                    }
                }
            }
        }
        SlimTitleBar(listState, "Follow-ups", padding.calculateTopPadding(), Modifier.align(Alignment.TopCenter))
        }
    }

    if (showNew) FollowUpDialog(onDismiss = { showNew = false })
    if (pickPerson) PersonFilterSheet(every, onPick = { pickPerson = false; onPerson(it) }, onDismiss = { pickPerson = false })
    fu.Sheets()
}

/** The time picker in a sheet for a follow-up waiting with no time: one tap sets it. */
@Composable
fun SetTimeSheet(heading: String, about: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    SnoozeSheet(
        heading = heading,
        about = about,
        onPick = onPick,
        onPickTime = { custom = true },
        onDismiss = onDismiss,
    )
    if (custom) {
        DateTimePickerFlow(
            initialMs = QuickTime.OnDay(1, 10).toOption().whenMs,
            onPicked = { custom = false; onPick(it) },
            onDismiss = { custom = false }
        )
    }
}

/** "Follow-ups" with a status line and the Recordings / Settings icons. */
@Composable
fun FollowUpsHeader(status: String, red: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Follow-ups", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            DateStatusLine(status, red)
        }
        TopActions()
    }
}

/**
 * A follow-up as the My tasks card: tick = Done, the task in bold, the time box with 📞 💬 inside (red when late),
 * the person, then 📞 WA and ★. Tapping opens it in place: the details, 🕘 New time and the links.
 */
@Composable
internal fun FollowUpCard(
    e: FuEntry,
    pos: RowPos,
    now: Long,
    ticking: Boolean,
    starred: Boolean,
    expanded: Boolean,
    highlighted: Boolean,
    onToggle: () -> Unit,
    onTick: () -> Unit,
    onStar: () -> Unit,
    onCall: () -> Unit,
    onTexts: (SmsReminder) -> Unit,
    onNewTime: () -> Unit,
    onReviewLater: () -> Unit,
    onEdit: () -> Unit,
    onRemoveTime: () -> Unit,
    onMove: () -> Unit,
    onTimeline: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val call = e.call
    val text = e.text
    val overdue = e.callPending && call!!.dueAt <= now
    val finished = !e.open
    val hasNumber = e.number.isNotBlank()
    // A task you gave a team member (or passed down): its status chip, and Received, thanks / Not done yet.
    val teamTasks by TeamRepo.tasks.collectAsState()
    val teamV by TeamSync.version.collectAsState()
    val teamLink = call?.linkId?.takeIf { it != 0L } ?: text?.linkId ?: 0L
    val teamTask = remember(teamLink, teamTasks, teamV) { if (TeamRepo.activeNow) TeamUi.taskOfLink(ctx, teamLink, teamTasks) else null }
    val giver = teamTask != null && teamTask.fromUid == TeamRepo.uid
    // Passed down to you (his side): the task is the giver's, so only its Timeline.
    val passed = teamTask != null && teamTask.toUid == TeamRepo.uid
    val teamBanner = remember(teamTask, teamV) { teamTask?.let { TeamUi.banner(ctx, it, TeamRepo.uid) } }
    // Deleting a task you gave asks "Delete for you and Basharat?" (in FuController, the same for every delete).
    val delete: () -> Unit = onDelete

    val chips = buildList {
        if (finished) {
            when {
                e.moved -> add(RowChip("Moved to My tasks", ChipTone.BLUE, Icons.Rounded.CheckBox))
                text?.review == SmsReminder.Review.REPLIED || text?.log?.any { it.status == SmsLogEntry.Status.REPLIED_DONE } == true ->
                    add(RowChip("replied", ChipTone.GREEN, Icons.Rounded.Check))
            }
        } else if (text != null) {
            when {
                text.review == SmsReminder.Review.REPLIED -> add(RowChip("replied", ChipTone.GREEN, Icons.Rounded.Check))
                text.review == SmsReminder.Review.NOT_SENT -> add(RowChip("Text not sent", ChipTone.RED))
                text.review != null && !text.done -> add(RowChip(if (text.tracked) "No reply" else "Texts sent", if (text.tracked) ChipTone.ORANGE else ChipTone.MUTED))
            }
        }
        teamTask?.let { addAll(if (it.toUid == TeamRepo.uid) TeamUi.receiverChips(it) else TeamUi.giverChips(it)) }
        // One chip per meaning: a team task already says "✅ Done, check it".
        if (e.callReview && teamTask == null) add(RowChip(if (call!!.note.isBlank()) "No task written" else "✅ Done, check it", ChipTone.GREEN))
        // Snoozed: moved on by hand plus held on in review, one count.
        if (!finished) {
            val delays = call?.delays?.size ?: 0
            val held = call?.let { Held.countCall(ctx, it.id) } ?: text?.let { Held.countText(ctx, it.id) } ?: 0
            val snoozed = delays + held
            if (delays > 0 || held >= Held.SHOW_FROM) add(RowChip("💤 Snoozed ${snoozed}×", ChipTone.ORANGE, Icons.Rounded.History, onTimeline))
        }
    }

    val time = when {
        finished -> Fmt.dueLabel(e.finishedAt)
        else -> Fmt.dueLabel(e.at)
    }
    val icons = when {
        call != null && text != null -> TimeIcons.BOTH
        call != null -> TimeIcons.CALL
        else -> TimeIcons.TEXT
    }
    val timelineCount = (call?.historyLines()?.size ?: 0) + (text?.log?.size ?: 0)
    val timelineLabel = if (timelineCount > 0) "Timeline ($timelineCount)" else "Timeline"

    val details = buildList {
        val parts = mutableListOf<String>()
        if (call != null) parts += "📞 Call " + Fmt.dueLabel(if (call.done) call.doneAt else call.dueAt) + if (call.done) " (done)" else ""
        if (text != null) {
            val times = when {
                !e.textActive -> if (text.done) "finished" else "all sent"
                text.groupCount > 1 -> text.baseTimes.filter { it >= text.nextTextAt - text.groupGapMin * 60_000L * text.groupCount }
                    .ifEmpty { listOf(text.nextTextAt) }.joinToString(", ") { Fmt.timeOnly(it) } + " · burst ${text.groupCount}"
                text.slots.isNotEmpty() -> (text.burstIndex until text.burstCount).joinToString(", ") { Fmt.timeOnly(text.textAt(it)) }
                text.isBurst && text.burstIndex > 0 -> "${text.burstIndex} of ${text.burstCount} sent"
                text.isBurst -> text.burstLabel ?: Fmt.timeOnly(text.alarmAt)
                else -> Fmt.dueLabel(text.alarmAt)
            }
            parts += "💬 Texts $times"
        }
        add(parts.joinToString(" · "))
        val more = listOfNotNull(
            (call?.code?.takeIf { it.isNotEmpty() } ?: text?.takeIf { it.tracked }?.code?.takeIf { it.isNotEmpty() })?.let { "# Task no. $it" },
            call?.repeatLabel?.takeIf { e.callPending },
            "Skip my call if ${Names.first(e.person) ?: "they"} replies".takeIf { !finished && call != null && call.autopilot && text != null },
        )
        if (more.isNotEmpty()) add(more.joinToString(" · "))
        if (e.name.isNotBlank() && hasNumber) add("${e.person} · ${e.number}")
    }

    TaskCard(
        title = e.title,
        done = finished || ticking,
        onTick = onTick,
        tickEnabled = !(finished && e.moved),
        time = time,
        late = overdue,
        timeIcons = icons,
        person = Names.first(e.person) ?: e.person,
        chips = chips,
        starred = starred,
        onStar = onStar,
        onCall = if (hasNumber && call != null) onCall else null,
        onWhatsApp = if (hasNumber) ({
            if (call != null) WhatsApp.forCall(ctx, call) else if (text != null) WhatsApp.forText(ctx, text)
        }) else null,
        expanded = expanded,
        onClick = onToggle,
        pos = pos,
        highlighted = highlighted,
        corner = teamTask?.takeIf { giver }?.let { tt -> { TeamTicks(tt) } },
        banner = teamBanner,
        urgent = teamTask?.let { it.urgent && it.open } == true,
    ) {
        teamTask?.let { TeamBriefStrip(it) }
        // A team task's Timeline is its conversation.
        val timeline: () -> Unit = teamTask?.let { tt -> { TeamUi.openTimeline(tt.id) } } ?: onTimeline
        val tlLabel = teamTask?.let { TeamUi.timelineLabel(it) } ?: timelineLabel
        // His Done waits for your check: ✓ Received, thanks · ↩ Not done yet, the same on every card.
        val check = teamTask?.takeIf { giver && it.doneCheck && !finished }
        var notYet by remember(check?.id) { mutableStateOf(false) }
        if (notYet && check != null) TeamNotDoneSheet(check) { notYet = false }
        CardPanel(
            details = details + listOfNotNull(check?.takeIf { it.doneAt > 0L }?.let { t ->
                "${t.toFirst}: Done ${Fmt.dueLabel(t.doneAt)}" + if (t.passedTo.isNotBlank()) " · by ${t.passedTo}" else ""
            }),
            onNewTime = if (finished) null else onNewTime,
            onNotDone = check?.let { { notYet = true } },
            // The same order on every card: Timeline · Edit · (Remove time · Move to … · Texts) · Delete.
            links = buildList {
                add(RowLink(tlLabel, onClick = timeline))
                if (passed) return@buildList
                if (finished) {
                    add(RowLink("Delete", danger = true, onClick = delete))
                    return@buildList
                }
                add(RowLink("Edit", onClick = onEdit))
                if (e.inReview) add(RowLink("Review later", onClick = onReviewLater))
                if (!e.inReview && teamTask == null) add(RowLink("Remove time", onClick = onRemoveTime))
                if (teamTask == null) add(RowLink("Move to My tasks", onClick = onMove))
                if (text != null) add(RowLink("Texts", onClick = { onTexts(text) }))
                add(RowLink("Delete", danger = true, onClick = delete))
            }
        )
    }
}


/** 💬 on a row: what was sent, what is coming (with its burst), replies, and Send next now / Change times / Stop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextsSheet(
    t: SmsReminder,
    onDismiss: () -> Unit,
    onSendNow: (SmsReminder) -> Unit,
    onChange: (SmsReminder) -> Unit,
    onStop: (SmsReminder) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp + navBarMissing()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Sms, contentDescription = null, tint = cs.secondary)
                Spacer(Modifier.width(8.dp))
                Text("Texts to ${t.title}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                listOfNotNull(
                    t.message.take(60),
                    t.code.takeIf { it.isNotEmpty() }?.let { "# $it" },
                    if (t.tracked) "Tracking on" else "Tracking off",
                    t.repeatLabel,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val sent = t.log.filter {
                !it.fromCall && it.status in setOf(SmsLogEntry.Status.SENT, SmsLogEntry.Status.DELIVERED, SmsLogEntry.Status.FAILED, SmsLogEntry.Status.SKIPPED)
            }.takeLast(6).reversed()
            SheetHeading("SENT")
            if (sent.isEmpty()) SheetLine("Nothing sent yet") else sent.forEach { l ->
                SheetLine("✓ ${Fmt.dueLabel(l.at)} · ${l.status.label}" + if (l.detail.isNotBlank()) " · ${l.detail}" else "")
            }

            SheetHeading("COMING")
            if (!t.active) {
                SheetLine(if (t.done) "Finished" else "All texts sent")
            } else {
                val burst = t.eachBurst
                val base = t.baseTimes.filter { it >= t.textAt(t.burstIndex) - (burst?.let { it.count * it.gapMin * 60_000L } ?: 0L) }
                base.ifEmpty { listOf(t.nextTextAt) }.forEach { at ->
                    SheetLine("🕘 ${Fmt.dueLabel(at)}" + (burst?.let { " · Burst: ${it.label}" } ?: ""))
                }
            }

            SheetHeading("REPLIES")
            val replied = t.log.lastOrNull { it.status == SmsLogEntry.Status.REPLIED_DONE }
            SheetLine(replied?.let { "Replied “${t.doneReply}” · ${Fmt.dueLabel(it.at)}" } ?: "No reply yet")

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { onSendNow(t) },
                    enabled = t.active,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Send now", maxLines = 1)
                }
                OutlinedButton(onClick = { onChange(t) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("🕘 New time", maxLines = 1)
                }
                OutlinedButton(onClick = { onStop(t) }, enabled = !t.done, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) = ChipHeading(text.uppercase(), modifier = Modifier.padding(top = 10.dp))

@Composable
private fun SheetLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

// ---------------------------------------------------------------- Projects, and the one-time card

/** The Projects tab: a placeholder until projects are designed. */
@Composable
fun ProjectsScreen() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).padding(horizontal = 16.dp)) {
        PageTitleRow("Projects", "Coming soon", red = false)
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Projects", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(10.dp), color = Tones.amber.bg) {
                Text(
                    "Coming soon",
                    color = Tones.amber.fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Manage projects running now or in plan.\nEach task will get a Project field.",
                textAlign = TextAlign.Center,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/** Shown once after this update: what moved where. */
@Composable
fun WhatMovedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What moved where") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🔔  Calls and Texts are now one tab: Follow-ups. All your old items are there, task numbers kept. Use Open · Done and the Filter at the top.")
                Text("📞💬  One follow-up can remind you to call and send texts, at different times.")
                Text("⚡  Autopilot (off by default): a “Done” reply cancels your call.")
                Text("🕘  New time moves the texts too, and updates the due time in the message.")
                Text("👥  People tab: everyone you follow up with. 📁 Projects is coming soon.")
                Text("🎙 ⚙  Recordings and Settings stay at the top right.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

/** The switch at the top of New time for a follow-up whose texts are still going: "Texts move too". */
@Composable
fun TextsMoveSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Texts move too", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Same clock times on the new day, with the new due time in the message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
