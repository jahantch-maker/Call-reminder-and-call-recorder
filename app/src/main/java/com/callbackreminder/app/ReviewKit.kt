package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId

/** The Review page's sections, in their default order. */
enum class ReviewSection(
    val key: String,
    val emoji: String,
    val title: String,
    val about: String,
    val defaultShow: Boolean,
    val defaultSession: Boolean,
) {
    REPLIED("replied", "✅", "Replied, check it", "They texted back the task number, or you chose Done, review it", true, true),
    OVERDUE("overdue", "🔴", "Overdue", "Calls, and texts with no reply", true, true),
    SCHED_FU("sched_fu", "🕘", "To schedule · Follow-ups", "No time or no person yet", true, true),
    SCHED_MY("sched_my", "🕘", "To schedule · My tasks", "My tasks with no time", true, true),
    MY_LATE("my_late", "☑", "Overdue My tasks", "Past their time", true, true),
    DUE_TODAY("due_today", "🔔", "Still due today", "Calls later today", true, false);

    /** Calls are made in the call session; the rest get a card each. */
    val isCalls: Boolean get() = this == OVERDUE || this == DUE_TODAY
}

/** Settings › Review: which sections show, which join "Start review", their order and the resume reminder. */
object ReviewPrefs {
    private fun p(c: Context) = c.getSharedPreferences("review_reminder", Context.MODE_PRIVATE)

    fun order(c: Context): List<ReviewSection> {
        val saved = p(c).getString("sec_order", null)?.split(',')
            ?.mapNotNull { k -> ReviewSection.values().firstOrNull { it.key == k } }.orEmpty()
        return saved + ReviewSection.values().filter { it !in saved }
    }

    fun setOrder(c: Context, list: List<ReviewSection>) =
        p(c).edit().putString("sec_order", list.joinToString(",") { it.key }).apply()

    fun show(c: Context, s: ReviewSection) = p(c).getBoolean("sec_show_${s.key}", s.defaultShow)
    fun setShow(c: Context, s: ReviewSection, on: Boolean) = p(c).edit().putBoolean("sec_show_${s.key}", on).apply()

    fun session(c: Context, s: ReviewSection) = show(c, s) && p(c).getBoolean("sec_session_${s.key}", s.defaultSession)
    fun setSession(c: Context, s: ReviewSection, on: Boolean) = p(c).edit().putBoolean("sec_session_${s.key}", on).apply()

    /** Minutes paused before "Resume your call session?" pops up. */
    fun resumeMinutes(c: Context) = p(c).getInt("resume_min", 15)
    fun setResumeMinutes(c: Context, m: Int) = p(c).edit().putInt("resume_min", m).apply()
    val RESUME_CHOICES = listOf(5, 10, 15, 30, 60)

    fun shown(c: Context): List<ReviewSection> = order(c).filter { show(c, it) }

    /** v0.32: "Replied, check it" is switched on once and moved first (it used to be off by default). */
    fun migrate(c: Context) {
        if (p(c).getBoolean("replied_on_v32", false)) return
        val s = ReviewSection.REPLIED
        p(c).edit()
            .putBoolean("sec_show_${s.key}", true)
            .putBoolean("sec_session_${s.key}", true)
            .putBoolean("replied_on_v32", true)
            .apply()
        setOrder(c, listOf(s) + order(c).filter { it != s })
    }
}

/** One thing waiting on the Review page. [key] names it in a session queue, like "c:12". */
data class ReviewItem(
    val key: String,
    val section: ReviewSection,
    val kind: Kind,
    val id: Long,
    val title: String,
    val person: String,
    val number: String,
    /** Due time (0 = none). */
    val at: Long,
    val chips: List<String> = emptyList(),
    /** The task number (#yyMMddNN) of a follow-up, "" when it has none. */
    val code: String = "",
    /** A My task's length, for its free slots. */
    val lengthMin: Int = MyTaskStore.DEFAULT_LENGTH,
) {
    enum class Kind { CALL, TEXT, CALL_REVIEW, TEXT_REVIEW, SCHED_FU, SCHED_MY, MY_TASK }

    val isCall: Boolean get() = kind == Kind.CALL || kind == Kind.TEXT
    val isReplied: Boolean get() = kind == Kind.CALL_REVIEW || kind == Kind.TEXT_REVIEW
    val isMine: Boolean get() = kind == Kind.MY_TASK || kind == Kind.SCHED_MY
}

