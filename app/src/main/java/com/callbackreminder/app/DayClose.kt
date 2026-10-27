package com.callbackreminder.app

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One day's close: the evening review's numbers, and when it finished (0 = no evening review that day). */
data class DayReport(
    val date: String,
    val done: Int = 0,
    val newTime: Int = 0,
    val skipped: Int = 0,
    val tomorrow: Int = 0,
    val eveningAt: Long = 0L,
) {
    val reviewed: Boolean get() = eveningAt > 0L

    /** "Review done · 12 done · 5 new time · 3 skipped · 6 planned tmrw", or "Tasks review not done today". */
    val line: String get() =
        if (reviewed) "Review done · $done done · $newTime new time · $skipped skipped · $tomorrow planned tmrw"
        else "Tasks review not done today"

    fun toMap(): Map<String, Any> = mapOf(
        "date" to date, "done" to done, "newTime" to newTime, "skipped" to skipped, "tomorrow" to tomorrow, "eveningAt" to eveningAt,
    )

    companion object {
        fun of(m: Map<*, *>): DayReport? {
            fun int(k: String) = (m[k] as? Number)?.toInt() ?: 0
            val date = m["date"] as? String ?: return null
            return DayReport(date, int("done"), int("newTime"), int("skipped"), int("tomorrow"), (m["eveningAt"] as? Number)?.toLong() ?: 0L)
        }
    }
}

/**
 * 📋 "Tick after evening review" (v0.42): the evening review (finished after the last review reminder, 6 PM by
 * default) saves the day's numbers; the block's tick, from the review's CLOSE YOUR DAY card or by hand, carries
 * them. A tick with no evening review shows "Tasks review not done today" in History.
 */
object DayClose {
    private const val KEEP_DAYS = 60L

    private fun p(c: Context) = c.getSharedPreferences("day_close", Context.MODE_PRIVATE)

    fun all(c: Context): Map<String, DayReport> {
        val raw = p(c).getString("reports", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { k ->
                val r = o.getJSONObject(k)
                DayReport(k, r.optInt("done"), r.optInt("newTime"), r.optInt("skipped"), r.optInt("tomorrow"), r.optLong("eveningAt"))
            }.associateBy { it.date }
        }.getOrDefault(emptyMap())
    }

    private fun save(c: Context, r: DayReport) {
        val oldest = LocalDate.now().minusDays(KEEP_DAYS).toString()
        val map = all(c).filterKeys { it >= oldest } + (r.date to r)
        val o = JSONObject()
        map.values.forEach { x ->
            o.put(x.date, JSONObject().put("done", x.done).put("newTime", x.newTime).put("skipped", x.skipped)
                .put("tomorrow", x.tomorrow).put("eveningAt", x.eveningAt))
        }
        p(c).edit().putString("reports", o.toString()).apply()
    }

    fun report(c: Context, date: LocalDate): DayReport? = all(c)[date.toString()]

    /** After the last review reminder of the day (6 PM by default): a review finished now is the evening review. */
    fun isEvening(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = ReviewReminder.times(c).maxOrNull() ?: (18 * 60)
        val t = LocalTime.now(ZoneId.systemDefault())
        return Dossier.openDay(now) != LocalDate.now() || t.hour * 60 + t.minute >= last
    }

    /** Follow-ups and My tasks due tomorrow, still open. */
    fun plannedTomorrow(c: Context): Int {
        ReminderStore.init(c)
        MyTaskStore.init(c)
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.now().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return ReminderStore.items.value.count { !it.done && it.dueAt in from until to } +
            MyTaskStore.items.value.count { !it.done && it.dueAt in from until to }
    }

    /** The review session finished: an evening one saves today's numbers (the latest evening review wins). */
    fun onReviewFinished(c: Context, done: Int, newTime: Int, skipped: Int) {
        if (!isEvening(c)) return
        val day = Dossier.openDay()
        save(c, DayReport(day.toString(), done, newTime, skipped, plannedTomorrow(c), System.currentTimeMillis()))
    }

    /** The block was ticked (on the card, by hand, from the notification): tomorrow's count as it stands now. */
    fun onTick(c: Context, date: LocalDate) {
        val old = report(c, date) ?: DayReport(date.toString())
        save(c, old.copy(tomorrow = plannedTomorrow(c)))
        runCatching { TeamRepo.uploadDay(c) }
    }

    /** Today's 📋 blocks not ticked yet: the CLOSE YOUR DAY card shows while there are any. */
    fun openBlocks(now: Long = System.currentTimeMillis()): List<DossierTask> {
        val day = Dossier.openDay(now)
        return DossierStore.todays(now).filter { it.afterReview && DossierStore.record(it.id, day)?.done != true }
    }

    /** "Review done 5 of 6 days this week": the days of this week (Monday on) [t] came up, and how many had the evening review. */
    fun weekScore(reports: Map<String, DayReport>, isOn: (LocalDate) -> Boolean, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val open = Dossier.openDay(now)
        val monday = open.minusDays((open.dayOfWeek.value - 1).toLong())
        // Today counts once its evening review is done (the day isn't over before that).
        val days = generateSequence(monday) { it.plusDays(1) }.takeWhile { !it.isAfter(open) }.filter(isOn)
            .filter { it != open || reports[it.toString()]?.reviewed == true }.toList()
        return days.count { reports[it.toString()]?.reviewed == true } to days.size
    }

    /** The last 7 days, for the people above him (his day online). */
    fun recentMaps(c: Context): List<Map<String, Any>> {
        val oldest = LocalDate.now().minusDays(7).toString()
        return all(c).values.filter { it.date >= oldest }.sortedBy { it.date }.map { it.toMap() }
    }
}
