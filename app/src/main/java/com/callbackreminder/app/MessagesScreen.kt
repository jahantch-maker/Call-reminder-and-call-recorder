package com.callbackreminder.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** One day block of the Texts list. */
private class SmsBlock(val key: String, val title: String, val count: String, val tone: Int, val list: List<SmsReminder>)

private const val TONE_RED = 0
private const val TONE_BLUE = 1
private const val TONE_MUTED = 2

/** The blocks a tab shows: Scheduled by the day of the next text, To review as one block, Done by the day it was done. */
private fun smsBlocks(tab: Int, list: List<SmsReminder>): List<SmsBlock> {
    val today = LocalDate.now(ZoneId.systemDefault())
    return when (tab) {
        0 -> byDay(list) { it.alarmAt }.map { (day, l) ->
            val isToday = day == today
            SmsBlock("day-$day", RowFmt.dayTitle(day), "${l.size}", if (isToday) TONE_BLUE else TONE_MUTED, l)
        }
        1 -> if (list.isEmpty()) emptyList() else listOf(SmsBlock("review", "Waiting for you", "${list.size}", TONE_RED, list))
        else -> byDay(list) { it.doneAt }.map { (day, l) ->
            SmsBlock("done-$day", RowFmt.dayTitle(day), "${l.size} done", TONE_MUTED, l)
        }
    }
}

/** Where the row for [id] sits in the list, counting [before] items above the first block. */
private fun smsIndex(blocks: List<SmsBlock>, id: Long, before: Int): Int? {
    var i = before
    for (b in blocks) {
        i++ // the day header
        val pos = b.list.indexOfFirst { it.id == id }
        if (pos >= 0) return i + pos
        i += b.list.size
    }
    return null
}

/** The texts were answered with "Done <number>" at some point. */
private val SmsReminder.repliedDone: Boolean
    get() = log.any { it.status == SmsLogEntry.Status.REPLIED_DONE }

