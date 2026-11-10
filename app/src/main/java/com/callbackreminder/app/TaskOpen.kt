package com.callbackreminder.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow

/** A task opened in place (not in its own tab): a call, a text, a To schedule follow-up or a My task. */
sealed class OpenTask {
    data class Call(val id: Long) : OpenTask()
    data class Text(val id: Long) : OpenTask()
    data class Sched(val id: Long) : OpenTask()
    data class Mine(val id: Long) : OpenTask()

    companion object {
        fun of(item: WorkItem): OpenTask? = when (item.kind) {
            WorkItem.Kind.CALL -> Call(item.id)
            WorkItem.Kind.TEXT -> Text(item.id)
            WorkItem.Kind.MY_TASK -> Mine(item.id)
            WorkItem.Kind.DOSSIER -> null
        }

        fun of(item: ReviewItem): OpenTask = when (item.kind) {
            ReviewItem.Kind.CALL, ReviewItem.Kind.CALL_REVIEW -> Call(item.id)
            ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> Text(item.id)
            ReviewItem.Kind.SCHED_FU -> Sched(item.id)
            ReviewItem.Kind.MY_TASK, ReviewItem.Kind.SCHED_MY -> Mine(item.id)
        }
    }
}

/** A task tapped on Home, People, a person page or Your day: its editor opens over that page. */
object TaskOpenUi {
    val request = kotlinx.coroutines.flow.MutableStateFlow<OpenTask?>(null)

    /** Opens [item] in place; false for a Dossier task, which keeps going to its tab. */
    fun open(item: WorkItem): Boolean {
        val t = OpenTask.of(item) ?: return false
        request.value = t
        return true
    }
}

/** The extra bits an editor opened in place shows: "Show in … ›", History and WhatsApp (null hides each). */
class TaskExtras(
    val listName: String,
    val onShowInList: (() -> Unit)?,
    val onHistory: (() -> Unit)?,
    val onWhatsApp: (() -> Unit)?,
)

