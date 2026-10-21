package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier

/**
 * Tasks of any kind as one block of the same cards as everywhere (Home's Next up, the person page, My day).
 * Tick = Done, tapping opens the card in place; a dossier task opens on its own page. [snack] shows the Undo bars.
 */
@Composable
internal fun WorkBlock(
    list: List<WorkItem>,
    now: Long,
    showPerson: Boolean,
    onOpen: (WorkItem) -> Unit,
    snack: SnackbarHostState? = null,
) {
    val ctx = LocalContext.current
    val host = snack ?: remember { SnackbarHostState() }
    val fu = rememberFuController(host)
    val scope = rememberCoroutineScope()
    val my = remember(host) { MyCardActions(scope, host) }
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val records by DossierStore.records.collectAsState()
    val stars by FuStars.keys.collectAsState()
    LaunchedEffect(Unit) { FuStars.load(ctx) }
    val fes = remember(calls, texts) { entries(calls, texts) }
    fun entryOf(w: WorkItem): FuEntry? =
        fes.firstOrNull { if (w.kind == WorkItem.Kind.CALL) it.call?.id == w.id else it.text?.id == w.id }
    // A follow-up's call and its texts are one card.
    val shown = remember(list, fes) {
        list.distinctBy { w ->
            if (w.kind == WorkItem.Kind.CALL || w.kind == WorkItem.Kind.TEXT) entryOf(w)?.key ?: w.key else w.key
        }
    }
    Column(Modifier.animateContentSize(tween(240))) {
        shown.forEachIndexed { i, w ->
            key(w.key) {
                val pos = rowPos(i, shown.size)
                when (w.kind) {
                    WorkItem.Kind.CALL, WorkItem.Kind.TEXT -> {
                        val e = entryOf(w)
                        if (e != null) fu.Card(e, pos, now, e.call?.starred ?: (e.key in stars))
                    }
                    WorkItem.Kind.MY_TASK -> MyTaskStore.get(w.id)?.let { t ->
                        MyTaskCard(
                            t = t,
                            now = now,
                            listName = null,
                            pos = pos,
                            finishing = t.id in my.ticking,
                            expanded = my.openId == t.id,
                            onToggle = { my.openId = if (my.openId == t.id) null else t.id },
                            onTick = { my.tick(t) },
                            onStar = { MyTaskStore.setStar(t.id, !t.starred) },
                            onEdit = { my.editFor = t },
                            onNewTime = { my.timeFor = t },
                            onMoveToFollowUps = { my.moveFor = t },
                            onDelete = { my.delete(t) },
                        )
                    }
                    WorkItem.Kind.DOSSIER -> DossierStore.get(w.id)?.let { t ->
                        val day = Dossier.openDay(now)
                        val done = records[DossierStore.key(t.id, day)]?.done == true
                        TaskCard(
                            title = t.title,
                            done = done,
                            onTick = {
                                val ok = if (done) DossierStore.untick(t.id, day) else DossierStore.tick(t.id, day)
                                if (!ok) Toast.makeText(ctx, "This day is locked in History", Toast.LENGTH_SHORT).show()
                            },
                            time = Fmt.dueLabel(w.at),
                            late = w.overdue && !done,
                            chips = listOf(RowChip("Dossier", ChipTone.MUTED, Icons.Rounded.Checklist)),
                            starred = t.starred,
                            onStar = { DossierStore.setStar(t.id, !t.starred) },
                            onClick = { onOpen(w) },
                            pos = pos,
                        )
                    }
                }
            }
        }
    }
    fu.Sheets()
    my.Sheets()
}
