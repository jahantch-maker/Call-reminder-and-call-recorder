package com.callbackreminder.app

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.os.Bundle
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settings › Calendar › Working hours. Required before the Calendar can be used: the free time, the best
 * free slots and the "free" count only look inside these hours. Saved as minutes of the day.
 */
object WorkHours {
    private const val KEY_START = "wh_start"
    private const val KEY_END = "wh_end"

    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so screens showing free time redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun isSet(c: Context) = AppSettings.getString(c, KEY_START).isNotEmpty() && AppSettings.getString(c, KEY_END).isNotEmpty()
    fun start(c: Context) = AppSettings.getString(c, KEY_START).toIntOrNull()?.coerceIn(0, 24 * 60) ?: (9 * 60)
    fun end(c: Context) = AppSettings.getString(c, KEY_END).toIntOrNull()?.coerceIn(0, 24 * 60) ?: (18 * 60)

    fun set(c: Context, start: Int, end: Int) {
        AppSettings.setString(c, KEY_START, start.toString())
        AppSettings.setString(c, KEY_END, maxOf(end, start + 15).coerceAtMost(24 * 60).toString())
        _version.value++
    }

    fun label(c: Context): String =
        if (!isSet(c)) "Not set yet (needed for the Calendar)"
        else Dossier.timeLabel(start(c)) + " - " + Dossier.timeLabel(end(c) % (24 * 60))

    /** Today's working hours as times, or null on the weekly off day (no free slots then). */
    fun window(c: Context, date: LocalDate): Pair<Long, Long>? {
        if (!isSet(c) || OffDays.isOff(date)) return null
        return CalendarData.at(date, start(c)) to CalendarData.at(date, end(c))
    }
}

/** One event from the phone's synced calendars (Google Calendar). Read only. */
data class GEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val calendar: String,
    val color: Int,
)

/** One calendar on the phone (an account's own calendar, its Holidays, Birthdays …), for the on/off switches. */
data class GCalendar(
    val id: Long,
    val account: String,
    val accountType: String,
    val name: String,
    val color: Int,
)

/**
 * Reads the Google Calendar that is synced to this phone (Android's calendar store), with the
 * Read calendar permission. No Google sign-in; nothing is written back.
 */
object GoogleCal {
    private const val KEY_ON = "gcal_on"
    private const val KEY_ASKED = "gcal_asked"
    private const val KEY_CALS = "gcal_cals"
    private const val KEY_PICKED = "gcal_picked"
    private const val CACHE_MS = 60_000L

    private val cache = HashMap<LocalDate, Pair<Long, List<GEvent>>>()
    private val _version = MutableStateFlow(0)
    /** Bumps when the events may have changed (switch, permission, refresh). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun enabled(c: Context) = AppSettings.get(c, KEY_ON, false) && hasPermission(c)
    fun wanted(c: Context) = AppSettings.get(c, KEY_ON, false)
    fun asked(c: Context) = AppSettings.get(c, KEY_ASKED, false)

    fun setEnabled(c: Context, on: Boolean) {
        AppSettings.set(c, KEY_ON, on)
        AppSettings.set(c, KEY_ASKED, true)
        refresh()
    }

    fun setAsked(c: Context) = AppSettings.set(c, KEY_ASKED, true)

    /**
     * The calendars you switched on in Calendar settings › Google calendars. Everything else stays out
     * (other accounts, Holidays, Birthdays), and a calendar added to the phone later starts off.
     */
    fun onIds(c: Context): Set<Long> =
        AppSettings.getString(c, KEY_CALS).split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    fun setCalendarOn(c: Context, id: Long, on: Boolean) {
        val ids = onIds(c).toMutableSet()
        if (on) ids.add(id) else ids.remove(id)
        AppSettings.setString(c, KEY_CALS, ids.joinToString(","))
        refresh()
    }

    /** False until the calendar list has been shown once (it opens by itself the first time). */
    fun picked(c: Context) = AppSettings.get(c, KEY_PICKED, false)
    fun setPicked(c: Context) = AppSettings.set(c, KEY_PICKED, true)

