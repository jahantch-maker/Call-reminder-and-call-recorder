package com.callbackreminder.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WhatsApp-style photo markup: crop and rotate, then Pen / Arrow / Line / Circle / Box / Size (a measurement) /
 * Mark (highlighter) and text. Tap a mark to select it (end dots resize, drag moves, drag to the bin deletes). Holding an
 * end dot shows a 2x magnifier above the finger. Several photos at once. Marks live in photo pixels.
 */
private const val K_PEN = 0
private const val K_MARK = 1
private const val K_ARROW = 2
private const val K_LINE = 3
private const val K_CIRCLE = 4
private const val K_BOX = 5
private const val K_DIM = 6
private const val K_TEXT = 7

private data class Mk(
    val id: Long, val kind: Int, val a: Offset, val b: Offset, val color: Int, val w: Float,
    val pts: List<Offset> = emptyList(), val text: String = "", val bold: Boolean = false, val box: Boolean = false,
)

private class PItem(b: Bitmap) {
    var bmp by mutableStateOf(b)
    val marks = mutableStateListOf<Mk>()
}

private val toolList = listOf(
    Triple(K_PEN, "✏", "Pen"), Triple(K_ARROW, "➜", "Arrow"), Triple(K_LINE, "╱", "Line"),
    Triple(K_CIRCLE, "◯", "Circle"), Triple(K_BOX, "▭", "Box"), Triple(K_DIM, "↔", "Size"), Triple(K_MARK, "🖍", "Mark"),
)

private fun colorAt(f: Float): Int = when {
    f < 0.07f -> Color.White.toArgb()
    f > 0.93f -> Color.Black.toArgb()
    else -> Color.hsv(((f - 0.07f) / 0.86f) * 300f, 1f, 1f).toArgb()
}

private val sliderBrush = Brush.horizontalGradient(
    listOf(Color.White, Color.hsv(0f, 1f, 1f), Color.hsv(60f, 1f, 1f), Color.hsv(120f, 1f, 1f),
        Color.hsv(180f, 1f, 1f), Color.hsv(240f, 1f, 1f), Color.hsv(300f, 1f, 1f), Color.Black),
)

private fun distSeg(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    val t = if (l2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
    return (p - Offset(a.x + t * dx, a.y + t * dy)).getDistance()
}

private fun textPaint(m: Mk): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = m.w
    isFakeBoldText = m.bold
    textAlign = Paint.Align.CENTER
}

private fun textLines(m: Mk): List<String> = m.text.split('\n').ifEmpty { listOf("") }

/** Where a text mark sits (its box), around its centre [Mk.a]. */
private fun textRect(m: Mk): RectF {
    val p = textPaint(m)
    val lines = textLines(m)
    val w = (lines.maxOfOrNull { p.measureText(it) } ?: 0f) + if (m.box) m.w * 0.8f else m.w * 0.2f
    val h = lines.size * m.w * 1.2f + if (m.box) m.w * 0.5f else m.w * 0.1f
    return RectF(m.a.x - w / 2, m.a.y - h / 2, m.a.x + w / 2, m.a.y + h / 2)
}

private fun rectOf(m: Mk): RectF = RectF(min(m.a.x, m.b.x), min(m.a.y, m.b.y), max(m.a.x, m.b.x), max(m.a.y, m.b.y))

private fun corners(r: RectF): List<Offset> = listOf(Offset(r.left, r.top), Offset(r.right, r.top), Offset(r.right, r.bottom), Offset(r.left, r.bottom))

private fun handles(m: Mk): List<Offset> = when (m.kind) {
    K_ARROW, K_LINE, K_DIM -> listOf(m.a, m.b)
    K_CIRCLE, K_BOX -> corners(rectOf(m))
    K_TEXT -> textRect(m).let { listOf(Offset(it.right, it.bottom)) }
    else -> emptyList()
}

