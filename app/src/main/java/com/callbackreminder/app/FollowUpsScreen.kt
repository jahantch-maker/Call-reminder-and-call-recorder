package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.Snooze
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
}

/**
 * The Follow-ups tab: every follow-up once, a call and its texts together, with Open / Done and a Filter
 * (Everything, Calls only, Texts only, and the sort). Calls only and Texts only are the full call and text lists.
 */
@Composable
fun FollowUpsScreen(resumeKey: Int, onOpenTextSettings: () -> Unit, onOpenToSchedule: () -> Unit) {
    var view by rememberSaveable { mutableIntStateOf(FollowUpsUi.ALL) }
    var done by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(FollowUpsUi.SORT_DUE) }
    val asked by FollowUpsUi.view.collectAsState()
    LaunchedEffect(asked) {
        val v = asked ?: return@LaunchedEffect
        FollowUpsUi.view.value = null
        view = v
    }
    // A request for a call or a text (a notification, search, Home) opens the list that shows it.
    val callShow by CallsUi.showRequest.collectAsState()
    val callTab by CallsUi.tabRequest.collectAsState()
    val textShow by SmsUi.showRequest.collectAsState()
    val textTab by SmsUi.tabRequest.collectAsState()
    val textEdit by SmsUi.editRequest.collectAsState()
    LaunchedEffect(callShow, callTab) { if (callShow != null || callTab != null) view = FollowUpsUi.CALLS }
    LaunchedEffect(textShow, textTab, textEdit) { if (textShow != null || textTab != null || textEdit != null) view = FollowUpsUi.TEXTS }

    val bar: @Composable () -> Unit = {
        FollowUpsBar(
            done = done && view == FollowUpsUi.ALL,
            openSelected = !done && view == FollowUpsUi.ALL,
            show = view,
            sort = sort,
            onOpen = { done = false; view = FollowUpsUi.ALL },
            onDone = { done = true; view = FollowUpsUi.ALL },
            onShow = { view = it },
            onSort = { sort = it; if (view != FollowUpsUi.ALL) view = FollowUpsUi.ALL },
        )
    }
    when (view) {
        FollowUpsUi.CALLS -> CallsScreen(resumeKey = resumeKey, switcher = bar)
        FollowUpsUi.TEXTS -> MessagesScreen(resumeKey = resumeKey, onBack = null, onOpenSettings = onOpenTextSettings, switcher = bar)
        else -> FollowUpsAll(
            resumeKey = resumeKey,
            bar = bar,
            showDone = done,
            sort = sort,
            onOpenToSchedule = onOpenToSchedule,
        )
    }
}

