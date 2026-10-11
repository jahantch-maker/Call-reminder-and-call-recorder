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
    }

    private fun prefs() = appCtx.getSharedPreferences("sms_reminders", Context.MODE_PRIVATE)

    private fun load(): List<SmsReminder> {
        val raw = prefs().getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
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
                    code = o.optString("code"),
                    tracked = o.optBoolean("tracked", true),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<SmsReminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            val log = JSONArray()
            r.log.forEach { l ->
                log.put(JSONObject().put("at", l.at).put("s", l.status.code).put("d", l.detail))
            }
            arr.put(
                JSONObject()
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
            )
        }
        prefs().edit().putString("list", arr.toString()).apply()
        _items.value = list
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
                burstCount = burst?.count ?: 1,
                burstGapMin = burst?.gapMin ?: 0,
                tracked = tracked,
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
    ) {
        val cur = get(id) ?: return
        SmsNotifier.cancel(appCtx, id)
        val r = series(
            cur.copy(
                name = name.trim(),
                number = number.trim(),
                message = message.trim(),
                burstCount = burst?.count ?: 1,
                burstGapMin = burst?.gapMin ?: 0,
                tracked = tracked,
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

    @Synchronized
    fun addLog(id: Long, entry: SmsLogEntry) {
        val cur = get(id) ?: return
        replace(withLog(cur, entry))
    }

    /** You confirmed the work is done (from Scheduled or To review): stop everything, move to Done. */
    @Synchronized
    fun markDone(id: Long) {
        val cur = get(id)?.takeIf { !it.done } ?: return
        val now = System.currentTimeMillis()
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
            it.active && it.tracked && it.code == code && sameNumber(it.number, fromNumber)
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
     * Gives [id] its task number when its first text goes out: today's date ("261011") and the next
     * serial for that day ("-01", "-02", …). Reminders due in the same minute that were saved earlier get
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
        val day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
        val key = "serial_$day"
        var serial = prefs().getInt(key, 0)
        val codes = (first.map { it.id } + id).associateWith { "$day-${"%02d".format(++serial)}" }
        prefs().edit().putInt(key, serial).apply()
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
