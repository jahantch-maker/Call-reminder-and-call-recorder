package com.callbackreminder.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** The 7 home screen widgets. [setup] = it asks for a filter or list when you add it. */
enum class WidgetKind(val title: String, val setup: Boolean) {
    FOLLOW_UPS("Follow-ups", true),
    MY_TASKS("My tasks", true),
    REVIEW("Review", false),
    DAILY("Daily ticks", false),
    QUICK_ADD("Quick add", false),
    CAL_TASKS("My tasks calendar", false),
    CAL_FOLLOW_UPS("Follow-ups calendar", false);

    fun widget(): GlanceAppWidget = when (this) {
        FOLLOW_UPS -> FollowUpsWidget()
        MY_TASKS -> MyTasksWidget()
        REVIEW -> ReviewWidget()
        DAILY -> DailyTicksWidget()
        QUICK_ADD -> QuickAddWidget()
        CAL_TASKS -> TasksCalendarWidget()
        CAL_FOLLOW_UPS -> FollowUpsCalendarWidget()
    }

    fun receiver(): Class<*> = when (this) {
        FOLLOW_UPS -> FollowUpsWidgetReceiver::class.java
        MY_TASKS -> MyTasksWidgetReceiver::class.java
        REVIEW -> ReviewWidgetReceiver::class.java
        DAILY -> DailyTicksWidgetReceiver::class.java
        QUICK_ADD -> QuickAddWidgetReceiver::class.java
        CAL_TASKS -> TasksCalendarWidgetReceiver::class.java
        CAL_FOLLOW_UPS -> FollowUpsCalendarWidgetReceiver::class.java
    }

    fun ids(c: Context): IntArray =
        AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, receiver()))

    companion object {
        /** Which widget a home screen id belongs to (from the launcher's record of it). */
        fun of(c: Context, appWidgetId: Int): WidgetKind? {
            val cls = AppWidgetManager.getInstance(c).getAppWidgetInfo(appWidgetId)?.provider?.className ?: return null
            return entries.firstOrNull { it.receiver().name == cls }
        }
    }
}

/** One widget's own settings: what it shows and how it looks (picked when you add it, or on long-press). */
data class WidgetConfig(
    val filter: Int = WidgetPrefs.FILTER_TODAY,
    val listId: Long = WidgetPrefs.ALL_LISTS,
    val look: Int = WidgetPrefs.LOOK_DARK,
    /** 0 = solid … 80 = mostly see-through. */
    val seeThrough: Int = 0,
    val textSize: Int = WidgetPrefs.TEXT_NORMAL,
) {
    val textScale: Float get() = when (textSize) {
        WidgetPrefs.TEXT_SMALL -> 0.88f
        WidgetPrefs.TEXT_LARGE -> 1.15f
        else -> 1f
    }
}

object WidgetPrefs {
    const val FILTER_OVERDUE = 0
    const val FILTER_TODAY = 1
    const val FILTER_UPCOMING = 2
    const val FILTER_ALL = 3
    val FILTERS = listOf(FILTER_OVERDUE to "Overdue", FILTER_TODAY to "Today", FILTER_UPCOMING to "Upcoming", FILTER_ALL to "All")

    const val ALL_LISTS = -1L

    const val LOOK_DARK = 0
    const val LOOK_LIGHT = 1
    const val LOOK_WALLPAPER = 2
    val LOOKS = listOf(LOOK_DARK to "Dark", LOOK_LIGHT to "Light", LOOK_WALLPAPER to "Wallpaper")

    const val TEXT_SMALL = 0
    const val TEXT_NORMAL = 1
    const val TEXT_LARGE = 2
    val TEXT_SIZES = listOf(TEXT_SMALL to "Small", TEXT_NORMAL to "Normal", TEXT_LARGE to "Large")

    private const val KEY_TIP = "tip_dismissed"