private fun hit(m: Mk, p: Offset, tol: Float): Boolean = when (m.kind) {
    K_PEN, K_MARK -> {
        val r = tol + m.w * (if (m.kind == K_MARK) 1.5f else 0.5f)
        if (m.pts.size == 1) (p - m.pts[0]).getDistance() < r
        else m.pts.zipWithNext().any { (u, v) -> distSeg(p, u, v) < r }
    }
    K_ARROW, K_LINE, K_DIM -> distSeg(p, m.a, m.b) < tol + m.w
    K_CIRCLE -> {
        val r = rectOf(m)
        val rx = max(r.width() / 2, 1f)
        val ry = max(r.height() / 2, 1f)
        val q = sqrt(((p.x - r.centerX()) / rx).let { it * it } + ((p.y - r.centerY()) / ry).let { it * it })
        abs(q - 1f) * min(rx, ry) < tol + m.w
    }
    K_BOX -> {
        val r = rectOf(m)
        val outer = RectF(r.left - tol - m.w, r.top - tol - m.w, r.right + tol + m.w, r.bottom + tol + m.w)
        val inner = RectF(r.left + tol + m.w, r.top + tol + m.w, r.right - tol - m.w, r.bottom - tol - m.w)
        outer.contains(p.x, p.y) && !(inner.width() > 0 && inner.height() > 0 && inner.contains(p.x, p.y))
    }
    K_TEXT -> textRect(m).apply { inset(-tol, -tol) }.contains(p.x, p.y)
    else -> false
}

private fun drawMk(c: android.graphics.Canvas, m: Mk) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = m.color
        style = Paint.Style.STROKE
        strokeWidth = m.w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    when (m.kind) {
        K_PEN, K_MARK -> {
            if (m.kind == K_MARK) { p.alpha = 100; p.strokeWidth = m.w * 3f; p.strokeCap = Paint.Cap.SQUARE }
            if (m.pts.size == 1) {
                p.style = Paint.Style.FILL
                c.drawCircle(m.pts[0].x, m.pts[0].y, p.strokeWidth / 2, p)
            } else {
                val path = android.graphics.Path()
                m.pts.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                c.drawPath(path, p)
            }
        }
        K_LINE -> c.drawLine(m.a.x, m.a.y, m.b.x, m.b.y, p)
        K_ARROW -> {
            c.drawLine(m.a.x, m.a.y, m.b.x, m.b.y, p)
            val ang = atan2(m.b.y - m.a.y, m.b.x - m.a.x)
            val len = m.w * 5f + 14f
            for (d in listOf(0.5f, -0.5f)) {
                c.drawLine(m.b.x, m.b.y, m.b.x - len * cos(ang + d), m.b.y - len * sin(ang + d), p)
            }
        }
        K_CIRCLE -> c.drawOval(rectOf(m), p)
        K_BOX -> c.drawRect(rectOf(m), p)
        K_DIM -> {
            c.drawLine(m.a.x, m.a.y, m.b.x, m.b.y, p)
            val ang = atan2(m.b.y - m.a.y, m.b.x - m.a.x) + Math.PI.toFloat() / 2
            val t = m.w * 2.5f + 8f
            for (e in listOf(m.a, m.b)) c.drawLine(e.x - t * cos(ang), e.y - t * sin(ang), e.x + t * cos(ang), e.y + t * sin(ang), p)
            if (m.text.isNotBlank()) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = m.w * 3f + 22f; color = m.color; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                val mx = (m.a.x + m.b.x) / 2
                val my = (m.a.y + m.b.y) / 2
                val w = tp.measureText(m.text)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
                c.drawRoundRect(RectF(mx - w / 2 - 10, my - tp.textSize * 0.85f, mx + w / 2 + 10, my + tp.textSize * 0.35f), 12f, 12f, bg)
                c.drawText(m.text, mx, my, tp)
            }
        }
        K_TEXT -> {
            val tp = textPaint(m).apply { color = m.color }
            val r = textRect(m)
            if (m.box) {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                c.drawRoundRect(r, m.w * 0.3f, m.w * 0.3f, bg)
            } else {
                tp.setShadowLayer(m.w * 0.12f, 0f, 0f, 0xAA000000.toInt())
            }
            textLines(m).forEachIndexed { i, line ->
                c.drawText(line, r.centerX(), r.top + (if (m.box) m.w * 0.25f else m.w * 0.05f) + m.w * (1.0f + 1.2f * i), tp)
            }
        }
    }
}

