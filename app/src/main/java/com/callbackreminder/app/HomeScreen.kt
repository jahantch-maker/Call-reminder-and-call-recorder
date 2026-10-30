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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.rounded.Check

/**
 * The Home tab: date and search, the Needs you card (Replied, Overdue, To schedule, Start review), three rings
 * (Follow-ups, My tasks, Dossier done today) and one time-ordered "Next up" list with the same cards as everywhere.
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
    onOpenOverdue: () -> Unit = {},
    onOpenMyTasks: (sub: Int) -> Unit,
    onOpenDossierReview: () -> Unit,
    onOpenReview: () -> Unit = {},
    onOpenSession: () -> Unit = {},
    onOpenPeople: () -> Unit,
    onOpenPerson: (key: String) -> Unit,
    onOpenItem: (WorkItem) -> Unit,
    onOpenSearch: (String) -> Unit = {},
    onOpenUpdate: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val newerVersion by AppUpdate.newer.collectAsState()
    LaunchedEffect(Unit) { AppUpdate.autoCheck(ctx) }
    val unreadable by Db.failed.collectAsState()
    var unreadableSeen by remember { mutableStateOf(false) }
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

    val dossierDay = Dossier.openDay(now)
    val dossierToday = dossier.filter { it.isOn(dossierDay) }
    val dossierDone = dossierToday.count { records[DossierStore.key(it.id, dossierDay)]?.done == true }

    val open = Work.open(now, calls, texts, myTasks, dossier, records)
    val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    // The rings: what was done today out of what was due today (late ones still count as due).
    val fuEntries = entries(calls, texts)
    val fuDone = fuEntries.count { !it.open && !it.moved && it.finishedAt >= startOfToday }
    val fuLeft = fuEntries.count { it.open && !it.hidden && !it.inReview && it.at < endOfToday }
    val myDone = myTasks.count { it.done && it.doneAt >= startOfToday }
    val myLeft = myTasks.count { !it.done && it.timed && it.dueAt < endOfToday }

    val nextUp = open.filter { !it.inReview && it.at < endOfToday }.sortedBy { it.at }
    val snack = remember { SnackbarHostState() }

    PullRefresh(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
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
                    // One line: "Good morning  Tue 21 Oct".
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        Text(
                            greeting(Instant.ofEpochMilli(now).atZone(zone).toLocalTime()),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    TopActions()
                }
            }

            item(key = "search") { HomeSearchBar(onOpen = onOpenSearch) }

            // A newer version is waiting: Update itself lives at the top of Settings.
            newerVersion?.let { v ->
                item(key = "update") {
                    Banner(
                        title = "Update available",
                        body = "Version ${v.name} is ready. Open Settings to update.",
                        button = "Open Settings",
                        onClick = onOpenUpdate
                    )
                }
            }

            // A list that couldn't be read is never saved over: say so, and point to the backup.
            if (unreadable.isNotEmpty() && !unreadableSeen) {
                item(key = "unreadable") {
                    Banner(
                        title = "Some of your data couldn't be read",
                        body = "Nothing was deleted or saved over it. Restore last night's backup in Settings › Backup " +
                            "to get it back.",
                        button = "Got it",
                        onClick = { unreadableSeen = true }
                    )
                }
            }

            // Needs you: Replied, Overdue and To schedule, with Start review under them.
            item(key = "review") {
                ReviewHomeCard(
                    resumeKey = resumeKey,
                    onStart = {
                        if (ReviewSession.start(ctx, ReviewSession.MODE_ALL)) onOpenSession()
                        else onOpenReview()
                    },
                    onOpen = onOpenReview,
                    onResume = onOpenSession,
                    onOpenOverdue = onOpenOverdue,
                    onOpenToSchedule = onOpenToSchedule,
                )
            }

            setupBanners(status, ctx, onOpenSetup, onOpenRecordingSettings)

            item(key = "rings") {
                TodayRings(
                    fuDone = fuDone, fuTotal = fuDone + fuLeft,
                    myDone = myDone, myTotal = myDone + myLeft,
                    dossierDone = dossierDone, dossierTotal = dossierToday.size,
                    onFollowUps = onOpenCalls,
                    onMyTasks = { onOpenMyTasks(MyTasksUi.ONE_TIME) },
                    onDossier = { onOpenMyTasks(MyTasksUi.DOSSIER) },
                )
            }

            item(key = "next-title") {
                ChipHeading("NEXT UP", modifier = Modifier.padding(top = 2.dp, start = 4.dp))
            }

            if (nextUp.isEmpty()) {
                item(key = "next-empty") {
                    EmptyPicture(Icons.Rounded.EventAvailable, "Nothing left for today", "Tap ＋ to add a follow-up or a task.")
                }
            }
            if (nextUp.isNotEmpty()) item(key = "next-list") { WorkBlock(nextUp, now, showPerson = true, onOpen = onOpenItem, snack = snack) }
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
    // ✕ on a setup card: it never comes back (read here so the list redraws at once).
    SetupBannerUi.tick.intValue
    if (status.requiredOk && (!status.callPhone || !status.notificationAccess) && AppSettings.get(ctx, SetupBannerUi.SHOW_CALL_DIRECT)) {
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
                onClick = onOpenSetup,
                onClose = { AppSettings.set(ctx, SetupBannerUi.SHOW_CALL_DIRECT, false); SetupBannerUi.tick.intValue++ },
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
                onClick = onOpenRecordingSettings,
                // ✕ = no recording: phone recording goes off (Settings › Call recording switches it on again).
                onClose = { AppSettings.set(ctx, AppSettings.REC_PHONE, false); SetupBannerUi.tick.intValue++ },
            )
        }
    }
}

/** Redraws the setup cards after a ✕. */
internal object SetupBannerUi {
    const val SHOW_CALL_DIRECT = "setup_banner_call_direct"
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
}