    private fun p(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(c: Context, id: Int): WidgetConfig {
        val p = p(c)
        return WidgetConfig(
            filter = p.getInt("w${id}_filter", FILTER_TODAY),
            listId = p.getLong("w${id}_list", ALL_LISTS),
            look = p.getInt("w${id}_look", LOOK_DARK),
            seeThrough = p.getInt("w${id}_see", 0),
            textSize = p.getInt("w${id}_text", TEXT_NORMAL),
        )
    }

    fun set(c: Context, id: Int, cfg: WidgetConfig) {
        p(c).edit()
            .putInt("w${id}_filter", cfg.filter)
            .putLong("w${id}_list", cfg.listId)
            .putInt("w${id}_look", cfg.look)
            .putInt("w${id}_see", cfg.seeThrough)
            .putInt("w${id}_text", cfg.textSize)
            .apply()
    }

    /** This widget was set up before (so the setup page saves instead of adding). */
    fun exists(c: Context, id: Int) = p(c).contains("w${id}_look")

    fun remove(c: Context, id: Int) {
        val e = p(c).edit()
        listOf("filter", "list", "look", "see", "text").forEach { e.remove("w${id}_$it") }
        e.apply()
    }

    fun filterLabel(f: Int) = FILTERS.firstOrNull { it.first == f }?.second ?: "Today"

    /** The one-time "New: home screen widgets" card on Home. */
    fun tipDismissed(c: Context) = p(c).getBoolean(KEY_TIP, false)
    fun dismissTip(c: Context) = p(c).edit().putBoolean(KEY_TIP, true).apply()
}

/** A widget's colours, from the app's own light and dark themes (or the wallpaper's colours on Android 12+). */
data class WidgetColors(
    val bg: Color,
    val row: Color,
    val on: Color,
    val onVar: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryC: Color,
    val onPrimaryC: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryC: Color,
    val onSecondaryC: Color,
    val error: Color,
    val errorC: Color,
    val onErrorC: Color,
    val orangeC: Color,
    val onOrangeC: Color,
    val outline: Color,
    val tertiaryC: Color,
    val onTertiaryC: Color,
)

object WidgetLook {
    fun colors(c: Context, cfg: WidgetConfig): WidgetColors = when {
        cfg.look == WidgetPrefs.LOOK_LIGHT -> fromScheme(false)
        cfg.look == WidgetPrefs.LOOK_WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> wallpaper(c)
        else -> fromScheme(true)
    }

    private fun fromScheme(dark: Boolean): WidgetColors {
        val s = if (dark) DarkColors else LightColors
        val orange = if (dark) OrangeDark else OrangeLight
        return WidgetColors(
            bg = s.surface,
            row = s.surfaceVariant,
            on = s.onSurface,
            onVar = s.onSurfaceVariant,
            primary = s.primary,
            onPrimary = s.onPrimary,
            primaryC = s.primaryContainer,
            onPrimaryC = s.onPrimaryContainer,
            secondary = s.secondary,
            onSecondary = s.onSecondary,
            secondaryC = s.secondaryContainer,
            onSecondaryC = s.onSecondaryContainer,
            error = s.error,
            errorC = s.errorContainer,
            onErrorC = s.onErrorContainer,
            orangeC = orange.first,
            onOrangeC = orange.second,
            outline = s.outline,
            tertiaryC = s.tertiaryContainer,
            onTertiaryC = s.onTertiaryContainer,
        )
    }

    /** Material You: tones picked by the phone from your wallpaper, kept dark so the app's colours still read. */
    private fun wallpaper(c: Context): WidgetColors {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fromScheme(true)
        fun col(id: Int) = Color(c.getColor(id))
        val dark = fromScheme(true)
        return dark.copy(
            bg = col(android.R.color.system_accent2_800),
            row = col(android.R.color.system_accent2_700),
            on = col(android.R.color.system_accent2_50),
            onVar = col(android.R.color.system_accent2_200),
            primary = col(android.R.color.system_accent1_200),
            onPrimary = col(android.R.color.system_accent1_800),
            primaryC = col(android.R.color.system_accent1_600),
            onPrimaryC = col(android.R.color.system_accent1_50),
            outline = col(android.R.color.system_accent2_500),
        )
    }
}

/** What the widgets show, worked out the same way as the screens in the app. */
object WidgetData {
    fun ensure(c: Context) {
        ReminderStore.init(c)
        SmsStore.init(c)
        ToScheduleStore.init(c)
        MyTaskStore.init(c)
        DossierStore.init(c)
        OffDays.init(c)
    }

    /** A titled part of the Follow-ups widget: Overdue, Today, Tomorrow … */
    class FuSection(val title: String, val red: Boolean, val items: List<FuEntry>)

