package com.callbackreminder.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the scheduled text messages on the phone, next to (but separate from) the call-backs.
 * Every change is saved straight away and the matching alarm is set or cancelled.
 *
 * A reminder is in one of three places: Scheduled (still sending), To review (the person replied
 * Done, or every text went out without a reply) or Done.
 */
object SmsStore {
    const val LOG_LIMIT = 60

    private lateinit var appCtx: Context
    private val _items = MutableStateFlow<List<SmsReminder>>(emptyList())
    val items: StateFlow<List<SmsReminder>> = _items.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appCtx.isInitialized) return
        appCtx = context.applicationContext
        _items.value = load()
        PersonNames.refresh()
    }

    private fun prefs() = appCtx.getSharedPreferences("sms_reminders", Context.MODE_PRIVATE)

    private val rows = Rows<SmsReminder>("sms_reminders", { it.id }, ::toJson, ::fromJson, archivable = { r -> r.doneAt.takeIf { r.done } })

    private fun load(): List<SmsReminder> = rows.load(appCtx, prefs())

    /** Finished items older than [Db.ARCHIVE_DAYS] days, kept for search. */
    fun archived(): List<SmsReminder> = rows.archived(appCtx)

    private fun fromJson(o: JSONObject): SmsReminder = run {
        val logArr = o.optJSONArray("log") ?: JSONArray()
        val log = (0 until logArr.length()).mapNotNull { j ->
            val l = logArr.getJSONObject(j)
            val status = SmsLogEntry.Status.values().firstOrNull { it.code == l.optString("s") }
                ?: return@mapNotNull null
            SmsLogEntry(l.getLong("at"), status, l.optString("d"))
        }
        SmsReminder(
            id = o.getLong("id"),
            name = o.optString("name"),
            number = o.optString("number"),
            message = o.optString("message"),
            dueAt = o.getLong("dueAt"),
            createdAt = o.optLong("createdAt"),
            done = o.optBoolean("done"),
            doneAt = o.optLong("doneAt"),
            repeat = Repeat.fromJson(o.optJSONObject("repeat")),
            seriesStart = o.optLong("seriesStart", o.getLong("dueAt")),
            occurrenceAt = o.optLong("occurrenceAt", o.getLong("dueAt")),
            occurrenceCount = o.optInt("occurrenceCount", 1),
            attempts = o.optInt("attempts"),
            retryAt = o.optLong("retryAt"),
            log = log,
            burstCount = o.optInt("burstCount", 1).coerceAtLeast(1),
            burstGapMin = o.optInt("burstGap"),
            burstIndex = o.optInt("burstIndex"),
            review = SmsReminder.Review.values().firstOrNull { it.code == o.optString("review") },
            reviewAt = o.optLong("reviewAt"),
            code = o.optString("code").replace("-", ""),
            tracked = o.optBoolean("tracked", true),
            linkId = o.optLong("linkId"),
            slots = o.optJSONArray("slots")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
            taskDue = o.optLong("taskDue"),
            reviewLaterUntil = o.optLong("reviewLater"),
            baseSlots = o.optJSONArray("baseSlots")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
            groupCount = o.optInt("groupCount", 1).coerceAtLeast(1),
            groupGapMin = o.optInt("groupGap"),
        )
    }

    private fun save(list: List<SmsReminder>) {
        rows.save(appCtx, list)
        _items.value = list
        PersonNames.refresh()
    }

    private fun toJson(r: SmsReminder): JSONObject {
        val log = JSONArray()
        r.log.forEach { l ->
            log.put(JSONObject().put("at", l.at).put("s", l.status.code).put("d", l.detail))
        }
        return JSONObject()
            .put("id", r.id)
            .put("name", r.name)
            .put("number", r.number)
            .put("message", r.message)
            .put("dueAt", r.dueAt)
            .put("createdAt", r.createdAt)
            .put("done", r.done)
            .put("doneAt", r.doneAt)
            .put("repeat", r.repeat?.toJson())
            .put("seriesStart", r.seriesStart)
            .put("occurrenceAt", r.occurrenceAt)
            .put("occurrenceCount", r.occurrenceCount)
            .put("attempts", r.attempts)
            .put("retryAt", r.retryAt)
            .put("log", log)
            .put("burstCount", r.burstCount)
            .put("burstGap", r.burstGapMin)
            .put("burstIndex", r.burstIndex)
            .put("review", r.review?.code ?: "")
            .put("reviewAt", r.reviewAt)
            .put("code", r.code)
            .put("tracked", r.tracked)
            .put("linkId", r.linkId)
            .put("slots", JSONArray().apply { r.slots.forEach { put(it) } })
            .put("taskDue", r.taskDue)
            .put("reviewLater", r.reviewLaterUntil)
            .put("baseSlots", JSONArray().apply { r.baseSlots.forEach { put(it) } })
            .put("groupCount", r.groupCount)
            .put("groupGap", r.groupGapMin)
    }

    fun get(id: Long): SmsReminder? = _items.value.firstOrNull { it.id == id }

    private fun replace(updated: SmsReminder) {
        save(_items.value.map { if (it.id == updated.id) updated else it })
    }

    private fun withLog(r: SmsReminder, entry: SmsLogEntry) = r.copy(log = (r.log + entry).takeLast(LOG_LIMIT))

    /** A fresh series starting at [dueAt] (a weekly repeat may move it to its first chosen weekday). */
    private fun series(base: SmsReminder, dueAt: Long, repeat: Repeat?): SmsReminder {
        val first = repeat?.first(Repeat.at(dueAt))?.toInstant()?.toEpochMilli() ?: dueAt
        return base.copy(
            dueAt = first,
            repeat = repeat,
            seriesStart = dueAt,
            occurrenceAt = first,
            occurrenceCount = 1,
            done = false,
            doneAt = 0L,
            attempts = 0,
            retryAt = 0L,
            burstIndex = 0,
            review = null,
            reviewAt = 0L,
        )
    }

    @Synchronized
    fun add(
        name: String,
        number: String,
        message: String,
        dueAt: Long,
        repeat: Repeat?,
        burst: BurstTemplate?,
        tracked: Boolean,
        history: List<SmsLogEntry> = emptyList(),
        slots: List<Int> = emptyList(),
        linkId: Long = 0L,
        taskDue: Long = 0L,
        baseSlots: List<Int> = emptyList(),
        group: BurstTemplate? = null,
        code: String = "",
    ): SmsReminder {
        val id = prefs().getLong("nextId", 1L)
        prefs().edit().putLong("nextId", id + 1).apply()
        val r = series(
            SmsReminder(
                id = id,
                name = name.trim(),
                number = number.trim(),
                message = message.trim(),
                dueAt = dueAt,
                createdAt = System.currentTimeMillis(),
                burstCount = if (slots.isNotEmpty()) slots.size else burst?.count ?: 1,
                burstGapMin = if (slots.isNotEmpty()) 0 else burst?.gapMin ?: 0,
                tracked = tracked,
                log = history.takeLast(LOG_LIMIT),
                slots = slots.takeIf { it.size > 1 } ?: emptyList(),
                linkId = linkId,
                taskDue = taskDue,
                baseSlots = if (group != null || baseSlots.size > 1) baseSlots else emptyList(),
                groupCount = group?.count ?: 1,
                groupGapMin = group?.gapMin ?: 0,
                code = code,
            ),
            dueAt,
            repeat
        )
        save(_items.value + r)
        SmsScheduler.schedule(appCtx, r)
        RecentPeople.record(appCtx, r.name, r.number)
        return r
    }

    /**
     * Edit who, what, when, the repeat, the burst and tracking. Starts the series again; the history and
     * the task number are kept.
     */
    @Synchronized
    fun edit(
        id: Long,
        name: String,
        number: String,
        message: String,
        dueAt: Long,
        repeat: Repeat?,
        burst: BurstTemplate?,
        tracked: Boolean,
        slots: List<Int> = emptyList(),
        taskDue: Long? = null,
        baseSlots: List<Int> = emptyList(),
        group: BurstTemplate? = null,
    ) {
        val cur = get(id) ?: return
        SmsNotifier.cancel(appCtx, id)
        val timed = slots.takeIf { it.size > 1 } ?: emptyList()
        val r = series(
            cur.copy(
                name = name.trim(),
                number = number.trim(),
                message = message.trim(),
                burstCount = if (timed.isNotEmpty()) timed.size else burst?.count ?: 1,
                burstGapMin = if (timed.isNotEmpty()) 0 else burst?.gapMin ?: 0,
                tracked = tracked,
                slots = timed,
                taskDue = taskDue ?: cur.taskDue,
                baseSlots = if (group != null || baseSlots.size > 1) baseSlots else emptyList(),
                groupCount = group?.count ?: 1,
                groupGapMin = group?.gapMin ?: 0,
            ),
            dueAt,
            repeat
        )
        replace(r)
        SmsScheduler.schedule(appCtx, r)
    }

    /** Move the current message (or burst) to a new time; also brings a finished one back to Scheduled. */
    @Synchronized
    fun reschedule(id: Long, newDueAt: Long) {
        val cur = get(id) ?: return
        val r = cur.copy(
            dueAt = newDueAt,
            done = false,
            doneAt = 0L,
            attempts = 0,
            retryAt = 0L,
            burstIndex = 0,
            review = null,
            reviewAt = 0L,
        )
        replace(r)
        SmsScheduler.schedule(appCtx, r)
    }

    @Synchronized
    fun stopRepeating(id: Long) {
        val cur = get(id) ?: return
        val r = cur.copy(repeat = null)
        replace(r)
        if (r.active) SmsScheduler.schedule(appCtx, r)
    }

    @Synchronized
    fun delete(id: Long) {
        SmsScheduler.cancel(appCtx, id)
        SmsNotifier.cancel(appCtx, id)
        save(_items.value.filter { it.id != id })
    }

    /**
     * Undo after a swipe: put the text back exactly as it was. Its alarm is set again only for a time still
     * ahead, so an undo never sends at once.
     */
    @Synchronized
    fun restore(r: SmsReminder) {
        SmsScheduler.cancel(appCtx, r.id)
        val list = _items.value
        save(if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else list + r)
        if (r.active && r.alarmAt > System.currentTimeMillis()) SmsScheduler.schedule(appCtx, r)
    }

    @Synchronized
    fun addLog(id: Long, entry: SmsLogEntry) {
        val cur = get(id) ?: return
        replace(withLog(cur, entry))
    }

    /** You confirmed the work is done (from Scheduled or To review): stop everything, move to Done. */
    @Synchronized
    fun markDone(id: Long, linked: Boolean = true) {
        val cur = get(id)?.takeIf { !it.done } ?: return
        val now = System.currentTimeMillis()
        // The task is done, so its follow-up's call reminder is done too.
        if (linked && cur.linkId != 0L) FollowUps.finishCall(cur.linkId)
        SmsScheduler.cancel(appCtx, id)
        SmsNotifier.cancel(appCtx, id)
        SmsSender.InFlight.clear(appCtx, id)
        replace(
            withLog(cur, SmsLogEntry(now, SmsLogEntry.Status.MARKED_DONE))
                .copy(done = true, doneAt = now, review = null, reviewAt = 0L, retryAt = 0L, attempts = 0)
        )
    }

    /**
     * "Not done" after the person replied Done: back to Scheduled, carrying on from the next text still
     * ahead. (After "No reply" the screen opens Edit instead, to set new days.)
     */
    @Synchronized
    fun resume(id: Long) {
        val cur = get(id)?.takeIf { it.review == SmsReminder.Review.REPLIED } ?: return
        val now = System.currentTimeMillis()
        SmsNotifier.cancel(appCtx, id)
        val r = withLog(cur, SmsLogEntry(now, SmsLogEntry.Status.MARKED_NOT_DONE))
            .copy(review = null, reviewAt = 0L)
        replace(r)
        // Texts whose time passed while it waited are skipped (see SmsSender.sendDue).
        SmsScheduler.schedule(appCtx, r)
    }

    /** "Not done, move back" on a Done text: it goes back to To review so you can decide again. */
    @Synchronized
    fun reopen(id: Long) {
        val cur = get(id)?.takeIf { it.done } ?: return
        val now = System.currentTimeMillis()
        replace(
            withLog(cur, SmsLogEntry(now, SmsLogEntry.Status.MARKED_NOT_DONE))
                .copy(done = false, doneAt = 0L, review = SmsReminder.Review.NO_REPLY, reviewAt = now)
        )
    }

    /** "Not done" after No reply: note it before Edit sets new days. */
    @Synchronized
    fun noteNotDone(id: Long) {
        addLog(id, SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.MARKED_NOT_DONE))
    }

    /**
     * The person texted back "Done <number>": only the tracked reminder with that number, sent to that
     * person, stops and waits in To review. Returns it, or null when nothing matches.
     */
    @Synchronized
    fun markReplied(fromNumber: String, code: String): SmsReminder? {
        val r = _items.value.firstOrNull {
            !it.done && it.review != SmsReminder.Review.REPLIED && it.code == code && sameNumber(it.number, fromNumber)
        } ?: return null
        val now = System.currentTimeMillis()
        SmsScheduler.cancel(appCtx, r.id)
        SmsSender.InFlight.clear(appCtx, r.id)
        val m = withLog(r, SmsLogEntry(now, SmsLogEntry.Status.REPLIED_DONE, r.doneReply))
            .copy(review = SmsReminder.Review.REPLIED, reviewAt = now, retryAt = 0L, attempts = 0)
        replace(m)
        return m
    }

    /**
     * [n] new task numbers: today's date and the next serials for that day, like "26101804". A number,
     * once given, never comes back.
     */
    fun newCodes(n: Int): List<String> = TaskCodes.next(appCtx, n)

    fun newCode(): String = newCodes(1).first()

    @Synchronized
    fun setCode(id: Long, code: String) {
        save(_items.value.map { if (it.id == id) it.copy(code = code) else it })
    }

    /**
     * Gives [id] its task number when its first text goes out (follow-ups get theirs when saved): today's
     * date ("261011") and the next serial for that day ("01", "02", …). Reminders due in the same minute that were saved earlier get
     * theirs first, so the order follows saving. A number, once given, never changes or comes back.
     */
    @Synchronized
    fun ensureCode(id: Long): SmsReminder? {
        val cur = get(id) ?: return null
        if (cur.code.isNotEmpty()) return cur
        val minute = cur.nextTextAt / 60_000L
        val first = _items.value
            .filter { it.active && it.code.isEmpty() && it.id < id && it.nextTextAt / 60_000L == minute }
            .sortedBy { it.id }
        val ids = first.map { it.id } + id
        val codes = ids.zip(newCodes(ids.size)).toMap()
        save(_items.value.map { r -> codes[r.id]?.let { r.copy(code = it) } ?: r })
        return get(id)
    }

    /** Compares the last 10 digits, so "+92 300 1234567" and "0300-1234567" are the same person. */
    fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }.takeLast(10)
        val db = b.filter { it.isDigit() }.takeLast(10)
        return da.length >= 7 && da == db
    }

    /** One text of a burst is handled (sent, failed or skipped): move on to the next, or finish the burst. */
    @Synchronized
    fun advanceBurst(id: Long, entry: SmsLogEntry?, handled: Int = 1) {
        val cur = get(id)?.takeIf { it.active } ?: return
        val r = (if (entry != null) withLog(cur, entry) else cur).copy(burstIndex = cur.burstIndex + handled)
        if (r.burstIndex >= r.burstCount) {
            replace(r)
            finishOccurrence(id, null)
        } else {
            replace(r)
            SmsScheduler.schedule(appCtx, r)
        }
    }

    /**
     * The current message or burst is finished: note it in the history ([entry], if any), then move a
     * repeat on to its next time still ahead. When nothing is left, it goes to To review for you to check
     * (single text, burst or repeat alike), marked "Not sent" when its last text failed or was skipped.
     * Only your own Mark done moves it to Done.
     */
    @Synchronized
    fun finishOccurrence(id: Long, entry: SmsLogEntry?) {
        val cur = get(id)?.takeIf { it.active } ?: return
        val now = System.currentTimeMillis()
        var r = (if (entry != null) withLog(cur, entry) else cur).copy(attempts = 0, retryAt = 0L, burstIndex = 0)
        if (r.repeat != null) {
            while (true) {
                val at = r.nextOccurrence() ?: break
                r = r.copy(dueAt = at, occurrenceAt = at, occurrenceCount = r.occurrenceCount + 1)
                if (at > now) {
                    replace(r)
                    SmsScheduler.schedule(appCtx, r)
                    return
                }
            }
        }
        SmsScheduler.cancel(appCtx, id)
        val notSent = !r.isBurst &&
            (entry?.status == SmsLogEntry.Status.FAILED || entry?.status == SmsLogEntry.Status.SKIPPED)
        val reviewed = (if (notSent) r else withLog(r, SmsLogEntry(now, SmsLogEntry.Status.NO_REPLY)))
            .copy(review = if (notSent) SmsReminder.Review.NOT_SENT else SmsReminder.Review.NO_REPLY, reviewAt = now)
        replace(reviewed)
        SmsNotifier.showReview(appCtx, reviewed)
    }

    /** A single text failed: try again at [retryAt]. */
    @Synchronized
    fun setRetry(id: Long, attempts: Int, retryAt: Long, entry: SmsLogEntry) {
        val cur = get(id)?.takeIf { it.active } ?: return
        val r = withLog(cur, entry).copy(attempts = attempts, retryAt = retryAt)
        replace(r)
        SmsScheduler.schedule(appCtx, r)
    }

    /**
     * The follow-up got a new due time: its texts start again at [dueAt], from text [fromIndex] (earlier ones
     * already passed), and the due time in the message becomes [taskDue]. [detail] goes in the history.
     */
    @Synchronized
    fun moveWithTask(id: Long, dueAt: Long, fromIndex: Int, taskDue: Long, detail: String) {
        val cur = get(id) ?: return
        SmsNotifier.cancel(appCtx, id)
        SmsSender.InFlight.clear(appCtx, id)
        val r = withLog(cur, SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.MOVED, detail)).copy(
            dueAt = dueAt,
            occurrenceAt = dueAt,
            seriesStart = if (cur.repeat == null) dueAt else cur.seriesStart,
            done = false,
            doneAt = 0L,
            attempts = 0,
            retryAt = 0L,
            burstIndex = fromIndex.coerceIn(0, cur.burstCount - 1),
            review = null,
            reviewAt = 0L,
            reviewLaterUntil = 0L,
            taskDue = taskDue,
        )
        replace(r)
        SmsScheduler.schedule(appCtx, r)
    }

    /** Only the due time written in the message changes (the texts stay where they are). */
    @Synchronized
    fun setTaskDue(id: Long, taskDue: Long, detail: String) {
        val cur = get(id) ?: return
        replace(withLog(cur, SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.MOVED, detail)).copy(taskDue = taskDue))
    }

    /** Joins this text to a follow-up (an older text gets a link when you edit it). */
    @Synchronized
    fun setLink(id: Long, linkId: Long) {
        val cur = get(id) ?: return
        replace(cur.copy(linkId = linkId))
    }

    /** "Review later": hidden from To review until [until]. */
    @Synchronized
    fun reviewLater(id: Long, until: Long) {
        val cur = get(id) ?: return
        replace(cur.copy(reviewLaterUntil = until))
    }

    /** Send now on a burst: start the whole burst of the current day right now. */
    @Synchronized
    fun restartBurstNow(id: Long): SmsReminder? {
        val cur = get(id)?.takeIf { it.active } ?: return null
        val r = cur.copy(dueAt = System.currentTimeMillis(), burstIndex = 0, retryAt = 0L, attempts = 0)
        replace(r)
        return r
    }
}

