package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Checklist
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
    onOpenPeople: () -> Unit,
    onOpenPerson: (key: String) -> Unit,
    onOpenItem: (WorkItem) -> Unit,
) {
    val ctx = LocalContext.current
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val toSchedule by ToScheduleStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    val dossier by DossierStore.tasks.collectAsState()
    val records by DossierStore.records.collectAsState()
    val status = remember(resumeKey) { SetupStatus.read(ctx) }
    var chooseNew by remember { mutableStateOf(false) }
    var newCall by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf(false) }
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
    val toReview = textsToReview + callsToReview + dossierLeft
    val myTasksToday = myTasks.count { !it.done && it.dueAt < endOfToday }

    val open = Work.open(now, calls, texts, myTasks, dossier, records)
    val overdue = open.filter { it.overdue }
    val ageCounts = (0..3).map { b -> overdue.count { Work.ageBucket(it, now) == b } }
    val people = Work.people(now, calls, texts, myTasks)
        .sortedWith(compareByDescending<PersonSummary> { it.overdue }.thenByDescending { it.pending })

    val nextUp = open.filter { !it.inReview && it.at < endOfToday }.sortedBy { it.at }
    val filtered = if (!filtering) emptyList() else open.filter { w ->
        (personFilter == null || w.personKey == personFilter) &&
            (typeFilter == null || w.kind == typeFilter) &&
            createdFilter.matches(w.createdAt) &&
            dueFilter.matches(w.at) &&
            (ageFilter == null || (w.overdue && Work.ageBucket(w, now) == ageFilter))
    }.sortedBy { it.at }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { chooseNew = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "New reminder")
            }
        }
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

            setupBanners(status, ctx, onOpenSetup, onOpenRecordingSettings)

            item(key = "tiles") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountTile(
                            icon = Icons.Rounded.PhoneCallback,
                            count = "${callsDue.size}",
                            label = "Calls due",
                            container = Color(0xFFFFE3E6),
                            content = Color(0xFF7A1020),
                            modifier = Modifier.weight(1f),
                            onClick = onOpenCalls,
                        )
                        CountTile(
                            icon = Icons.Rounded.Sms,
                            count = "${textsToday.size}",
                            label = "Texts today",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenTexts(0, null) },
                        )
                        CountTile(
                            icon = Icons.Rounded.PendingActions,
                            count = "$toReview",
                            label = "To review",
                            container = Color(0xFFFFF1C9),
                            content = Color(0xFF6A4A00),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when {
                                    dossierLeft > 0 -> onOpenDossierReview()
                                    textsToReview == 0 && callsToReview > 0 -> {
                                        CallsUi.tabRequest.value = CallsUi.TO_REVIEW
                                        onOpenCalls()
                                    }
                                    else -> onOpenTexts(1, null)
                                }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountTile(
                            icon = Icons.Rounded.TaskAlt,
                            count = "$myTasksToday",
                            label = "My tasks",
                            container = Color(0xFFEDE4FF),
                            content = Color(0xFF3F1F8C),
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenMyTasks(MyTasksUi.ONE_TIME) },
                        )
                        CountTile(
                            icon = Icons.Rounded.Checklist,
                            count = "$dossierDone/${dossierToday.size}",
                            label = "Dossier",
                            container = Color(0xFFD9F3EC),
                            content = Color(0xFF0B5A4C),
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenMyTasks(MyTasksUi.DOSSIER) },
                        )
                        CountTile(
                            icon = Icons.Rounded.EditCalendar,
                            count = "${toSchedule.size}",
                            label = "To schedule",
                            container = Color(0xFFFFE6C2),
                            content = Color(0xFF6A3B00),
                            modifier = Modifier.weight(1f),
                            onClick = onOpenToSchedule,
                        )
                    }
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
                item(key = "next-empty") { AllClear() }
            }
            if (nextUp.isNotEmpty()) item(key = "next-list") { WorkBlock(nextUp, now, showPerson = true, onOpen = onOpenItem) }
        }
    }

    if (chooseNew) {
        ModalBottomSheet(onDismissRequest = { chooseNew = false }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
                Text(
                    "Create new",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                CreateOption(
                    icon = Icons.Rounded.PhoneCallback,
                    title = "Call-back reminder",
                    subtitle = "Rings like a call at the time you pick",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { chooseNew = false; newCall = true },
                )
                CreateOption(
                    icon = Icons.Rounded.Sms,
                    title = "Text message",
                    subtitle = "Sends an SMS on time, once, on a repeat or as a burst",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { chooseNew = false; newText = true },
                )
                CreateOption(
                    icon = Icons.Rounded.TaskAlt,
                    title = "My task",
                    subtitle = "Your own to-do, or a dossier task you tick every day",
                    container = Color(0xFFEDE4FF),
                    content = Color(0xFF3F1F8C),
                    onClick = { chooseNew = false; newTask = true },
                )
            }
        }
    }

    if (newCall) {
        AddReminderDialog(
            onDismiss = { newCall = false },
            onSave = { name, number, note, dueAt, repeat ->
                ReminderStore.add(name, number, note, dueAt, repeat)
                newCall = false
            }
        )
    }

    if (newText) {
        SmsReminderDialog(
            onDismiss = { newText = false },
            onSave = { name, number, message, dueAt, repeat, burst, tracked ->
                SmsStore.add(name, number, message, dueAt, repeat, burst, tracked)
                newText = false
            }
        )
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
    val colors = listOf(Color(0xFFF0B850), Color(0xFFEE8A3C), Color(0xFFD93A4A), Color(0xFF8E1B27))
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
private fun CountTile(
    icon: ImageVector,
    count: String,
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                count,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
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

@Composable
private fun AllClear() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.EventAvailable,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text("Nothing left for today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Tap + to add a call-back or a text message.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
