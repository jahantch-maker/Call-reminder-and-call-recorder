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
                    Text("History", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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
            MyTaskDialog(
                existing = t,
                onDismiss = onDismiss,
                onSwitchKind = null,
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
fun MyTaskHistoryDialog(t: MyTask, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Tasks from before v0.31 have no lines yet: at least when they were made.
    val lines = t.events.ifEmpty { listOf(TaskEvent(t.createdAt, "Created")) }.sortedByDescending { it.at }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("History", style = MaterialTheme.typography.headlineSmall)
                Text(t.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                lines.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(color = cs.surfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(Fmt.dueLabel(e.at), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(96.dp))
                        Text(e.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Scrolls a list down until [found] says the asked row is on screen (a row inside a group item can't be found
 * by its key), then lets that row bring itself fully into view. Gives up at the end of the list.
 */
suspend fun LazyListState.revealUntil(found: () -> Boolean) {
    delay(150L)
    scrollToItem(0)
    repeat(60) {
        delay(40L)
        if (found()) return
        val step = layoutInfo.viewportSize.height * 0.7f
        if (scrollBy(step) < 1f) return
    }
}

/** Scrolls a list to the item with [key] (a whole row of its own). */
suspend fun LazyListState.revealKey(key: Any) {
    revealUntil {
        val hit = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        hit != null && hit.offset + hit.size <= layoutInfo.viewportEndOffset
    }
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { animateScrollToItem(maxOf(0, it.index - 1)) }
}