/** Screen requests that come from a notification, e.g. "Not done" opening a reminder's Edit window. */
object SmsUi {
    val editRequest = MutableStateFlow<Long?>(null)

    /** A notification was tapped: show this reminder (its tab, scrolled to and highlighted). */
    val showRequest = MutableStateFlow<Long?>(null)

    /** Home asked for a tab: 0 Scheduled, 1 To review, 2 Done. */
    val tabRequest = MutableStateFlow<Int?>(null)
}

/** Your burst templates (up to 20), kept on the phone. */
object BurstTemplates {
    private val DEFAULTS = listOf(BurstTemplate(3, 5), BurstTemplate(5, 5), BurstTemplate(10, 10))

    private fun p(c: Context) = c.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)

    fun load(c: Context): List<BurstTemplate> {
        val raw = p(c).getString("templates", null) ?: return DEFAULTS
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                BurstTemplate(o.getInt("n"), o.getInt("gap"))
            }
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun save(c: Context, list: List<BurstTemplate>) {
        val arr = JSONArray()
        list.take(BurstTemplate.MAX_TEMPLATES).forEach { arr.put(JSONObject().put("n", it.count).put("gap", it.gapMin)) }
        p(c).edit().putString("templates", arr.toString()).apply()
    }

    /** "Stop when the person replies Done". Off until you turn it on. */
    fun stopOnReply(c: Context): Boolean = p(c).getBoolean("stop_on_reply", false)

    fun setStopOnReply(c: Context, on: Boolean) = p(c).edit().putBoolean("stop_on_reply", on).apply()
}

/**
 * Task numbers for texts and calls alike: yyMMdd + the day's serial, like "26101804". Never reused. Only tracked
 * texts and daily lists take one, so a day stays far below 99; past it the serial goes on (100, 101…) and replies
 * still match.
 */
object TaskCodes {
    @Synchronized
    fun next(ctx: Context, n: Int = 1): List<String> {
        val p = ctx.applicationContext.getSharedPreferences("sms_reminders", Context.MODE_PRIVATE)
        val day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
        val key = "serial_$day"
        var serial = p.getInt(key, 0)
        val codes = (1..n).map { day + "%02d".format(++serial) }
        p.edit().putInt(key, serial).apply()
        return codes
    }
}
