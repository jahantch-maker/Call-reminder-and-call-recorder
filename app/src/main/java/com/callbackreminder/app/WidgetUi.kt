package com.callbackreminder.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.LocalDate
import java.time.ZoneId

// ---- The widgets ----

/** Shared by all 7: reads this widget's settings and redraws whenever the app's data changes. */
abstract class CbWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetData.ensure(context)
        WidgetSync.start(context)
        val widgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        } catch (e: Exception) {
            0
        }
        provideContent {
            val tick by WidgetSync.tick.collectAsState()
            val now = remember(tick) { System.currentTimeMillis() }
            val cfg = remember(tick) { WidgetPrefs.get(context, widgetId) }
            Content(context, widgetId, cfg, WidgetLook.colors(context, cfg), now)
        }
    }

    @Composable
    abstract fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long)
}

class FollowUpsWidget : CbWidget() {
    @Composable
    override fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) =
        FollowUpsBody(c, widgetId, cfg, col, now)
}

class MyTasksWidget : CbWidget() {
    @Composable
    override fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) =
        MyTasksBody(c, widgetId, cfg, col, now)
}

class ReviewWidget : CbWidget() {
    @Composable
    override fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) =
        ReviewBody(c, cfg, col, now)
}

class DailyTicksWidget : CbWidget() {
    @Composable
    override fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) =
        DailyBody(c, cfg, col, now)
}

class QuickAddWidget : CbWidget() {
    @Composable
    override fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) =
        QuickAddBody(c, cfg, col)
}

class TasksCalendarWidget : CbWidget() {
    @Composable
    override fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) =
        TasksCalendarBody(c, cfg, col, now)
}

class FollowUpsCalendarWidget : CbWidget() {
    @Composable
    override fun Content(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) =
        FollowUpsCalendarBody(c, cfg, col, now)
}

/** Forgets a widget's settings when you remove it from the home screen. */
abstract class CbWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetPrefs.remove(context, it) }
    }
}

class FollowUpsWidgetReceiver : CbWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FollowUpsWidget()
}

class MyTasksWidgetReceiver : CbWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MyTasksWidget()
}

class ReviewWidgetReceiver : CbWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReviewWidget()
}

class DailyTicksWidgetReceiver : CbWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyTicksWidget()
}

class QuickAddWidgetReceiver : CbWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}

class TasksCalendarWidgetReceiver : CbWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksCalendarWidget()
}

class FollowUpsCalendarWidgetReceiver : CbWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FollowUpsCalendarWidget()
}

// ---- Small parts ----

private fun cp(c: Color) = ColorProvider(c)

private fun ts(
    color: Color,
    size: Float,
    scale: Float,
    bold: Boolean = false,
    strike: Boolean = false,
    center: Boolean = false,
) = TextStyle(
    color = cp(color),
    fontSize = (size * scale).sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textDecoration = if (strike) TextDecoration.LineThrough else null,
    textAlign = if (center) TextAlign.Center else null,
)

