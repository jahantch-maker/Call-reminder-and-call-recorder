package com.callbackreminder.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A task of your own (My tasks › Tasks), with an optional person to call or text about it. It sits in
 * one list ([listId] 0 is Unsorted). [dueAt] 0 means no time yet: it waits in To review as "Needs a time".
 */
data class MyTask(
    val id: Long,
    val title: String,
    val note: String,
    val dueAt: Long,
    val name: String = "",
    val number: String = "",
    val createdAt: Long,
    val done: Boolean = false,
    val doneAt: Long = 0L,
    val listId: Long = MyTaskStore.UNSORTED,
    val starred: Boolean = false,
    /** Place in the list's "My order" (smaller first). */
    val position: Long = createdAt,
    /** Extra heads-ups before the due time, in minutes (e.g. 60 and 1440 for 1 hour and 1 day before). */
    val remindBefore: List<Int> = emptyList(),
    /** How long it takes, in minutes (the Calendar books this much time from [dueAt]). */
    val durationMin: Int = MyTaskStore.DEFAULT_LENGTH,
    /** What happened to it, oldest first (kept since v0.31; older tasks start empty). */
    val events: List<TaskEvent> = emptyList(),
) {
    val hasPerson: Boolean get() = number.isNotBlank()
    val endAt: Long get() = dueAt + durationMin * 60_000L
    val timed: Boolean get() = dueAt > 0L
    val personTitle: String get() = name.ifBlank { number }
}

/** One line in a My task's History: created, time moved, done, not done. */
data class TaskEvent(val at: Long, val text: String)

/**
 * A repeat task you tick every day it comes up (My tasks › Dossier). It repeats on the chosen
 * weekdays, or once a month on [monthDay]. The time is required.
 */
data class DossierTask(
    val id: Long,
    val title: String,
    val note: String = "",
    /** Due time, as minutes after midnight. */
    val minute: Int,
    /** Weekdays it repeats on: bit 0 = Monday … bit 6 = Sunday. Not used when [monthDay] is set. */
    val days: Int = Dossier.ALL_DAYS,
    val starred: Boolean = false,
    /** 1..31 for "Monthly on the 14th" (the last day in shorter months), or 0 for weekdays. */
    val monthDay: Int = 0,
    /** Minutes before the due time for an extra heads-up notification, or 0 for none. */
    val remindBefore: Int = 0,
    /** The first day it counts. Days before it are never Missed. */
    val startDate: String,
    val createdAt: Long,
    /** When the time block ends, as minutes after midnight ([minute] is its start). */
    val endMinute: Int = minOf(minute + 30, 24 * 60),
    /** Weekday repeats: 1 = every week, 2 = every other week, and so on (counted from [startDate]). */
    val everyWeeks: Int = 1,
    /** Also comes up on the weekly off day (when that day is one of its repeat days). */
    val onOffDay: Boolean = false,
) {
    val start: LocalDate get() = runCatching { LocalDate.parse(startDate) }.getOrElse { LocalDate.now() }

    /** Length of the time block in minutes (at least 5; an end before the start runs to midnight). */
    val lengthMin: Int get() = (if (endMinute > minute) endMinute - minute else 24 * 60 - minute).coerceAtLeast(5)

    fun isOn(date: LocalDate): Boolean {
        if (date.isBefore(start)) return false
        // Weekly off day: the task doesn't come up, so it is never Missed (unless it's set to).
        if (!onOffDay && OffDays.isOff(date)) return false
        return if (monthDay > 0) {
            date.dayOfMonth == minOf(monthDay, YearMonth.from(date).lengthOfMonth())
        } else {
            if (days and (1 shl (date.dayOfWeek.value - 1)) == 0) return false
            if (everyWeeks <= 1) return true
            val weekOf = { d: LocalDate -> d.minusDays((d.dayOfWeek.value - 1).toLong()) }
            val weeks = java.time.temporal.ChronoUnit.WEEKS.between(weekOf(start), weekOf(date))
            weeks % everyWeeks == 0L
        }
    }

    /** Like "9:00 AM - 10:30 AM". */
    val rangeLabel: String get() = Dossier.timeLabel(minute) + " - " + Dossier.timeLabel(endMinute % (24 * 60))

    fun dueAt(date: LocalDate): Long =
        date.atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val timeLabel: String get() = Dossier.timeLabel(minute)

    /** Like "Daily, Mon-Sat", "Weekly, every Tuesday" or "Monthly on the 14th". */
    val frequency: String get() = Dossier.frequency(days, monthDay).let {
        if (monthDay == 0 && everyWeeks > 1) "$it, every $everyWeeks weeks" else it
    }
}