    fun followUps(filter: Int, now: Long): List<FuSection> {
        val zone = ZoneId.systemDefault()
        val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val open = entries(ReminderStore.items.value, SmsStore.items.value)
            .filter { it.open && !it.hidden && !it.inReview }
            .sortedBy { it.at }
        val overdue = open.filter { it.callPending && it.at <= now }
        val rest = open.filter { !(it.callPending && it.at <= now) }
        val today = rest.filter { it.at < endOfToday }
        val later = rest.filter { it.at >= endOfToday }
        val out = ArrayList<FuSection>()
        if (filter != WidgetPrefs.FILTER_UPCOMING && overdue.isNotEmpty()) out += FuSection("OVERDUE", true, overdue)
        if ((filter == WidgetPrefs.FILTER_TODAY || filter == WidgetPrefs.FILTER_ALL) && today.isNotEmpty()) {
            out += FuSection("TODAY", false, today)
        }
        if (filter == WidgetPrefs.FILTER_UPCOMING || filter == WidgetPrefs.FILTER_ALL) {
            byDay(later) { it.at }.forEach { (day, list) -> out += FuSection(RowFmt.dayTitle(day).uppercase(), false, list) }
        }
        return out
    }

    /** Open My tasks of one list (or all): timed ones by time, then the ones with no time. */
    fun myTasks(listId: Long): List<MyTask> =
        MyTaskStore.items.value
            .filter { !it.done && (listId == WidgetPrefs.ALL_LISTS || it.listId == listId) }
            .sortedWith(compareBy<MyTask> { !it.timed }.thenBy { if (it.timed) it.dueAt else 0L }.thenBy { !it.starred }.thenBy { it.position })

    data class ReviewCounts(val toReview: Int, val toSchedule: Int, val overdue: Int)

    fun reviewCounts(c: Context, now: Long): ReviewCounts {
        val all = ReviewData.build(c, now)
        fun n(s: ReviewSection) = all[s]?.size ?: 0
        return ReviewCounts(
            toReview = n(ReviewSection.REPLIED),
            toSchedule = n(ReviewSection.SCHED_FU) + n(ReviewSection.SCHED_MY),
            overdue = n(ReviewSection.OVERDUE) + n(ReviewSection.MY_LATE),
        )
    }

    /** One hour of a calendar widget, with what starts in it and what carries on from before. */
    class Hour<T>(val hour: Int, val starts: List<T>, val carried: List<T>, val freeFrom: Pair<Long, Long>?)

    /** First hour a calendar widget shows: now, or the earliest hour that still has something open in it. */
    fun firstHour(now: Long, openStarts: List<Long>): Int {
        val zone = ZoneId.systemDefault()
        val nowHour = java.time.Instant.ofEpochMilli(now).atZone(zone).hour
        val earliest = openStarts.filter { it < now }.minOrNull()?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).hour }
        return minOf(nowHour, earliest ?: nowHour)
    }

    fun hourOf(ms: Long): Int = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour
}

/**
 * Keeps every widget up to date: any change to follow-ups, tasks, ticks or the Calendar colours redraws them
 * (a little after the last change, so a burst of saves draws once).
 */
object WidgetSync {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tick = MutableStateFlow(0)
    /** Bumps on every redraw, so a widget that is on screen recomposes with fresh data. */
    val tick: StateFlow<Int> = _tick.asStateFlow()

    private var started = false

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start(app: Context) {
        if (started) return
        started = true
        val c = app.applicationContext
        WidgetData.ensure(c)
        val changes: List<Flow<Unit>> = listOf(
            ReminderStore.items.map { },
            SmsStore.items.map { },
            ToScheduleStore.items.map { },
            MyTaskStore.items.map { },
            MyTaskStore.lists.map { },
            DossierStore.tasks.map { },
            DossierStore.records.map { },
            CardColours.version.map { },
            GoogleCal.version.map { },
            WorkHours.version.map { },
        )
        scope.launch {
            merge(*changes.toTypedArray()).drop(changes.size).debounce(400L).collect { refresh(c) }
        }
    }

    /** Redraws every widget on the home screen (nothing happens when none are added). */
    fun refresh(c: Context) {
        _tick.value = _tick.value + 1
        scope.launch {
            WidgetKind.entries.forEach { k ->
                try {
                    if (k.ids(c).isNotEmpty()) k.widget().updateAll(c)
                } catch (e: Exception) {
                    // A widget that can't draw now draws on its next update.
                }
            }
        }
    }
}

/** The Undo bar a list widget shows for 5 seconds after you tap Done on it. */
object WidgetUndo {
    class Entry(val label: String, val undo: () -> Unit, val until: Long)

    private const val SHOW_MS = 5_000L
    private val map = ConcurrentHashMap<Int, Entry>()

    fun get(widgetId: Int, now: Long = System.currentTimeMillis()): Entry? = map[widgetId]?.takeIf { it.until > now }

