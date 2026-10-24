package com.callbackreminder.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * What ties an online team task to this phone's own lists. Kept on the phone only:
 * - recv: team task → the My task it became on his phone
 * - recvFu: team task → his follow-up link, once he passed it down with Move to Follow-ups
 * - given: team task → the giver's own follow-up link (his call-back that follows the date)
 * - last: the task as last seen ("status|due|moreAt"), to spot what changed
 * - sets: SMS already sent, Urgent already announced, 🔒 private My tasks
 */
object TeamLinks {
    private fun p(c: Context) = c.getSharedPreferences("team_links", Context.MODE_PRIVATE)

    fun map(c: Context, key: String): MutableMap<String, String> {
        val o = runCatching { JSONObject(p(c).getString(key, "{}") ?: "{}") }.getOrElse { JSONObject() }
        return o.keys().asSequence().associateWith { o.optString(it) }.toMutableMap()
    }

    fun putMap(c: Context, key: String, m: Map<String, String>) {
        p(c).edit().putString(key, JSONObject(m).toString()).apply()
    }

    fun set(c: Context, key: String): MutableSet<String> = p(c).getStringSet(key, emptySet())!!.toMutableSet()
    fun putSet(c: Context, key: String, s: Set<String>) = p(c).edit().putStringSet(key, s).apply()

    /** The team task a My task came from (null for your own tasks). */
    fun teamTaskOfMyTask(c: Context, myTaskId: Long): String? =
        map(c, "recv").entries.firstOrNull { it.value == myTaskId.toString() }?.key

    /** The team task a follow-up belongs to: one you gave, or one you passed down. */
    fun givenOfLink(c: Context, linkId: Long): String? =
        if (linkId == 0L) null else map(c, "given").entries.firstOrNull { it.value == linkId.toString() }?.key

    fun passedOfLink(c: Context, linkId: Long): String? =
        if (linkId == 0L) null else map(c, "recvFu").entries.firstOrNull { it.value == linkId.toString() }?.key

    fun isPrivate(c: Context, myTaskId: Long) = myTaskId.toString() in set(c, "private")
    fun setPrivate(c: Context, myTaskId: Long, on: Boolean) {
        val s = set(c, "private")
        if (on) s += myTaskId.toString() else s -= myTaskId.toString()
        putSet(c, "private", s)
        TeamSync.version.value++
    }
    fun privateCount(c: Context): Int = set(c, "private").count { id -> MyTaskStore.get(id.toLongOrNull() ?: -1L)?.done == false }
}

/**
 * Keeps both sides in step: his My task / your follow-up ↔ the online team task. Runs when a task changes
 * online (listener or the 15-minute check) and when your own lists change.
 */
object TeamSync {
    private val lock = Any()
    private var started = false

