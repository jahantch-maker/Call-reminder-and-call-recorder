package com.callbackreminder.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** WhatsApp's ticks on a task you gave: one grey = sent, two grey = on his phone, two blue = he opened it. */
object Ticks {
    val GREY = Color(0xFF9EA2B8)
    val BLUE = Color(0xFF53BDEB)

    fun seen(t: TeamTask): Boolean = t.seenAt > 0L || t.status != Team.SENT
    fun count(t: TeamTask): Int = if (seen(t) || t.deliveredAt > 0L) 2 else 1
}

/** The tick marks alone, drawn like WhatsApp's (the second one overlaps the first). */
@Composable
fun TickMarks(two: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = if (two) 17.dp else 12.dp, height = 11.dp)) {
        val s = size.height / 11f
        val stroke = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun tick(dx: Float, short: Boolean) {
            val p = Path()
            if (!short) {
                p.moveTo(dx + 1f * s, 6f * s)
                p.lineTo(dx + 4f * s, 9f * s)
            } else {
                p.moveTo(dx + 4f * s, 9f * s)
            }
            p.lineTo(dx + 10.5f * s, 2f * s)
            drawPath(p, color, style = stroke)
        }
        tick(0f, false)
        // The second tick only shows its long stroke, tucked behind the first one's tip.
        if (two) tick(5f * s, true)
    }
}

/** The card corner on a team task you gave: sent time + ticks; tap for Sent · Delivered · Seen times. */
@Composable
fun TeamTicks(t: TeamTask) {
    var info by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val seen = Ticks.seen(t)
    val two = Ticks.count(t) == 2
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable { info = true }.padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(Fmt.timeOnly(t.createdAt), fontSize = 11.sp, color = cs.onSurfaceVariant)
        TickMarks(two, if (seen) Ticks.BLUE else Ticks.GREY)
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TickLine(false, Ticks.GREY, "Sent", t.createdAt)
                    TickLine(true, Ticks.GREY, "Delivered", t.deliveredAt.takeIf { it > 0L } ?: t.seenAt)
                    TickLine(true, Ticks.BLUE, "Seen", t.seenAt)
                }
            },
            confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun TickLine(two: Boolean, color: Color, label: String, at: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TickMarks(two, color, Modifier.width(17.dp))
        Text(
            "  $label · " + if (at > 0L) Fmt.dueLabel(at) else "not yet",
            fontSize = 14.sp,
            fontWeight = if (label == "Seen" && at > 0L) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