/** Scheduled text messages: sent from your own SIM at the time you set, once or on a repeat. */
@Composable
fun MessagesScreen(resumeKey: Int, onBack: (() -> Unit)?, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val all by SmsStore.items.collectAsState()
    var createdFilter by ListFilters.textsCreated
    var dueFilter by ListFilters.textsDue
    val items = all.filter { createdFilter.matches(it.createdAt) && dueFilter.matches(it.dueAt) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var editFor by remember { mutableStateOf<SmsReminder?>(null) }
    var rescheduleFor by remember { mutableStateOf<SmsReminder?>(null) }
    var pickTimeFor by remember { mutableStateOf<SmsReminder?>(null) }
    var sendNowFor by remember { mutableStateOf<SmsReminder?>(null) }
    var historyFor by remember { mutableStateOf<SmsReminder?>(null) }
    var callInsteadFor by remember { mutableStateOf<SmsReminder?>(null) }
    var laterFor by remember { mutableStateOf<SmsReminder?>(null) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showPermissionHelp by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val canSend = remember(resumeKey, refresh) { SmsSender.hasPermission(ctx) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }

    // On Android 15+ an app installed outside Play Store can't even show the SMS question until
    // "Allow restricted settings" is switched on, so a refusal leads to the step-by-step help.
    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refresh++
        if (!granted) showPermissionHelp = true
    }

    val pending = items.filter { it.active }.sortedBy { it.alarmAt }
    val toReview = items.filter { !it.done && it.review != null }.sortedByDescending { it.reviewAt }
    val finished = items.filter { it.done }.sortedByDescending { it.doneAt }

    val listState = rememberLazyListState()
    var highlightId by remember { mutableStateOf<Long?>(null) }

    // A tapped notification: switch to the reminder's tab, scroll to it and highlight it for a moment.
    val showRequest by SmsUi.showRequest.collectAsState()
    LaunchedEffect(showRequest) {
        val id = showRequest ?: return@LaunchedEffect
        SmsUi.showRequest.value = null
        val r = SmsStore.get(id) ?: return@LaunchedEffect
        // Filters could hide it, so they're cleared and its place is found in the full list.
        createdFilter = DateFilter()
        dueFilter = DateFilter()
        val everything = SmsStore.items.value
        val (t, list) = when {
            r.done -> 2 to everything.filter { it.done }.sortedByDescending { it.doneAt }
            r.review != null -> 1 to everything.filter { !it.done && it.review != null }.sortedByDescending { it.reviewAt }
            else -> 0 to everything.filter { it.active }.sortedBy { it.alarmAt }
        }
        tab = t
        highlightId = id
        // Header, the permission card when it shows, then the tabs come before the day blocks.
        val index = smsIndex(smsBlocks(t, list), id, before = if (canSend) 2 else 3)
        if (index != null) {
            delay(150L)
            listState.animateScrollToItem(index)
        }
        delay(3_000L)
        if (highlightId == id) highlightId = null
    }

    // Home's Texts or To review tile.
    val tabRequest by SmsUi.tabRequest.collectAsState()
    LaunchedEffect(tabRequest) {
        val t = tabRequest ?: return@LaunchedEffect
        SmsUi.tabRequest.value = null
        tab = t
    }

    val editRequest by SmsUi.editRequest.collectAsState()
    LaunchedEffect(editRequest, items) {
        val id = editRequest ?: return@LaunchedEffect
        items.firstOrNull { it.id == id }?.let { editFor = it }
        SmsUi.editRequest.value = null
    }

    fun done(r: SmsReminder) {
        val before = r
        SmsStore.markDone(r.id)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Done · ${r.message}") { SmsStore.restore(before) }
    }
    fun later(r: SmsReminder, ms: Long) {
        val before = r
        SmsStore.reschedule(r.id, ms)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Moved to ${Fmt.dueLabel(ms)}") { SmsStore.restore(before) }
    }

    fun deleteNow(r: SmsReminder) {
        val before = r
        SmsStore.delete(r.id)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Deleted · ${r.message}") { SmsStore.restore(before) }
    }
    fun moveBack(r: SmsReminder) {
        val before = r
        SmsStore.reopen(r.id)
        if (openId == r.id) openId = null
        scope.offerUndo(snack, "Moved back to To review") { SmsStore.restore(before) }
    }
    fun notDone(r: SmsReminder) {
        val before = r
        if (r.review == SmsReminder.Review.REPLIED) {
            SmsStore.resume(r.id)
            scope.offerUndo(snack, "Back to Scheduled") { SmsStore.restore(before) }
        } else {
            SmsStore.noteNotDone(r.id)
            editFor = r
        }
    }

    val toneColors = listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New message") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    ) { padding ->
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 0.dp)) {
                        Text(
                            "Text messages",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (pending.isEmpty()) "Nothing scheduled"
                            else "${pending.size} scheduled · sent from your SIM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Text message settings")
                    }
                    TopActions()
                }
            }

            if (!canSend) {
                item(key = "permission") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Allow sending SMS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "So your messages go out on their own at the set time. Until then you'll get " +
                                    "a notification instead, and send it yourself from your Messages app.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { smsPermission.launch(Manifest.permission.SEND_SMS) }) {
                                    Text("Allow")
                                }
                                TextButton(onClick = { showPermissionHelp = true }) { Text("Not working?") }
                            }
                        }
                    }
                }
            }

            item(key = "tabs") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            label = { Text("Scheduled (${pending.size})") }
                        )
                        FilterChip(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            label = { Text("To review (${toReview.size})") }
                        )
                        FilterChip(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            label = { Text("Done (${finished.size})") }
                        )
                    }
                    CreatedDueFilterRow(
                        created = createdFilter,
                        due = dueFilter,
                        onCreated = { createdFilter = it },
                        onDue = { dueFilter = it },
                    )
                }
            }

            val list = when (tab) {
                0 -> pending
                1 -> toReview
                else -> finished
            }
            if (list.isEmpty()) {
                item(key = "empty-$tab") {
                    if (createdFilter.active || dueFilter.active) {
                        Text(
                            "No text messages match these filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    } else EmptyMessages(tab)
                }
            }
            smsBlocks(tab, list).forEach { b ->
                item(key = "group-${b.key}") { DayHeader(b.title, b.count, toneColors[b.tone]) }
                itemsIndexed(b.list, key = { _, r -> r.id }) { i, r ->
                    Box(Modifier.animateItem()) {
                        SmsRow(
                            r = r,
                            pos = rowPos(i, b.list.size),
                            now = now,
                            expanded = openId == r.id,
                            highlighted = r.id == highlightId,
                            onToggle = { openId = if (openId == r.id) null else r.id },
                            onSendNow = { sendNowFor = r },
                            onDone = { done(r) },
                            onLater = { laterFor = r },
                            onHistory = { historyFor = r },
                            onEdit = { editFor = r },
                            onNotDone = { notDone(r) },
                            onPickTime = { rescheduleFor = r },
                            onDelete = { deleteNow(r) },
                            onMoveBack = { moveBack(r) },
                            onCallNow = {
                                // The after-call popup asks how this text task went.
                                FollowUp.markText(ctx, r)
                                dial(ctx, r.number)
                            },
                            onCallInstead = { callInsteadFor = r },
                        )
                    }
                }
            }
        }
    }

    laterFor?.let { r ->
        SnoozeSheet(
            heading = if (r.active) "Send later" else "Send again later",
            about = r.message,
            onPick = { ms -> laterFor = null; later(r, ms) },
            onPickTime = { laterFor = null; pickTimeFor = r },
            onDismiss = { laterFor = null }
        )
    }

    // Change time / Send again later: the quick times first, one tap sets it and closes.
    rescheduleFor?.let { r ->
        SnoozeSheet(
            heading = if (r.active) "Change time" else "Send again later",
            about = r.message,
            onPick = { ms -> rescheduleFor = null; later(r, ms) },
            onPickTime = { rescheduleFor = null; pickTimeFor = r },
            onDismiss = { rescheduleFor = null }
        )
    }

    if (showAdd) {
        SmsReminderDialog(
            onDismiss = { showAdd = false },
            onSave = { name, number, message, dueAt, repeat, burst, tracked ->
                SmsStore.add(name, number, message, dueAt, repeat, burst, tracked)
                showAdd = false
            }
        )
    }

    editFor?.let { r ->
        SmsReminderDialog(
            initial = r,
            onDismiss = { editFor = null },
            onSave = { name, number, message, dueAt, repeat, burst, tracked ->
                SmsStore.edit(r.id, name, number, message, dueAt, repeat, burst, tracked)
                editFor = null
            }
        )
    }

    callInsteadFor?.let { r ->
        AddReminderDialog(
            title = "Call instead",
            initialName = r.name,
            initialNumber = r.number,
            initialNote = r.message,
            onDismiss = { callInsteadFor = null },
            onSave = { name, number, note, dueAt, repeat, starred ->
                TextToCall.move(ctx, r, name, number, note, dueAt, repeat).let { if (starred) ReminderStore.setStar(it.id, true) }
                callInsteadFor = null
                Toast.makeText(ctx, "Moved to Calls · ${Fmt.dueLabel(dueAt)}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    pickTimeFor?.let { r ->
        DateTimePickerFlow(
            initialMs = maxOf(r.dueAt, now + 60_000L),
            onPicked = { ms ->
                pickTimeFor = null
                later(r, ms)
            },
            onDismiss = { pickTimeFor = null }
        )
    }

    sendNowFor?.let { r ->
        AlertDialog(
            onDismissRequest = { sendNowFor = null },
            title = { Text("Send now?") },
            text = {
                Text(
                    when {
                        r.isBurst -> "Starts today's burst to ${r.title} now: ${r.burstCount} texts, " +
                            "${r.burstGapMin} min apart, starting with Reminder 1."
                        r.repeat != null -> "Sends the message to ${r.title} now. It counts as the one due " +
                            "${Fmt.dueLabel(r.dueAt)}, and the next one follows the repeat."
                        else -> "Sends the message to ${r.title} now instead of ${Fmt.dueLabel(r.dueAt)}."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sendNowFor = null
                    if (!r.isBurst && SmsSender.InFlight.busy(ctx, r.id, System.currentTimeMillis())) {
                        Toast.makeText(ctx, "Already sending this message", Toast.LENGTH_SHORT).show()
                    } else {
                        SmsSender.sendDue(ctx, r.id, manual = true)
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sendNowFor = null }) { Text("Cancel") } }
        )
    }

    historyFor?.let { r ->
        // Read the live copy so new results show while the window is open.
        val live = items.firstOrNull { it.id == r.id } ?: r
        HistoryDialog(live, onDismiss = { historyFor = null })
    }

    if (showPermissionHelp) {
        AlertDialog(
            onDismissRequest = { showPermissionHelp = false },
            title = { Text("Turn on SMS for this app") },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT >= 35) {
                        "Android locks SMS for apps installed outside Play Store. One time only:\n\n" +
                            "1. Tap Open app info below.\n" +
                            "2. Tap ⋮ (top right) › Allow restricted settings, and confirm.\n" +
                            "3. Tap Permissions › SMS › Allow.\n\n" +
                            "On Samsung the ⋮ menu may say More. Then come back here."
                    } else {
                        "Tap Open app info below, then Permissions › SMS › Allow. Then come back here."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionHelp = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text("Open app info") }
            },
            dismissButton = { TextButton(onClick = { showPermissionHelp = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun EmptyMessages(tab: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Sms,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when (tab) {
                0 -> "No messages scheduled"
                1 -> "Nothing to review"
                else -> "Nothing done yet"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (tab) {
                0 -> "Schedule a text to someone, once or on a repeat, like a weekly \"please wash the solar panels\"."
                1 -> "Every message that went out, or couldn't be sent, waits here for you to check."
                else -> "Messages you marked done show up here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = TextAlign.Center
        )
    }
}

/** One text task as a slim row: the message in bold, the person under it, and the task number as a chip. */
@Composable
private fun SmsRow(
    r: SmsReminder,
    pos: RowPos,
    now: Long,
    expanded: Boolean,
    highlighted: Boolean,
    onToggle: () -> Unit,
    onSendNow: () -> Unit,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
    onNotDone: () -> Unit,
    onPickTime: () -> Unit,
    onDelete: () -> Unit,
    onCallNow: () -> Unit,
    onCallInstead: () -> Unit,
    onMoveBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val inReview = !r.done && r.review != null
    val retrying = r.active && r.retryAt > 0L
    val hasNumber = r.number.isNotBlank()

    val chips = buildList {
        when {
            !r.tracked -> add(RowChip("Tracking off", ChipTone.MUTED))
            r.code.isEmpty() -> add(RowChip("comes with 1st text", ChipTone.MUTED, Icons.Rounded.Tag))
            else -> add(RowChip(r.code, ChipTone.BLUE, Icons.Rounded.Tag))
        }
        if (r.active) {
            if (retrying) add(RowChip("Retry ${r.attempts + 1} of ${SmsSender.MAX_RETRIES + 1}", ChipTone.RED))
            if (r.isBurst) {
                add(
                    if (r.burstIndex > 0) RowChip("${r.burstIndex} of ${r.burstCount} sent", ChipTone.BLUE, Icons.Rounded.Sms)
                    else RowChip(r.burstLabel ?: "", ChipTone.BLUE, Icons.Rounded.Sms)
                )
            }
            r.repeatLabel?.let { add(RowChip(it, ChipTone.MUTED, Icons.Rounded.Repeat)) }
        }
        if (inReview) {
            add(
                when {
                    r.review == SmsReminder.Review.REPLIED -> RowChip("Replied “${r.doneReply}”", ChipTone.GREEN, Icons.Rounded.Check)
                    r.review == SmsReminder.Review.NOT_SENT -> RowChip("Not sent", ChipTone.RED)
                    !r.tracked -> RowChip(if (r.isBurst || r.repeat != null) "All texts sent" else "Sent", ChipTone.MUTED)
                    r.isBurst -> RowChip("No reply · all ${r.burstCount} texts sent", ChipTone.ORANGE)
                    r.repeat != null -> RowChip("No reply · all texts sent", ChipTone.ORANGE)
                    else -> RowChip("No reply · text sent", ChipTone.ORANGE)
                }
            )
        }
        if (r.done && r.repliedDone) add(RowChip("Replied Done", ChipTone.GREEN))
    }

    val (timeTop, timeSub) = when {
        r.done -> "✓ ${Fmt.timeOnly(r.doneAt)}" to null
        inReview -> Pair(
            when (r.review) {
                SmsReminder.Review.REPLIED -> "Replied"
                SmsReminder.Review.NOT_SENT -> "Failed"
                else -> "Sent"
            },
            RowFmt.doneSub(r.reviewAt)
        )
        retrying -> Fmt.timeOnly(r.retryAt) to "retrying"
        r.isBurst && r.burstIndex > 0 -> Fmt.timeOnly(r.nextTextAt) to "next text"
        else -> Fmt.timeOnly(r.alarmAt) to RowFmt.timeSub(r.alarmAt, now)
    }

    // The latest result, shown under the message once the row is open.
    val last = r.lastLog?.let { e ->
        if (e.status == SmsLogEntry.Status.CALLED) "Called · ${e.detail} · ${Fmt.dueLabel(e.at)}"
        else "Last: ${e.status.label} · ${Fmt.dueLabel(e.at)}"
    }

    SwipeActions(
        enabled = !expanded && !r.done,
        shape = rowShape(pos),
        doneLabel = if (inReview) "Mark done" else "Done",
        snoozeLabel = "Later",
        onDone = onDone,
        onSnooze = onLater,
    ) {
        TaskRow(
            pos = pos,
            timeTop = timeTop,
            timeSub = timeSub,
            timeRed = retrying,
            title = r.message,
            titleStyle = if (r.done) TitleStyle.DONE else TitleStyle.NORMAL,
            person = r.title,
            personDetail = r.number.takeIf { r.name.isNotBlank() && it.isNotBlank() },
            chips = chips,
            expanded = expanded,
            onClick = onToggle,
            highlighted = highlighted,
            action = {
                when {
                    r.done -> RoundAction(Icons.Rounded.Replay, "Send again later", cs.secondaryContainer, cs.onSecondaryContainer, onClick = onPickTime)
                    inReview -> RoundAction(Icons.Rounded.Check, "Mark done", cs.secondaryContainer, cs.onSecondaryContainer, onClick = onDone)
                    else -> RoundAction(Icons.AutoMirrored.Rounded.Send, "Send now", cs.secondary, cs.onSecondary, onClick = onSendNow)
                }
            },
            expandedContent = {
                if (last != null) {
                    Text(last, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    r.done -> RowActions(
                        actions = listOf(
                            RowAction("Not done, move back", Icons.AutoMirrored.Rounded.Undo, ActionStyle.ORANGE, onMoveBack),
                            RowAction("Send again later", Icons.Rounded.Replay, ActionStyle.OUTLINED, onPickTime),
                        ),
                        links = listOf(
                            RowLink("History", onClick = onHistory),
                            RowLink("Edit", onClick = onEdit),
                            RowLink("Delete", danger = true, onClick = onDelete),
                        )
                    )
                    inReview -> RowActions(
                        actions = listOfNotNull(
                            RowAction("Mark done", Icons.Rounded.Check, ActionStyle.GREEN, onDone),
                            RowAction("Not done", Icons.Rounded.Replay, ActionStyle.OUTLINED, onNotDone),
                            RowAction("Call now", Icons.Rounded.Call, ActionStyle.FILLED, onCallNow).takeIf { hasNumber },
                        ),
                        links = listOfNotNull(
                            RowLink("History", onClick = onHistory),
                            RowLink("Edit", onClick = onEdit),
                            RowLink("Send again later", onClick = onLater),
                            RowLink("Call instead", onClick = onCallInstead).takeIf { hasNumber },
                            RowLink("Delete", danger = true, onClick = onDelete),
                        )
                    )
                    else -> RowActions(
                        actions = listOf(
                            RowAction("Send now", Icons.AutoMirrored.Rounded.Send, ActionStyle.GREEN, onSendNow),
                            RowAction("Change time", Icons.Rounded.Schedule, ActionStyle.OUTLINED, onPickTime),
                            RowAction("Edit", Icons.Rounded.Edit, ActionStyle.OUTLINED, onEdit),
                            RowAction("Done", Icons.Rounded.Check, ActionStyle.OUTLINED, onDone),
                        ),
                        links = listOfNotNull(
                            RowLink("Call now", onClick = onCallNow).takeIf { hasNumber },
                            RowLink("Call instead", onClick = onCallInstead).takeIf { hasNumber },
                            RowLink("History", onClick = onHistory),
                            RowLink("Stop repeat") { SmsStore.stopRepeating(r.id) }.takeIf { r.repeat != null },
                            RowLink("Delete", danger = true, onClick = onDelete),
                        )
                    )
                }
            }
        )
    }
}

/** Every time this message was due: sent, delivered, failed, skipped. Newest first. */
@Composable
private fun HistoryDialog(r: SmsReminder, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History · ${r.title}" + if (r.code.isEmpty()) "" else " · ${r.code}") },
        text = {
            if (r.log.isEmpty()) {
                Text("Nothing sent yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(r.log.reversed()) { e ->
                        val bad = e.status == SmsLogEntry.Status.FAILED || e.status == SmsLogEntry.Status.RETRYING
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                if (e.status == SmsLogEntry.Status.CALL_EVENT) e.detail else e.status.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                listOf(Fmt.dueLabel(e.at), e.detail.takeIf { e.status != SmsLogEntry.Status.CALL_EVENT })
                                    .filter { !it.isNullOrBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