/** Open · Done · Filter ▾ under the Follow-ups title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpsBar(
    done: Boolean,
    openSelected: Boolean,
    show: Int,
    sort: Int,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onShow: (Int) -> Unit,
    onSort: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val filterLabel = when (show) {
        FollowUpsUi.CALLS -> "Calls ▾"
        FollowUpsUi.TEXTS -> "Texts ▾"
        else -> "Filter ▾"
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = openSelected,
                onClick = onOpen,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            ) { Text("Open") }
            SegmentedButton(
                selected = done,
                onClick = onDone,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            ) { Text("Done") }
            SegmentedButton(
                selected = show != FollowUpsUi.ALL,
                onClick = { menu = true },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                icon = {},
            ) { Text(filterLabel, maxLines = 1) }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuHeading("SHOW")
                listOf(FollowUpsUi.ALL to "Everything", FollowUpsUi.CALLS to "📞  Calls only", FollowUpsUi.TEXTS to "💬  Texts only").forEach { (v, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = if (show == v) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                        onClick = { menu = false; onShow(v) }
                    )
                }
                MenuHeading("SORT")
                listOf(FollowUpsUi.SORT_DUE to "Due time", FollowUpsUi.SORT_CREATED to "Created", FollowUpsUi.SORT_PERSON to "Person").forEach { (v, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = if (sort == v && show == FollowUpsUi.ALL) ({ Icon(Icons.Rounded.Check, contentDescription = null) }) else null,
                        onClick = { menu = false; onSort(v) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

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
    bar: @Composable () -> Unit,
    showDone: Boolean,
    sort: Int,
    onOpenToSchedule: () -> Unit,
) {
    val ctx = LocalContext.current
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val toSchedule by ToScheduleStore.items.collectAsState()
    val myTasks by MyTaskStore.items.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var sendFor by remember { mutableStateOf<SmsReminder?>(null) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editLink by remember { mutableStateOf<Long?>(null) }
    var editItem by remember { mutableStateOf<ToScheduleItem?>(null) }
    var snoozeFor by remember { mutableStateOf<FuEntry?>(null) }
    var newTimeFor by remember { mutableStateOf<FuEntry?>(null) }
    var pickTimeFor by remember { mutableStateOf<FuEntry?>(null) }
    var reviewLaterFor by remember { mutableStateOf<FuEntry?>(null) }
    var setTimeFor by remember { mutableStateOf<ToScheduleItem?>(null) }
    var timelineFor by remember { mutableStateOf<FuEntry?>(null) }
    var textsFor by remember { mutableStateOf<SmsReminder?>(null) }
    var moveTexts by remember { mutableStateOf(true) }
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

    val every = entries(calls, texts)
    val all = every.filter { it.open && !it.hidden }
    val review = all.filter { it.inReview }.sortedByDescending { it.at }
    val open = all.filter { !it.inReview }.sortedBy { it.at }
    val overdue = open.filter { it.callPending && it.at <= now }
    val ahead = open.filter { !(it.callPending && it.at <= now) }
    val finished = every.filter { !it.open }.sortedByDescending { it.finishedAt }
    val noTime = toSchedule.sortedByDescending { it.createdAt }
    val myNoTime = myTasks.count { !it.done && !it.timed }
    val today = LocalDate.now(ZoneId.systemDefault())
    val cs = MaterialTheme.colorScheme

    /** Puts back this follow-up's call and texts as they are now (for Undo). */
    fun undoFor(e: FuEntry): () -> Unit {
        val link = FollowUps.snapshot(e.call?.linkId ?: e.text?.linkId ?: 0L)
        val c = e.call
        val t = e.text
        return {
            c?.let { ReminderStore.restore(it) }
            t?.let { SmsStore.restore(it) }
            link()
        }
    }

    fun done(e: FuEntry) {
        val undo = undoFor(e)
        when {
            e.callPending -> ReminderStore.markDone(e.call!!.id)
            e.textActive -> SmsStore.markDone(e.text!!.id)
        }
        openKey = null
        scope.offerUndo(snack, "Done · ${e.title}", undo)
    }
    fun checked(e: FuEntry) {
        val undo = undoFor(e)
        if (e.callReview) ReminderStore.reviewed(e.call!!.id)
        if (e.textReview) SmsStore.markDone(e.text!!.id, linked = false)
        openKey = null
        scope.offerUndo(snack, "Checked · ${e.title}", undo)
    }
    fun snooze(e: FuEntry, ms: Long) {
        val c = e.call ?: return
        val undo = undoFor(e)
        ReminderStore.reschedule(c.id, ms, "Snoozed to")
        openKey = null
        scope.offerUndo(snack, "Snoozed to ${Fmt.dueLabel(ms)}", undo)
    }
    fun newTime(e: FuEntry, ms: Long) {
        val undo = undoFor(e)
        val c = e.call
        val t = e.text
        if (c != null && (e.callPending || e.callReview || t == null || !e.textActive)) {
            ReminderStore.reschedule(c.id, ms, "Moved to")
            // A new time moves the task's due date: his texts follow it (Snooze only moves your call).
            if (!c.done) FollowUps.onNewTime(ctx, c, ms, moveTexts)
        } else if (t != null) {
            SmsStore.reschedule(t.id, ms)
            if (t.taskDue > 0L) SmsStore.setTaskDue(t.id, ms, "Message updated: due ${Fmt.fullDate(ms)}")
        }
        moveTexts = true
        openKey = null
        scope.offerUndo(snack, "Moved to ${Fmt.dueLabel(ms)}", undo)
    }
    fun reviewLater(e: FuEntry, ms: Long) {
        val undo = undoFor(e)
        if (e.callReview) ReminderStore.reviewLater(e.call!!.id, ms)
        if (e.textReview) SmsStore.reviewLater(e.text!!.id, ms)
        openKey = null
        scope.offerUndo(snack, "Back in To review ${Fmt.dueLabel(ms)}", undo)
    }
    fun delete(e: FuEntry) {
        val undo = undoFor(e)
        val link = e.call?.linkId?.takeIf { it != 0L } ?: e.text?.linkId?.takeIf { it != 0L }
        if (link != null) {
            ReminderStore.items.value.filter { it.linkId == link }.forEach { ReminderStore.delete(it.id) }
            SmsStore.items.value.filter { it.linkId == link }.forEach { SmsStore.delete(it.id) }
        } else {
            e.call?.let { ReminderStore.delete(it.id) }
            e.text?.let { SmsStore.delete(it.id) }
        }
        openKey = null
        scope.offerUndo(snack, "Deleted · ${e.title}", undo)
    }
    fun moveBack(e: FuEntry) {
        val undo = undoFor(e)
        when {
            e.call != null -> ReminderStore.reopen(e.call.id)
            e.text != null -> SmsStore.reopen(e.text.id)
        }
        openKey = null
        scope.offerUndo(snack, "Moved back · ${e.title}", undo)
    }
    fun removeTime(e: FuEntry) {
        val undo = undoFor(e)
        val item = FollowUps.removeTime(ctx, e.call, e.text)
        openKey = null
        scope.offerUndo(snack, "Time removed · texts paused · in To schedule") {
            ToScheduleStore.remove(item.id)
            undo()
        }
    }
    fun moveToMyTasks(e: FuEntry) {
        val undo = undoFor(e)
        val task = FollowUps.moveToMyTasks(ctx, e.call, e.text)
        openKey = null
        scope.offerUndo(snack, "Moved to My tasks" + if (e.text != null) " · texts cancelled" else "") {
            MyTaskStore.delete(task.id)
            undo()
        }
    }
    fun edit(e: FuEntry) {
        editLink = FollowUps.ensureLink(ctx, e.call, e.text)
    }
    fun call(e: FuEntry) {
        val r = e.call
        if (r != null && (r.review || (!r.done && r.dueAt <= System.currentTimeMillis()))) FollowUp.mark(ctx, r)
        dial(ctx, e.number)
    }
    fun deleteItem(t: ToScheduleItem) {
        ToScheduleStore.remove(t.id)
        openKey = null
        scope.offerUndo(snack, "Deleted · ${t.headline}") { ToScheduleStore.put(t) }
    }
    fun itemToMyTasks(t: ToScheduleItem) {
        val task = FollowUps.moveToMyTasks(ctx, t)
        openKey = null
        scope.offerUndo(snack, "Moved to My tasks") { MyTaskStore.delete(task.id); ToScheduleStore.put(t) }
    }
    fun setTime(t: ToScheduleItem) {
        if (t.needsDetails) editItem = t else setTimeFor = t
    }

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
        Box(Modifier.fillMaxSize()) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
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
                FollowUpsHeader(
                    when {
                        overdue.isNotEmpty() && review.isNotEmpty() -> "${overdue.size} overdue · ${review.size} to review"
                        overdue.isNotEmpty() -> "${overdue.size} overdue"
                        review.isNotEmpty() -> "${review.size} to review"
                        open.isEmpty() && noTime.isEmpty() -> "All caught up"
                        else -> "${open.size + noTime.size} open"
                    },
                    red = overdue.isNotEmpty() || review.isNotEmpty(),
                )
            }
            item(key = "switch") { bar() }
            val waiting = noTime.size + myNoTime
            if (!showDone && waiting > 0) {
                item(key = "to-schedule") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = cs.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable(onClick = onOpenToSchedule)
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = cs.onTertiaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "To schedule ($waiting) · no time set yet",
                                color = cs.onTertiaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text("Open", color = cs.onTertiaryContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            val row: @Composable (FuEntry, RowPos) -> Unit = { e, pos ->
                FollowUpRow(
                    e = e,
                    pos = pos,
                    now = now,
                    expanded = openKey == e.key,
                    onToggle = { openKey = if (openKey == e.key) null else e.key },
                    onCall = { call(e) },
                    onTexts = { textsFor = it },
                    onDone = { if (e.inReview) checked(e) else done(e) },
                    onSnooze = { snoozeFor = e },
                    onNewTime = { newTimeFor = e },
                    onReviewLater = { reviewLaterFor = e },
                    onEdit = { edit(e) },
                    onRemoveTime = { removeTime(e) },
                    onMove = { moveToMyTasks(e) },
                    onTimeline = { timelineFor = e },
                    onDelete = { delete(e) },
                    onMoveBack = { moveBack(e) },
                )
            }
            fun block(key: String, title: String, count: String, color: Color, list: List<FuEntry>) {
                if (list.isEmpty()) return
                item(key = "group-$key") { DayHeader(title, count, color) }
                itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                    Box(Modifier.animateItem()) { row(e, rowPos(i, list.size)) }
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
                if (all.isEmpty() && noTime.isEmpty()) {
                    item(key = "empty") {
                        EmptyPicture(
                            Icons.Rounded.PhoneCallback,
                            "Nothing to follow up",
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
                if (noTime.isNotEmpty()) {
                    item(key = "group-notime") { DayHeader("No time", "${noTime.size}", cs.onSurfaceVariant) }
                    itemsIndexed(noTime, key = { _, t -> "notime-${t.id}" }) { i, t ->
                        Box(Modifier.animateItem()) {
                            NoTimeRow(
                                t = t,
                                pos = rowPos(i, noTime.size),
                                expanded = openKey == "notime-${t.id}",
                                onToggle = { openKey = if (openKey == "notime-${t.id}") null else "notime-${t.id}" },
                                onCall = { dial(ctx, t.number) },
                                onSetTime = { setTime(t) },
                                onEdit = { editItem = t },
                                onMove = { itemToMyTasks(t) },
                                onDelete = { deleteItem(t) },
                            )
                        }
                    }
                }
            }
        }
        SlimTitleBar(listState, "Follow-ups", padding.calculateTopPadding(), Modifier.align(Alignment.TopCenter))
        }
    }

    if (showNew) FollowUpDialog(onDismiss = { showNew = false })
    editLink?.let { link ->
        val e = every.firstOrNull { (it.call?.linkId ?: it.text?.linkId) == link }
        FollowUpDialog(
            onDismiss = { editLink = null },
            linkId = link,
            onMoveToMyTasks = e?.takeIf { it.open }?.let { en -> { moveToMyTasks(en) } },
        )
    }
    editItem?.let { t ->
        FollowUpDialog(onDismiss = { editItem = null }, item = t, onMoveToMyTasks = { itemToMyTasks(t) })
    }

    snoozeFor?.let { e ->
        SnoozeSheet(
            heading = "Snooze",
            about = e.title,
            onPick = { ms -> snoozeFor = null; snooze(e, ms) },
            onPickTime = { snoozeFor = null; pickTimeFor = e },
            onDismiss = { snoozeFor = null },
            plusRow = true
        )
    }
    newTimeFor?.let { e ->
        val hasTexts = e.call != null && !e.call.done && e.textActive
        SnoozeSheet(
            heading = "New time",
            about = e.title,
            onPick = { ms -> newTimeFor = null; newTime(e, ms) },
            onPickTime = { newTimeFor = null; pickTimeFor = e },
            onDismiss = { newTimeFor = null; moveTexts = true },
            footer = if (hasTexts) ({ TextsMoveSwitch(moveTexts) { moveTexts = it } }) else null
        )
    }
    pickTimeFor?.let { e ->
        DateTimePickerFlow(
            initialMs = maxOf(e.at, now + 60_000L),
            onPicked = { ms -> pickTimeFor = null; newTime(e, ms) },
            onDismiss = { pickTimeFor = null; moveTexts = true }
        )
    }
    reviewLaterFor?.let { e ->
        SnoozeSheet(
            heading = "Review later",
            about = e.title,
            onPick = { ms -> reviewLaterFor = null; reviewLater(e, ms) },
            onPickTime = { reviewLaterFor = null },
            onDismiss = { reviewLaterFor = null },
            plusRow = true
        )
    }
    setTimeFor?.let { t ->
        SetTimeSheet(
            heading = "Set time",
            about = t.headline,
            onPick = { ms ->
                setTimeFor = null
                FollowUps.setTime(ctx, t, ms)
                Toast.makeText(ctx, "Set · ${Fmt.dueLabel(ms)}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { setTimeFor = null },
        )
    }
    timelineFor?.let { e ->
        val c = e.call?.let { r -> calls.firstOrNull { it.id == r.id } ?: r }
        if (c != null) CallHistoryDialog(r = c, onDismiss = { timelineFor = null })
        else e.text?.let { t -> HistoryDialog(r = texts.firstOrNull { it.id == t.id } ?: t, onDismiss = { timelineFor = null }) }
    }
    textsFor?.let { t ->
        TextsSheet(
            t = texts.firstOrNull { it.id == t.id } ?: t,
            onDismiss = { textsFor = null },
            onSendNow = { r -> textsFor = null; sendFor = r },
            onChange = { r -> textsFor = null; editLink = FollowUps.ensureLink(ctx, null, r) },
            onStop = { r ->
                textsFor = null
                val before = r
                SmsStore.markDone(r.id, linked = false)
                scope.offerUndo(snack, "Texts stopped") { SmsStore.restore(before) }
            },
        )
    }

    sendFor?.let { r ->
        AlertDialog(
            onDismissRequest = { sendFor = null },
            title = { Text("Send now?") },
            text = { Text("Sends “${r.message}” to ${r.title} now.") },
            confirmButton = {
                TextButton(onClick = {
                    sendFor = null
                    if (!r.isBurst && SmsSender.InFlight.busy(ctx, r.id, System.currentTimeMillis())) {
                        Toast.makeText(ctx, "Already sending this message", Toast.LENGTH_SHORT).show()
                    } else {
                        SmsSender.sendDue(ctx, r.id, manual = true)
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sendFor = null }) { Text("Cancel") } }
        )
    }
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
 * A follow-up as one slim row: time, the task in bold, the person, chips. Small round buttons on the chips line
 * say what it has: 📞 blue for the call, 💬 green for the texts, grey with ✓ once that part is finished, and WA
 * opens his WhatsApp chat with the reminder typed. Tapping the row opens it in place with every action.
 */
@Composable
private fun FollowUpRow(
    e: FuEntry,
    pos: RowPos,
    now: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onTexts: (SmsReminder) -> Unit,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onNewTime: () -> Unit,
    onReviewLater: () -> Unit,
    onEdit: () -> Unit,
    onRemoveTime: () -> Unit,
    onMove: () -> Unit,
    onTimeline: () -> Unit,
    onDelete: () -> Unit,
    onMoveBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val call = e.call
    val text = e.text
    val overdue = e.callPending && call!!.dueAt <= now
    val finished = !e.open
    val noTask = call != null && call.note.isBlank() && text == null

    val chips = buildList {
        if (finished) {
            when {
                e.moved -> add(RowChip("Moved to My tasks", ChipTone.BLUE, Icons.Rounded.CheckBox))
                text?.review == SmsReminder.Review.REPLIED || text?.log?.any { it.status == SmsLogEntry.Status.REPLIED_DONE } == true ->
                    add(RowChip("Replied “${text!!.doneReply}”", ChipTone.GREEN, Icons.Rounded.Check))
            }
        } else {
            if (overdue) add(RowChip(RowFmt.late(call!!.dueAt, now), ChipTone.RED))
            if (e.callPending && text != null && e.textActive) add(RowChip(Fmt.timeOnly(call!!.dueAt), ChipTone.BLUE, Icons.Rounded.Call))
        }
        if (text != null && !finished) {
            when {
                e.textActive -> {
                    val base = text.baseTimes
                    val label = when {
                        text.groupCount > 1 -> base.filter { it >= text.nextTextAt - text.groupGapMin * 60_000L * text.groupCount }
                            .ifEmpty { listOf(text.nextTextAt) }.joinToString(" · ") { Fmt.timeOnly(it) } + " · burst ${text.groupCount}"
                        text.slots.isNotEmpty() -> (text.burstIndex until text.burstCount).joinToString(" · ") { Fmt.timeOnly(text.textAt(it)) }
                        text.isBurst && text.burstIndex > 0 -> "${text.burstIndex} of ${text.burstCount} sent"
                        text.isBurst -> text.burstLabel ?: ""
                        else -> Fmt.timeOnly(text.alarmAt)
                    }
                    add(RowChip(label, ChipTone.GREEN, Icons.Rounded.Sms))
                }
                text.review == SmsReminder.Review.REPLIED -> add(RowChip("Replied “${text.doneReply}”", ChipTone.GREEN, Icons.Rounded.Check))
                text.review == SmsReminder.Review.NOT_SENT -> add(RowChip("Text not sent", ChipTone.RED))
                text.review != null -> add(RowChip(if (text.tracked) "No reply" else "Texts sent", if (text.tracked) ChipTone.ORANGE else ChipTone.MUTED))
            }
            if (text.tracked && text.code.isNotEmpty()) add(RowChip(text.code, ChipTone.BLUE, Icons.Rounded.Tag))
        }
        if (e.callReview) add(RowChip(if (call!!.note.isBlank()) "No task written" else "Done, review it", ChipTone.GREEN))
        if (!finished && call != null && call.autopilot && text != null) add(RowChip("Autopilot", ChipTone.MUTED, Icons.Rounded.Bolt))
        if (!finished && call != null && call.delays.isNotEmpty()) add(RowChip("Delayed ${call.delays.size}×", ChipTone.ORANGE, Icons.Rounded.History, onTimeline))
        if (call != null && call.repeatLabel != null && e.callPending) add(RowChip(call.repeatLabel!!, ChipTone.MUTED, Icons.Rounded.AutoMode))
    }

    val (timeTop, timeSub) = when {
        finished -> (if (e.moved) "Moved" else "Done") to Fmt.timeOnly(e.finishedAt)
        e.inReview -> when {
            e.textReview && text!!.review == SmsReminder.Review.REPLIED -> "Replied" to RowFmt.doneSub(text.reviewAt)
            e.textReview -> (if (text!!.review == SmsReminder.Review.NOT_SENT) "Failed" else "Sent") to RowFmt.doneSub(text.reviewAt)
            else -> "Done" to RowFmt.doneSub(call!!.doneAt)
        }
        else -> Fmt.timeOnly(e.at) to (if (!e.callPending && e.textActive && text!!.burstIndex > 0) "next text" else RowFmt.timeSub(e.at, now))
    }
    val hasNumber = e.number.isNotBlank()
    val timelineCount = (call?.historyLines()?.size ?: 0) + (text?.log?.size ?: 0)
    val timelineLabel = if (timelineCount > 0) "Timeline ($timelineCount)" else "Timeline"

    TaskRow(
        pos = pos,
        timeTop = timeTop,
        timeSub = timeSub,
        timeRed = overdue,
        title = e.title,
        titleStyle = when {
            finished -> TitleStyle.DONE
            noTask -> TitleStyle.NO_TASK
            else -> TitleStyle.NORMAL
        },
        person = e.person,
        personDetail = e.number.takeIf { e.name.isNotBlank() && it.isNotBlank() },
        chips = chips,
        expanded = expanded,
        onClick = onToggle,
        bottomActions = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (call != null) {
                    RoundAction(
                        Icons.Rounded.Call, "Call", cs.primary, cs.onPrimary, hasNumber,
                        finished = !e.callPending && !e.callReview, onClick = onCall
                    )
                }
                if (text != null) {
                    RoundAction(
                        Icons.Rounded.Sms, "Texts", cs.secondary, cs.onSecondary,
                        finished = !e.textActive, onClick = { onTexts(text) }
                    )
                }
                if (hasNumber) {
                    WhatsAppAction {
                        if (call != null) WhatsApp.forCall(ctx, call) else if (text != null) WhatsApp.forText(ctx, text)
                    }
                }
            }
        },
        expandedContent = {
            when {
                finished -> RowActions(
                    actions = listOfNotNull(
                        RowAction("Not done, move back", Icons.AutoMirrored.Rounded.Undo, ActionStyle.ORANGE, onMoveBack).takeIf { !e.moved },
                        RowAction("Call again", Icons.Rounded.Replay, ActionStyle.FILLED, onCall).takeIf { hasNumber },
                    ),
                    links = listOf(
                        RowLink(timelineLabel, onClick = onTimeline),
                        RowLink("Delete", danger = true, onClick = onDelete),
                    )
                )
                e.inReview -> RowActions(
                    actions = listOfNotNull(
                        RowAction("Call now", Icons.Rounded.Call, ActionStyle.FILLED, onCall).takeIf { hasNumber },
                        RowAction("New time", Icons.Rounded.Schedule, ActionStyle.OUTLINED, onNewTime),
                        RowAction("Mark done", Icons.Rounded.Check, ActionStyle.GREEN, onDone),
                    ),
                    links = listOf(
                        RowLink("Review later", onClick = onReviewLater),
                        RowLink("Edit", onClick = onEdit),
                        RowLink("Move to My tasks", onClick = onMove),
                        RowLink(timelineLabel, onClick = onTimeline),
                        RowLink("Delete", danger = true, onClick = onDelete),
                    )
                )
                else -> RowActions(
                    actions = listOfNotNull(
                        RowAction("Call", Icons.Rounded.Call, ActionStyle.FILLED, onCall).takeIf { hasNumber },
                        RowAction("New time", Icons.Rounded.Schedule, ActionStyle.OUTLINED, onNewTime),
                        RowAction("Done", Icons.Rounded.Check, ActionStyle.GREEN, onDone),
                        // Snooze only once the call is due: it moves my call, not his texts.
                        RowAction("Snooze", Icons.Rounded.Snooze, ActionStyle.OUTLINED, onSnooze).takeIf { overdue },
                    ),
                    links = listOf(
                        RowLink("Edit", onClick = onEdit),
                        RowLink("Remove time", onClick = onRemoveTime),
                        RowLink("Move to My tasks", onClick = onMove),
                        RowLink(timelineLabel, onClick = onTimeline),
                        RowLink("Delete", danger = true, onClick = onDelete),
                    )
                )
            }
        },
    )
}

/** A follow-up waiting with no time (To schedule), under "No time". */
@Composable
private fun NoTimeRow(
    t: ToScheduleItem,
    pos: RowPos,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onSetTime: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val chips = buildList {
        if (t.needsDetails) add(RowChip("Needs details", ChipTone.ORANGE, Icons.Rounded.Warning, onEdit))
        if (t.textOn && !t.needsDetails) add(RowChip("Texts paused", ChipTone.ORANGE))
        add(RowChip("＋ Set time", ChipTone.BLUE, onClick = onSetTime))
    }
    TaskRow(
        pos = pos,
        timeTop = "No time",
        timeSub = null,
        timeRed = false,
        title = t.headline,
        titleStyle = if (t.note.isBlank() && t.message.isBlank()) TitleStyle.NO_TASK else TitleStyle.NORMAL,
        person = t.title.takeIf { t.number.isNotBlank() || t.name.isNotBlank() },
        personDetail = t.number.takeIf { t.name.isNotBlank() && it.isNotBlank() },
        chips = chips,
        expanded = expanded,
        onClick = onToggle,
        bottomActions = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.callOn && t.number.isNotBlank()) {
                    RoundAction(Icons.Rounded.Call, "Call", cs.primary, cs.onPrimary, onClick = onCall)
                }
                if (t.textOn && t.number.isNotBlank()) {
                    RoundAction(Icons.Rounded.Sms, "Texts", cs.secondary, cs.onSecondary, onClick = onEdit)
                }
                if (t.number.isNotBlank()) {
                    WhatsAppAction { WhatsApp.open(ctx, t.number, WhatsApp.message(ctx, t.code, t.headline, 0L, t.name)) }
                }
            }
        },
        expandedContent = {
            RowActions(
                actions = listOfNotNull(
                    RowAction(if (t.needsDetails) "Add details" else "Set time", Icons.Rounded.Schedule, ActionStyle.FILLED, onSetTime),
                    RowAction("Call", Icons.Rounded.Call, ActionStyle.OUTLINED, onCall).takeIf { t.number.isNotBlank() },
                ),
                links = listOf(
                    RowLink("Edit", onClick = onEdit),
                    RowLink("Move to My tasks", onClick = onMove),
                    RowLink("Delete", danger = true, onClick = onDelete),
                )
            )
        },
    )
}

/** 💬 on a row: what was sent, what is coming (with its burst), replies, and Send next now / Change times / Stop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextsSheet(
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSendNow(t) },
                    enabled = t.active,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send next now", maxLines = 1)
                }
                OutlinedButton(onClick = { onChange(t) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Change times", maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onChange(t) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit message", maxLines = 1)
                }
                OutlinedButton(onClick = { onStop(t) }, enabled = !t.done, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop texts", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp)
    )
}

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
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Projects", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TopActions()
        }
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
                Text("📞💬  One follow-up can remind you to call and text him, at different times.")
                Text("⚡  Autopilot (off by default): his “Done” reply cancels your call.")
                Text("🕘  New time moves his texts too, and updates the due time in the message.")
                Text("👥  People tab: everyone you follow up with. 📁 Projects is coming soon.")
                Text("🎙 ⚙  Recordings and Settings stay at the top right.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

/** The switch at the top of New time for a follow-up whose texts are still going: "His texts move too". */
@Composable
fun TextsMoveSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("His texts move too", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Same clock times on the new day, with the new due time in the message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