@Composable
private fun Frame(col: WidgetColors, cfg: WidgetConfig, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(22.dp)
            .background(col.bg.copy(alpha = 1f - cfg.seeThrough.coerceIn(0, 80) / 100f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        content = content,
    )
}

/** A round button; [gap] puts a little space on its left. */
@Composable
private fun RoundBtn(label: String, bg: Color, fg: Color, onClick: Action, size: Dp, s: Float, font: Float = 14f, gap: Boolean = false) {
    Box(GlanceModifier.padding(start = if (gap) 4.dp else 0.dp)) {
        Box(
            modifier = GlanceModifier.size(size).cornerRadius(size / 2).background(bg).clickable(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = ts(fg, font, s, bold = true, center = true), maxLines = 1)
        }
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color, s: Float, onClick: Action? = null) {
    val m = GlanceModifier.cornerRadius(7.dp).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)
    Box(if (onClick != null) m.clickable(onClick) else m) {
        Text(text, style = ts(fg, 10.5f, s, bold = true), maxLines = 1)
    }
}

/** Title, count, filter chip, ⟳ and ＋. */
@Composable
private fun Header(
    c: Context,
    widgetId: Int,
    col: WidgetColors,
    s: Float,
    title: String,
    count: String?,
    countRed: Boolean,
    chip: String?,
    add: Intent?,
    info: String? = null,
) {
    Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = ts(col.on, 15f, s, bold = true), maxLines = 1)
        if (count != null) {
            Box(GlanceModifier.padding(start = 6.dp)) {
                Chip(count, if (countRed) col.errorC else col.primaryC, if (countRed) col.onErrorC else col.onPrimaryC, s)
            }
        }
        if (info != null) {
            Text("  $info", style = ts(col.onVar, 11.5f, s), maxLines = 1)
        }
        if (chip != null) {
            Box(GlanceModifier.padding(start = 6.dp)) {
                Box(
                    GlanceModifier.cornerRadius(10.dp).background(col.row).padding(horizontal = 7.dp, vertical = 2.dp)
                        .clickable(actionStartActivity(WidgetIntents.setup(c, widgetId)))
                ) {
                    Text("$chip ▾", style = ts(col.primary, 11.5f, s, bold = true), maxLines = 1)
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        RoundBtn("⟳", col.row, col.onVar, actionRunCallback<WidgetRefreshAction>(), 28.dp, s)
        if (add != null) RoundBtn("＋", col.primaryC, col.onPrimaryC, actionStartActivity(add), 28.dp, s, font = 16f, gap = true)
    }
}

@Composable
private fun UndoBar(widgetId: Int, col: WidgetColors, s: Float, now: Long) {
    val e = WidgetUndo.get(widgetId, now) ?: return
    Row(
        GlanceModifier.fillMaxWidth().cornerRadius(12.dp).background(col.row).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓ ${e.label}", style = ts(col.on, 12.5f, s), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text(
            "UNDO",
            style = ts(col.primary, 13f, s, bold = true),
            modifier = GlanceModifier.padding(start = 8.dp).clickable(actionRunCallback<WidgetUndoAction>()),
        )
    }
}

@Composable
private fun SectionTitle(text: String, count: Int, color: Color, s: Float) {
    Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 3.dp)) {
        Text(text, style = ts(color, 10.5f, s, bold = true), modifier = GlanceModifier.defaultWeight())
        Text("$count", style = ts(color, 10.5f, s, bold = true))
    }
}

@Composable
private fun Empty(col: WidgetColors, s: Float, sub: String, button: String, onClick: Action) {
    Column(GlanceModifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("All clear ✓", style = ts(col.secondary, 17f, s, bold = true, center = true))
        Text(sub, style = ts(col.onVar, 12f, s, center = true), modifier = GlanceModifier.padding(top = 3.dp, bottom = 10.dp))
        Box(GlanceModifier.cornerRadius(16.dp).background(col.primaryC).padding(horizontal = 14.dp, vertical = 6.dp).clickable(onClick)) {
            Text(button, style = ts(col.onPrimaryC, 12.5f, s, bold = true))
        }
    }
}

/** "11:00" over "AM", in red when late. */
@Composable
private fun TimeColumn(at: Long, sub: String?, red: Boolean, col: WidgetColors, s: Float, width: Dp = 50.dp) {
    val parts = Fmt.timeOnly(at).split(' ')
    Column(GlanceModifier.width(width)) {
        Text(parts.first(), style = ts(if (red) col.error else col.on, 12f, s, bold = true), maxLines = 1)
        if (parts.size > 1) Text(parts.drop(1).joinToString(" "), style = ts(if (red) col.error else col.on, 12f, s, bold = true), maxLines = 1)
        if (sub != null) Text(sub, style = ts(col.onVar, 10f, s), maxLines = 1)
    }
}

// ---- Follow-ups ----

@Composable
private fun FollowUpsBody(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) {
    val s = cfg.textScale
    val sections = WidgetData.followUps(cfg.filter, now)
    val total = sections.sumOf { it.items.size }
    val narrow = LocalSize.current.width < 330.dp
    Frame(col, cfg) {
        Header(
            c, widgetId, col, s, "Follow-ups", "$total", sections.any { it.red }, WidgetPrefs.filterLabel(cfg.filter),
            WidgetIntents.popup(c, WidgetActivity.MODE_NEW_FOLLOW_UP),
        )
        LazyColumn(GlanceModifier.fillMaxWidth()) {
            item { UndoBar(widgetId, col, s, now) }
            if (sections.isEmpty()) {
                item {
                    Empty(
                        col, s, emptyNote(cfg.filter), "＋ New follow-up",
                        actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_NEW_FOLLOW_UP)),
                    )
                }
            }
            sections.forEach { sec ->
                item { SectionTitle(sec.title, sec.items.size, if (sec.red) col.error else col.primary, s) }
                items(sec.items) { e -> FuRow(c, widgetId, e, col, s, now, narrow) }
            }
        }
    }
}

