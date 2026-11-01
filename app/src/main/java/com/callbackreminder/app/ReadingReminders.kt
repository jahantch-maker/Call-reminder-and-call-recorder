package com.callbackreminder.app

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Calendar

/**
 * v0.50 Reading-deadline reminders. A team member has a reading list on the portal (his job description and the SOPs for
 * his designation) with a deadline: the days set in the portal's Settings (7 by default) counted from the day he started
 * in the seat. The phone reminds him once the day before, once on the last day, then once a day while it is overdue,
 * only in the working day, and stops when every item is read. Runs with the 15-minute team check.
 */
object ReadingReminders {
    private const val DAY = 24 * 3_600_000L
    private fun p(c: Context) = c.getSharedPreferences("reading_reminders", Context.MODE_PRIVATE)

    /** Which reminder is due: 0 none, 1 the day before, 2 the last day, 3+ overdue (one per day). Pure, so it can be tested. */
    fun stage(now: Long, deadline: Long): Int = when {
        now >= deadline -> 3 + ((now - deadline) / DAY).toInt()
        deadline - now <= DAY / 2 -> 2
        deadline - now <= DAY * 3 / 2 -> 1
        else -> 0
    }

    /** Hours when a reminder may show (11 AM to 8 PM, his working day). */
    fun inWorkingHours(now: Long): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return h in 11..19
    }

    suspend fun check(c: Context) {
        val me = TeamRepo.me.value ?: return
        val t = TeamRepo.teamId ?: return
        if (!me.active || me.seatId.isBlank() || me.designation.isBlank()) return
        val now = System.currentTimeMillis()
        if (!inWorkingHours(now)) return
        val team = FirebaseFirestore.getInstance().collection("teams").document(t)

        val days = (team.collection("config").document("meeting").get().await().getLong("readingDays") ?: 7L).toInt().coerceIn(1, 60)
        val seat = team.collection("seats").document(me.seatId).get().await()
        val holders = (seat.get("holders") as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
        val from = holders.lastOrNull { it["uid"] == me.uid }?.get("from").let { (it as? Number)?.toLong() } ?: me.joinedAt
        if (from <= 0L) return
        val deadline = from + days * DAY

        val done = (team.collection("reading").document(me.uid).get().await().get("done") as? Map<*, *>)
            ?.mapNotNull { (k, v) -> (k as? String)?.let { it to (((v as? Map<*, *>)?.get("version") as? Number)?.toInt() ?: 1) } }?.toMap() ?: emptyMap()
        val unread = team.collection("docs").get().await().documents.count { d ->
            val des = (d.get("designations") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            d.getString("type") in setOf("jd", "sop") && me.designation in des && (done[d.id] ?: 0) < (d.getLong("version") ?: 1L).toInt()
        }
        if (unread == 0) { p(c).edit().clear().apply(); return }

        val stage = stage(now, deadline)
        if (stage == 0) return
        val key = "$deadline:$stage"
        if (p(c).getString("last", null) == key) return
        p(c).edit().putString("last", key).apply()
        val what = if (unread == 1) "1 item" else "$unread items"
        val (title, text) = when (stage) {
            1 -> "📖 Reading due tomorrow" to "$what left on your reading list. Finish by tomorrow."
            2 -> "📖 Reading due today" to "$what left on your reading list. Today is the last day."
            else -> "📖 Reading overdue" to "$what left on your reading list, past the deadline. Please finish today."
        }
        TeamSync.notify(c, "reading", title, text, null, kind = NotifKind.MESSAGES)
    }
}