object ReviewData {
    private const val MIN = 60_000L

    fun lateLabel(at: Long, now: Long): String {
        val m = ((now - at) / MIN).coerceAtLeast(1)
        return when {
            m < 60 -> "$m min late"
            m < 24 * 60 -> "${m / 60} h late"
            else -> (m / (24 * 60)).let { "$it ${if (it == 1L) "day" else "days"} late" }
        }
    }

    /** The first working day after [now]'s day (the weekly off day skipped). */
    private fun nextWorkDay(now: Long): LocalDate {
        var d = CalendarData.dateOf(now).plusDays(1)
        repeat(7) { if (!OffDays.isOff(d)) return d; d = d.plusDays(1) }
        return CalendarData.dateOf(now).plusDays(1)
    }

    /** Your work start on the next working day: never a suggestion outside working hours. */
    fun nextWorkStart(ctx: Context, now: Long = System.currentTimeMillis()): Long =
        CalendarData.at(nextWorkDay(now), WorkHours.start(ctx))

    /**
     * The same clock time on the next working day, or null when there is no time or the time is outside your
     * working hours (those are left for you to set).
     */
    fun sameTimeNextDay(ctx: Context, at: Long, now: Long = System.currentTimeMillis()): Long? {
        if (at <= 0L) return null
        val t = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime()
        val minute = t.hour * 60 + t.minute
        if (WorkHours.isSet(ctx) && (minute < WorkHours.start(ctx) || minute >= WorkHours.end(ctx))) return null
        return CalendarData.at(nextWorkDay(now), minute)
    }

    /** Up to [count] free starts for a My task of [lengthMin], from now over the next two weeks (working hours only). */
    fun freeSlots(ctx: Context, lengthMin: Int, excludeTaskId: Long, count: Int = 6, now: Long = System.currentTimeMillis()): List<Long> {
        val out = ArrayList<Long>()
        var d = CalendarData.dateOf(now)
        repeat(14) {
            if (out.size >= count) return out
            out += CalendarData.bestSlots(ctx, d, lengthMin, excludeTaskId, count - out.size).filter { it > now }
            d = d.plusDays(1)
        }
        return out.take(count)
    }

    /** When a replied task put to "Review later" comes back: your next review reminder, else tomorrow's work start. */
    fun nextReviewAt(ctx: Context, now: Long = System.currentTimeMillis()): Long =
        ReviewReminder.nextAt(ctx, now) ?: nextWorkStart(ctx, now)