private fun emptyNote(filter: Int) = when (filter) {
    WidgetPrefs.FILTER_OVERDUE -> "No overdue follow-ups"
    WidgetPrefs.FILTER_UPCOMING -> "Nothing coming up"
    WidgetPrefs.FILTER_ALL -> "Nothing to follow up"
    else -> "Nothing left for today"
}

@Composable
private fun FuRow(c: Context, widgetId: Int, e: FuEntry, col: WidgetColors, s: Float, now: Long, narrow: Boolean) {
    val call = e.call
    val text = e.text
    val overdue = e.callPending && e.at <= now
    val hasNumber = e.number.isNotBlank()
    val (chipText, chipColors) = when {
        overdue -> RowFmt.late(e.at, now) to (col.errorC to col.onErrorC)
        e.callPending -> "📞 ${Fmt.timeOnly(e.at)}" to (col.primaryC to col.onPrimaryC)
        else -> "💬 ${Fmt.timeOnly(e.at)}" to (col.secondaryC to col.onSecondaryC)
    }
    val open = actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_FOLLOW_UPS))

    @Composable
    fun Buttons() {
        if (call != null && hasNumber) {
            RoundBtn("📞", col.primary, col.onPrimary, actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_CALL, call.id)), 30.dp, s)
        }
        if (hasNumber) {
            RoundBtn(
                "💬", col.secondary, col.onSecondary,
                actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_TEXT, text?.id ?: -1L, call?.id ?: -1L)), 30.dp, s, gap = true,
            )
            RoundBtn(
                "WA", WhatsAppGreen, WhatsAppInk,
                actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_WHATSAPP, call?.id ?: -1L, text?.id ?: -1L)), 30.dp, s, font = 10f, gap = true,
            )
        }
        Box(GlanceModifier.padding(horizontal = 4.dp)) { Box(GlanceModifier.width(1.dp).height(20.dp).background(col.outline)) {} }
        RoundBtn(
            "✓", col.secondaryC, col.onSecondaryC,
            actionRunCallback<FollowUpDoneAction>(actionParametersOf(KEY_ID to (call?.id ?: -1L), KEY_TEXT_ID to (text?.id ?: -1L))), 30.dp, s,
        )
        if (e.callPending && call != null) {
            RoundBtn("⏰", col.orangeC, col.onOrangeC, actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_SNOOZE_FOLLOW_UP, call.id)), 30.dp, s, gap = true)
        }
    }

    Box(GlanceModifier.fillMaxWidth().padding(bottom = 5.dp)) {
        Row(GlanceModifier.fillMaxWidth().cornerRadius(14.dp).background(col.row).padding(start = 9.dp, top = 8.dp, end = 8.dp, bottom = 7.dp)) {
            TimeColumn(e.at, RowFmt.timeSub(e.at, now), overdue, col, s)
            Column(GlanceModifier.defaultWeight()) {
                Column(GlanceModifier.fillMaxWidth().clickable(open)) {
                    Text(e.title, style = ts(col.on, 13.5f, s, bold = true), maxLines = 2)
                    Row(GlanceModifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            GlanceModifier.size(17.dp).cornerRadius(9.dp).background(col.primaryC),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(e.person.take(1).uppercase(), style = ts(col.onPrimaryC, 9f, s, bold = true))
                        }
                        Text(" ${e.person}", style = ts(col.on, 12f, s, bold = true), maxLines = 1)
                    }
                }
                if (narrow) {
                    Row(GlanceModifier.padding(top = 5.dp)) { Chip(chipText, chipColors.first, chipColors.second, s) }
                    Row(GlanceModifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) { Buttons() }
                } else {
                    Row(GlanceModifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(chipText, chipColors.first, chipColors.second, s)
                        Spacer(GlanceModifier.defaultWeight())
                        Buttons()
                    }
                }
            }
        }
    }
}

// ---- My tasks ----