    /** Every calendar on the phone, grouped by account (sorted by account, then name). */
    fun calendars(c: Context): List<GCalendar> {
        if (!hasPermission(c)) return emptyList()
        val cols = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        return try {
            c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { cur ->
                val out = ArrayList<GCalendar>()
                while (cur.moveToNext()) {
                    out.add(
                        GCalendar(
                            id = cur.getLong(0),
                            account = cur.getString(1).orEmpty(),
                            accountType = cur.getString(2).orEmpty(),
                            name = cur.getString(3).orEmpty().ifBlank { "(No name)" },
                            color = cur.getInt(4),
                        )
                    )
                }
                out.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The ↻ button: asks Android to sync the switched-on accounts with Google now, waits until that sync
     * finishes (at most 20 s), then reloads.
     */
    suspend fun syncNow(c: Context) = withContext(Dispatchers.IO) {
        val on = onIds(c)
        val accounts = calendars(c)
            .filter { it.id in on && it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL && it.account.isNotBlank() }
            .map { Account(it.account, it.accountType) }
            .distinct()
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        accounts.forEach { runCatching { ContentResolver.requestSync(it, CalendarContract.AUTHORITY, extras) } }
        delay(1_500L)
        val until = System.currentTimeMillis() + 20_000L
        fun busy() = accounts.any { a ->
            runCatching {
                ContentResolver.isSyncActive(a, CalendarContract.AUTHORITY) || ContentResolver.isSyncPending(a, CalendarContract.AUTHORITY)
            }.getOrDefault(false)
        }
        while (System.currentTimeMillis() < until && busy()) delay(500L)
        refresh()
    }

    @Synchronized
    fun refresh() {
        cache.clear()
        _version.value++
    }

    /** The day's events, newest copy at most a minute old. Empty when off or not allowed. */
    @Synchronized
    fun events(c: Context, date: LocalDate): List<GEvent> {
        if (!enabled(c)) return emptyList()
        val ids = onIds(c)
        if (ids.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        cache[date]?.let { (at, list) -> if (now - at < CACHE_MS) return list }
        val list = query(c, date, ids)
        cache[date] = now to list
        return list
    }

    private fun query(c: Context, date: LocalDate, ids: Set<Long>): List<GEvent> {
        val from = CalendarData.at(date, 0)
        val to = CalendarData.at(date.plusDays(1), 0)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        return try {
            c.contentResolver.query(
                uri, cols, "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")})", null, CalendarContract.Instances.BEGIN
            )
                ?.use { cur ->
                    val out = ArrayList<GEvent>()
                    while (cur.moveToNext()) {
                        // Meetings you said No to don't take your time.
                        if (cur.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        val allDay = cur.getInt(4) == 1
                        var start = cur.getLong(2)
                        var end = cur.getLong(3)
                        if (allDay) {
                            // All-day events are stored in UTC midnights; show them on this day.
                            start = from
                            end = to
                        }
                        out += GEvent(
                            id = cur.getLong(0),
                            title = cur.getString(1).orEmpty().ifBlank { "(No title)" },
                            start = start,
                            end = maxOf(end, start + 5 * 60_000L),
                            allDay = allDay,
                            calendar = cur.getString(5).orEmpty(),
                            color = cur.getInt(6),
                        )
                    }
                    out
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** One thing that books your time in the Calendar: a timed My task, a Dossier block or a Google event. */
data class CalBlock(
    val kind: Kind,
    val id: Long,
    val title: String,
    val sub: String,
    val start: Long,
    val end: Long,
    val done: Boolean = false,
    val color: Int = 0,
) {
    enum class Kind { TASK, DOSSIER, GOOGLE }

    fun overlaps(from: Long, to: Long) = start < to && from < end
}

/** One follow-up on the Calendar: a call or one text time. Never books time, never clashes. */
data class CalFollowUp(
    val isCall: Boolean,
    val id: Long,
    val title: String,
    val person: String,
    val number: String,
    val at: Long,
    val overdue: Boolean,
) {
    val key: String get() = (if (isCall) "c" else "t") + "$id@$at"
}

/** The Calendar's sums: what books time on a day, the free gaps, the best free slots and clashes. */
object CalendarData {
    const val MIN_GAP = 15

    fun at(date: LocalDate, minute: Int): Long =
        date.atStartOfDay(ZoneId.systemDefault()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Everything that books time on [date] (done My tasks included, so you see them), sorted by start. */
    fun blocks(c: Context, date: LocalDate, excludeTaskId: Long = -1L): List<CalBlock> {
        val from = at(date, 0)
        val to = at(date.plusDays(1), 0)
        val out = ArrayList<CalBlock>()
        MyTaskStore.items.value.forEach { t ->
            if (t.timed && t.id != excludeTaskId && t.dueAt < to && t.endAt > from) {
                out += CalBlock(CalBlock.Kind.TASK, t.id, t.title, t.personTitle, t.dueAt, t.endAt, t.done)
            }
        }
        DossierStore.tasks.value.forEach { d ->
            if (d.isOn(date)) {
                val start = at(date, d.minute)
                out += CalBlock(
                    CalBlock.Kind.DOSSIER, d.id, d.title, d.frequency, start, start + d.lengthMin * 60_000L,
                    DossierStore.record(d.id, date)?.done == true
                )
            }
        }
        GoogleCal.events(c, date).filter { !it.allDay }.forEach { e ->
            out += CalBlock(CalBlock.Kind.GOOGLE, e.id, e.title, e.calendar, e.start, e.end, color = e.color)
        }
        return out.sortedWith(compareBy<CalBlock> { it.start }.thenBy { it.end })
    }

    /** The follow-ups on [date]: open calls at their due time, and each set time of active texts. */
    fun followUps(date: LocalDate, now: Long = System.currentTimeMillis()): List<CalFollowUp> {
        val from = at(date, 0)
        val to = at(date.plusDays(1), 0)
        val out = ArrayList<CalFollowUp>()
        ReminderStore.items.value.forEach { r ->
            if (!r.done && !r.review && r.dueAt in from until to) {
                out += CalFollowUp(
                    true, r.id, r.note.ifBlank { "Call back" }, r.name.ifBlank { r.number }, r.number, r.dueAt, r.dueAt <= now
                )
            }
        }
        SmsStore.items.value.forEach { s ->
            if (s.active) {
                s.baseTimes.filter { it in from until to }.forEach { t ->
                    out += CalFollowUp(false, s.id, s.message.lineSequence().firstOrNull().orEmpty().ifBlank { "Text" }, s.title, s.number, t, false)
                }
            }
        }
        return out.sortedBy { it.at }
    }

    /** Busy time as merged ranges (done items and the edited task left out). */
    private fun busy(blocks: List<CalBlock>): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        blocks.filter { !it.done }.sortedBy { it.start }.forEach { b ->
            val last = out.lastOrNull()
            if (last != null && b.start <= last.second) out[out.size - 1] = last.first to maxOf(last.second, b.end)
            else out += b.start to b.end
        }
        return out
    }

    /** Free gaps inside working hours on [date], from [now] on (rounded up to 5 minutes), each at least [MIN_GAP]. */
    fun freeGaps(c: Context, date: LocalDate, blocks: List<CalBlock>, now: Long = System.currentTimeMillis()): List<Pair<Long, Long>> {
        val (ws, we) = WorkHours.window(c, date) ?: return emptyList()
        var cursor = maxOf(ws, roundUp(now, 5))
        val out = ArrayList<Pair<Long, Long>>()
        busy(blocks).forEach { (s, e) ->
            if (s > cursor && minOf(s, we) - cursor >= MIN_GAP * 60_000L) out += cursor to minOf(s, we)
            cursor = maxOf(cursor, e)
        }
        if (we - cursor >= MIN_GAP * 60_000L) out += cursor to we
        return out
    }

    fun freeMinutes(c: Context, date: LocalDate, blocks: List<CalBlock>, now: Long = System.currentTimeMillis()): Long =
        freeGaps(c, date, blocks, now).sumOf { (it.second - it.first) / 60_000L }

    /**
     * Up to [count] best free starts for a task of [lengthMin] on [date]: the start of each free gap it fits in,
     * earliest first (a gap only gives one, so the three are spread over the day).
     */
    fun bestSlots(c: Context, date: LocalDate, lengthMin: Int, excludeTaskId: Long = -1L, count: Int = 3): List<Long> {
        val len = lengthMin * 60_000L
        return freeGaps(c, date, blocks(c, date, excludeTaskId))
            .filter { it.second - it.first >= len }
            .map { roundUp(it.first, 15).takeIf { s -> s + len <= it.second } ?: it.first }
            .take(count)
    }

    /** What a task from [start] for [lengthMin] would clash with (open items only). */
    fun clashes(c: Context, start: Long, lengthMin: Int, excludeTaskId: Long = -1L): List<CalBlock> {
        if (start <= 0L) return emptyList()
        val end = start + lengthMin * 60_000L
        val days = generateSequence(dateOf(start)) { it.plusDays(1) }.takeWhile { !it.isAfter(dateOf(end - 1)) }.toList()
        return days.flatMap { blocks(c, it, excludeTaskId) }.filter { !it.done && it.overlaps(start, end) }.distinct()
    }

    /** The next free start at or after [from] that fits [lengthMin], inside working hours, over the next 14 days. */
    fun nextFree(c: Context, from: Long, lengthMin: Int, excludeTaskId: Long = -1L): Long? {
        val len = lengthMin * 60_000L
        var date = dateOf(from)
        repeat(14) {
            val gaps = freeGaps(c, date, blocks(c, date, excludeTaskId), maxOf(from, System.currentTimeMillis()))
            gaps.firstOrNull { it.second - it.first >= len }?.let { return it.first }
            date = date.plusDays(1)
        }
        return null
    }

    /** Is a slot from [start] free of your own busy time (and inside working hours)? */
    fun fits(blocks: List<CalBlock>, window: Pair<Long, Long>?, start: Long, lengthMin: Int): Boolean {
        val end = start + lengthMin * 60_000L
        if (window == null || start < window.first || end > window.second) return false
        return blocks.none { !it.done && it.overlaps(start, end) }
    }

    fun roundUp(ms: Long, minutes: Int): Long {
        val step = minutes * 60_000L
        return (ms + step - 1) / step * step
    }

    /** Like "1 h 30 m" or "45 m". */
    fun lengthLabel(min: Long): String = when {
        min >= 60 && min % 60 == 0L -> "${min / 60} h"
        min >= 60 -> "${min / 60} h ${min % 60} m"
        else -> "$min m"
    }
}

/**
 * Set by a task editor around its time picker: the picker then crosses out busy times, outlines in green
 * where the task's length fits, and shows the 3 best free slots of the day.
 */
class PickBusy(val lengthMin: Int, val excludeTaskId: Long)

val LocalPickBusy = compositionLocalOf<PickBusy?> { null }
