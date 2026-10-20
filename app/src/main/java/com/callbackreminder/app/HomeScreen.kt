package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Home tab: today's counts, filters (person, type, due, created), overdue work by age, people with
 * pending work, and one time-ordered "Next up" list of calls, texts, My tasks and dossier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    resumeKey: Int,
    onOpenCalls: () -> Unit,
    onOpenTexts: (tab: Int, showId: Long?) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenRecordingSettings: () -> Unit,
    onOpenToSchedule: () -> Unit,
    onOpenMyTasks: (sub: Int) -> Unit,
    onOpenDossierReview: () -> Unit,
    onOpenReview: () -> Unit = {},
    onOpenSession: () -> Unit = {},
    onOpenPeople: () -> Unit,
    onOpenPerson: (key: String) -> Unit,
    onOpenItem: (WorkItem) -> Unit,
    onOpenSearch: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val toSchedule by ToScheduleStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    val status = remember(resumeKey) { SetupStatus.read(ctx) }
    var dialOpen by remember { mutableStateOf(false) }
    var newCall by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf(false) }

    var personFilter by ListFilters.homePerson
    var typeFilter by ListFilters.homeType
    var createdFilter by ListFilters.homeCreated
    var dueFilter by ListFilters.homeDue
    var ageFilter by ListFilters.homeAge
    val filtering = personFilter != null || typeFilter != null || createdFilter.active || dueFilter.active || ageFilter != null

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val callsDue = calls.filter { !it.done && it.dueAt < endOfToday }
    val textsToday = texts.filter { it.active && it.alarmAt < endOfToday }
    val textsToReview = texts.count { !it.done && it.review != null }
    val callsToReview = calls.count { it.done && it.review }
    val dossierDay = Dossier.openDay(now)
    val dossierToday = dossier.filter { it.isOn(dossierDay) }
    val dossierDone = dossierToday.count { records[DossierStore.key(it.id, dossierDay)]?.done == true }
    val dossierLeft = dossierToday.count {
        records[DossierStore.key(it.id, dossierDay)]?.done != true && it.dueAt(dossierDay) <= now
    }
    val needsTime = myTasks.count { !it.done && !it.timed }
    val toReview = textsToReview + callsToReview + dossierLeft + needsTime
    val myTasksToday = myTasks.count { !it.done && it.timed && it.dueAt < endOfToday }

    val open = Work.open(now, calls, texts, myTasks, dossier, records)
    val overdue = open.filter { it.overdue }
    val ageCounts = (0..3).map { b -> overdue.count { Work.ageBucket(it, now) == b } }
    val people = Work.people(now, calls, texts, myTasks)
        .sortedWith(compareByDescending<PersonSummary> { it.overdue }.thenByDescending { it.pending })

    val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val doneToday = calls.count { it.done && it.doneAt >= startOfToday } +
        texts.count { it.done && it.doneAt >= startOfToday } +
        myTasks.count { it.done && it.doneAt >= startOfToday }
    val dueTodayOpen = open.count { it.at < endOfToday }
    val monthScore = remember(dossier, records, dossierDay) {
        dossier.map { DossierStore.monthScore(it, now) }.fold(0 to 0) { acc, p -> (acc.first + p.first) to (acc.second + p.second) }
    }

    val nextUp = open.filter { !it.inReview && it.at < endOfToday }.sortedBy { it.at }
    val filtered = if (!filtering) emptyList() else open.filter { w ->
        (personFilter == null || w.personKey == personFilter) &&
            (typeFilter == null || w.kind == typeFilter) &&
            createdFilter.matches(w.createdAt) &&
            dueFilter.matches(w.at) &&
            (ageFilter == null || (w.overdue && Work.ageBucket(w, now) == ageFilter))
    }.sortedBy { it.at }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            greeting(Instant.ofEpochMilli(now).atZone(zone).toLocalTime()),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TopActions()
                }
            }

            item(key = "search") { HomeSearchBar(onOpen = onOpenSearch) }

            // The Review card replaces the old To review tile.
            item(key = "review") {
                ReviewHomeCard(
                    resumeKey = resumeKey,
                    onStart = {
                        if (ReviewSession.start(ctx, ReviewSession.MODE_ALL)) onOpenSession()
                        else onOpenReview()
                    },
                    onOpen = onOpenReview,
                    onResume = onOpenSession,
                )
            }

            setupBanners(status, ctx, onOpenSetup, onOpenRecordingSettings)

            item(key = "widgets_tip") { WidgetsTip() }

            item(key = "rings") {
                TodayRings(
                    doneToday = doneToday,
                    dueToday = doneToday + dueTodayOpen,
                    dossierDone = dossierDone,
                    dossierTotal = dossierToday.size,
                    monthDone = monthScore.first,
                    monthTotal = monthScore.second,
                    onDone = { onOpenMyTasks(MyTasksUi.ONE_TIME) },
                    onDossier = { onOpenMyTasks(MyTasksUi.DOSSIER) },
                )
            }

            item(key = "tiles") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallTile(Icons.Rounded.PhoneCallback, "${callsDue.size}", "Calls due", Tones.red, Modifier.weight(1f), onOpenCalls)
                    SmallTile(Icons.Rounded.Sms, "${textsToday.size}", "Texts", Tones.blue, Modifier.weight(1f)) { onOpenTexts(0, null) }
                    SmallTile(Icons.Rounded.TaskAlt, "$myTasksToday", "My tasks", Tones.purple, Modifier.weight(1f)) { onOpenMyTasks(MyTasksUi.ONE_TIME) }
                    SmallTile(Icons.Rounded.EditCalendar, "${toSchedule.size + needsTime}", "Schedule", Tones.amber, Modifier.weight(1f), onOpenToSchedule)
                }
            }

            item(key = "filters") {
                HomeFilterRow(
                    people = Work.people(now, calls, texts, myTasks).sortedBy { it.title.lowercase() },
                    person = personFilter,
                    type = typeFilter,
                    created = createdFilter,
                    due = dueFilter,
                    age = ageFilter,
                    onPerson = { personFilter = it },
                    onType = { typeFilter = it },
                    onCreated = { createdFilter = it },
                    onDue = { dueFilter = it },
                    onClear = {
                        personFilter = null
                        typeFilter = null
                        createdFilter = DateFilter()
                        dueFilter = DateFilter()
                        ageFilter = null
                    },
                )
            }

            if (filtering) {
                item(key = "filtered-title") {
                    val who = personFilter?.let { k -> people.firstOrNull { it.key == k }?.title }
                    Text(
                        buildString {
                            append("${filtered.size} ${if (filtered.size == 1) "task" else "tasks"}")
                            if (who != null) append(" for ").append(who)
                            ageFilter?.let { append(" · overdue ").append(Work.AGE_LABELS[it]) }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "filtered-empty") {
                        Text(
                            "Nothing matches these filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp)
                        )
                    }
                }
                if (filtered.isNotEmpty()) item(key = "filtered-list") { WorkBlock(filtered, now, showPerson = true, onOpen = onOpenItem) }
                return@LazyColumn
            }

            if (overdue.isNotEmpty()) {
                item(key = "age") {
                    OverdueChart(ageCounts) { ageFilter = it }
                }
            }

            if (people.isNotEmpty()) {
                item(key = "people") {
                    PeopleBlock(people.take(4), now, onSeeAll = onOpenPeople, onOpen = onOpenPerson)
                }
            }

            item(key = "next-title") {
                Text(
                    "Next up",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            if (nextUp.isEmpty()) {
                item(key = "next-empty") {
                    EmptyPicture(Icons.Rounded.EventAvailable, "Nothing left for today", "Tap ＋ to add a call-back or a text message.")
                }
            }
            if (nextUp.isNotEmpty()) item(key = "next-list") { WorkBlock(nextUp, now, showPerson = true, onOpen = onOpenItem) }
        }
    }

    // Google Keep style ＋: from the bottom up My task, Follow-up.
    SpeedDial(
        open = dialOpen,
        onOpenChange = { dialOpen = it },
        options = listOf(
            DialOption("My task", Icons.Rounded.TaskAlt, Tones.purple.fg) { newTask = true },
            DialOption("Follow-up", Icons.Rounded.Call, MaterialTheme.colorScheme.primary) { newCall = true },
        ),
    )
    }

    if (newCall) {
        FollowUpDialog(onDismiss = { newCall = false })
    }

    if (newTask) {
        MyTaskDialog(
            existing = null,
            onDismiss = { newTask = false },
            onSwitchKind = { newTask = false; onOpenMyTasks(MyTasksUi.DOSSIER) },
        )
    }
}