@Composable
private fun MyTasksBody(c: Context, widgetId: Int, cfg: WidgetConfig, col: WidgetColors, now: Long) {
    val s = cfg.textScale
    val tasks = WidgetData.myTasks(cfg.listId)
    val listName = if (cfg.listId == WidgetPrefs.ALL_LISTS) "All lists" else MyTaskStore.listName(cfg.listId)
    val listId = if (cfg.listId == WidgetPrefs.ALL_LISTS) MyTaskStore.UNSORTED else cfg.listId
    val anyLate = tasks.any { it.timed && it.dueAt <= now }
    Frame(col, cfg) {
        Header(
            c, widgetId, col, s, "My tasks", "${tasks.size}", anyLate, listName,
            WidgetIntents.popup(c, WidgetActivity.MODE_NEW_TASK, extra = listId),
        )
        LazyColumn(GlanceModifier.fillMaxWidth()) {
            item { UndoBar(widgetId, col, s, now) }
            if (tasks.isEmpty()) {
                item {
                    Empty(col, s, "No open tasks in $listName", "＋ New task", actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_NEW_TASK, extra = listId)))
                }
            }
            items(tasks) { t -> TaskLine(c, t, col, s, now) }
        }
    }
}

@Composable
private fun TaskLine(c: Context, t: MyTask, col: WidgetColors, s: Float, now: Long) {
    val late = t.timed && t.dueAt <= now
    val sub = if (t.timed) RowFmt.dayTitle(RowFmt.dayOf(t.dueAt)) + " · " + Fmt.timeOnly(t.dueAt) else "No time"
    Row(GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.size(24.dp).cornerRadius(12.dp).background(col.onVar)
                .clickable(actionRunCallback<TaskDoneAction>(actionParametersOf(KEY_ID to t.id))),
            contentAlignment = Alignment.Center,
        ) {
            Box(GlanceModifier.size(20.dp).cornerRadius(10.dp).background(col.bg)) {}
        }
        Column(
            GlanceModifier.defaultWeight().padding(start = 10.dp)
                .clickable(actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_MY_TASKS, t.id)))
        ) {
            Text(t.title, style = ts(col.on, 13.5f, s, bold = true), maxLines = 2)
            Text(sub, style = ts(if (late) col.error else col.onVar, 11f, s), maxLines = 1)
        }
        if (t.starred) Text("★", style = ts(Color(0xFFFFC93C), 15f, s))
    }
}

// ---- Review counter ----

