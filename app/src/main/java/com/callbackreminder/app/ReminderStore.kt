package com.callbackreminder.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps all reminders on the phone (no server, no account).
 * Everything the UI shows comes from [items]; every change is saved straight away
 * and the matching alarm is created or cancelled.
 */
object ReminderStore {
    private lateinit var appCtx: Context
    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        _items.value = load()
        PersonNames.refresh()
    }

    private fun prefs() = appCtx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    private val rows = Rows<Reminder>("reminders", { it.id }, ::toJson, ::fromJson, archivable = { r -> r.doneAt.takeIf { r.done && !r.review } })

    private fun load(): List<Reminder> = rows.load(appCtx, prefs())

    /** Finished items older than [Db.ARCHIVE_DAYS] days, kept for search. */
    fun archived(): List<Reminder> = rows.archived(appCtx)

    private fun fromJson(o: JSONObject): Reminder = run {
        Reminder(
            id = o.getLong("id"),
            name = o.optString("name"),
            number = o.optString("number"),
            note = o.optString("note"),
            dueAt = o.getLong("dueAt"),
            createdAt = o.optLong("createdAt"),
            done = o.optBoolean("done"),
            doneAt = o.optLong("doneAt"),
            repeat = Repeat.fromJson(o.optJSONObject("repeat")),
            seriesStart = o.optLong("seriesStart", o.getLong("dueAt")),
            occurrenceAt = o.optLong("occurrenceAt", o.getLong("dueAt")),
            occurrenceCount = o.optInt("occurrenceCount", 1),
            review = o.optBoolean("review"),
            delays = o.optJSONArray("delays")?.let { d ->
                (0 until d.length()).map { j ->
                    val e = d.getJSONObject(j)
                    Delay(e.optLong("at"), e.optLong("wasDue"), e.optLong("newDue"), e.optString("reason"))
                }
            } ?: emptyList(),
            events = o.optJSONArray("events")?.let { d ->
                (0 until d.length()).map { j ->
                    val e = d.getJSONObject(j)
                    CallEvent(e.optLong("at"), e.optString("what"), e.optLong("due"), e.optString("note"))
                }
            } ?: emptyList(),
            starred = o.optBoolean("starred"),
            linkId = o.optLong("linkId"),
            autopilot = o.optBoolean("autopilot"),
            reviewLaterUntil = o.optLong("reviewLater"),
            code = o.optString("code").replace("-", ""),
        )
    }

    private fun save(list: List<Reminder>) {
        rows.save(appCtx, list)
        _items.value = list
        PersonNames.refresh()
    }

    private fun toJson(r: Reminder): JSONObject {
        return JSONObject()
            .put("id", r.id)
            .put("name", r.name)
            .put("number", r.number)
            .put("note", r.note)
            .put("dueAt", r.dueAt)
            .put("createdAt", r.createdAt)
            .put("done", r.done)
            .put("doneAt", r.doneAt)
            .put("repeat", r.repeat?.toJson())
            .put("seriesStart", r.seriesStart)
            .put("occurrenceAt", r.occurrenceAt)
            .put("occurrenceCount", r.occurrenceCount)
            .put("review", r.review)
            .put("starred", r.starred)
            .put("linkId", r.linkId)
            .put("autopilot", r.autopilot)
            .put("reviewLater", r.reviewLaterUntil)
            .put("code", r.code)
            .put("delays", JSONArray().apply {
                r.delays.forEach { d ->
                    put(
                        JSONObject()
                            .put("at", d.at)
                            .put("wasDue", d.wasDue)
                            .put("newDue", d.newDue)
                            .put("reason", d.reason)
                    )
                }
            })
            .put("events", JSONArray().apply {
                r.events.forEach { e ->
                    put(
                        JSONObject()
                            .put("at", e.at)
                            .put("what", e.what)
                            .put("due", e.due)
                            .put("note", e.note)
                    )
                }
            })
    }

    fun get(id: Long): Reminder? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(
        name: String,
        number: String,
        note: String,
        dueAt: Long,
        repeat: Repeat? = null,
        createdAs: String = "Created",
        earlier: List<CallEvent> = emptyList(),
        starred: Boolean = false,
        code: String = "",
    ): Reminder {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        // A weekly repeat on chosen days starts on the first of those days.
        val first = repeat?.first(Repeat.at(dueAt))?.toInstant()?.toEpochMilli() ?: dueAt
        val r = Reminder(
            id = id,
            name = name.trim(),
            number = number.trim(),
            note = note.trim(),
            dueAt = first,
            createdAt = System.currentTimeMillis(),
            repeat = repeat,
            seriesStart = dueAt,
            occurrenceAt = first,
            events = earlier + CallEvent(System.currentTimeMillis(), "$createdAs, due", first),
            starred = starred,
            // No number for a plain call reminder: one is given only when a tracked text (or a daily list) sends it.
            code = code,
        )
        save(_items.value + r)
        Scheduler.schedule(appCtx, r)
        RecentPeople.record(appCtx, r.name, r.number)
        return r
    }

    /**
     * Mark a task done. With [review], it waits on the To review chip until you check it.
     * A repeating task leaves a finished copy there and carries on with its next time.
     */
    @Synchronized
    fun markDone(id: Long, review: Boolean = false, linked: Boolean = true) {
        if (get(id)?.done == false) logEvent(id, if (review) "Done, sent to To review" else "Marked done")
        val cur = get(id) ?: return
        // The task is done, so its follow-up's texts stop too (not for a repeat, which carries on).
        if (linked && cur.linkId != 0L && cur.repeat == null && !cur.done) FollowUps.stopTexts(cur.linkId)
        if (review && cur.repeat != null && !cur.done) {
            val copyId = prefs().getLong("nextId", 1L)
            prefs().edit().putLong("nextId", copyId + 1).apply()
            val now = System.currentTimeMillis()
            save(_items.value + cur.copy(id = copyId, repeat = null, done = true, doneAt = now, review = true))
        } else if (review) {
            Scheduler.cancel(appCtx, id)
            Notifier.cancel(appCtx, id)
            RingController.dismiss(id)
            save(_items.value.map {
                if (it.id == id) it.copy(done = true, doneAt = System.currentTimeMillis(), review = true) else it
            })
            return
        }
        Scheduler.cancel(appCtx, id)
        Notifier.cancel(appCtx, id)
        RingController.dismiss(id)
        val now = System.currentTimeMillis()

        // A repeating reminder moves on to its next time that is still ahead (missed ones count too).
        if (cur.repeat != null && !cur.done) {
            var next = cur
            while (true) {
                val at = next.nextOccurrence() ?: break
                next = next.copy(dueAt = at, occurrenceAt = at, occurrenceCount = next.occurrenceCount + 1)
                if (at > now) {
                    save(_items.value.map { if (it.id == id) next else it })
                    Scheduler.schedule(appCtx, next)
                    return
                }
            }
        }
        save(_items.value.map { if (it.id == id) it.copy(done = true, doneAt = now, review = false) else it })
    }

    /**
     * Edit from the card's ⋮ menu: person, note, time and repeat. History gets an "Edited" line
     * naming what changed. A finished task stays finished; a waiting one gets its alarm moved.
     */
    @Synchronized
    fun edit(id: Long, name: String, number: String, note: String, dueAt: Long, repeat: Repeat?, starred: Boolean? = null) {
        val cur0 = get(id) ?: return
        // The star is not "an edit" worth a History line; it is just saved.
        if (starred != null && starred != cur0.starred) setStar(id, starred)
        val cur = get(id) ?: return
        val changes = mutableListOf<String>()
        if (name.trim() != cur.name || number.trim() != cur.number) changes += "person"
        if (note.trim() != cur.note) changes += "note"
        if (dueAt != cur.dueAt) changes += "time"
        if (repeat != cur.repeat) changes += "repeat"
        if (changes.isEmpty()) return
        val timeChanged = dueAt != cur.dueAt || repeat != cur.repeat
        var updated = cur.copy(name = name.trim(), number = number.trim(), note = note.trim(), repeat = repeat)
        if (timeChanged) {
            val first = repeat?.first(Repeat.at(dueAt))?.toInstant()?.toEpochMilli() ?: dueAt
            updated = updated.copy(
                dueAt = first,
                seriesStart = dueAt,
                occurrenceAt = first,
                occurrenceCount = if (repeat != cur.repeat) 0 else cur.occurrenceCount,
            )
        }
        val what = "Edited (" + changes.joinToString(", ") + ")" + if (timeChanged) ", due" else ""
        val e = CallEvent(System.currentTimeMillis(), what, if (timeChanged) updated.dueAt else 0L,
            if ("note" in changes) updated.note else "")
        updated = updated.copy(events = (updated.events + e).takeLast(EVENT_LIMIT))
        save(_items.value.map { if (it.id == id) updated else it })
        if (!updated.done) {
            Notifier.cancel(appCtx, id)
            RingController.dismiss(id)
            // v0.51 An overdue task stays quiet after an edit (e.g. a new person in Review): its alarm is set again
            // only for a time still ahead (or its next repeat); a time in the past would ring at once.
            if (updated.dueAt > System.currentTimeMillis() || updated.repeat != null) Scheduler.schedule(appCtx, updated)
            else Scheduler.cancel(appCtx, id)
        }
        RecentPeople.record(appCtx, updated.name, updated.number)
    }

    /** You checked a task on the To review chip: it is finished for good. */
    @Synchronized
    fun reviewed(id: Long) {
        logEvent(id, "Checked on To review")
        save(_items.value.map { if (it.id == id) it.copy(review = false) else it })
    }

    /** After a follow-up call: the task is not done yet, so keep why and move it to [newDueAt]. */
    @Synchronized
    fun postpone(id: Long, newDueAt: Long, reason: String) {
        val cur = get(id) ?: return
        // Only a move after the due time has passed counts as Delayed; moving it earlier on is just planning.
        if (cur.dueAt <= System.currentTimeMillis()) {
            val entry = Delay(System.currentTimeMillis(), cur.dueAt, newDueAt, reason.trim())
            save(_items.value.map { if (it.id == id) it.copy(delays = it.delays + entry) else it })
        }
        reschedule(id, newDueAt, logAs = if (cur.dueAt <= System.currentTimeMillis()) null else "Moved to")
    }

    /** New time from a card: moves it, and counts Delayed only when its due time had already passed. */
    @Synchronized
    fun newTime(id: Long, newDueAt: Long) {
        val cur = get(id) ?: return
        if (!cur.done && cur.dueAt <= System.currentTimeMillis()) {
            val entry = Delay(System.currentTimeMillis(), cur.dueAt, newDueAt, "")
            save(_items.value.map { if (it.id == id) it.copy(delays = it.delays + entry) else it })
        }
        reschedule(id, newDueAt, "Moved to")
    }

    /**
     * The next repeat arrived while the previous one was still waiting: move on to the latest
     * repeat that is due now, so a forgotten reminder does not stop the series.
     */
    @Synchronized
    fun rollOver(id: Long): Reminder? {
        val cur = get(id)?.takeIf { !it.done && it.repeat != null } ?: return null
        val now = System.currentTimeMillis()
        var r = cur
        while (true) {
            val at = r.nextOccurrence() ?: break
            if (at > now + 1_000L) break
            r = r.copy(dueAt = at, occurrenceAt = at, occurrenceCount = r.occurrenceCount + 1)
        }
        if (r === cur) return cur
        Notifier.cancel(appCtx, id)
        save(_items.value.map { if (it.id == id) r else it })
        return r
    }

    /** Keep the reminder but stop it repeating. */
    @Synchronized
    fun stopRepeating(id: Long) {
        val cur = get(id) ?: return
        val updated = cur.copy(repeat = null)
        save(_items.value.map { if (it.id == id) updated else it })
        Scheduler.cancel(appCtx, id)
        if (!updated.done && updated.dueAt > System.currentTimeMillis()) Scheduler.schedule(appCtx, updated)
    }

    /**
     * Move a reminder to a new time (also brings a finished one back to the to-call list). [logAs] names the
     * move in History, like "Snoozed to"; null leaves no line (the caller keeps its own record).
     */
    @Synchronized
    fun reschedule(id: Long, newDueAt: Long, logAs: String? = "Moved to") {
        val cur = get(id) ?: return
        val log = if (logAs != null) cur.events + CallEvent(System.currentTimeMillis(), logAs, newDueAt) else cur.events
        val updated = cur.copy(dueAt = newDueAt, done = false, doneAt = 0L, review = false, events = log)
        Notifier.cancel(appCtx, id)
        RingController.dismiss(id)
        save(_items.value.map { if (it.id == id) updated else it })
        Scheduler.schedule(appCtx, updated)
    }

    /**
     * Undo after a swipe: put the task back exactly as it was. Its alarm is set again only for a time
     * still ahead (or its next repeat), so undoing a Done on an overdue task does not ring at once.
     */
    @Synchronized
    fun restore(r: Reminder) {
        Scheduler.cancel(appCtx, r.id)
        Notifier.cancel(appCtx, r.id)
        RingController.dismiss(r.id)
        val list = _items.value
        save(if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else list + r)
        if (!r.done && (r.dueAt > System.currentTimeMillis() || r.repeat != null)) Scheduler.schedule(appCtx, r)
    }

    /** Joins this call to a follow-up (its texts share [linkId]). */
    @Synchronized
    fun setCode(id: Long, code: String) {
        save(_items.value.map { if (it.id == id) it.copy(code = code) else it })
    }

    @Synchronized
    fun setLink(id: Long, linkId: Long, autopilot: Boolean) {
        save(_items.value.map { if (it.id == id) it.copy(linkId = linkId, autopilot = autopilot) else it })
        if (linkId != 0L) mergeDuplicates()
    }

    /**
     * v0.51 One follow-up never has two open calls: two open ones with the same link are joined into one, at the
     * later time, with both histories. Run when a call gets its link and at every start. Returns how many went.
     */
    @Synchronized
    fun mergeDuplicates(): Int {
        val groups = _items.value.filter { !it.done && it.linkId != 0L }.groupBy { it.linkId }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return 0
        val drop = HashSet<Long>()
        val merged = HashMap<Long, Reminder>()
        groups.forEach { g ->
            val keep = g.maxWith(compareBy<Reminder>({ it.dueAt }, { it.id }))
            val others = g.filter { it.id != keep.id }
            merged[keep.id] = keep.copy(
                events = g.flatMap { it.events }.distinct().sortedBy { it.at } +
                    CallEvent(System.currentTimeMillis(), "Two open calls for this follow-up joined into one"),
                delays = g.flatMap { it.delays }.distinct().sortedBy { it.at },
                starred = g.any { it.starred },
                autopilot = g.any { it.autopilot },
                code = keep.code.ifEmpty { others.firstOrNull { it.code.isNotEmpty() }?.code.orEmpty() },
            )
            drop += others.map { it.id }
        }
        drop.forEach { id ->
            Scheduler.cancel(appCtx, id)
            Notifier.cancel(appCtx, id)
            RingController.dismiss(id)
        }
        save(_items.value.filter { it.id !in drop }.map { merged[it.id] ?: it })
        merged.values.forEach { r -> if (r.dueAt > System.currentTimeMillis() || r.repeat != null) Scheduler.schedule(appCtx, r) }
        return drop.size
    }

    /** "Review later": hidden from To review until [until]. */
    @Synchronized
    fun reviewLater(id: Long, until: Long) {
        save(_items.value.map { if (it.id == id) it.copy(reviewLaterUntil = until) else it })
        logEvent(id, "Review later,", until)
    }

    /** Star or unstar a task (important). */
    @Synchronized
    fun setStar(id: Long, starred: Boolean) {
        save(_items.value.map { if (it.id == id) it.copy(starred = starred) else it })
    }

    /**
     * "Not done, move back" on a finished task: it waits again (overdue if its time has passed) and
     * its alarm is set again only when that time is still ahead.
     */
    @Synchronized
    fun reopen(id: Long) {
        val cur = get(id) ?: return
        restore(cur.copy(done = false, doneAt = 0L, review = false))
        logEvent(id, "Moved back from Done")
    }

    /** Adds a line to the task's History. Kept to the newest [EVENT_LIMIT]. */
    @Synchronized
    fun logEvent(id: Long, what: String, due: Long = 0L, note: String = "") {
        val e = CallEvent(System.currentTimeMillis(), what, due, note)
        save(_items.value.map { if (it.id == id) it.copy(events = (it.events + e).takeLast(EVENT_LIMIT)) else it })
    }

    private const val EVENT_LIMIT = 60

    @Synchronized
    fun delete(id: Long) {
        Scheduler.cancel(appCtx, id)
        Notifier.cancel(appCtx, id)
        RingController.dismiss(id)
        save(_items.value.filter { it.id != id })
    }
}