private fun drawAll(c: android.graphics.Canvas, bmp: Bitmap, marks: List<Mk>) {
    c.drawBitmap(bmp, 0f, 0f, null)
    marks.forEach { drawMk(c, it) }
}

/** The picture with its marks burned in, as a small JPEG. */
private fun flatten(it: PItem): ByteArray? {
    val out = it.bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return null
    val cv = android.graphics.Canvas(out)
    it.marks.forEach { m -> drawMk(cv, m) }
    var q = 85
    var bytes: ByteArray
    do {
        val s = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, q, s)
        bytes = s.toByteArray()
        q -= 10
    } while (bytes.size > 220_000 && q >= 35)
    return bytes
}

private class DragState(val mode: Int, val id: Long, val handle: Int = -1, var last: Offset = Offset.Zero) // mode 0 new, 1 move, 2 handle

@Composable
fun PhotoMarkupEditor(photos: List<ByteArray>, onDone: (List<ByteArray>) -> Unit, onCancel: () -> Unit) {
    val items = remember { photos.mapNotNull { TeamMedia.bitmap(it) }.map { PItem(it) }.toMutableStateList() }
    var cur by remember { mutableStateOf(0) }
    var pen by remember { mutableStateOf(true) }
    var tool by remember { mutableStateOf(K_PEN) }
    var colorF by remember { mutableStateOf(0.14f) }
    var thickF by remember { mutableStateOf(0.35f) }
    var selId by remember { mutableStateOf<Long?>(null) }
    var crop by remember { mutableStateOf(false) }
    var textEdit by remember { mutableStateOf<Mk?>(null) }
    var dimEdit by remember { mutableStateOf<Mk?>(null) }
    var loupe by remember { mutableStateOf<Offset?>(null) }
    var bin by remember { mutableStateOf(false) }
    var nextId by remember { mutableStateOf(1L) }
    val haptic = LocalHapticFeedback.current
    val dens = LocalDensity.current

    if (items.isEmpty()) { onCancel(); return }
    val item = items[cur.coerceIn(0, items.size - 1)]
    val bmp = item.bmp
    val marks = item.marks

    fun curColor() = colorAt(colorF)
    fun curWidth() = item.bmp.width * (0.004f + 0.026f * thickF)
    fun replace(m: Mk) { val i = marks.indexOfFirst { it.id == m.id }; if (i >= 0) marks[i] = m }

    Dialog(
        onDismissRequest = { if (crop) crop = false else onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // fitBetweenBars: the tool bar and Done can never sit under the phone's own navigation buttons.
        Box(Modifier.fillMaxSize().background(Color.Black).fitBetweenBars()) {
            Column(Modifier.fillMaxSize()) {
                // Top bar
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("✕") { onCancel() }
                    Box(Modifier.weight(1f))
                    Chip("⟲ Crop") { selId = null; crop = true }
                    Chip("↶") { if (marks.isNotEmpty()) { marks.removeAt(marks.size - 1); selId = null } }
                    Chip("T", on = false) {
                        selId = null
                        textEdit = Mk(nextId++, K_TEXT, Offset(bmp.width / 2f, bmp.height / 2f), Offset.Zero, curColor(), bmp.width * (0.03f + 0.05f * thickF))
                    }
                    Chip("✏", on = pen) { pen = !pen; selId = null }
                    Box(
                        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1FAF6B))
                            .clickable { onDone(items.mapNotNull { flatten(it) }) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    ) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold) }
                }
                // Picture area with sliders over it
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    var viewW by remember { mutableStateOf(1f) }
                    var viewH by remember { mutableStateOf(1f) }
                    // v0.54 Zoom: two fingers pinch and move, double-tap fits again. Marks live in photo pixels, so they stay put.
                    var zoom by remember(cur, bmp) { mutableStateOf(1f) }
                    var panX by remember(cur, bmp) { mutableStateOf(0f) }
                    var panY by remember(cur, bmp) { mutableStateOf(0f) }
                    val scale = min(viewW / bmp.width, viewH / bmp.height) * zoom
                    val left = (viewW - bmp.width * scale) / 2f + panX
                    val top = (viewH - bmp.height * scale) / 2f + panY
                    fun fitBase() = min(viewW / item.bmp.width, viewH / item.bmp.height)
                    fun sc() = fitBase() * zoom
                    fun toImg(o: Offset): Offset {
                        val s = sc()
                        return Offset((o.x - (viewW - item.bmp.width * s) / 2f - panX) / s, (o.y - (viewH - item.bmp.height * s) / 2f - panY) / s)
                    }
                    /** Zoom by [factor] around [c] (a point on the view) and move by [pan]; keeps the photo point under [c] in place. */
                    fun zoomBy(factor: Float, c: Offset, pan: Offset) {
                        val s0 = sc()
                        val ix = (c.x - (viewW - item.bmp.width * s0) / 2f - panX) / s0
                        val iy = (c.y - (viewH - item.bmp.height * s0) / 2f - panY) / s0
                        val z = (zoom * factor).coerceIn(1f, 8f)
                        val s1 = fitBase() * z
                        var nx = c.x + pan.x - ix * s1 - (viewW - item.bmp.width * s1) / 2f
                        var ny = c.y + pan.y - iy * s1 - (viewH - item.bmp.height * s1) / 2f
                        if (z <= 1.001f) { nx = 0f; ny = 0f }
                        else {
                            val mx = max(0f, (item.bmp.width * s1 - viewW) / 2f) + 120f
                            val my = max(0f, (item.bmp.height * s1 - viewH) / 2f) + 120f
                            nx = nx.coerceIn(-mx, mx); ny = ny.coerceIn(-my, my)
                        }
                        zoom = z; panX = nx; panY = ny
                    }
                    fun tolNow() = with(dens) { 22.dp.toPx() } / max(sc(), 0.01f)
                    var drag by remember { mutableStateOf<DragState?>(null) }
                    var fingerV by remember { mutableStateOf(Offset.Zero) }

                    Canvas(
                        Modifier.fillMaxSize()
                            .clipToBounds()
                            .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
                            // Two fingers: pinch to zoom and move. Seen first (Initial pass) and consumed, so drawing never starts under them.
                            .pointerInput(cur) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.count { it.pressed } >= 2) {
                                            zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = false), event.calculatePan())
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .pointerInput(cur) {
                                detectTapGestures(onDoubleTap = { zoom = 1f; panX = 0f; panY = 0f }) { pos ->
                                    val p = toImg(pos)
                                    val hitM = marks.lastOrNull { hit(it, p, tolNow()) }
                                    if (hitM != null && hitM.id == selId && (hitM.kind == K_TEXT)) textEdit = hitM
                                    else if (hitM != null && hitM.id == selId && hitM.kind == K_DIM) dimEdit = hitM
                                    else selId = hitM?.id
                                }
                            }
                            .pointerInput(cur) {
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val p = toImg(pos)
                                        val sel = marks.firstOrNull { it.id == selId }
                                        val hIdx = sel?.let { s -> handles(s).indexOfFirst { (it - p).getDistance() < tolNow() * 1.6f } } ?: -1
                                        if (sel != null && hIdx >= 0) {
                                            drag = DragState(2, sel.id, hIdx, p)
                                            loupe = pos
                                        } else if (sel != null && hit(sel, p, tolNow())) {
                                            drag = DragState(1, sel.id, -1, p)
                                            bin = true
                                        } else if (pen) {
                                            selId = null
                                            val id = nextId++
                                            val m = Mk(id, tool, p, p, curColor(), curWidth(), pts = listOf(p))
                                            marks.add(m)
                                            selId = if (tool == K_PEN || tool == K_MARK) null else id
                                            drag = DragState(0, id, -1, p)
                                            if (tool == K_ARROW || tool == K_LINE || tool == K_DIM) loupe = pos
                                        } else selId = null
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val d = drag ?: return@detectDragGestures
                                        val p = toImg(change.position)
                                        val m = marks.firstOrNull { it.id == d.id } ?: return@detectDragGestures
                                        when (d.mode) {
                                            0 -> {
                                                if (m.kind == K_PEN || m.kind == K_MARK) replace(m.copy(pts = m.pts + p, b = p))
                                                else replace(m.copy(b = p))
                                                if (loupe != null) loupe = change.position
                                            }
                                            1 -> {
                                                val dx = p.x - d.last.x
                                                val dy = p.y - d.last.y
                                                val sh = Offset(dx, dy)
                                                replace(m.copy(a = m.a + sh, b = m.b + sh, pts = m.pts.map { it + sh }))
                                                d.last = p
                                            }
                                            else -> {
                                                loupe = change.position
                                                when (m.kind) {
                                                    K_ARROW, K_LINE, K_DIM -> replace(if (d.handle == 0) m.copy(a = p) else m.copy(b = p))
                                                    K_CIRCLE, K_BOX -> {
                                                        val cs = corners(rectOf(m))
                                                        replace(m.copy(a = cs[(d.handle + 2) % 4], b = p))
                                                    }
                                                    K_TEXT -> {
                                                        val c0 = m.a
                                                        val hOld = handles(m)[0]
                                                        val before = (hOld - c0).getDistance()
                                                        val after = (p - c0).getDistance()
                                                        if (before > 1f) replace(m.copy(w = (m.w * after / before).coerceIn(14f, item.bmp.width / 4f)))
                                                    }
                                                }
                                            }
                                        }
                                        fingerV = change.position
                                    },
                                    onDragEnd = {
                                        val d = drag
                                        val m = d?.let { dd -> marks.firstOrNull { it.id == dd.id } }
                                        if (d != null && m != null) {
                                            if (d.mode == 1) {
                                                if (fingerV.y > viewH - with(dens) { 90.dp.toPx() } && abs(fingerV.x - viewW / 2) < with(dens) { 60.dp.toPx() }) {
                                                    marks.removeAll { it.id == d.id }
                                                    selId = null
                                                }
                                            } else if (d.mode == 0) {
                                                val tiny = (m.b - m.a).getDistance() < tolNow() * 0.7f
                                                if ((m.kind == K_PEN || m.kind == K_MARK).not() && tiny) { marks.removeAll { it.id == d.id }; selId = null }
                                                else if (m.kind == K_DIM) dimEdit = m
                                            }
                                            if (loupe != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        drag = null; loupe = null; bin = false
                                    },
                                    onDragCancel = {
                                        // A second finger came down (zoom): a mark that was just started is dropped.
                                        drag?.let { d -> if (d.mode == 0) marks.removeAll { it.id == d.id } }
                                        drag = null; loupe = null; bin = false
                                    },
                                )
                            },
                    ) {
                        drawIntoCanvas { cv ->
                            val n = cv.nativeCanvas
                            n.save()
                            n.translate(left, top)
                            n.scale(scale, scale)
                            drawAll(n, bmp, marks)
                            n.restore()
                        }
                        // Selected mark: dotted frame (approximate) and end dots
                        val sel = marks.firstOrNull { it.id == selId }
                        if (sel != null) {
                            val dot = 9.dp.toPx()
                            handles(sel).forEach { h ->
                                val c = Offset(left + h.x * scale, top + h.y * scale)
                                drawCircle(Color(0xFF1FAF6B), dot, c)
                                drawCircle(Color.White, dot, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            }
                        }
                        // Magnifier
                        loupe?.let { f ->
                            val r = 54.dp.toPx()
                            val lc = if (f.y < r * 2.6f) Offset(f.x.coerceIn(r, size.width - r), f.y + r * 1.9f)
                            else Offset(f.x.coerceIn(r, size.width - r), f.y - r * 1.9f)
                            val pf = Offset((f.x - left) / scale, (f.y - top) / scale)
                            drawIntoCanvas { cv ->
                                val n = cv.nativeCanvas
                                n.save()
                                val clip = android.graphics.Path().apply { addCircle(lc.x, lc.y, r, android.graphics.Path.Direction.CW) }
                                n.clipPath(clip)
                                n.drawColor(android.graphics.Color.BLACK)
                                n.translate(lc.x, lc.y)
                                n.scale(scale * 2f, scale * 2f)
                                n.translate(-pf.x, -pf.y)
                                drawAll(n, bmp, marks)
                                n.restore()
                            }
                            drawCircle(Color.White, r, lc, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                            drawLine(Color.White, Offset(lc.x - 10.dp.toPx(), lc.y), Offset(lc.x + 10.dp.toPx(), lc.y), 1.5.dp.toPx())
                            drawLine(Color.White, Offset(lc.x, lc.y - 10.dp.toPx()), Offset(lc.x, lc.y + 10.dp.toPx()), 1.5.dp.toPx())
                        }
                    }

                    // Zoom buttons (and the level) for when pinching is awkward; double-tap fits the photo again.
                    Column(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("+" to 1.4f, "−" to 1f / 1.4f).forEach { (g, f) ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(Color(0xCC222222))
                                    .clickable { zoomBy(f, Offset(viewW / 2f, viewH / 2f), Offset.Zero) },
                                contentAlignment = Alignment.Center,
                            ) { Text(g, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    if (zoom > 1.01f) Box(
                        Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 10.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0x88000000)).clickable { zoom = 1f; panX = 0f; panY = 0f }.padding(horizontal = 8.dp, vertical = 3.dp),
                    ) { Text("${(zoom * 100).toInt()}% · Fit", color = Color.White, fontSize = 11.sp) }
                    if (bin) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(52.dp).clip(CircleShape)
                            .background(Color(0xFF222222)).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                            Text("🗑", fontSize = 22.sp)
                        }
                    }
                    selMark(marks, selId)?.let { m ->
                        if (m.kind == K_DIM || m.kind == K_TEXT) Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF222222))
                                .clickable { if (m.kind == K_TEXT) textEdit = m else dimEdit = m }.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text(if (m.kind == K_TEXT) "✎ Edit text" else "✎ Edit size", color = Color.White, fontSize = 13.sp) }
                        else if (selId != null) Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF222222))
                                .clickable { marks.removeAll { it.id == m.id }; selId = null }.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("🗑 Delete", color = Color.White, fontSize = 13.sp) }
                    }
                }
                // Thin ◁▷ Thick and colour: two slim sliders under the picture, so nothing covers the drawing area.
                if (pen || selId != null) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).height(34.dp)) {
                        HSlider(thickF, null, wedge = true) { f ->
                            thickF = f
                            marks.firstOrNull { it.id == selId }?.let { m ->
                                if (m.kind == K_TEXT) replace(m.copy(w = bmp.width * (0.03f + 0.05f * f)))
                                else replace(m.copy(w = bmp.width * (0.004f + 0.026f * f)))
                            }
                        }
                    }
                    Box(Modifier.weight(1f).height(34.dp)) {
                        HSlider(colorF, sliderBrush, wedge = false) { f ->
                            colorF = f
                            marks.firstOrNull { it.id == selId }?.let { replace(it.copy(color = colorAt(f))) }
                        }
                    }
                }
                // Photo strip when several
                if (items.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEachIndexed { i, it ->
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (i == cur) Color(0xFF1FAF6B) else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { cur = i; selId = null }) {
                            androidx.compose.foundation.Image(it.bmp.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text("✕", color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.TopEnd).background(Color(0x99000000)).clickable {
                                    items.removeAt(i)
                                    cur = cur.coerceIn(0, max(0, items.size - 1)); selId = null
                                }.padding(3.dp))
                        }
                    }
                }
                // Tool bar
                if (pen) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1B1F24)).padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    toolList.forEach { (k, glyph, label) ->
                        Column(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(if (tool == k) Color(0xFF1FAF6B) else Color.Transparent)
                                .clickable { tool = k; selId = null }.padding(horizontal = 7.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(glyph, color = Color.White, fontSize = 18.sp)
                            Text(label, color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }

            if (crop) CropPane(bmp, onCancel = { crop = false }) { nb ->
                item.bmp = nb
                item.marks.clear()
                selId = null
                crop = false
            }
        }
    }

    textEdit?.let { t0 ->
        var text by remember(t0.id) { mutableStateOf(t0.text) }
        var bold by remember(t0.id) { mutableStateOf(t0.bold) }
        var box by remember(t0.id) { mutableStateOf(t0.box) }
        AlertDialog(
            onDismissRequest = { textEdit = null },
            title = { Text("Text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { bold = !bold }) { Text(if (bold) "Bold ✓" else "Bold") }
                        TextButton(onClick = { box = !box }) { Text(if (box) "▣ Box ✓" else "▣ Box") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = t0.copy(text = text, bold = bold, box = box)
                    if (text.isBlank()) marks.removeAll { it.id == t0.id }
                    else if (marks.any { it.id == t0.id }) replace(m) else { marks.add(m); selId = m.id }
                    textEdit = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { textEdit = null }) { Text("Cancel") } },
        )
    }
    dimEdit?.let { d0 ->
        var text by remember(d0.id) { mutableStateOf(d0.text) }
        AlertDialog(
            onDismissRequest = { dimEdit = null },
            title = { Text("Measurement") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("e.g. 3'6\" or 120 cm") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { replace(d0.copy(text = text.trim())); dimEdit = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { dimEdit = null }) { Text("Skip") } },
        )
    }
}

private fun selMark(marks: List<Mk>, id: Long?): Mk? = marks.firstOrNull { it.id == id }

@Composable
private fun Chip(label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Color.White else Color(0xFF2A2F35))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/**
 * Horizontal slider, left = 0, right = 1. The thickness one is drawn as a wedge, thin on the left and thick on the right,
 * so the picture on the track says which way is thicker (the line width grows from left to right).
 */
@Composable
private fun HSlider(value: Float, brush: Brush?, wedge: Boolean, onChange: (Float) -> Unit) {
    var w by remember { mutableStateOf(1f) }
    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { w = it.width.toFloat() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> onChange((o.x / w).coerceIn(0f, 1f)) },
                    onDrag = { c, _ -> c.consume(); onChange((c.position.x / w).coerceIn(0f, 1f)) },
                )
            }
            .pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / w).coerceIn(0f, 1f)) } },
    ) {
        val r = 11.dp.toPx()
        val x0 = r
        val x1 = size.width - r
        val cy = size.height / 2f
        if (wedge) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x0, cy - 1.5.dp.toPx())
                lineTo(x1, cy - 9.dp.toPx())
                lineTo(x1, cy + 9.dp.toPx())
                lineTo(x0, cy + 1.5.dp.toPx())
                close()
            }
            drawPath(path, Color(0xFF9A9A9A))
        } else if (brush != null) {
            drawRoundRect(brush, topLeft = Offset(x0, cy - 7.dp.toPx()), size = Size(x1 - x0, 14.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
        }
        val x = x0 + value * (x1 - x0)
        drawCircle(Color.White, r, Offset(x, cy))
        drawCircle(Color(0xFF888888), r, Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

/** Crop and rotate: corner handles, drag inside to move, Rotate 90, ratios, Reset. Applying removes marks. */
@Composable
private fun CropPane(src: Bitmap, onCancel: () -> Unit, onApply: (Bitmap) -> Unit) {
    var rot by remember { mutableStateOf(0) }
    val rotated = remember(src, rot) {
        if (rot == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot * 90f) }, true)
    }
    var rect by remember(rotated) { mutableStateOf(RectF(0f, 0f, rotated.width.toFloat(), rotated.height.toFloat())) }
    var ratio by remember { mutableStateOf(0f) } // 0 = free
    val dens = LocalDensity.current

    fun fitRatio(r: Float): RectF {
        val w = rotated.width.toFloat()
        val h = rotated.height.toFloat()
        var nw = w
        var nh = nw / r
        if (nh > h) { nh = h; nw = nh * r }
        return RectF((w - nw) / 2, (h - nh) / 2, (w + nw) / 2, (h + nh) / 2)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("✕") { onCancel() }
                Box(Modifier.weight(1f))
                Chip("⟳ Rotate") { rot = (rot + 1) % 4 }
                Chip("Reset") { rot = 0; ratio = 0f; rect = RectF(0f, 0f, rotated.width.toFloat(), rotated.height.toFloat()) }
                Box(
                    Modifier.padding(start = 6.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1FAF6B)).clickable {
                        val r = rect
                        val nb = Bitmap.createBitmap(rotated, r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0),
                            r.width().toInt().coerceAtLeast(1).coerceAtMost(rotated.width - r.left.toInt().coerceAtLeast(0)),
                            r.height().toInt().coerceAtLeast(1).coerceAtMost(rotated.height - r.top.toInt().coerceAtLeast(0)))
                        onApply(nb)
                    }.padding(horizontal = 16.dp, vertical = 9.dp),
                ) { Text("Apply", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                var vw by remember { mutableStateOf(1f) }
                var vh by remember { mutableStateOf(1f) }
                val s = min(vw / rotated.width, vh / rotated.height)
                val ox = (vw - rotated.width * s) / 2
                val oy = (vh - rotated.height * s) / 2
                var active by remember { mutableStateOf(-2) } // -1 move, 0..3 corner
                fun geo(): Triple<Float, Float, Float> {
                    val k = min(vw / rotated.width, vh / rotated.height)
                    return Triple(k, (vw - rotated.width * k) / 2, (vh - rotated.height * k) / 2)
                }
                Canvas(
                    Modifier.fillMaxSize().onSizeChanged { vw = it.width.toFloat(); vh = it.height.toFloat() }.pointerInput(rotated, ratio) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val (s, ox, oy) = geo()
                                val p = Offset((pos.x - ox) / s, (pos.y - oy) / s)
                                val near = with(dens) { 40.dp.toPx() } / s
                                val cs = corners(rect)
                                val i = cs.indexOfFirst { (it - p).getDistance() < near }
                                active = if (i >= 0) i else if (rect.contains(p.x, p.y)) -1 else -2
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val (s, ox, oy) = geo()
                                val r = rect
                                val W = rotated.width.toFloat()
                                val H = rotated.height.toFloat()
                                if (active == -1) {
                                    val dx = (amount.x / s).coerceIn(-r.left, W - r.right)
                                    val dy = (amount.y / s).coerceIn(-r.top, H - r.bottom)
                                    rect = RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
                                } else if (active >= 0) {
                                    val p = Offset(((change.position.x - ox) / s).coerceIn(0f, W), ((change.position.y - oy) / s).coerceIn(0f, H))
                                    val o = corners(r)[(active + 2) % 4]
                                    val sx = if (p.x >= o.x) 1f else -1f
                                    val sy = if (p.y >= o.y) 1f else -1f
                                    var dx = max(abs(p.x - o.x), 24f)
                                    var dy = max(abs(p.y - o.y), 24f)
                                    if (ratio > 0f) {
                                        if (dx / dy > ratio) dx = dy * ratio else dy = dx / ratio
                                        val maxX = if (sx > 0) W - o.x else o.x
                                        val maxY = if (sy > 0) H - o.y else o.y
                                        val f = min(1f, min(maxX / dx, maxY / dy))
                                        dx *= f; dy *= f
                                    }
                                    val q = Offset(o.x + sx * dx, o.y + sy * dy)
                                    rect = RectF(min(o.x, q.x), min(o.y, q.y), max(o.x, q.x), max(o.y, q.y))
                                }
                            },
                            onDragEnd = { active = -2 },
                            onDragCancel = { active = -2 },
                        )
                    },
                ) {
                    drawIntoCanvas { cv ->
                        val n = cv.nativeCanvas
                        n.save()
                        n.translate(ox, oy)
                        n.scale(s, s)
                        n.drawBitmap(rotated, 0f, 0f, null)
                        n.restore()
                    }
                    val l = ox + rect.left * s
                    val t = oy + rect.top * s
                    val r = ox + rect.right * s
                    val b = oy + rect.bottom * s
                    val dim = Color(0xAA000000)
                    drawRect(dim, Offset(ox, oy), Size(rotated.width * s, t - oy))
                    drawRect(dim, Offset(ox, b), Size(rotated.width * s, oy + rotated.height * s - b))
                    drawRect(dim, Offset(ox, t), Size(l - ox, b - t))
                    drawRect(dim, Offset(r, t), Size(ox + rotated.width * s - r, b - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b)).forEach { drawCircle(Color.White, 9.dp.toPx(), it) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Free" to 0f, "Square" to 1f, "4:3" to 4f / 3f, "16:9" to 16f / 9f).forEach { (lab, r) ->
                    Chip(lab, on = ratio == r) {
                        ratio = r
                        if (r > 0f) rect = fitRatio(r)
                    }
                }
            }
            Text("Crop and rotate come first: marks already made on this photo are removed.", color = Color(0x99FFFFFF), fontSize = 11.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
        }
    }
}