@Composable
private fun ReviewBody(c: Context, cfg: WidgetConfig, col: WidgetColors, now: Long) {
    val s = cfg.textScale
    val n = WidgetData.reviewCounts(c, now)
    val size = LocalSize.current
    val toReview = actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_REVIEW))
    val toSchedule = actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_TO_SCHEDULE))
    val overdue = actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_FOLLOW_UPS))
    val start = actionStartActivity(WidgetIntents.startReview(c))

    @Composable
    fun Count(value: Int, label: String, bg: Color, fg: Color, onClick: Action, m: GlanceModifier) {
        Column(
            m.cornerRadius(14.dp).background(bg).padding(vertical = 6.dp).clickable(onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$value", style = ts(fg, 21f, s, bold = true, center = true))
            Text(label, style = ts(fg, 10.5f, s, bold = true, center = true), maxLines = 1)
        }
    }

    @Composable
    fun StartButton(m: GlanceModifier) {
        Box(m.cornerRadius(17.dp).background(col.primary).padding(vertical = 8.dp, horizontal = 10.dp).clickable(start), contentAlignment = Alignment.Center) {
            Text("▶ Start review", style = ts(col.onPrimary, 13f, s, bold = true, center = true), maxLines = 1)
        }
    }

    Frame(col, cfg) {
        when {
            // A one-row bar: the three counts and the button side by side.
            size.height < 110.dp -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Count(n.toReview, "To review", col.primaryC, col.onPrimaryC, toReview, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(5.dp))
                Count(n.toSchedule, "Schedule", col.tertiaryC, col.onTertiaryC, toSchedule, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(5.dp))
                Count(n.overdue, "Overdue", col.errorC, col.onErrorC, overdue, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(5.dp))
                StartButton(GlanceModifier)
            }
            // The small tile.
            size.width < 230.dp -> Column(GlanceModifier.fillMaxSize()) {
                Text("Review", style = ts(col.on, 13.5f, s, bold = true), modifier = GlanceModifier.padding(start = 4.dp, bottom = 5.dp))
                Count(n.toReview, "To review", col.primaryC, col.onPrimaryC, toReview, GlanceModifier.fillMaxWidth())
                Row(GlanceModifier.fillMaxWidth().padding(top = 5.dp)) {
                    Count(n.toSchedule, "Schedule", col.tertiaryC, col.onTertiaryC, toSchedule, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(5.dp))
                    Count(n.overdue, "Overdue", col.errorC, col.onErrorC, overdue, GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.height(6.dp))
                StartButton(GlanceModifier.fillMaxWidth())
            }
            // The wide bar.
            else -> Column(GlanceModifier.fillMaxSize()) {
                Text("Review", style = ts(col.on, 15f, s, bold = true), modifier = GlanceModifier.padding(start = 4.dp, bottom = 6.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    Count(n.toReview, "To review", col.primaryC, col.onPrimaryC, toReview, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(6.dp))
                    Count(n.toSchedule, "To schedule", col.tertiaryC, col.onTertiaryC, toSchedule, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(6.dp))
                    Count(n.overdue, "Overdue", col.errorC, col.onErrorC, overdue, GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.height(7.dp))
                StartButton(GlanceModifier.fillMaxWidth())
            }
        }
    }
}

// ---- Daily ticks ----

@Composable
private fun DailyBody(c: Context, cfg: WidgetConfig, col: WidgetColors, now: Long) {
    val s = cfg.textScale
    val day = Dossier.openDay(now)
    val list = DossierStore.todays(now)
    val done = list.count { DossierStore.record(it.id, day)?.done == true }
    Frame(col, cfg) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Daily ticks", style = ts(col.on, 15f, s, bold = true))
            Box(GlanceModifier.padding(start = 6.dp)) { Chip("$done / ${list.size}", col.secondaryC, col.onSecondaryC, s) }
            Spacer(GlanceModifier.defaultWeight())
            RoundBtn("⟳", col.row, col.onVar, actionRunCallback<WidgetRefreshAction>(), 28.dp, s)
        }
        LazyColumn(GlanceModifier.fillMaxWidth()) {
            if (list.isEmpty()) {
                item {
                    Empty(col, s, "No Dossier items today", "Open Dossier", actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_DOSSIER)))
                }
            }
            items(list) { d ->
                val ticked = DossierStore.record(d.id, day)?.done == true
                Row(GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        GlanceModifier.size(24.dp).cornerRadius(6.dp).background(if (ticked) col.secondary else col.onVar)
                            .clickable(actionRunCallback<DossierTickAction>(actionParametersOf(KEY_ID to d.id, KEY_DATE to day.toString()))),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (ticked) Text("✓", style = ts(col.onSecondary, 14f, s, bold = true, center = true))
                        else Box(GlanceModifier.size(20.dp).cornerRadius(4.dp).background(col.bg)) {}
                    }
                    Column(
                        GlanceModifier.defaultWeight().padding(start = 10.dp)
                            .clickable(actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_DOSSIER)))
                    ) {
                        Text(d.title, style = ts(if (ticked) col.onVar else col.on, 13.5f, s, bold = true, strike = ticked), maxLines = 2)
                        Text(d.rangeLabel, style = ts(col.onVar, 11f, s), maxLines = 1)
                    }
                }
            }
            item {
                Text(
                    "Tap again to untick · locks at 3 AM, then the new day starts",
                    style = ts(col.onVar, 10.5f, s),
                    modifier = GlanceModifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

// ---- Quick add ----

@Composable
private fun QuickAddBody(c: Context, cfg: WidgetConfig, col: WidgetColors) {
    val s = cfg.textScale
    Box(
        GlanceModifier.fillMaxSize().cornerRadius(22.dp)
            .background(col.bg.copy(alpha = 1f - cfg.seeThrough.coerceIn(0, 80) / 100f)).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                GlanceModifier.defaultWeight().height(42.dp).cornerRadius(21.dp).background(col.primary)
                    .clickable(actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_NEW_FOLLOW_UP))),
                contentAlignment = Alignment.Center,
            ) { Text("＋ Follow-up", style = ts(col.onPrimary, 13.5f, s, bold = true, center = true), maxLines = 1) }
            Spacer(GlanceModifier.width(6.dp))
            Box(
                GlanceModifier.defaultWeight().height(42.dp).cornerRadius(21.dp).background(col.secondary)
                    .clickable(actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_NEW_TASK))),
                contentAlignment = Alignment.Center,
            ) { Text("＋ Task", style = ts(col.onSecondary, 13.5f, s, bold = true, center = true), maxLines = 1) }
            Spacer(GlanceModifier.width(6.dp))
            RoundBtn("🎤", col.row, col.on, actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_VOICE)), 42.dp, s, font = 18f)
        }
    }
}