    /** Every section with what's waiting in it right now (all sections, shown or not). */
    fun build(ctx: Context, now: Long = System.currentTimeMillis()): Map<ReviewSection, List<ReviewItem>> {
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        ToScheduleStore.init(ctx)
        MyTaskStore.init(ctx)
        val zone = ZoneId.systemDefault()
        val endOfDay = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val calls = ReminderStore.items.value
        val texts = SmsStore.items.value
        val openLinks = calls.filter { !it.done && it.linkId != 0L }.map { it.linkId }.toSet()

        val replied = calls.filter { it.done && it.review && it.reviewLaterUntil <= now }.map {
            ReviewItem("cr:${it.id}", ReviewSection.REPLIED, ReviewItem.Kind.CALL_REVIEW, it.id,
                it.note.ifBlank { "Call back" }, it.title, it.number, it.doneAt, listOf("To check"), code = it.code)
        } + texts.filter { !it.done && it.review == SmsReminder.Review.REPLIED && it.reviewLaterUntil <= now }.map {
            ReviewItem("tr:${it.id}", ReviewSection.REPLIED, ReviewItem.Kind.TEXT_REVIEW, it.id,
                it.message, it.title, it.number, it.reviewAt, listOf("✅ Replied Done ${it.code} · ${Fmt.timeOnly(it.reviewAt)}"), code = it.code)
        }

        val overdueCalls = calls.filter { !it.done && it.dueAt <= now }.map {
            ReviewItem("c:${it.id}", ReviewSection.OVERDUE, ReviewItem.Kind.CALL, it.id,
                it.note.ifBlank { "Call back" }, it.title, it.number, it.dueAt,
                listOfNotNull(lateLabel(it.dueAt, now), it.delays.size.takeIf { n -> n > 0 }?.let { n -> "Delayed ${n}×" }), code = it.code)
        }
        // A text follow-up with no call of its own: overdue once its texts went with no reply, or its due passed.
        val overdueTexts = texts.filter { t ->
            !t.done && t.review != SmsReminder.Review.REPLIED && (t.linkId == 0L || t.linkId !in openLinks) &&
                (t.review == SmsReminder.Review.NO_REPLY || (t.taskDue in 1..now))
        }.map { t ->
            val at = t.taskDue.takeIf { it > 0L } ?: t.reviewAt
            ReviewItem("t:${t.id}", ReviewSection.OVERDUE, ReviewItem.Kind.TEXT, t.id, t.message, t.title, t.number, at,
                listOf(lateLabel(at, now), "💬 No reply"), code = t.code)
        }

        val schedFu = ToScheduleStore.items.value.sortedBy { it.createdAt }.map {
            ReviewItem("s:${it.id}", ReviewSection.SCHED_FU, ReviewItem.Kind.SCHED_FU, it.id,
                it.headline, if (it.number.isNotBlank() || it.name.isNotBlank()) it.title else "", it.number, 0L,
                listOf(if (it.number.isBlank()) "No person" else if (it.needsDetails) "Needs details" else "No time"))
        }
        val my = MyTaskStore.items.value.filter { !it.done }
        val schedMy = my.filter { !it.timed }.sortedBy { it.createdAt }.map {
            ReviewItem("ms:${it.id}", ReviewSection.SCHED_MY, ReviewItem.Kind.SCHED_MY, it.id,
                it.title, if (it.hasPerson) it.personTitle else "", it.number, 0L, listOf("☑ My task"), lengthMin = it.durationMin)
        }
        val myLate = my.filter { it.timed && it.dueAt <= now }.sortedBy { it.dueAt }.map {
            ReviewItem("ml:${it.id}", ReviewSection.MY_LATE, ReviewItem.Kind.MY_TASK, it.id,
                it.title, if (it.hasPerson) it.personTitle else "", it.number, it.dueAt, listOf(lateLabel(it.dueAt, now), "☑ My task"),
                lengthMin = it.durationMin)
        }
        val dueToday = calls.filter { !it.done && it.dueAt in (now + 1)..endOfDay }.sortedBy { it.dueAt }.map {
            ReviewItem("c:${it.id}", ReviewSection.DUE_TODAY, ReviewItem.Kind.CALL, it.id,
                it.note.ifBlank { "Call back" }, it.title, it.number, it.dueAt, listOf("Later today"), code = it.code)
        }

        return mapOf(
            ReviewSection.REPLIED to replied.sortedBy { it.at },
            ReviewSection.OVERDUE to (overdueCalls + overdueTexts).sortedBy { it.at },
            ReviewSection.SCHED_FU to schedFu,
            ReviewSection.SCHED_MY to schedMy,
            ReviewSection.MY_LATE to myLate,
            ReviewSection.DUE_TODAY to dueToday,
        )
    }

    /** Count of everything in the shown sections (for Home and the 1 PM / 6 PM popup). */
    fun shownCounts(ctx: Context, now: Long = System.currentTimeMillis()): List<Pair<ReviewSection, Int>> {
        val all = build(ctx, now)
        return ReviewPrefs.shown(ctx).map { it to (all[it]?.size ?: 0) }
    }

