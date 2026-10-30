package com.callbackreminder.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The day a Timeline row belongs to (one small chip above the first row of each day, like WhatsApp). */
class DayChip(val at: Long) {
    val label: String get() = TimelineDates.chip(at)
}

object TimelineDates {
    private val zone get() = ZoneId.systemDefault()
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val dayYearFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

    fun day(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** "Today", "Yesterday", else "Wed 28 Oct" (with the year when it is not this year). */
    fun chip(at: Long): String {
        val d = day(at)
        val today = LocalDate.now(zone)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> d.format(dayFmt)
            else -> d.format(dayYearFmt)
        }
    }

    /**
     * A line written as "Time moved · Today, 4:00 pm" says "Today" for the day it was written, not for the day it is
     * read: the words are turned into real dates, using the day the line was written ([at]).
     */
    fun unfreeze(text: String, at: Long): String {
        if (at <= 0L || (!text.contains("Today, ") && !text.contains("Tomorrow, ") && !text.contains("Yesterday, "))) return text
        val written = day(at)
        fun name(d: LocalDate) = d.format(dayFmt) + ", "
        return text.replace("Today, ", name(written))
            .replace("Tomorrow, ", name(written.plusDays(1)))
            .replace("Yesterday, ", name(written.minusDays(1)))
    }
}

/** [items] in time order with a [DayChip] before the first one of each day. */
fun <T : Any> withDayChips(items: List<T>, at: (T) -> Long): List<Any> {
    val out = ArrayList<Any>(items.size + 4)
    var last: LocalDate? = null
    for (it in items) {
        val d = TimelineDates.day(at(it))
        if (d != last) { out += DayChip(at(it)); last = d }
        out += it
    }
    return out
}

@Composable
fun DayChipView(label: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** The date of the rows now on screen, floating on top while you scroll (the chips themselves scroll with the chat). */
@Composable
fun FloatingDayChip(state: LazyListState, rows: List<Any>, modifier: Modifier = Modifier) {
    val label by remember(rows) {
        derivedStateOf {
            val first = state.firstVisibleItemIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
            (first downTo 0).firstNotNullOfOrNull { (rows.getOrNull(it) as? DayChip)?.label }.orEmpty()
        }
    }
    AnimatedVisibility(
        visible = state.isScrollInProgress && label.isNotEmpty(),
        enter = fadeIn(), exit = fadeOut(), modifier = modifier,
    ) { DayChipView(label) }
}

/** One button of the bar that replaces the Timeline's top while messages are selected. */
class SelAction(val icon: String, val label: String, val run: () -> Unit)

@Composable
fun TimelineSelectionBar(count: Int, actions: List<SelAction>, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Clear selection") }
        Text("$count", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp).weight(1f))
        actions.forEach { a ->
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = a.run).padding(horizontal = 9.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(a.icon, fontSize = 17.sp)
                Text(a.label, fontSize = 10.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

fun copyText(c: Context, text: String) {
    c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", text))
    Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show()
}

/** Share to WhatsApp or any app: the text, or the photo / voice note as a file. */
fun shareMsg(c: Context, m: TeamMsg) {
    val send = Intent(Intent.ACTION_SEND)
    val data = m.data
    when {
        m.kind == Team.PHOTO && data != null -> send.setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, TeamMedia.shareUri(c, data, "jpg")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        m.kind == Team.VOICE && data != null -> send.setType("audio/aac")
            .putExtra(Intent.EXTRA_STREAM, TeamMedia.shareUri(c, data, "aac")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else -> send.setType("text/plain")
    }
    if (m.text.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, m.text.substringAfterLast('\n'))
    runCatching { c.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