// ---- The two calendar widgets (Best fit: an hour grows with what's in it) ----

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun hourLabel(h: Int): Pair<String, String> = "${if (h % 12 == 0) 12 else h % 12}" to if (h < 12) "am" else "pm"

private fun hourStart(day: LocalDate, h: Int): Long = CalendarData.at(day, h * 60)

@Composable
private fun HourRow(col: WidgetColors, s: Float, h: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(GlanceModifier.fillMaxWidth()) {
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(col.row)) {}
        Row(GlanceModifier.fillMaxWidth().padding(top = 3.dp, bottom = 3.dp)) {
            val (n, ap) = hourLabel(h)
            Column(GlanceModifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(n, style = ts(col.onVar, 11f, s, center = true))
                Text(ap, style = ts(col.onVar, 8.5f, s, center = true))
            }
            Column(GlanceModifier.defaultWeight().padding(start = 4.dp), content = content)
        }
    }
}

/** A card with its colour stripe on the left (the Calendar's look). */
@Composable
private fun CalCard(
    title: String,
    sub: String,
    fill: Color,
    edge: Color,
    ink: Color,
    s: Float,
    done: Boolean,
    onClick: Action,
    call: Action? = null,
    callBg: Color = edge,
) {
    Box(GlanceModifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Box(GlanceModifier.fillMaxWidth().cornerRadius(9.dp).background(edge).padding(start = 4.dp)) {
            Row(
                GlanceModifier.fillMaxWidth().background(fill).padding(start = 7.dp, end = 6.dp, top = 5.dp, bottom = 5.dp).clickable(onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(title, style = ts(ink, 12.5f, s, bold = true, strike = done), maxLines = 2)
                    Text(sub, style = ts(ink, 10.5f, s), maxLines = 1)
                }
                if (done) Text("✓", style = ts(ink, 13f, s, bold = true))
                if (call != null) RoundBtn("📞", callBg, Color.White, call, 26.dp, s, font = 12f, gap = true)
            }
        }
    }
}

@Composable
private fun CarryLine(text: String, edge: Color, col: WidgetColors, s: Float) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(4.dp).height(16.dp).cornerRadius(2.dp).background(edge)) {}
        Text("  $text", style = ts(col.onVar, 10.5f, s), maxLines = 1)
    }
}

@Composable
private fun EarlierRow(c: Context, text: String, col: WidgetColors, s: Float) {
    Text(
        text,
        style = ts(col.primary, 11.5f, s, bold = true),
        maxLines = 1,
        modifier = GlanceModifier.padding(start = 4.dp, bottom = 4.dp).clickable(actionStartActivity(WidgetIntents.app(c, MainActivity.OPEN_CALENDAR))),
    )
}