/** One task on one day: ticked (with the time), or a reason why not. */
data class DossierRecord(
    val taskId: Long,
    val date: String,
    val doneAt: Long = 0L,
    val reason: String = "",
) {
    val done: Boolean get() = doneAt > 0L
}

/** One day in a dossier task's History. */
data class DossierDay(val date: LocalDate, val status: Status, val doneAt: Long, val reason: String) {
    enum class Status { DONE, MISSED, OPEN }
}

/** The dossier's day rules: a day can be ticked until 3:00 AM the next morning, then it locks. */
object Dossier {
    const val ALL_DAYS = 0b1111111
    const val LOCK_HOUR = 3
    val REMIND_CHOICES = listOf(5, 15, 30, 60)

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val shortNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun timeLabel(minute: Int): String = LocalTime.of(minute / 60, minute % 60).format(timeFmt)

    /** The day you are ticking now. Between midnight and 3:00 AM that is still yesterday. */
    fun openDay(now: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(now - LOCK_HOUR * 3_600_000L).atZone(ZoneId.systemDefault()).toLocalDate()

    /** When [date] locks into History: 3:00 AM the next morning. */
    fun lockAt(date: LocalDate): Long =
        date.plusDays(1).atTime(LOCK_HOUR, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun isLocked(date: LocalDate, now: Long = System.currentTimeMillis()) = now >= lockAt(date)

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    fun frequency(days: Int, monthDay: Int): String {
        if (monthDay > 0) return "Monthly on the ${ordinal(monthDay)}"
        val on = (0..6).filter { days and (1 shl it) != 0 }
        return when {
            on.size == 7 -> "Daily"
            on == (0..5).toList() -> "Daily, Mon-Sat"
            on == (0..4).toList() -> "Daily, Mon-Fri"
            on.size == 1 -> "Weekly, every ${dayNames[on[0]]}"
            on.isEmpty() -> "No days picked"
            else -> on.joinToString(", ") { shortNames[it] }
        }
    }

    fun dayOfWeekBit(d: DayOfWeek) = 1 shl (d.value - 1)
}

/** One-time My tasks, kept on the phone like the other lists. */
object MyTaskStore {
    private lateinit var appCtx: Context
    private val _items = MutableStateFlow<List<MyTask>>(emptyList())
    val items: StateFlow<List<MyTask>> = _items.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        _items.value = load()
        _lists.value = loadLists()
    }

    private fun prefs() = appCtx.getSharedPreferences("my_tasks", Context.MODE_PRIVATE)

    private val rows = Rows<MyTask>("my_tasks", { it.id }, ::toJson, ::fromJson, archivable = { t -> t.doneAt.takeIf { t.done } })

    private fun load(): List<MyTask> = rows.load(appCtx, prefs())

    /** Finished items older than [Db.ARCHIVE_DAYS] days, kept for search. */
    fun archived(): List<MyTask> = rows.archived(appCtx)

    private fun fromJson(o: JSONObject): MyTask = run {
        MyTask(
            id = o.getLong("id"),
            title = o.optString("title"),
            note = o.optString("note"),
            dueAt = o.optLong("dueAt"),
            name = o.optString("name"),
            number = o.optString("number"),
            createdAt = o.optLong("createdAt"),
            done = o.optBoolean("done"),
            doneAt = o.optLong("doneAt"),
            listId = o.optLong("listId", UNSORTED),
            starred = o.optBoolean("starred"),
            position = o.optLong("position", o.optLong("createdAt")),
            remindBefore = o.optJSONArray("remindBefore")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
            durationMin = o.optInt("durationMin", DEFAULT_LENGTH),
            events = o.optJSONArray("events")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { e -> TaskEvent(e.optLong("a"), e.optString("t")) } }
            } ?: emptyList(),
        )
    }

    private fun save(list: List<MyTask>) {
        rows.save(appCtx, list)
        _items.value = list
    }

    private fun toJson(t: MyTask): JSONObject {
        return JSONObject()
            .put("id", t.id)
            .put("title", t.title)
            .put("note", t.note)
            .put("dueAt", t.dueAt)
            .put("name", t.name)
            .put("number", t.number)
            .put("createdAt", t.createdAt)
            .put("done", t.done)
            .put("doneAt", t.doneAt)
            .put("listId", t.listId)
            .put("starred", t.starred)
            .put("position", t.position)
            .put("remindBefore", JSONArray().apply { t.remindBefore.forEach { put(it) } })
            .put("durationMin", t.durationMin)
            .put("events", JSONArray().apply { t.events.forEach { put(JSONObject().put("a", it.at).put("t", it.text)) } })
    }

    // ---- Lists ----

    const val UNSORTED = 0L

    /** A new task's length when you don't pick one (the Calendar's "How long?"). */
    const val DEFAULT_LENGTH = 30
    val LENGTH_CHOICES = listOf(15, 30, 60, 90, 120, 180)

    private val _lists = MutableStateFlow(listOf(TaskList(UNSORTED, "Unsorted")))
    /** Unsorted first, then your lists in your order. */
    val lists: StateFlow<List<TaskList>> = _lists.asStateFlow()

    private fun loadLists(): List<TaskList> {
        val raw = prefs().getString("lists", null)
        val saved = try {
            if (raw == null) emptyList() else JSONArray(raw).let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    TaskList(o.getLong("id"), o.optString("name"), o.optBoolean("byDate"))
                }
            }
        } catch (e: Exception) {
            // Unreadable: never saved over (see saveLists).
            listsBroken = true
            emptyList()
        }
        val unsorted = saved.firstOrNull { it.id == UNSORTED } ?: TaskList(UNSORTED, "Unsorted")
        return listOf(unsorted.copy(name = "Unsorted")) + saved.filter { it.id != UNSORTED }
    }

    private var listsBroken = false

    private fun saveLists(list: List<TaskList>) {
        if (listsBroken) return
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("byDate", it.byDate)) }
        prefs().edit().putString("lists", arr.toString()).apply()
        _lists.value = list
    }

    fun list(id: Long): TaskList? = _lists.value.firstOrNull { it.id == id }
    fun listName(id: Long): String = list(id)?.name ?: "Unsorted"

    @Synchronized
    fun addList(name: String): TaskList {
        val id = prefs().getLong("nextListId", 1L)
        prefs().edit().putLong("nextListId", id + 1).apply()
        val l = TaskList(id, name.trim())
        saveLists(_lists.value + l)
        return l
    }

    @Synchronized
    fun renameList(id: Long, name: String) {
        if (id == UNSORTED) return
        saveLists(_lists.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    @Synchronized
    fun setListByDate(id: Long, byDate: Boolean) {
        saveLists(_lists.value.map { if (it.id == id) it.copy(byDate = byDate) else it })
    }

    /** Your lists in a new order (Unsorted always stays first). */
    @Synchronized
    fun reorderLists(ids: List<Long>) {
        val byId = _lists.value.associateBy { it.id }
        val mine = ids.filter { it != UNSORTED }.mapNotNull { byId[it] }
        val rest = _lists.value.filter { it.id != UNSORTED && it.id !in ids }
        saveLists(listOf(byId.getValue(UNSORTED)) + mine + rest)
    }

    /**
     * Deletes a list: its tasks move to Unsorted, or are deleted with it. Unsorted can't be deleted.
     * Returns what was there, for Undo.
     */
    @Synchronized
    fun deleteList(id: Long, deleteTasks: Boolean): Pair<List<TaskList>, List<MyTask>>? {
        if (id == UNSORTED) return null
        val beforeLists = _lists.value
        val beforeTasks = _items.value
        saveLists(beforeLists.filter { it.id != id })
        if (deleteTasks) {
            beforeTasks.filter { it.listId == id }.forEach {
                TaskAlarms.cancelTask(appCtx, it.id)
                TaskAlarms.cancelTaskNotification(appCtx, it.id)
            }
            save(beforeTasks.filter { it.listId != id })
        } else {
            save(beforeTasks.map { if (it.listId == id) it.copy(listId = UNSORTED) else it })
        }
        return beforeLists to beforeTasks
    }

    /** Undo for [deleteList]. */
    @Synchronized
    fun restoreLists(lists: List<TaskList>, tasks: List<MyTask>) {
        saveLists(lists)
        save(tasks)
        tasks.filter { !it.done }.forEach { TaskAlarms.scheduleTask(appCtx, it) }
    }

    fun get(id: Long): MyTask? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(
        title: String, note: String, dueAt: Long, name: String, number: String,
        listId: Long = UNSORTED, starred: Boolean = false, remindBefore: List<Int> = emptyList(),
        durationMin: Int = DEFAULT_LENGTH,
    ): MyTask {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        val now = System.currentTimeMillis()
        // New tasks go on top of "My order", like Google Tasks.
        val top = (_items.value.filter { it.listId == listId }.minOfOrNull { it.position } ?: now) - 1
        val t = MyTask(
            id = id,
            title = title.trim(),
            note = note.trim(),
            dueAt = dueAt,
            name = name.trim(),
            number = number.trim(),
            createdAt = now,
            listId = if (list(listId) != null) listId else UNSORTED,
            starred = starred,
            position = minOf(top, now),
            remindBefore = remindBefore.distinct().sorted(),
            durationMin = durationMin.coerceIn(5, 24 * 60),
            events = listOf(TaskEvent(now, if (dueAt > 0L) "Created · due ${Fmt.dueLabel(dueAt)}" else "Created · no time")),
        )
        save(_items.value + t)
        if (t.hasPerson) RecentPeople.record(appCtx, t.name, t.number)
        TaskAlarms.scheduleTask(appCtx, t)
        return t
    }

    @Synchronized
    fun edit(
        id: Long, title: String, note: String, dueAt: Long, name: String, number: String,
        listId: Long? = null, starred: Boolean? = null, remindBefore: List<Int>? = null,
        durationMin: Int? = null,
    ) {
        val old = get(id) ?: return
        val t = old.copy(
            title = title.trim(), note = note.trim(), dueAt = dueAt, name = name.trim(), number = number.trim(),
            listId = listId ?: old.listId, starred = starred ?: old.starred,
            remindBefore = remindBefore?.distinct()?.sorted() ?: old.remindBefore,
            durationMin = durationMin?.coerceIn(5, 24 * 60) ?: old.durationMin,
            events = old.events + listOfNotNull(timeEvent(old.dueAt, dueAt)),
        )
        save(_items.value.map { if (it.id == id) t else it })
        if (t.hasPerson) RecentPeople.record(appCtx, t.name, t.number)
        TaskAlarms.cancelTaskNotification(appCtx, id)
        if (!t.done) TaskAlarms.scheduleTask(appCtx, t)
    }

    @Synchronized
    fun setDone(id: Long, done: Boolean) {
        val old = get(id) ?: return
        if (old.done == done) return
        val now = System.currentTimeMillis()
        val t = old.copy(done = done, doneAt = if (done) now else 0L, events = old.events + TaskEvent(now, if (done) "Done" else "Not done, opened again"))
        save(_items.value.map { if (it.id == id) t else it })
        TaskAlarms.cancelTaskNotification(appCtx, id)
        if (done) TaskAlarms.cancelTask(appCtx, id) else TaskAlarms.scheduleTask(appCtx, t)
    }

    @Synchronized
    fun delete(id: Long) {
        save(_items.value.filter { it.id != id })
        TaskAlarms.cancelTask(appCtx, id)
        TaskAlarms.cancelTaskNotification(appCtx, id)
    }

    @Synchronized
    fun setStar(id: Long, starred: Boolean) {
        save(_items.value.map { if (it.id == id) it.copy(starred = starred) else it })
    }

    @Synchronized
    fun moveToList(id: Long, listId: Long) {
        save(_items.value.map { if (it.id == id) it.copy(listId = listId) else it })
    }

    /** Sets (or clears, with 0) the time, e.g. "Set time" in To review. */
    @Synchronized
    fun setDue(id: Long, dueAt: Long, reason: String = "") {
        val old = get(id) ?: return
        val why = reason.trim().takeIf { it.isNotEmpty() }?.let { TaskEvent(System.currentTimeMillis(), "Why not done: $it") }
        val t = old.copy(dueAt = dueAt, events = old.events + listOfNotNull(timeEvent(old.dueAt, dueAt), why))
        save(_items.value.map { if (it.id == id) t else it })
        TaskAlarms.cancelTaskNotification(appCtx, id)
        if (!t.done) TaskAlarms.scheduleTask(appCtx, t)
    }

    /** A History line for a time change (null when the time stayed the same). */
    private fun timeEvent(old: Long, new: Long): TaskEvent? {
        if (old == new) return null
        val now = System.currentTimeMillis()
        return when {
            new <= 0L -> TaskEvent(now, "Time removed (To schedule)")
            old <= 0L -> TaskEvent(now, "Time set · ${Fmt.dueLabel(new)}")
            else -> TaskEvent(now, "Time moved · ${Fmt.dueLabel(old)} → ${Fmt.dueLabel(new)}")
        }
    }

    /** A new length (the Calendar's resize handle). */
    @Synchronized
    fun setLength(id: Long, minutes: Int) {
        save(_items.value.map { if (it.id == id) it.copy(durationMin = minutes.coerceIn(5, 24 * 60)) else it })
    }

    /** "My order" after a drag: [ids] top to bottom. */
    @Synchronized
    fun reorder(ids: List<Long>) {
        val pos = ids.withIndex().associate { (i, id) -> id to i.toLong() }
        save(_items.value.map { t -> pos[t.id]?.let { t.copy(position = it) } ?: t })
    }

    /** Undo: puts the task back exactly as it was. */
    @Synchronized
    fun restore(t: MyTask) {
        val list = _items.value
        save(if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t)
        TaskAlarms.cancelTaskNotification(appCtx, t.id)
        if (t.done) TaskAlarms.cancelTask(appCtx, t.id) else TaskAlarms.scheduleTask(appCtx, t)
    }
}

/** A My tasks list, like Google Tasks' lists. [byDate] off sorts by your own order. */
data class TaskList(val id: Long, val name: String, val byDate: Boolean = false)

/** Dossier tasks and each day's ticks and reasons. */
object DossierStore {
    private lateinit var appCtx: Context
    private val _tasks = MutableStateFlow<List<DossierTask>>(emptyList())
    val tasks: StateFlow<List<DossierTask>> = _tasks.asStateFlow()

    /** Keyed by "taskId|yyyy-MM-dd". */
    private val _records = MutableStateFlow<Map<String, DossierRecord>>(emptyMap())
    val records: StateFlow<Map<String, DossierRecord>> = _records.asStateFlow()

    /** How long ticks and reasons are kept (for History and the monthly score). */
    private const val KEEP_DAYS = 400L

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        _tasks.value = loadTasks()
        _records.value = loadRecords()
    }

    private fun prefs() = appCtx.getSharedPreferences("dossier", Context.MODE_PRIVATE)

    fun key(taskId: Long, date: LocalDate) = "$taskId|$date"

    private fun loadTasks(): List<DossierTask> {
        val raw = prefs().getString("tasks", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DossierTask(
                    id = o.getLong("id"),
                    title = o.optString("title"),
                    note = o.optString("note"),
                    minute = o.optInt("minute", 9 * 60),
                    days = o.optInt("days", Dossier.ALL_DAYS),
                    starred = o.optBoolean("starred"),
                    monthDay = o.optInt("monthDay", 0),
                    remindBefore = o.optInt("remindBefore", 0),
                    startDate = o.optString("startDate").ifBlank { LocalDate.now().toString() },
                    createdAt = o.optLong("createdAt"),
                    endMinute = o.optInt("endMinute", -1).takeIf { it >= 0 }
                        ?: minOf(o.optInt("minute", 9 * 60) + 30, 24 * 60),
                    everyWeeks = o.optInt("everyWeeks", 1).coerceAtLeast(1),
                    onOffDay = o.optBoolean("onOffDay"),
                )
            }
        } catch (e: Exception) {
            // Unreadable: never saved over (see saveTasks).
            tasksBroken = true
            Db.markFailed("dossier")
            emptyList()
        }
    }

    private var tasksBroken = false
    private var recordsBroken = false

    private fun saveTasks(list: List<DossierTask>) {
        if (tasksBroken) return
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("note", t.note)
                    .put("minute", t.minute)
                    .put("days", t.days)
                    .put("starred", t.starred)
                    .put("monthDay", t.monthDay)
                    .put("remindBefore", t.remindBefore)
                    .put("startDate", t.startDate)
                    .put("createdAt", t.createdAt)
                    .put("endMinute", t.endMinute)
                    .put("everyWeeks", t.everyWeeks)
                    .put("onOffDay", t.onOffDay)
            )
        }
        prefs().edit().putString("tasks", arr.toString()).apply()
        _tasks.value = list
    }

    private fun loadRecords(): Map<String, DossierRecord> {
        val raw = prefs().getString("records", null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DossierRecord(
                    taskId = o.getLong("t"),
                    date = o.getString("d"),
                    doneAt = o.optLong("done"),
                    reason = o.optString("why"),
                )
            }.associateBy { "${it.taskId}|${it.date}" }
        } catch (e: Exception) {
            recordsBroken = true
            Db.markFailed("dossier_records")
            emptyMap()
        }
    }

    private fun saveRecords(map: Map<String, DossierRecord>) {
        if (recordsBroken) return
        val oldest = LocalDate.now().minusDays(KEEP_DAYS).toString()
        val kept = map.filterValues { (it.done || it.reason.isNotBlank()) && it.date >= oldest }
        val arr = JSONArray()
        kept.values.forEach { r ->
            arr.put(JSONObject().put("t", r.taskId).put("d", r.date).put("done", r.doneAt).put("why", r.reason))
        }
        prefs().edit().putString("records", arr.toString()).apply()
        _records.value = kept
    }

    fun get(id: Long): DossierTask? = _tasks.value.firstOrNull { it.id == id }

    fun record(taskId: Long, date: LocalDate): DossierRecord? = _records.value[key(taskId, date)]

    /**
     * The first day a new task counts: today if its time has not passed yet, otherwise tomorrow,
     * so a task added in the evening is not Missed on its first day.
     */
    private fun firstDay(minute: Int, now: Long): LocalDate {
        val today = Dossier.openDay(now)
        val dueToday = today.atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return if (dueToday > now) today else today.plusDays(1)
    }

    @Synchronized
    fun add(
        title: String, note: String, minute: Int, days: Int, monthDay: Int, remindBefore: Int, starred: Boolean = false,
        endMinute: Int = minOf(minute + 30, 24 * 60), everyWeeks: Int = 1, onOffDay: Boolean = false,
    ): DossierTask {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        val now = System.currentTimeMillis()
        val t = DossierTask(
            id = id,
            title = title.trim(),
            note = note.trim(),
            minute = minute,
            days = days,
            monthDay = monthDay,
            remindBefore = remindBefore,
            starred = starred,
            startDate = firstDay(minute, now).toString(),
            createdAt = now,
            endMinute = endMinute,
            everyWeeks = everyWeeks.coerceAtLeast(1),
            onOffDay = onOffDay,
        )
        saveTasks(_tasks.value + t)
        TaskAlarms.scheduleDossier(appCtx, t)
        return t
    }

    @Synchronized
    fun edit(
        id: Long, title: String, note: String, minute: Int, days: Int, monthDay: Int, remindBefore: Int,
        starred: Boolean? = null, endMinute: Int? = null, everyWeeks: Int? = null, onOffDay: Boolean? = null,
    ) {
        val old = get(id) ?: return
        val t = old.copy(
            starred = starred ?: old.starred,
            title = title.trim(),
            note = note.trim(),
            minute = minute,
            days = days,
            monthDay = monthDay,
            remindBefore = remindBefore,
            endMinute = endMinute ?: old.endMinute,
            everyWeeks = (everyWeeks ?: old.everyWeeks).coerceAtLeast(1),
            onOffDay = onOffDay ?: old.onOffDay,
        )
        saveTasks(_tasks.value.map { if (it.id == id) t else it })
        TaskAlarms.scheduleDossier(appCtx, t)
    }

    @Synchronized
    fun delete(id: Long) {
        saveTasks(_tasks.value.filter { it.id != id })
        saveRecords(_records.value.filterValues { it.taskId != id })
        TaskAlarms.cancelDossier(appCtx, id)
        TaskAlarms.cancelDossierNotification(appCtx, id)
    }

    /** Ticks [taskId] for [date]. Ticking removes a "why not" reason. False once the day has locked. */
    @Synchronized
    fun tick(taskId: Long, date: LocalDate, now: Long = System.currentTimeMillis()): Boolean {
        if (Dossier.isLocked(date, now)) return false
        val r = DossierRecord(taskId, date.toString(), doneAt = now, reason = "")
        saveRecords(_records.value + (key(taskId, date) to r))
        TaskAlarms.cancelDossierNotification(appCtx, taskId)
        return true
    }

    @Synchronized
    fun setStar(id: Long, starred: Boolean) {
        saveTasks(_tasks.value.map { if (it.id == id) it.copy(starred = starred) else it })
    }

    /** Undo for a delete: the task and its History come back. */
    @Synchronized
    fun restore(t: DossierTask, recs: Map<String, DossierRecord>) {
        if (_tasks.value.none { it.id == t.id }) saveTasks(_tasks.value + t)
        saveRecords(_records.value + recs.filterValues { it.taskId == t.id })
        TaskAlarms.scheduleDossier(appCtx, t)
    }

    /** Takes a tick back (tapped by mistake). Only before the day locks. */
    @Synchronized
    fun untick(taskId: Long, date: LocalDate): Boolean {
        if (Dossier.isLocked(date)) return false
        saveRecords(_records.value - key(taskId, date))
        return true
    }

    /** Saves (or clears, when blank) why the task was not done that day. Only before the day locks. */
    @Synchronized
    fun setReason(taskId: Long, date: LocalDate, reason: String): Boolean {
        if (Dossier.isLocked(date)) return false
        val old = record(taskId, date)
        val r = DossierRecord(taskId, date.toString(), doneAt = 0L, reason = reason.trim())
        if (old?.done == true) return false
        saveRecords(_records.value + (key(taskId, date) to r))
        return true
    }

    /** One-time move when the off day became Friday: "Mon-Sat" tasks run every day, Friday pauses them. */
    @Synchronized
    fun sundayToEveryDay() {
        val list = _tasks.value
        if (list.none { it.monthDay == 0 && it.days == 0b0111111 }) return
        saveTasks(list.map { if (it.monthDay == 0 && it.days == 0b0111111) it.copy(days = Dossier.ALL_DAYS) else it })
    }

    /** Today's dossier (the day being ticked), in time order. */
    fun todays(now: Long = System.currentTimeMillis()): List<DossierTask> {
        val day = Dossier.openDay(now)
        return _tasks.value.filter { it.isOn(day) }.sortedBy { it.minute }
    }

    /** Unticked tasks of the open day whose time has passed: what the review reminder counts. */
    fun leftNow(now: Long = System.currentTimeMillis()): Int {
        val day = Dossier.openDay(now)
        return todays(now).count { record(it.id, day)?.done != true && it.dueAt(day) <= now }
    }

    /** The task's days from the newest, back to [limit] days or its first day. */
    fun history(t: DossierTask, now: Long = System.currentTimeMillis(), limit: Int = 120): List<DossierDay> {
        val open = Dossier.openDay(now)
        val out = mutableListOf<DossierDay>()
        var d = open
        var n = 0
        while (!d.isBefore(t.start) && n < limit) {
            if (t.isOn(d)) {
                val r = record(t.id, d)
                val status = when {
                    r?.done == true -> DossierDay.Status.DONE
                    Dossier.isLocked(d, now) -> DossierDay.Status.MISSED
                    else -> DossierDay.Status.OPEN
                }
                out += DossierDay(d, status, r?.doneAt ?: 0L, r?.reason.orEmpty())
            }
            d = d.minusDays(1)
            n++
        }
        return out
    }

    /**
     * This month's score: (done, counted). Counted days are the locked ones plus today when it is
     * already ticked, so an open day never pulls the score down.
     */
    fun monthScore(t: DossierTask, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val open = Dossier.openDay(now)
        val first = open.withDayOfMonth(1)
        var done = 0
        var total = 0
        var d = maxOf(first, t.start)
        while (!d.isAfter(open)) {
            if (t.isOn(d)) {
                val ticked = record(t.id, d)?.done == true
                if (ticked || Dossier.isLocked(d, now)) {
                    total++
                    if (ticked) done++
                }
            }
            d = d.plusDays(1)
        }
        return done to total
    }
}