    /** The items a session goes through: "all" sections with Session on, calls only, or one section. */
    fun queue(ctx: Context, mode: String, now: Long = System.currentTimeMillis()): List<ReviewItem> {
        val all = build(ctx, now)
        val sections = when (mode) {
            ReviewSession.MODE_ALL -> ReviewPrefs.order(ctx).filter { ReviewPrefs.session(ctx, it) }
            ReviewSession.MODE_CALLS -> ReviewPrefs.order(ctx).filter { it.isCalls && ReviewPrefs.session(ctx, it) }
                .ifEmpty { listOf(ReviewSection.OVERDUE) }
            else -> listOfNotNull(ReviewSection.values().firstOrNull { it.key == mode })
        }
        return sections.flatMap { all[it].orEmpty() }.distinctBy { it.key }
    }

    /** A replied task put to "Review later" (hidden from [build] until then), while it still waits for your check. */
    fun findLater(ctx: Context, key: String): ReviewItem? {
        val id = key.substringAfter(':').toLongOrNull() ?: return null
        return when {
            key.startsWith("cr:") -> ReminderStore.get(id)?.takeIf { it.done && it.review }?.let {
                ReviewItem(key, ReviewSection.REPLIED, ReviewItem.Kind.CALL_REVIEW, it.id, it.note.ifBlank { "Call back" }, it.title,
                    it.number, it.reviewLaterUntil, listOf("To check"), code = it.code)
            }
            key.startsWith("tr:") -> SmsStore.get(id)?.takeIf { !it.done && it.review == SmsReminder.Review.REPLIED }?.let {
                ReviewItem(key, ReviewSection.REPLIED, ReviewItem.Kind.TEXT_REVIEW, it.id, it.message, it.title, it.number,
                    it.reviewLaterUntil, listOf("Replied Done ${it.code}"), code = it.code)
            }
            else -> null
        }
    }

    /** One item by its queue key, as it is now (null when it has gone, e.g. done elsewhere). */
    fun find(ctx: Context, key: String, now: Long = System.currentTimeMillis()): ReviewItem? =
        build(ctx, now).values.flatten().firstOrNull { it.key == key }
}

/**
 * The review session: one item after another (calls with the Next card, the rest with a card each). It is kept
 * in prefs, so it survives the app closing and a phone restart. While paused, a quiet ongoing notification and the
 * Home card offer "Continue review"; there is no timed resume popup. The next 1 PM / 6 PM review starts with the
 * items left over, and what is left at midnight is carried to the next day's first review.
 */
object ReviewSession {
    const val MODE_ALL = "all"
    const val MODE_CALLS = "calls"

    const val ACTION_REMIND = "com.callbackreminder.app.SESSION_REMIND"
    const val ACTION_END = "com.callbackreminder.app.SESSION_END"
    private const val REQ_REMIND = 1_501_000
    private const val NOTIF_ID = 1_501_001

    enum class Status { IDLE, RUNNING, PAUSED, ENDED }