@Composable
private fun TasksCalendarBody(c: Context, cfg: WidgetConfig, col: WidgetColors, now: Long) {
    val s = cfg.textScale
    val day = LocalDate.now(zone)
    val all = CalendarData.blocks(c, day)
    val blocks = all.filter { it.kind != CalBlock.Kind.DOSSIER }
    val gaps = CalendarData.freeGaps(c, day, all, now)
    val freeMin = gaps.sumOf { (it.second - it.first) / 60_000L }
    val first = WidgetData.firstHour(now, blocks.filter { !it.done && it.end > now }.map { it.start })
    val earlier = blocks.count { it.end <= hourStart(day, first) }
    val task = CardColours.swatch(c, CardColours.Kind.TASK)
    val google = CardColours.swatch(c, CardColours.Kind.GOOGLE)
    val ink = CardColours.INK
    Frame(col, cfg) {
        Header(
            c, 0, col, s, "My tasks", null, false, null,
            WidgetIntents.popup(c, WidgetActivity.MODE_NEW_TASK),
            info = "Today · ${CalendarData.lengthLabel(freeMin)} free",
        )
        LazyColumn(GlanceModifier.fillMaxWidth()) {
            if (earlier > 0) item { EarlierRow(c, "Earlier today: $earlier · open Calendar ▸", col, s) }
            items((first..23).toList()) { h ->
                val from = hourStart(day, h)
                val to = from + 3_600_000L
                val starts = blocks.filter { it.start in from until to }
                val carried = blocks.filter { it.start < from && it.end > from }
                val free = gaps.filter { it.first in from until to }
                HourRow(col, s, h) {
                    carried.take(2).forEach { b ->
                        val sw = if (b.kind == CalBlock.Kind.GOOGLE) google else task
                        CarryLine("${b.title} until ${Fmt.timeOnly(b.end)}", if (b.done) col.outline else sw.edgeColor, col, s)
                    }
                    free.take(1).forEach { g ->
                        Row(
                            GlanceModifier.fillMaxWidth().padding(bottom = 3.dp).cornerRadius(9.dp).background(col.secondaryC)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .clickable(actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_NEW_TASK, extra = -1L, at = g.first))),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Free ${Fmt.timeOnly(g.first)}–${Fmt.timeOnly(g.second)}",
                                style = ts(col.onSecondaryC, 12f, s, bold = true),
                                modifier = GlanceModifier.defaultWeight(),
                                maxLines = 1,
                            )
                            Chip("＋ Add", col.secondary, col.onSecondary, s)
                        }
                    }
                    val shown = starts.take(6)
                    shown.forEach { b ->
                        val sw = if (b.kind == CalBlock.Kind.GOOGLE) google else task
                        val len = (b.end - b.start) / 60_000L
                        val sub = (if (b.kind == CalBlock.Kind.GOOGLE) "Google · " else "") +
                            "${Fmt.timeOnly(b.start)}–${Fmt.timeOnly(b.end)}" + if (len >= 60) " · ${CalendarData.lengthLabel(len)}" else ""
                        val open: Action = if (b.kind == CalBlock.Kind.GOOGLE) {
                            actionStartActivity(
                                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, b.id))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } else {
                            actionStartActivity(WidgetIntents.popup(c, WidgetActivity.MODE_TASK_ACTIONS, b.id))
                        }
                        CalCard(
                            b.title, sub,
                            if (b.done) col.row else sw.fillColor,
                            if (b.done) col.outline else sw.edgeColor,
                            if (b.done) col.onVar else ink, s, b.done, open,
                        )
                    }
                    if (starts.size > shown.size) CarryLine("+${starts.size - shown.size} more · open Calendar", col.outline, col, s)
                }
            }
        }
    }
}

@Composable
private fun FollowUpsCalendarBody(c: Context, cfg: WidgetConfig, col: WidgetColors, now: Long) {
    val s = cfg.textScale
    val day = LocalDate.now(zone)
    val list = CalendarData.followUps(c, day, now)
    val first = WidgetData.firstHour(now, list.filter { !it.done }.map { it.at })
    val earlier = list.count { it.at < hourStart(day, first) }
    val fu = CardColours.swatch(c, CardColours.Kind.FOLLOW_UP)
    val ink = CardColours.INK
    Frame(col, cfg) {
        Header(
            c, 0, col, s, "Follow-ups", null, false, null,
            WidgetIntents.popup(c, WidgetActivity.MODE_NEW_FOLLOW_UP),
            info = "Today · ${list.count { !it.done }}",
        )
        LazyColumn(GlanceModifier.fillMaxWidth()) {
            if (earlier > 0) item { EarlierRow(c, "Earlier today: $earlier · open Calendar ▸", col, s) }
            items((first..23).toList()) { h ->
                val from = hourStart(day, h)
                val starts = list.filter { it.at in from until from + 3_600_000L }
                HourRow(col, s, h) {
                    val shown = starts.take(7)
                    shown.forEach { f ->
                        val mode = if (f.isCall) WidgetActivity.MODE_CALL_ACTIONS else WidgetActivity.MODE_TEXT_ACTIONS
                        val call = if (!f.done && f.number.isNotBlank()) {
                            actionStartActivity(
                                WidgetIntents.popup(
                                    c, if (f.isCall) WidgetActivity.MODE_CALL else WidgetActivity.MODE_CALL_NUMBER_OF_TEXT, f.id
                                )
                            )
                        } else null
                        CalCard(
                            f.title,
                            (if (f.isCall) "📞 " else "💬 ") + "${Fmt.timeOnly(f.at)} · ${f.person}",
                            if (f.done) col.row else fu.fillColor,
                            if (f.done) col.outline else if (f.overdue) col.error else fu.edgeColor,
                            if (f.done) col.onVar else ink, s, f.done,
                            actionStartActivity(WidgetIntents.popup(c, mode, f.id, f.at)),
                            call, fu.edgeColor,
                        )
                    }
                    if (starts.size > shown.size) CarryLine("+${starts.size - shown.size} more · open Calendar", col.outline, col, s)
                }
            }
        }
    }
}
