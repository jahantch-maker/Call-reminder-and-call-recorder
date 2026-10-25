package com.callbackreminder.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Tap a follow-up in the Calendar: THE Follow-ups card, already open, with everything it can do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalFollowUpSheet(f: CalFollowUp, now: Long, onDismiss: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    val fu = rememberFuController(snack)
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val e = remember(calls, texts) {
        entries(calls, texts).firstOrNull { if (f.isCall) it.call?.id == f.id else it.text?.id == f.id }
    }
    LaunchedEffect(e?.key) { if (e != null && fu.openKey == null) fu.openKey = e.key }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp + navBarMissing())) {
            if (e == null) LaunchedEffect(Unit) { onDismiss() } else fu.Card(e, RowPos.SINGLE, now, e.call?.starred ?: false)
            SnackbarHost(snack)
        }
    }
    fu.Sheets()
}

/** Tap a My task in the Calendar: THE My tasks card, already open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalMyTaskSheet(taskId: Long, now: Long, onDismiss: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val my = remember(snack) { MyCardActions(scope, snack) }
    val tasks by MyTaskStore.items.collectAsState()
    val t = tasks.firstOrNull { it.id == taskId }
    LaunchedEffect(taskId) { my.openId = taskId }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp + navBarMissing())) {
            if (t == null) LaunchedEffect(Unit) { onDismiss() } else MyTaskCard(
                t = t,
                now = now,
                listName = null,
                pos = RowPos.SINGLE,
                finishing = t.id in my.ticking,
                expanded = my.openId == t.id,
                onToggle = { my.openId = if (my.openId == t.id) null else t.id },
                onTick = { my.tick(t) },
                onStar = { MyTaskStore.setStar(t.id, !t.starred) },
                onEdit = { my.editFor = t },
                onNewTime = { my.timeFor = t },
                onMoveToFollowUps = { my.moveFor = t },
                onDelete = { my.delete(t); onDismiss() },
            )
            SnackbarHost(snack)
        }
    }
    my.Sheets()
}