/** "Show in Follow-ups ›": a small link next to the editor's title. */
@Composable
fun ShowInListLink(listName: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
        Text(
            "Show in $listName ›",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** Small History and WA buttons, sized to sit in an existing bar without making the window bigger. */
@Composable
fun TaskExtraButtons(x: TaskExtras) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        x.onHistory?.let { open ->
            Surface(onClick = open, shape = RoundedCornerShape(18.dp), color = cs.surfaceVariant, contentColor = cs.onSurface) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Timeline", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        x.onWhatsApp?.let { WhatsAppAction(size = 32.dp, onClick = it) }
    }
}

/**
 * The task's own editor, opened right where you are (the same window as on its page), with "Show in … ›",
 * History and WA. [onShowInList] opens its tab (true: My tasks), which scrolls to it and outlines it for a moment.
 */
@Composable
fun TaskEditorDialog(task: OpenTask, onDismiss: () -> Unit, onShowInList: ((mine: Boolean) -> Unit)? = null) {
    val ctx = LocalContext.current
    var history by remember { mutableStateOf(false) }
    when (task) {
        is OpenTask.Call, is OpenTask.Text -> {
            val call = (task as? OpenTask.Call)?.let { ReminderStore.get(it.id) }
            val text = (task as? OpenTask.Text)?.let { SmsStore.get(it.id) }
            if (call == null && text == null) {
                LaunchedEffect(Unit) { onDismiss() }
                return
            }
            val link = remember(task) { FollowUps.ensureLink(ctx, call, text) }
            val number = call?.number ?: text?.number.orEmpty()
            FollowUpDialog(
                onDismiss = onDismiss,
                linkId = link,
                extras = TaskExtras(
                    listName = "Follow-ups",
                    onShowInList = onShowInList?.let { go -> { FollowUpsUi.showEntry.value = "link-$link"; go(false) } },
                    onHistory = { history = true },
                    onWhatsApp = if (number.isNotBlank()) ({
                        if (call != null) WhatsApp.forCall(ctx, call) else WhatsApp.forText(ctx, text!!)
                    }) else null,
                ),
            )
            if (history) {
                // The call's history (calls, new times and why) when there is one, else the texts'.
                val c = FollowUps.call(link) ?: call
                val t = FollowUps.text(link) ?: text
                if (c != null) CallHistoryDialog(r = ReminderStore.get(c.id) ?: c, onDismiss = { history = false })
                else if (t != null) HistoryDialog(SmsStore.get(t.id) ?: t, onDismiss = { history = false })
            }
        }
        is OpenTask.Sched -> {
            val item = ToScheduleStore.get(task.id)
            if (item == null) {
                LaunchedEffect(Unit) { onDismiss() }
                return
            }
            FollowUpDialog(
                onDismiss = onDismiss,
                item = item,
                extras = TaskExtras(
                    listName = "Follow-ups",
                    onShowInList = null,
                    onHistory = null,
                    onWhatsApp = if (item.number.isNotBlank()) ({ WhatsApp.open(ctx, item.number, WhatsApp.message(ctx, "", item.headline, 0L, item.name)) }) else null,
                ),
            )
        }
        is OpenTask.Mine -> {
            val t = MyTaskStore.get(task.id)
            if (t == null) {
                LaunchedEffect(Unit) { onDismiss() }
                return
            }
            // v0.51 Move to Follow-ups from the task's own window, wherever it opens (Review session, Home, People…).
            var moveFor by remember { mutableStateOf<MyTask?>(null) }
            moveFor?.let { m ->
                FollowUpDialog(onDismiss = { moveFor = null; onDismiss() }, fromMyTask = m)
                return
            }
            MyTaskDialog(
                existing = t,
                onDismiss = onDismiss,
                onSwitchKind = null,
                onMoveToFollowUps = { moveFor = it },
                extras = TaskExtras(
                    listName = "My tasks",
                    onShowInList = onShowInList?.let { go -> { MyTasksUi.showTask.value = t.id; go(true) } },
                    onHistory = { history = true },
                    onWhatsApp = if (t.hasPerson) ({ WhatsApp.open(ctx, t.number, WhatsApp.message(ctx, "", t.title, t.dueAt, t.name)) }) else null,
                ),
            )
            if (history) MyTaskHistoryDialog(MyTaskStore.get(t.id) ?: t) { history = false }
        }
    }
}

/** A My task's History: created, time set / moved / removed, done and not done, newest first. */
@Composable
fun MyTaskHistoryDialog(t: MyTask, onDismiss: () -> Unit) = MyTaskTimeline(t, onDismiss)

/**
 * Scrolls a list down until [found] says the asked row is on screen (a row inside a group item can't be found
 * by its key), then lets that row bring itself fully into view. Gives up at the end of the list.
 */
suspend fun LazyListState.revealUntil(found: () -> Boolean) {
    // Wait until the list is drawn: a scroll before that moves nothing, and the search gave up at once.
    withTimeoutOrNull(2_000L) {
        snapshotFlow { layoutInfo.totalItemsCount > 0 && layoutInfo.viewportSize.height > 0 }.first { it }
    }
    delay(150L)
    scrollToItem(0)
    repeat(80) {
        delay(40L)
        if (found()) return
        val step = layoutInfo.viewportSize.height * 0.7f
        if (scrollBy(step) < 1f) return
    }
}

/** Scrolls a list to the item with one of [keys] (a whole row of its own). */
suspend fun LazyListState.revealKey(vararg keys: Any) {
    revealUntil {
        val hit = layoutInfo.visibleItemsInfo.firstOrNull { it.key in keys }
        hit != null && hit.offset + hit.size <= layoutInfo.viewportEndOffset
    }
    layoutInfo.visibleItemsInfo.firstOrNull { it.key in keys }?.let { animateScrollToItem(maxOf(0, it.index - 1)) }
}

/**
 * v0.51 The one way every "show this task" jumps (search, Show in list, notifications, widgets, Review, People,
 * calendar…): waits until the list has built the item that holds the task ([keyOf], null until then), finds that
 * item, puts it at the top exactly, then [bring] lets the task's own row bring all of itself into view.
 */
suspend fun LazyListState.showItem(keyOf: () -> Any?, bring: () -> Unit) {
    var key: Any? = null
    repeat(60) {
        key = keyOf()
        if (key != null) return@repeat
        delay(40L)
    }
    val k = key ?: run { bring(); return }
    revealUntil { layoutInfo.visibleItemsInfo.any { it.key == k } }
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.let { scrollToItem(it.index) }
    delay(80L)
    bring()
}
