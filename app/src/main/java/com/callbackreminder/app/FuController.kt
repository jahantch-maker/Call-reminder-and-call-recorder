package com.callbackreminder.app

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything a follow-up card can do (tick, New time, Edit, Remove time, Move, Timeline, Delete…) with its
 * sheets and Undo bars, so the Follow-ups tab and the Overdue page work the same. Put [Sheets] once on the page.
 */
internal class FuController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    val snack: SnackbarHostState,
) {
    var openKey by mutableStateOf<String?>(null)
    var editLink by mutableStateOf<Long?>(null)
    private var newTimeFor by mutableStateOf<FuEntry?>(null)
    private var pickTimeFor by mutableStateOf<FuEntry?>(null)
    private var reviewLaterFor by mutableStateOf<FuEntry?>(null)
    private var timelineFor by mutableStateOf<FuEntry?>(null)
    private var textsFor by mutableStateOf<SmsReminder?>(null)
    private var sendFor by mutableStateOf<SmsReminder?>(null)
    private var moveTexts by mutableStateOf(true)
    // Just ticked: shown ticked for a moment before it leaves the list.
    var ticking by mutableStateOf(setOf<String>())
        private set

    /** Puts back this follow-up's call and texts as they are now (for Undo). */
    private fun undoFor(e: FuEntry): () -> Unit {
        val link = FollowUps.snapshot(e.call?.linkId ?: e.text?.linkId ?: 0L)
        val c = e.call
        val t = e.text
        return {
            c?.let { ReminderStore.restore(it) }
            t?.let { SmsStore.restore(it) }
            link()
        }
    }

    private fun close(e: FuEntry) { if (openKey == e.key) openKey = null }

    fun done(e: FuEntry) {
        val undo = undoFor(e)
        when {
            e.callPending -> ReminderStore.markDone(e.call!!.id)
            e.textActive -> SmsStore.markDone(e.text!!.id)
        }
        close(e)
        scope.offerUndo(snack, "Done · ${e.title}", undo)
    }

    fun checked(e: FuEntry) {
        val undo = undoFor(e)
        (e.call?.code ?: e.text?.code)?.let { ReplyNotes.clear(ctx, it) }
        if (e.callReview) ReminderStore.reviewed(e.call!!.id)
        if (e.textReview) SmsStore.markDone(e.text!!.id, linked = false)
        close(e)
        scope.offerUndo(snack, "Checked · ${e.title}", undo)
    }

    fun newTime(e: FuEntry, ms: Long) {
        val undo = undoFor(e)
        val c = e.call
        val t = e.text
        if (c != null && (e.callPending || e.callReview || t == null || !e.textActive)) {
            ReminderStore.newTime(c.id, ms)
            // A new time moves the task's due date: the texts follow it.
            if (!c.done) FollowUps.onNewTime(ctx, c, ms, moveTexts)
        } else if (t != null) {
            SmsStore.reschedule(t.id, ms)
            if (t.taskDue > 0L) SmsStore.setTaskDue(t.id, ms, "Message updated: due ${Fmt.fullDate(ms)}")
        }
        moveTexts = true
        openKey = null
        scope.offerUndo(snack, "Moved to ${Fmt.dueLabel(ms)}", undo)
    }

    private fun reviewLater(e: FuEntry, ms: Long) {
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
        close(e)
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

    fun call(e: FuEntry) {
        val r = e.call
        if (r != null && (r.review || (!r.done && r.dueAt <= System.currentTimeMillis()))) FollowUp.mark(ctx, r)
        dial(ctx, e.number)
    }

    /** The tick: Done (or Checked in review); on the Done tab it moves the follow-up back. */
    fun tick(e: FuEntry) {
        if (e.key in ticking) return
        if (!e.open) { moveBack(e); return }
        ticking = ticking + e.key
        scope.launch {
            delay(380L)
            if (e.inReview) checked(e) else done(e)
            ticking = ticking - e.key
        }
    }

    fun star(e: FuEntry) {
        val c = e.call
        if (c != null) ReminderStore.setStar(c.id, !c.starred) else FuStars.toggle(ctx, e.key)
    }

    /** One follow-up card wired to all of the above. */
    @Composable
    fun Card(e: FuEntry, pos: RowPos, now: Long, starred: Boolean, highlighted: Boolean = false) {
        FollowUpCard(
            e = e,
            pos = pos,
            now = now,
            ticking = e.key in ticking,
            starred = starred,
            expanded = openKey == e.key,
            highlighted = highlighted,
            onToggle = { openKey = if (openKey == e.key) null else e.key },
            onTick = { tick(e) },
            onStar = { star(e) },
            onCall = { call(e) },
            onTexts = { textsFor = it },
            onNewTime = { newTimeFor = e },
            onReviewLater = { reviewLaterFor = e },
            onEdit = { editLink = FollowUps.ensureLink(ctx, e.call, e.text) },
            onRemoveTime = { removeTime(e) },
            onMove = { moveToMyTasks(e) },
            onTimeline = { timelineFor = e },
            onDelete = { delete(e) },
        )
    }

    /** The sheets and windows the cards open. */
    @Composable
    fun Sheets() {
        val calls by ReminderStore.items.collectAsState()
        val texts by SmsStore.items.collectAsState()
        editLink?.let { link ->
            val e = entries(calls, texts).firstOrNull { (it.call?.linkId ?: it.text?.linkId) == link }
            FollowUpDialog(
                onDismiss = { editLink = null },
                linkId = link,
                onMoveToMyTasks = e?.takeIf { it.open }?.let { en -> { moveToMyTasks(en) } },
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
                plusRow = true,
                footer = if (hasTexts) ({ TextsMoveSwitch(moveTexts) { moveTexts = it } }) else null
            )
        }
        pickTimeFor?.let { e ->
            DateTimePickerFlow(
                initialMs = maxOf(e.at, System.currentTimeMillis() + 60_000L),
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
}

@Composable
internal fun rememberFuController(snack: SnackbarHostState): FuController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(snack) { FuController(ctx, scope, snack) }
}
