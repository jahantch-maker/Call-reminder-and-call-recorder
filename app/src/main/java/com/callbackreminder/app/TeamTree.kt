package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings › Team (locked 23 Nov, mockup P1 "Tree list"): the website chart as one list for everyone, read only. Only people
 * who have joined, your own row highlighted with YOU, search by name or designation. Tap a person: Reports to, Timetable,
 * Official phone, Call and WhatsApp. All from the website's seats, kept on the phone for no-internet use.
 */
@Composable
fun TeamTree(me: TeamMember, members: List<TeamMember>, tasks: List<TeamTask>) {
    val cs = MaterialTheme.colorScheme
    val seats by Seats.seats.collectAsState()
    val tts by Seats.timetables.collectAsState()
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Seat?>(null) }
    val tree = remember(seats) { Seats.tree() }
    val mySeat = remember(seats, me.uid) { Seats.of(me.uid, me.seatId) }
    val shown = q.trim().lowercase().let { k ->
        if (k.isEmpty()) tree else tree.filter { (s, _) -> k in s.name.lowercase() || k in s.designation.lowercase() }.map { it.first to 0 }
    }

    OutlinedTextField(
        q, { q = it }, placeholder = { Text("Search name or designation") }, singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
    )
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (shown.isEmpty()) Text(
                if (seats.isEmpty()) "The team shows here once it has loaded (needs internet the first time)." else "Nobody matches “$q”.",
                fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(14.dp),
            )
            shown.forEach { (s, depth) -> TreeRow(s, depth, mine = s.id == mySeat?.id, photo = members.find { it.uid == s.uid }?.photo.orEmpty()) { open = s } }
        }
    }

    open?.let { s -> PersonSheet(s, me, members, tasks, tts.isNotEmpty()) { open = null } }
}

@Composable
private fun TreeRow(s: Seat, depth: Int, mine: Boolean, photo: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(if (mine) cs.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One thin guide line per level, like the website chart's lines.
        repeat(depth) {
            Box(Modifier.width(16.dp).fillMaxHeight()) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(cs.outlineVariant))
            }
        }
        Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.name, size = 34.dp, photo = photo)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (mine) {
                        Spacer(Modifier.width(6.dp))
                        Text("YOU", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary,
                            modifier = Modifier.background(cs.primary, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                if (s.dept.isNotBlank()) Text(s.dept, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.designation.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                DesigChip(s.designation)
            }
        }
    }
}

@Composable
private fun DesigChip(d: String) = Text(
    d, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B1C1F), maxLines = 1, overflow = TextOverflow.Ellipsis,
    modifier = Modifier.background(Color(Seats.chipColor(d)), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonSheet(s: Seat, me: TeamMember, members: List<TeamMember>, tasks: List<TeamTask>, ttLoaded: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val m = members.firstOrNull { it.uid == s.uid }
    val head = Seats.seat(s.reportsTo)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp + navBarMissing()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.name, size = 48.dp, photo = members.find { it.uid == s.uid }?.photo.orEmpty())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(listOf(s.designation, s.dept).filter { it.isNotBlank() }.joinToString(" · "), color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            Detail("Reports to", when {
                s.reportsTo == Seats.OWNERS -> "All owners"
                head != null -> listOf(head.name, head.designation).filter { it.isNotBlank() }.joinToString(" · ")
                else -> "Nobody (top)"
            })
            if (ttLoaded || s.timetable.isBlank()) Detail("Timetable", Seats.ttLabel(Seats.timetableOf(s)))
            Detail("Official phone", s.phone.ifBlank { "Not added yet" })
            // v0.50 "See how my people are doing" (portal switch): late and due-today counts of the tasks you gave him.
            if (m != null && me.uid in m.managers && me.can("seeNumbers", false)) {
                val now = System.currentTimeMillis()
                val dayEnd = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val given = tasks.filter { it.fromUid == me.uid && it.toUid == m.uid && it.open && it.due > 0L }
                Detail("Your tasks", "${given.count { it.due < now }} late · ${given.count { it.due in now until dayEnd }} due today")
            }
            if (s.uid != me.uid && s.phone.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { dial(ctx, s.phone) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("📞 Call", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { WhatsApp.open(ctx, s.phone, "") }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F7A45), contentColor = Color.White),
                ) { Text("WhatsApp", fontWeight = FontWeight.Bold) }
            }
            // His day, for the people above him (as the old chart's arrow).
            if (m != null && me.uid in m.managers && me.can("seeDay", true)) {
                TextButton(onClick = { onDismiss(); TeamUi.openDay(m.uid) }, modifier = Modifier.fillMaxWidth()) { Text("See ${m.first}'s day") }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) = Row {
    Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
    Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
}