/** Person, Type, Due and Created chips, plus Clear when any is set. */
@Composable
private fun HomeFilterRow(
    people: List<PersonSummary>,
    person: String?,
    type: WorkItem.Kind?,
    created: DateFilter,
    due: DateFilter,
    age: Int?,
    onPerson: (String?) -> Unit,
    onType: (WorkItem.Kind?) -> Unit,
    onCreated: (DateFilter) -> Unit,
    onDue: (DateFilter) -> Unit,
    onClear: () -> Unit,
) {
    var personMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            FilterChip(
                selected = person != null,
                onClick = { personMenu = true },
                label = {
                    Text(person?.let { k -> people.firstOrNull { it.key == k }?.title ?: "Person" } ?: "Person")
                },
                leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            DropdownMenu(expanded = personMenu, onDismissRequest = { personMenu = false }) {
                DropdownMenuItem(text = { Text("Everyone") }, onClick = { personMenu = false; onPerson(null) })
                if (people.isEmpty()) {
                    DropdownMenuItem(text = { Text("No one has pending work") }, onClick = { personMenu = false }, enabled = false)
                }
                people.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.title} · ${p.pending}") },
                        onClick = { personMenu = false; onPerson(p.key) }
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = type != null,
                onClick = { typeMenu = true },
                label = { Text(type?.label ?: "Type") },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                DropdownMenuItem(text = { Text("All types") }, onClick = { typeMenu = false; onType(null) })
                WorkItem.Kind.values().forEach { k ->
                    DropdownMenuItem(text = { Text(k.label) }, onClick = { typeMenu = false; onType(k) })
                }
            }
        }
        DateFilterChip("Due", due, ListFilters.DUE_PRESETS, onDue)
        DateFilterChip("Created", created, ListFilters.CREATED_PRESETS, onCreated)
        if (person != null || type != null || created.active || due.active || age != null) {
            ClearChip(onClick = onClear)
        }
    }
}