    data class State(
        val status: Status = Status.IDLE,
        val mode: String = MODE_ALL,
        val queue: List<String> = emptyList(),
        val pos: Int = 0,
        val tried: Set<String> = emptySet(),
        val done: Int = 0,
        val newTime: Int = 0,
        val skipped: List<String> = emptyList(),
        val noAnswer: List<String> = emptyList(),
        /** Replied tasks put to "Review later" (back at the next review time). */
        val later: List<String> = emptyList(),
        val awaiting: String = "",
        val awaitingAt: Long = 0L,
        val startedAt: Long = 0L,
        val pausedAt: Long = 0L,
        val remindAt: Long = 0L,
        val day: String = "",
    ) {
        val current: String? get() = queue.getOrNull(pos)
        val total: Int get() = queue.size
        val handled: Int get() = pos.coerceAtMost(queue.size)
        val active: Boolean get() = status == Status.RUNNING || status == Status.PAUSED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loaded = false

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("review_session", Context.MODE_PRIVATE)
    private fun today() = LocalDate.now().toString()

    @Synchronized
    fun init(ctx: Context): State {
        if (!loaded) {
            loaded = true
            val s = p(ctx)
            fun list(k: String) = s.getString(k, "").orEmpty().split(',').filter { it.isNotBlank() }
            _state.value = State(
                status = runCatching { Status.valueOf(s.getString("status", "IDLE")!!) }.getOrDefault(Status.IDLE),
                mode = s.getString("mode", MODE_ALL)!!,
                queue = list("queue"),
                pos = s.getInt("pos", 0),
                tried = list("tried").toSet(),
                done = s.getInt("done", 0),
                newTime = s.getInt("newTime", 0),
                skipped = list("skipped"),
                noAnswer = list("noAnswer"),
                later = list("later"),
                awaiting = s.getString("awaiting", "")!!,
                awaitingAt = s.getLong("awaitingAt", 0L),
                startedAt = s.getLong("startedAt", 0L),
                pausedAt = s.getLong("pausedAt", 0L),
                remindAt = s.getLong("remindAt", 0L),
                day = s.getString("day", "")!!,
            )
        }
        // A new day: what was left goes first into the next review (never dropped), then a fresh start.
        if (_state.value.active && _state.value.day != today()) {
            setCarry(ctx, (carry(ctx) + leftovers(_state.value)).distinct())
            set(ctx, State())
            cancelReminder(ctx)
        }
        return _state.value
    }

    private fun set(ctx: Context, s: State) {
        _state.value = s
        p(ctx).edit()
            .putString("status", s.status.name)
            .putString("mode", s.mode)
            .putString("queue", s.queue.joinToString(","))
            .putInt("pos", s.pos)
            .putString("tried", s.tried.joinToString(","))
            .putInt("done", s.done)
            .putInt("newTime", s.newTime)
            .putString("skipped", s.skipped.joinToString(","))
            .putString("noAnswer", s.noAnswer.joinToString(","))
            .putString("later", s.later.joinToString(","))
            .putString("awaiting", s.awaiting)
            .putLong("awaitingAt", s.awaitingAt)
            .putLong("startedAt", s.startedAt)
            .putLong("pausedAt", s.pausedAt)
            .putLong("remindAt", s.remindAt)
            .putString("day", s.day)
            .commit()
    }

    /** What a session had not reached yet. */
    private fun leftovers(s: State): List<String> = if (s.active) s.queue.drop(s.pos) else emptyList()

    /** Items left over from yesterday's unfinished session. */
    private fun carry(ctx: Context): List<String> = p(ctx).getString("carry", "").orEmpty().split(',').filter { it.isNotBlank() }
    private fun setCarry(ctx: Context, keys: List<String>) = p(ctx).edit().putString("carry", keys.joinToString(",")).commit()

    /**
     * Starts a session. Items left over from a paused or unfinished session (today's, or carried from yesterday)
     * come first, oldest first, then everything else that is waiting now.
     */
    fun start(ctx: Context, mode: String): Boolean {
        val items = ReviewData.queue(ctx, mode).map { it.key }
        val fresh = items.toSet()
        val left = (carry(ctx) + leftovers(init(ctx))).distinct().filter { it in fresh }
        val queue = left + items.filter { it !in left }
        if (queue.isEmpty()) return false
        cancelReminder(ctx)
        ReviewReminder.cancelSnooze(ctx)
        setCarry(ctx, emptyList())
        set(ctx, State(status = Status.RUNNING, mode = mode, queue = queue, startedAt = System.currentTimeMillis(), day = today()))
        return true
    }

    /** Moves past items that were finished elsewhere meanwhile. Ends when nothing is left. */
    fun current(ctx: Context): ReviewItem? {
        var s = init(ctx)
        while (true) {
            val key = s.current ?: break
            val item = ReviewData.find(ctx, key)
            if (item != null) return item
            s = s.copy(pos = s.pos + 1)
            set(ctx, s)
        }
        if (s.status == Status.RUNNING) finish(ctx)
        return null
    }

    fun upNext(ctx: Context, n: Int = 4): List<ReviewItem> {
        val s = init(ctx)
        return s.queue.drop(s.pos + 1).asSequence().mapNotNull { ReviewData.find(ctx, it) }.take(n).toList()
    }

    private fun advance(ctx: Context, s: State) {
        val next = s.copy(pos = s.pos + 1, awaiting = "", awaitingAt = 0L)
        set(ctx, next)
        if (next.pos >= next.queue.size) finish(ctx)
    }

    fun markDone(ctx: Context) = init(ctx).let { advance(ctx, it.copy(done = it.done + 1)) }
    fun markNewTime(ctx: Context) = init(ctx).let { advance(ctx, it.copy(newTime = it.newTime + 1)) }
    fun skip(ctx: Context) = init(ctx).let { s -> advance(ctx, s.copy(skipped = s.skipped + listOfNotNull(s.current))) }

    /** Skip on a replied task: hidden from Review and sessions until the next review time, then back in Replied. */
    fun reviewLater(ctx: Context, item: ReviewItem) {
        val until = ReviewData.nextReviewAt(ctx)
        when (item.kind) {
            ReviewItem.Kind.CALL_REVIEW -> ReminderStore.reviewLater(item.id, until)
            ReviewItem.Kind.TEXT_REVIEW -> SmsStore.reviewLater(item.id, until)
            else -> {}
        }
        init(ctx).let { s -> advance(ctx, s.copy(later = s.later + item.key)) }
    }

    /** Call now: remember which item the call is about, so its outcome is read when you come back. */
    fun calling(ctx: Context, key: String) {
        val s = init(ctx)
        set(ctx, s.copy(awaiting = key, awaitingAt = System.currentTimeMillis()))
    }

    /**
     * Back from a call: Done or a new time move on; still due means no answer, so it goes to the end of the
     * queue for one more try, and after that it stays overdue.
     */
    fun settle(ctx: Context) {
        val s = init(ctx)
        val key = s.awaiting.ifEmpty { return }
        val now = System.currentTimeMillis()
        val id = key.substringAfter(':').toLongOrNull() ?: -1L
        val outcome = when {
            key.startsWith("c:") -> ReminderStore.get(id).let { r ->
                when {
                    r == null || r.done -> 1
                    r.dueAt > now + 30_000L -> 2
                    else -> 0
                }
            }
            key.startsWith("t:") -> SmsStore.get(id).let { t ->
                when {
                    // Changed to a call with a new time (Call later).
                    t == null -> 2
                    t.done || t.review == SmsReminder.Review.REPLIED -> 1
                    t.review == null && t.dueAt > now -> 2
                    else -> 0
                }
            }
            else -> 1
        }
        when (outcome) {
            1 -> advance(ctx, s.copy(done = s.done + 1))
            2 -> advance(ctx, s.copy(newTime = s.newTime + 1))
            else -> if (key !in s.tried) {
                // No answer: once more at the end.
                val q = s.queue.toMutableList().apply { removeAt(s.pos); add(key) }
                set(ctx, s.copy(queue = q, tried = s.tried + key, awaiting = "", awaitingAt = 0L))
                if (s.pos >= q.size) finish(ctx)
            } else {
                advance(ctx, s.copy(noAnswer = s.noAnswer + key))
            }
        }
    }

    fun finish(ctx: Context) {
        cancelReminder(ctx)
        set(ctx, init(ctx).copy(status = Status.ENDED, awaiting = ""))
    }

    /** Closes the summary (or End from Home / the popup): back to no session. */
    fun clear(ctx: Context) {
        cancelReminder(ctx)
        set(ctx, State())
    }

    fun pause(ctx: Context) {
        val s = init(ctx)
        if (s.status != Status.RUNNING) return
        // No timed "resume?" popup (you are pulled away many times a day): the quiet notification and the Home
        // card wait, and the next review reminder picks the rest up.
        set(ctx, s.copy(status = Status.PAUSED, pausedAt = System.currentTimeMillis(), remindAt = 0L))
        showPausedNotification(ctx)
    }

    fun resume(ctx: Context) {
        val s = init(ctx)
        if (s.status != Status.PAUSED) return
        cancelReminder(ctx)
        // What became due while paused joins at the end, so your place doesn't jump.
        val added = ReviewData.queue(ctx, s.mode).map { it.key }.filter { it !in s.queue }
        set(ctx, s.copy(status = Status.RUNNING, queue = s.queue + added, pausedAt = 0L, remindAt = 0L))
    }

    fun remindLater(ctx: Context, minutes: Int = 15) {
        val s = init(ctx)
        if (s.status != Status.PAUSED) return
        val at = System.currentTimeMillis() + minutes * 60_000L
        set(ctx, s.copy(remindAt = at))
        armReminder(ctx, at)
        showPausedNotification(ctx)
    }

    /** After a restart: the paused notification comes back. */
    fun rearm(ctx: Context) {
        val s = init(ctx)
        if (s.status != Status.PAUSED) return
        showPausedNotification(ctx)
    }

    /** Undo on the session card: back to how the session was before that tap. */
    fun restore(ctx: Context, before: State) {
        if (init(ctx).day != before.day) return
        set(ctx, before)
    }

    /** The after-call popup closed: a call made from the session can now be read. */
    fun popupClosed(ctx: Context) = p(ctx).edit().putLong("popupClosedAt", System.currentTimeMillis()).commit()
    fun popupClosedAt(ctx: Context) = p(ctx).getLong("popupClosedAt", 0L)

    fun nextName(ctx: Context): String? {
        val s = init(ctx)
        val key = s.current ?: return null
        val item = ReviewData.find(ctx, key) ?: return null
        return listOf(item.title, item.person).filter { it.isNotBlank() }.joinToString(" · ")
    }

    // ---- Resume reminder ----

    private fun remindIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_REMIND,
        Intent(ctx, ReviewSessionReceiver::class.java).setAction(ACTION_REMIND).setData(Uri.parse("callback://session-remind")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun armReminder(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setClockSafely(AlarmManager.AlarmClockInfo(at, openIntent(ctx, REQ_REMIND + 5)), remindIntent(ctx))
    }

    private fun cancelReminder(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(remindIntent(ctx))
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }

    fun openIntent(ctx: Context, req: Int, resume: Boolean = false): PendingIntent = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SESSION)
            .putExtra(MainActivity.EXTRA_RESUME, resume)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @SuppressLint("MissingPermission")
    private fun showPausedNotification(ctx: Context) {
        if (!Notifier.canNotify(ctx)) return
        Notifier.createChannels(ctx)
        Notify.createChannel(ctx)
        val s = init(ctx)
        val end = PendingIntent.getBroadcast(
            ctx, REQ_REMIND + 2,
            Intent(ctx, ReviewSessionReceiver::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, Notify.CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Continue review · ${s.total - s.handled} left")
            .setContentText(nextName(ctx)?.let { "Next: $it" } ?: "Tap to resume")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent(ctx, REQ_REMIND + 3))
            .addAction(0, "Continue", openIntent(ctx, REQ_REMIND + 4, resume = true))
            .addAction(0, "End", end)
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build())
    }

    /** The resume time came: the popup (or a loud notification when popups can't open). */
    @SuppressLint("MissingPermission")
    fun remind(ctx: Context) {
        val s = init(ctx)
        if (s.status != Status.PAUSED) return
        showPausedNotification(ctx)
        val popup = Intent(ctx, ReviewAlertActivity::class.java)
            .putExtra(ReviewAlertActivity.EXTRA_RESUME, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(popup)
                return
            } catch (e: Exception) {
                // The notification below instead.
            }
        }
        if (!Notifier.canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Resume your review session?")
            .setContentText("Paused · ${s.handled} of ${s.total} done")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, REQ_REMIND + 6, resume = true))
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID + 1, b.build())
    }
}

/** The resume reminder alarm and End on the paused notification. */
class ReviewSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.init(context)
        SmsStore.init(context)
        when (intent.action) {
            ReviewSession.ACTION_REMIND -> ReviewSession.remind(context)
            ReviewSession.ACTION_END -> ReviewSession.clear(context)
        }
    }
}