/** Settings › Dossier. */
object DossierSettings {
    private fun p(c: Context) = c.getSharedPreferences("dossier_settings", Context.MODE_PRIVATE)

    /** Off: dossier is part of the To review reminder. On: its own reminder at [times]. */
    fun separate(c: Context) = p(c).getBoolean("separate", false)
    fun setSeparate(c: Context, on: Boolean) {
        p(c).edit().putBoolean("separate", on).apply()
        TaskAlarms.scheduleNudge(c)
    }

    /** The separate reminder's times, minutes after midnight (9:00 PM to start with). */
    fun times(c: Context): List<Int> =
        p(c).getString("times", null)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.sorted()
            ?: listOf(21 * 60)

    fun setTimes(c: Context, list: List<Int>) {
        p(c).edit().putString("times", list.distinct().sorted().joinToString(",")).apply()
        TaskAlarms.scheduleNudge(c)
    }

    fun skipWhenAllDone(c: Context) = p(c).getBoolean("skip_all_done", true)
    fun setSkipWhenAllDone(c: Context, on: Boolean) = p(c).edit().putBoolean("skip_all_done", on).apply()

    fun summary(c: Context): String =
        if (separate(c)) "Own reminder at " + times(c).joinToString(", ") { Dossier.timeLabel(it) }
        else "Reminded at the To review times"
}