/** "Overdue by age": four bars (1 day, 2-3, 4-7, 7+ days). Tapping a bar lists those tasks. */
@Composable
private fun OverdueChart(counts: List<Int>, onBar: (Int) -> Unit) {
    val colors = if (LocalDarkTheme.current) listOf(Color(0xFFF5C76A), Color(0xFFF29A55), Color(0xFFEF5B6A), Color(0xFFC2394A))
    else listOf(Color(0xFFF0B850), Color(0xFFEE8A3C), Color(0xFFD93A4A), Color(0xFF8E1B27))
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Overdue by age", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Tap a bar to see those tasks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                counts.forEachIndexed { i, c ->
                    Column(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = c > 0) { onBar(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text("$c", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Box(
                            Modifier
                                .fillMaxWidth(0.7f)
                                .height((6 + 60 * c / max).dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(colors[i])
                        )
                        Text(
                            Work.AGE_LABELS[i],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** "People with pending work": the top few, with Delayed N× or On time. */
@Composable
private fun PeopleBlock(people: List<PersonSummary>, now: Long, onSeeAll: () -> Unit, onOpen: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "People with pending work",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSeeAll) { Text("See all") }
            }
            people.forEach { p -> PersonRow(p, now, withWaiting = false) { onOpen(p.key) } }
        }
    }
}

private fun greeting(t: LocalTime): String = when {
    t.hour < 12 -> "Good morning"
    t.hour < 17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun KindIcon(icon: ImageVector, container: Color, content: Color, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size((size * 0.56f).dp))
    }
}

@Composable
private fun CreateOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindIcon(icon, container, content, size = 44)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The "finish setup" cards that used to sit on top of the call list. */
private fun LazyListScope.setupBanners(
    status: SetupStatus,
    ctx: android.content.Context,
    onOpenSetup: () -> Unit,
    onOpenRecordingSettings: () -> Unit,
) {
    if (!status.requiredOk) {
        item(key = "setup") {
            Banner(
                title = "Finish setup",
                body = "${status.missingRequired} permission(s) still needed for the popup after each call.",
                button = "Set up now",
                onClick = onOpenSetup
            )
        }
    }
    if (status.requiredOk && (!status.callPhone || !status.notificationAccess)) {
        item(key = "setup_new") {
            Banner(
                title = "New: call directly + WhatsApp calls",
                body = listOfNotNull(
                    "Allow \"Call directly\" so Call back dials without opening the dialer."
                        .takeIf { !status.callPhone },
                    "Turn on Notification access to get the popup after WhatsApp calls."
                        .takeIf { !status.notificationAccess },
                ).joinToString(" "),
                button = "Open setup",
                onClick = onOpenSetup
            )
        }
    }
    if (status.requiredOk && !status.recordingReady && AppSettings.get(ctx, AppSettings.REC_PHONE)) {
        item(key = "setup_recording") {
            Banner(
                title = "New: call recording",
                body = "Allow the Microphone and switch on the call-recording Accessibility service " +
                    "to record your calls.",
                button = "Set up recording",
                onClick = onOpenRecordingSettings
            )
        }
    }
}

@Composable
private fun Banner(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

/** One-time card: the app now has home screen widgets. Show me explains how to add one; Dismiss hides it for good. */
@Composable
private fun WidgetsTip() {
    val ctx = LocalContext.current
    var shown by remember { mutableStateOf(!WidgetPrefs.tipDismissed(ctx)) }
    var howTo by remember { mutableStateOf(false) }
    androidx.compose.animation.AnimatedVisibility(
        visible = shown,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "New: home screen widgets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Follow-ups, My tasks, Review, Daily ticks, Quick add and two calendars, right on your home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { howTo = true }) { Text("Show me") }
                    TextButton(onClick = { WidgetPrefs.dismissTip(ctx); shown = false }) { Text("Dismiss") }
                }
            }
        }
    }
    if (howTo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { howTo = false },
            title = { Text("Add a widget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1.  Long-press an empty spot on your home screen.")
                    Text("2.  Tap Widgets.")
                    Text("3.  Find Callback Reminder, then drag the widget you want onto the screen.")
                    Text(
                        "It asks how it should look when you drop it. Long-press it later to change that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { howTo = false; WidgetPrefs.dismissTip(ctx); shown = false }) { Text("Got it") }
            },
            dismissButton = { TextButton(onClick = { howTo = false }) { Text("Later") } },
        )
    }
}