@Composable
private fun Banner(title: String, body: String, button: String, onClick: () -> Unit, onClose: (() -> Unit)? = null) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f).padding(top = 2.dp)
                )
                if (onClose != null) {
                    Text(
                        "✕",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

/**
 * Three rings: Follow-ups, My tasks and Dossier, each done today out of due today with "N left". A full ring turns
 * green with ✓. Tapping a ring opens that page.
 */
@Composable
private fun TodayRings(
    fuDone: Int,
    fuTotal: Int,
    myDone: Int,
    myTotal: Int,
    dossierDone: Int,
    dossierTotal: Int,
    onFollowUps: () -> Unit,
    onMyTasks: () -> Unit,
    onDossier: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val purple = if (dark) Color(0xFFB79CFF) else Color(0xFF7C4DFF)
    val amber = if (dark) Color(0xFFFFC14D) else Color(0xFFE09A12)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
            RingItem(fuDone, fuTotal, cs.primary, "Follow-ups", Modifier.weight(1f), onFollowUps)
            RingItem(myDone, myTotal, purple, "My tasks", Modifier.weight(1f), onMyTasks)
            RingItem(dossierDone, dossierTotal, amber, "Dossier", Modifier.weight(1f), onDossier)
        }
    }
}

private fun frac(a: Int, b: Int): Float = if (b <= 0) 0f else (a.toFloat() / b).coerceIn(0f, 1f)

@Composable
private fun RingItem(done: Int, total: Int, color: Color, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val full = total > 0 && done >= total
    val green = if (LocalDarkTheme.current) Color(0xFF3FD6B8) else Color(0xFF0E9F87)
    val ring by androidx.compose.animation.animateColorAsState(if (full) green else color, androidx.compose.animation.core.tween(400), label = "ringColor")
    val sweep by androidx.compose.animation.core.animateFloatAsState(
        frac(done, total), androidx.compose.animation.core.tween(700), label = "ring"
    )
    val track = cs.outlineVariant.copy(alpha = 0.5f)
    val ringBox: @Composable () -> Unit = {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(38.dp)) {
                val w = 4.5.dp.toPx()
                val inset = w / 2
                val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                val st = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawArc(track, 0f, 360f, false, tl, sz, style = st)
                if (sweep > 0f) drawArc(ring, -90f, 360f * sweep, false, tl, sz, style = st)
            }
            androidx.compose.animation.Crossfade(full, label = "ringMid") { f ->
                if (f) Icon(Icons.Rounded.Check, contentDescription = "All done", tint = green, modifier = Modifier.size(18.dp))
                else Text("$done/$total", fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
    val sub = when {
        total == 0 -> "nothing due"
        full -> "all done"
        else -> "${total - done} left"
    }
    // Narrow (small phone, Large display size): the words go under the ring instead of beside it, never cut.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        if (maxWidth >= 116.dp) Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ringBox()
            Spacer(Modifier.width(6.dp))
            Column {
                FitText(label, 13.sp, fontWeight = FontWeight.Bold)
                FitText(sub, 11.sp, color = cs.onSurfaceVariant)
            }
        } else Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ringBox()
            Spacer(Modifier.height(3.dp))
            FitText(label, 13.sp, fontWeight = FontWeight.Bold)
            FitText(sub, 11.sp, color = cs.onSurfaceVariant)
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