    fun put(c: Context, widgetId: Int, label: String, undo: () -> Unit) {
        val e = Entry(label, undo, System.currentTimeMillis() + SHOW_MS)
        map[widgetId] = e
        WidgetSync.refresh(c)
        WidgetSync.scope.launch {
            delay(SHOW_MS + 200L)
            if (map[widgetId] === e) {
                map.remove(widgetId)
                WidgetSync.refresh(c)
            }
        }
    }

    fun undo(c: Context, widgetId: Int) {
        val e = map.remove(widgetId) ?: return
        e.undo()
        WidgetSync.refresh(c)
    }
}

/** Opens the app on a page, or a widget popup, from a widget tap. */
object WidgetIntents {
    fun app(c: Context, open: String? = null, task: Long = -1L): Intent =
        Intent(c, MainActivity::class.java)
            .setData(Uri.parse("cbwidget://app/${open ?: "home"}/$task"))
            .apply {
                if (open != null) putExtra(MainActivity.EXTRA_OPEN, open)
                if (task >= 0) putExtra(MainActivity.EXTRA_TASK, task)
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Start review: the review session of everything. */
    fun startReview(c: Context): Intent =
        app(c, MainActivity.OPEN_REVIEW).setData(Uri.parse("cbwidget://app/review/start"))
            .putExtra(MainActivity.EXTRA_START, true)

    fun popup(c: Context, mode: String, id: Long = -1L, extra: Long = -1L, widgetId: Int = 0, at: Long = -1L): Intent =
        Intent(c, WidgetActivity::class.java)
            // A different address per item, so each button on each row keeps its own tap.
            .setData(Uri.parse("cbwidget://popup/$mode/$id/$extra/$widgetId/$at"))
            .putExtra(WidgetActivity.EXTRA_AT, at)
            .putExtra(WidgetActivity.EXTRA_MODE, mode)
            .putExtra(WidgetActivity.EXTRA_ID, id)
            .putExtra(WidgetActivity.EXTRA_EXTRA, extra)
            .putExtra(WidgetActivity.EXTRA_WIDGET, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    fun setup(c: Context, widgetId: Int): Intent =
        Intent(c, WidgetSetupActivity::class.java)
            .setData(Uri.parse("cbwidget://setup/$widgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

// ---- Taps that act straight on the widget (no screen opens) ----

internal val KEY_ID = ActionParameters.Key<Long>("id")
internal val KEY_TEXT_ID = ActionParameters.Key<Long>("text_id")
internal val KEY_DATE = ActionParameters.Key<String>("date")

private suspend fun widgetIdOf(c: Context, glanceId: GlanceId): Int =
    try {
        GlanceAppWidgetManager(c).getAppWidgetId(glanceId)
    } catch (e: Exception) {
        0
    }

/** ✓ on a follow-up row: done right away, Undo for 5 seconds. */
class FollowUpDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetData.ensure(context)
        val callId = parameters[KEY_ID] ?: -1L
        val textId = parameters[KEY_TEXT_ID] ?: -1L
        val call = ReminderStore.get(callId)
        val text = SmsStore.get(textId)
        val link = FollowUps.snapshot(call?.linkId ?: text?.linkId ?: 0L)
        val title = call?.note?.takeIf { it.isNotBlank() } ?: text?.message ?: call?.title ?: "Follow-up"
        when {
            call != null && !call.done -> ReminderStore.markDone(call.id)
            text != null && text.active -> SmsStore.markDone(text.id)
            else -> return
        }
        WidgetUndo.put(context, widgetIdOf(context, glanceId), "Done: $title") {
            call?.let { ReminderStore.restore(it) }
            text?.let { SmsStore.restore(it) }
            link()
        }
    }
}

/** The round tick on a My tasks row. */
class TaskDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetData.ensure(context)
        val id = parameters[KEY_ID] ?: return
        val t = MyTaskStore.get(id)?.takeIf { !it.done } ?: return
        MyTaskStore.setDone(id, true)
        WidgetUndo.put(context, widgetIdOf(context, glanceId), "Done: ${t.title}") { MyTaskStore.setDone(id, false) }
    }
}

/** A Daily ticks box: tick, or tap again to untick (until the day locks at 3 AM). */
class DossierTickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetData.ensure(context)
        val id = parameters[KEY_ID] ?: return
        val date = parameters[KEY_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        if (DossierStore.record(id, date)?.done == true) DossierStore.untick(id, date) else DossierStore.tick(id, date)
        WidgetSync.refresh(context)
    }
}

class WidgetUndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetUndo.undo(context, widgetIdOf(context, glanceId))
    }
}

/** ⟳ in a widget's top bar. */
class WidgetRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        GoogleCal.refresh()
        WidgetSync.refresh(context)
    }
}