/**
 * The slim "Today" strip: three small rings for Done today, Dossier and the month score. It replaces the big
 * pastel tiles so Home keeps its room for the task list.
 */
@Composable
private fun TodayRings(
    doneToday: Int,
    dueToday: Int,
    dossierDone: Int,
    dossierTotal: Int,
    monthDone: Int,
    monthTotal: Int,
    onDone: () -> Unit,
    onDossier: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val teal = if (dark) Color(0xFF3FD6B8) else Color(0xFF0E9F87)
    val amber = if (dark) Color(0xFFFFC14D) else Color(0xFFE09A12)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            val pct = if (monthTotal == 0) 0 else monthDone * 100 / monthTotal
            RingItem(frac(doneToday, dueToday), cs.primary, "$doneToday/$dueToday", "Done today", Modifier.weight(1f), onDone)
            RingItem(frac(dossierDone, dossierTotal), teal, "$dossierDone/$dossierTotal", "Dossier", Modifier.weight(1f), onDossier)
            RingItem(if (monthTotal == 0) 0f else pct / 100f, amber, "$pct%", "Month score", Modifier.weight(1f), onDossier)
        }
    }
}

private fun frac(a: Int, b: Int): Float = if (b <= 0) 0f else (a.toFloat() / b).coerceIn(0f, 1f)

@Composable
private fun RingItem(progress: Float, color: Color, value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sweep by androidx.compose.animation.core.animateFloatAsState(
        progress, androidx.compose.animation.core.tween(700), label = "ring"
    )
    val track = cs.outlineVariant.copy(alpha = 0.5f)
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(34.dp)) {
            val w = 4.5.dp.toPx()
            val inset = w / 2
            val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            val st = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(track, 0f, 360f, false, tl, sz, style = st)
            if (sweep > 0f) drawArc(color, -90f, 360f * sweep, false, tl, sz, style = st)
        }
        Spacer(Modifier.width(6.dp))
        Column {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(label, fontSize = 10.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** One of the four small count tiles under the rings; colours follow the light or dark mode. */
@Composable
private fun SmallTile(icon: ImageVector, count: String, label: String, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tone.bg, contentColor = tone.fg)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(count, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Counts everything due today or late across the app and fires the once-a-day "Day cleared" burst at zero. */
@Composable
fun DayClearedWatcher() {
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    val left = remember(calls, texts, myTasks, dossier, records) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        Work.open(now, calls, texts, myTasks, dossier, records).count { !it.inReview && it.at < end }
    }
    WatchDayCleared(left)
}