    /** Bumps when links or a 🔒 Private mark change (cards redraw). */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        val mine: List<Flow<Unit>> = listOf(MyTaskStore.items.map { }, ReminderStore.items.map { }, ToScheduleStore.items.map { })
        TeamRepo.scope.launch {
            merge(*mine.toTypedArray()).drop(mine.size).debounce(800L).collect {
                CrashLog.guard(app, "Team push") { if (TeamRepo.activeNow) pushLocal(app) }
            }
        }
        val day: List<Flow<Unit>> = mine + listOf(DossierStore.records.map { }, DossierStore.tasks.map { }, version.map { })
        TeamRepo.scope.launch {
            merge(*day.toTypedArray()).debounce(20_000L).collect {
                CrashLog.guard(app, "Team day") { if (TeamRepo.activeNow) TeamRepo.uploadDay(app) }
            }
        }
    }

    private fun sig(t: TeamTask) = "${t.status}|${t.due}|${t.moreAt}|${t.lastMsgAt}|${t.urgent}"
    private fun msgOf(sig: String?): Long = sig?.split('|')?.getOrNull(3)?.toLongOrNull() ?: 0L

    /** You opened the task's Timeline up to [at]: its "new" chip goes. */
    fun markRead(c: Context, taskId: String, at: Long) {
        val m = TeamLinks.map(c, "read")
        if ((m[taskId]?.toLongOrNull() ?: 0L) >= at) return
        m[taskId] = at.toString()
        TeamLinks.putMap(c, "read", m)
        version.value++
    }

    /** A message from someone else you haven't opened yet. */
    fun unread(c: Context, t: TeamTask, me: String?): Boolean =
        t.lastMsgAt > 0L && t.lastMsgBy != me && t.lastMsgAt > (TeamLinks.map(c, "read")[t.id]?.toLongOrNull() ?: 0L)
    private fun dueOf(sig: String?): Long? = sig?.split('|')?.getOrNull(1)?.toLongOrNull()
    private fun statusOf(sig: String?): String? = sig?.split('|')?.getOrNull(0)
    private fun moreOf(sig: String?): Long = sig?.split('|')?.getOrNull(2)?.toLongOrNull() ?: 0L
    private fun urgentOf(sig: String?): Boolean? = sig?.split('|')?.getOrNull(4)?.toBooleanStrictOrNull()

    private fun initStores(c: Context) {
        ReminderStore.init(c)
        SmsStore.init(c)
        ToScheduleStore.init(c)
        MyTaskStore.init(c)
        DossierStore.init(c)
    }

    /** The online tasks changed: bring them into his My tasks / your follow-ups, and tell whoever needs to know. */
    fun apply(c: Context, tasks: List<TeamTask>, foreground: Boolean) {
        val me = TeamRepo.uid ?: return
        synchronized(lock) {
            initStores(c)
            val last = TeamLinks.map(c, "last")
            val recv = TeamLinks.map(c, "recv")
            val recvFu = TeamLinks.map(c, "recvFu")
            val given = TeamLinks.map(c, "given")
            for (t in tasks) {
                val before = last[t.id]
                if (t.toUid == me) receive(c, t, before, foreground, recv, recvFu)
                if (t.fromUid == me) giverSide(c, t, before, given)
                // A new message from someone else on the task.
                if (before != null && t.lastMsgAt > msgOf(before) && t.lastMsgBy != me && t.lastMsgBy.isNotEmpty()) {
                    val who = TeamRepo.member(t.lastMsgBy)?.first ?: if (t.lastMsgBy == t.fromUid) t.fromFirst else t.toFirst
                    val head = when {
                        t.stuck && t.stuckAt == t.lastMsgAt -> "⚠ $who is stuck"
                        t.asks && t.askAt == t.lastMsgAt -> "❓ $who asks"
                        else -> who
                    }
                    notify(c, "msg:${t.id}", "$head · ${t.title}".take(80), t.lastMsgText,
                        mine = if (t.toUid == me) recv[t.id]?.toLongOrNull() else null, taskId = t.id)
                }
                last[t.id] = sig(t)
            }
            TeamLinks.putMap(c, "recv", recv)
            TeamLinks.putMap(c, "recvFu", recvFu)
            TeamLinks.putMap(c, "given", given)
            TeamLinks.putMap(c, "last", last)
            rules(c, tasks, me)
        }
        version.value++
    }

    /** His side: the task is one of his My tasks (or his follow-up, once passed down). */
    private fun receive(
        c: Context, t: TeamTask, before: String?, foreground: Boolean,
        recv: MutableMap<String, String>, recvFu: MutableMap<String, String>,
    ) {
        val passed = recvFu[t.id]?.toLongOrNull()
        val local = recv[t.id]?.toLongOrNull()?.let { MyTaskStore.get(it) }
        val remoteMoved = before != null && dueOf(before) != t.due
        if (passed != null) {
            if (t.status == Team.CANCELLED || t.status == Team.RETURNED) {
                FollowUps.finishCall(passed)
                FollowUps.stopTexts(passed)
                recvFu.remove(t.id)
            }
        } else when {
            t.open -> {
                if (local == null) {
                    val made = MyTaskStore.add(
                        title = t.title, note = "", dueAt = t.due, name = "", number = "",
                        starred = t.urgent,
                    )
                    recv[t.id] = made.id.toString()
                } else {
                    if (local.done && t.status == Team.REOPENED) MyTaskStore.setDone(local.id, false)
                    if (urgentOf(before)?.let { it != t.urgent } == true && local.starred != t.urgent) MyTaskStore.setStar(local.id, t.urgent)
                    if (t.due > 0L && local.dueAt != t.due && (before == null || remoteMoved)) MyTaskStore.setDue(local.id, t.due)
                }
            }
            t.status == Team.CANCELLED || t.status == Team.RETURNED -> {
                local?.let { if (!it.done) MyTaskStore.delete(it.id) }
                recv.remove(t.id)
            }
            t.status == Team.DONE || t.status == Team.CHECKED -> local?.let { if (!it.done) MyTaskStore.setDone(it.id, true) }
        }
        if (t.status == Team.SENT && foreground) TeamRepo.markSeen(t)
        else if (t.open && t.deliveredAt == 0L) TeamRepo.markDelivered(t)

        // What he hears about.
        val was = statusOf(before)
        when {
            before == null && t.open && t.status != Team.REOPENED ->
                notify(c, "new:${t.id}", (if (t.urgent) "🔴 Urgent task" else "New task") + " from ${t.fromFirst}",
                    t.title + if (t.hisDate && t.due == 0L) "\nPick your date" + (if (t.deadline > 0L) " by ${Fmt.dueLabel(t.deadline)}" else "") else "\nDate: ${Fmt.dueLabel(t.due)}",
                    mine = recv[t.id]?.toLongOrNull())
            was != null && was != t.status && t.status == Team.REOPENED ->
                notify(c, "re:${t.id}", "Not done yet · ${t.fromFirst}", t.title + if (t.reason.isBlank()) "" else "\n“${t.reason}”", mine = recv[t.id]?.toLongOrNull())
            was != null && was != t.status && t.status == Team.CHECKED ->
                notify(c, "ok:${t.id}", "${t.fromFirst}: Received, thanks ✓", t.title, mine = recv[t.id]?.toLongOrNull())
            was != null && was != t.status && t.status == Team.CANCELLED ->
                notify(c, "x:${t.id}", "Task removed by ${t.fromFirst}", t.title, mine = null)
            moreOf(before) > 0L && t.moreAt == 0L ->
                notify(c, "more:${t.id}", if (t.due == t.moreDue) "More time given ✓" else "${t.fromFirst} kept the date",
                    "${t.title}\nDate: ${Fmt.dueLabel(t.due)}", mine = recv[t.id]?.toLongOrNull())
            before != null && remoteMoved && t.dateBy == Team.GIVER_DATE && t.moreAt == 0L && moreOf(before) == 0L ->
                notify(c, "date:${t.id}", "${t.fromFirst} set the date", "${t.title}\n${Fmt.dueLabel(t.due)}", mine = recv[t.id]?.toLongOrNull())
        }
    }

    /** Your side: your follow-up (call-back) follows his date; Done waits for you to check it. */
    private fun giverSide(c: Context, t: TeamTask, before: String?, given: MutableMap<String, String>) {
        val link = given[t.id]?.toLongOrNull()
        if (link != null) {
            val calls = ReminderStore.items.value.filter { it.linkId == link }
            val pending = calls.firstOrNull { !it.done }
            when {
                t.open && t.due > 0L -> {
                    if (pending == null && calls.isEmpty()) {
                        ToScheduleStore.items.value.firstOrNull { it.linkId == link }?.let { FollowUps.setTime(c, it, t.due) }
                    } else if (pending != null && pending.dueAt != t.due && (before == null || dueOf(before) != t.due)) {
                        ReminderStore.reschedule(pending.id, t.due, "${t.toFirst}'s date:")
                    }
                }
                // His Done waits in Home › Replied, check it (your call-back becomes the one to check).
                t.status == Team.DONE && statusOf(before) != Team.DONE -> {
                    if (pending != null) ReminderStore.markDone(pending.id, review = true)
                    else if (calls.none { it.review }) ToScheduleStore.items.value.firstOrNull { it.linkId == link }?.let { s ->
                        val r = ReminderStore.add(s.name, s.number, s.note, System.currentTimeMillis(), createdAs = "Given to ${t.toFirst}", starred = s.starred)
                        ReminderStore.setLink(r.id, link, false)
                        ReminderStore.markDone(r.id, review = true)
                        ToScheduleStore.remove(s.id)
                    }
                }
                t.status == Team.CHECKED || t.status == Team.CANCELLED || t.status == Team.RETURNED -> {
                    if (t.status != Team.RETURNED) calls.filter { !it.done }.forEach { ReminderStore.markDone(it.id) }
                    calls.filter { it.done && it.review }.forEach { ReminderStore.reviewed(it.id) }
                }
            }
        }
        val was = statusOf(before) ?: return
        when {
            was != t.status && t.status == Team.DONE ->
                notify(c, "done:${t.id}", "✅ ${t.toFirst}: Done · check it", t.title + if (t.passedTo.isNotBlank()) " (by ${t.passedTo})" else "", mine = null)
            moreOf(before) == 0L && t.moreAt > 0L ->
                notify(c, "ask:${t.id}", "${t.toFirst} asks for more time", "${t.title}\n${Fmt.dueLabel(t.moreDue)} · “${t.moreReason}”", mine = null)
            t.hisDate && (dueOf(before) ?: 0L) == 0L && t.due > 0L ->
                notify(c, "picked:${t.id}", "${t.toFirst} picked ${Fmt.dueLabel(t.due)}", t.title, mine = null)
        }
    }

    /** Urgent with no date → one notification; not seen for N working hours → one direct SMS. On the giver's phone. */
    private fun rules(c: Context, tasks: List<TeamTask>, me: String) {
        val urgentDone = TeamLinks.set(c, "urgent")
        val smsDone = TeamLinks.set(c, "sms")
        val now = System.currentTimeMillis()
        for (t in tasks.filter { it.fromUid == me && it.open }) {
            val waited = TeamRules.minutesSince(c, t.createdAt, now)
            if (t.urgent && t.due == 0L && t.id !in urgentDone && waited >= TeamRules.urgentMin(c)) {
                urgentDone += t.id
                notify(c, "urg:${t.id}", "🔴 ${t.toFirst} hasn't set a date", "${t.title}\nSet the date yourself in Team", mine = null)
            }
            val smsMin = TeamRules.smsMin(c)
            if (smsMin > 0 && t.status == Team.SENT && t.id !in smsDone && t.toPhone.isNotBlank() && waited >= smsMin) {
                smsDone += t.id
                if (SmsSender.hasPermission(c)) {
                    val ok = runCatching {
                        SmsSender.managerForDefaultSim(c).first.sendTextMessage(
                            t.toPhone, null, "New task from ${t.fromFirst}: ${t.title.take(90)}. Please open Callback Reminder.", null, null
                        )
                    }.isSuccess
                    if (ok) TeamRepo.note(t, "Not seen in ${TeamRules.label(smsMin)}: text sent to ${t.toFirst}")
                }
            }
        }
        TeamLinks.putSet(c, "urgent", urgentDone)
        TeamLinks.putSet(c, "sms", smsDone)
    }

    /** Your own lists changed: send his Done / his date up, and your check of a Done follow-up. */
    fun pushLocal(c: Context) {
        val me = TeamRepo.uid ?: return
        synchronized(lock) { pushLocked(c, me) }
    }

    private fun pushLocked(c: Context, me: String) {
        initStores(c)
        val recv = TeamLinks.map(c, "recv")
        val recvFu = TeamLinks.map(c, "recvFu")
        val given = TeamLinks.map(c, "given")
        var changed = false
        for (t in TeamRepo.tasks.value) {
            if (t.toUid == me) {
                val passed = recvFu[t.id]?.toLongOrNull()
                if (passed != null) {
                    val calls = ReminderStore.items.value.filter { it.linkId == passed }
                    val last = calls.maxByOrNull { it.id }
                    if (t.open && calls.isNotEmpty() && calls.all { it.done }) {
                        if (FollowUps.wasMoved(last, null)) {
                            // Moved back to My tasks: the link follows it there.
                            MyTaskStore.items.value.filter { !it.done && it.title == FollowUps.taskOf(last, null) }.maxByOrNull { it.id }?.let {
                                recv[t.id] = it.id.toString()
                                recvFu.remove(t.id)
                                changed = true
                            }
                        } else TeamRepo.markDone(t)
                    }
                    continue
                }
                val local = recv[t.id]?.toLongOrNull()?.let { MyTaskStore.get(it) } ?: continue
                when {
                    local.done && t.open && t.photoProof && t.proofAt == 0L -> {
                        // Photo proof was asked for: Done waits for the photo.
                        MyTaskStore.setDone(local.id, false)
                        notify(c, "proof:${t.id}", "📷 ${t.fromFirst} asked for a photo", "${t.title}\nOpen the task and tap ✅ Done with photo.", mine = local.id)
                    }
                    local.done && t.open -> TeamRepo.markDone(t)
                    t.open && local.dueAt != t.due -> {
                        val endOfDeadline = if (t.deadline > 0L) endOfDay(t.deadline) else Long.MAX_VALUE
                        if (t.hisDate && local.dueAt > 0L && local.dueAt <= endOfDeadline) {
                            TeamRepo.setHisDate(t, local.dueAt)
                        } else if (t.due > 0L || local.dueAt > 0L) {
                            // His date is fixed (or past the deadline): back to the agreed date.
                            MyTaskStore.setDue(local.id, t.due)
                            notify(c, "fixed:${t.id}", "Date stays ${if (t.due > 0L) Fmt.dueLabel(t.due) else "open"}",
                                "${t.title}\nLate? Tell ${t.fromFirst} in the Timeline.", mine = local.id)
                        }
                    }
                }
            }
            if (t.fromUid == me) {
                val link = given[t.id]?.toLongOrNull() ?: continue
                val calls = ReminderStore.items.value.filter { it.linkId == link }
                if (t.status == Team.DONE && calls.any { !it.done && it.events.any { e -> e.what == "Done, sent to To review" } }) {
                    // You gave his Done a new time in review: it goes back to him as not done yet.
                    TeamRepo.notDoneYet(t, "")
                } else if ((t.open || t.status == Team.DONE) && calls.isNotEmpty() && calls.all { it.done && !it.review } &&
                    !FollowUps.wasMoved(calls.maxByOrNull { it.id }, null)
                ) TeamRepo.check(t)
            }
        }
        if (changed) {
            TeamLinks.putMap(c, "recv", recv)
            TeamLinks.putMap(c, "recvFu", recvFu)
        }
    }

    private fun endOfDay(ms: Long): Long {
        val z = ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDate()
        return d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
    }

    // ------------------------------------------------------------ giving and passing down

    /**
     * The follow-up just saved for [number] about [task]: its link (made for one waiting in To schedule).
     * Used to tie it to the team task.
     */
    fun linkForNew(c: Context, number: String, task: String): Long {
        val key = task.trim()
        ReminderStore.items.value.filter { !it.done && it.linkId != 0L && it.note == key && SmsStore.sameNumber(it.number, number) }
            .maxByOrNull { it.id }?.let { return it.linkId }
        val item = ToScheduleStore.items.value.filter { it.note == key && SmsStore.sameNumber(it.number, number) }.maxByOrNull { it.id }
            ?: return 0L
        if (item.linkId != 0L) return item.linkId
        val link = FollowUps.newLink(c)
        ToScheduleStore.put(item.copy(linkId = link))
        return link
    }

    /**
     * New follow-up for a team member: the task goes online to his app and your own follow-up (call-back) follows
     * his date. With [myTime] it's his fixed date. Returns your follow-up's link, or null when it couldn't be sent.
     */
    fun giveFromDialog(
        c: Context, to: TeamMember, title: String, deadline: Long, urgent: Boolean, myTime: Long?, callOn: Boolean,
        photoProof: Boolean = false, brief: List<TeamAttach> = emptyList(),
    ): Long? {
        val id = TeamRepo.give(to, title, deadline, urgent, myTime, photoProof, brief) ?: return null
        val link = FollowUps.newLink(c)
        val item = ToScheduleStore.put(
            ToScheduleItem(id = 0L, name = to.name, number = to.phone, note = title.trim(), createdAt = 0L, callOn = callOn, starred = urgent, linkId = link)
        )
        if (myTime != null && myTime > 0L) FollowUps.setTime(c, item, myTime)
        synchronized(lock) {
            val given = TeamLinks.map(c, "given")
            given[id] = link.toString()
            TeamLinks.putMap(c, "given", given)
        }
        return link
    }

    /** His My task from the team was moved to Follow-ups: the link moves with it, and the giver sees who does it. */
    fun onMovedToFollowUps(c: Context, myTaskId: Long, link: Long, toName: String) {
        if (link == 0L) return
        synchronized(lock) {
            val recv = TeamLinks.map(c, "recv")
            val taskId = recv.entries.firstOrNull { it.value == myTaskId.toString() }?.key ?: return
            recv.remove(taskId)
            val recvFu = TeamLinks.map(c, "recvFu")
            recvFu[taskId] = link.toString()
            TeamLinks.putMap(c, "recv", recv)
            TeamLinks.putMap(c, "recvFu", recvFu)
            TeamRepo.tasks.value.firstOrNull { it.id == taskId }?.let { TeamRepo.passedDown(it, Names.first(toName) ?: toName) }
        }
    }

    /**
     * A member was approved: your open follow-ups with his number move to the team (your own times become his fixed
     * dates; ones waiting in To schedule wait for his date). Their texts stop, the app tells him instead.
     */
    fun moveOpenFollowUps(c: Context, m: TeamMember): Int {
        if (m.phone.isBlank()) return 0
        initStores(c)
        var n = 0
        synchronized(lock) {
            val given = TeamLinks.map(c, "given")
            val taken = given.values.toSet()
            ReminderStore.items.value
                .filter { !it.done && !it.review && it.repeat == null && it.number.isNotBlank() && SmsStore.sameNumber(it.number, m.phone) }
                .forEach { r ->
                    val link = FollowUps.ensureLink(c, r, FollowUps.text(r.linkId))
                    if (link.toString() in taken) return@forEach
                    val id = TeamRepo.give(m, r.note.ifBlank { "Call back" }, 0L, r.starred, r.dueAt) ?: return@forEach
                    FollowUps.stopTexts(link)
                    given[id] = link.toString()
                    n++
                }
            ToScheduleStore.items.value
                .filter { it.number.isNotBlank() && SmsStore.sameNumber(it.number, m.phone) }
                .forEach { item ->
                    val link = item.linkId.takeIf { it != 0L } ?: FollowUps.newLink(c).also { ToScheduleStore.put(item.copy(linkId = it, textOn = false)) }
                    if (link.toString() in taken) return@forEach
                    val id = TeamRepo.give(m, item.headline.ifBlank { "Call back" }, 0L, item.starred, null) ?: return@forEach
                    given[id] = link.toString()
                    n++
                }
            TeamLinks.putMap(c, "given", given)
        }
        return n
    }

    /** Remove member: your follow-up for his open task comes back to To schedule (still yours). */
    fun returnToMe(c: Context, t: TeamTask) {
        initStores(c)
        synchronized(lock) {
            val given = TeamLinks.map(c, "given")
            val link = given.remove(t.id)?.toLongOrNull() ?: return
            TeamLinks.putMap(c, "given", given)
            val call = ReminderStore.items.value.firstOrNull { it.linkId == link && !it.done }
            if (call != null) FollowUps.removeTime(c, call, FollowUps.text(link))
        }
    }

    // ------------------------------------------------------------ his day

    /** His day for the people above him: today's tasks (not 🔒 private), follow-ups and dossier ticks. */
    fun dayMap(c: Context, managers: List<String>): Map<String, Any> {
        initStores(c)
        val z = ZoneId.systemDefault()
        val today = LocalDate.now()
        val end = today.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val start = today.atStartOfDay(z).toInstant().toEpochMilli()
        val hidden = TeamLinks.set(c, "private")
        val recv = TeamLinks.map(c, "recv").entries.associate { it.value to it.key }
        val fromOf = TeamRepo.tasks.value.associateBy { it.id }
        val tasks = MyTaskStore.items.value
            .filter { it.id.toString() !in hidden }
            .filter { (!it.done && it.dueAt < end) || (it.done && it.doneAt >= start) }
            .sortedWith(compareBy({ it.done }, { if (it.dueAt == 0L) Long.MAX_VALUE else it.dueAt }))
            .take(60)
            .map { t ->
                mapOf(
                    "title" to t.title, "due" to t.dueAt, "done" to t.done, "doneAt" to t.doneAt,
                    "from" to (recv[t.id.toString()]?.let { fromOf[it]?.fromFirst } ?: ""),
                )
            }
        val fus = ReminderStore.items.value
            .filter { (!it.done && it.dueAt < end) || (it.done && it.doneAt >= start && !it.review) }
            .sortedWith(compareBy({ it.done }, { it.dueAt }))
            .take(60)
            .map { r -> mapOf("title" to r.note.ifBlank { "Call back" }, "name" to r.title, "due" to r.dueAt, "done" to r.done, "doneAt" to r.doneAt) }
        val open = Dossier.openDay(System.currentTimeMillis())
        val dossier = DossierStore.todays().map { d ->
            val rec = DossierStore.record(d.id, open)
            mapOf(
                "title" to d.title, "time" to d.rangeLabel, "frequency" to d.frequency, "doneAt" to (rec?.doneAt ?: 0L),
                "reason" to (rec?.reason ?: ""), "due" to d.dueAt(open), "date" to open.toString(),
                "blockId" to (TeamDossier.blockOf(c, d.id) ?: ""), "proof" to TeamDossier.needsProof(c, d.id),
                "photo" to TeamDossier.sent(c, d.id, open, TeamDossier.PHOTO), "voice" to TeamDossier.sent(c, d.id, open, TeamDossier.VOICE),
            )
        }
        return mapOf(
            "managers" to managers,
            "updatedAt" to System.currentTimeMillis(),
            "tasks" to tasks,
            "followUps" to fus,
            "dossier" to dossier,
            "privateCount" to TeamLinks.privateCount(c),
        )
    }

    // ------------------------------------------------------------ notifications

    private fun notify(c: Context, key: String, title: String, text: String, mine: Long?, taskId: String? = null) {
        val intent = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (taskId != null) intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TEAM).putExtra(MainActivity.EXTRA_TEAM_TASK, taskId)
        else if (mine != null) intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MY_TASKS).putExtra(MainActivity.EXTRA_TASK, mine)
        else intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TEAM)
        val open = PendingIntent.getActivity(c, NotifIds.team(key), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(c, Notifier.bannerChannel(c))
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("Team")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        Notify.post(c, NotifType.TEAM, NotifIds.team(key), b)
    }
}

/** The 15-minute check while the app is closed (the free plan has no instant push). */
class TeamWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { TeamRepo.fetchOnce(applicationContext) }
            .onFailure { CrashLog.record(applicationContext, it, "Team check") }
        return Result.success()
    }

    companion object {
        private const val NAME = "team_check"

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<TeamWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(c: Context) {
            runCatching { WorkManager.getInstance(c).cancelUniqueWork(NAME) }
        }
    }
}
