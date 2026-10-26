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

    /** You opened the task's Timeline up to [at]: its blue line and its notification go. */
    fun markRead(c: Context, taskId: String, at: Long) {
        val t = TeamRepo.tasks.value.firstOrNull { it.id == taskId }
        val m = TeamLinks.map(c, "read")
        val n = TeamLinks.map(c, "readN")
        val others = t?.msgsFromOthers(TeamRepo.uid) ?: 0L
        clearTold(c, taskId)
        if ((m[taskId]?.toLongOrNull() ?: 0L) >= at && (n[taskId]?.toLongOrNull() ?: 0L) >= others) return
        m[taskId] = maxOf(at, m[taskId]?.toLongOrNull() ?: 0L).toString()
        n[taskId] = others.toString()
        TeamLinks.putMap(c, "read", m)
        TeamLinks.putMap(c, "readN", n)
        version.value++
    }

    /** A message from someone else you haven't opened yet. */
    fun unread(c: Context, t: TeamTask, me: String?): Boolean =
        t.lastMsgAt > 0L && t.lastMsgBy != me && t.lastMsgAt > (TeamLinks.map(c, "read")[t.id]?.toLongOrNull() ?: 0L)

    /** How many messages from others came since you last opened the Timeline (at least 1 while one is unread). */
    fun unreadCount(c: Context, t: TeamTask, me: String?): Int {
        if (!unread(c, t, me)) return 0
        val seen = TeamLinks.map(c, "readN")[t.id]?.toLongOrNull() ?: 0L
        return maxOf(1L, t.msgsFromOthers(me) - seen).toInt()
    }

    /** His side: a task given to you that you haven't opened yet ("📥 New from Jahanzaib"). */
    fun isNew(c: Context, taskId: String): Boolean = taskId !in TeamLinks.set(c, "opened")

    fun markOpened(c: Context, taskId: String) {
        // Seen only when he opens the card in place, its Timeline, or taps its notification (that opens the Timeline).
        TeamRepo.tasks.value.firstOrNull { it.id == taskId && it.toUid == TeamRepo.uid && it.seenAt == 0L }?.let { TeamRepo.markSeen(it) }
        val s = TeamLinks.set(c, "opened")
        if (taskId in s) return
        s += taskId
        TeamLinks.putSet(c, "opened", s)
        version.value++
    }
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
    fun apply(c: Context, tasks: List<TeamTask>, foreground: Boolean, complete: Boolean = true) {
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
                // A new message from someone else on the task: ❓ and ⚠ on their own, the rest in the task's one notification.
                if (before != null && t.lastMsgAt > msgOf(before) && t.lastMsgBy != me && t.lastMsgBy.isNotEmpty()) {
                    val who = TeamRepo.member(t.lastMsgBy)?.first ?: if (t.lastMsgBy == t.fromUid) t.fromFirst else t.toFirst
                    when {
                        t.stuck && t.stuckAt == t.lastMsgAt ->
                            notify(c, "stuck:${t.id}", "⚠ $who is stuck · ${t.title}".take(80), t.lastMsgText, mine = null, taskId = t.id)
                        t.asks && t.askAt == t.lastMsgAt ->
                            notify(c, "ask:${t.id}", "❓ $who asks · ${t.title}".take(80), t.lastMsgText, mine = null, taskId = t.id)
                        else -> tell(c, t, who, "💬 ${t.lastMsgText}")
                    }
                }
                if (t.lastMsgBy != me && t.lastMsgAt > (t.msgDel[me] ?: 0L)) TeamRepo.markMsgDelivered(t)
                last[t.id] = sig(t)
            }
            // Tasks that left his list (read from the server): the giver gave them to someone else.
            val ids = tasks.map { it.id }.toSet()
            if (complete) recv.keys.filter { it !in ids }.forEach { id ->
                recv.remove(id)?.toLongOrNull()?.let { MyTaskStore.get(it) }?.takeIf { !it.done }?.let { local ->
                    MyTaskStore.delete(local.id)
                    clearTold(c, id)
                    notify(c, "gone:$id", "Task given to someone else", local.title, mine = null)
                }
            }
            if (complete) recvFu.keys.filter { it !in ids }.forEach { id ->
                recvFu.remove(id)?.toLongOrNull()?.let { link -> FollowUps.finishCall(link); FollowUps.stopTexts(link) }
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
                    // His reminder rings at the deadline until he sets his own: then only he moves it.
                    // A date he picks himself never sets his reminder: he taps ⏰ My reminder.
                    if (local.dueAt != t.due && !(t.hisDate && local.dueAt == 0L) && ((before == null && local.dueAt == 0L) || (remoteMoved && (local.dueAt == 0L || local.dueAt == dueOf(before)))))
                        MyTaskStore.setDue(local.id, t.due)
                }
            }
            t.status == Team.CANCELLED || t.status == Team.RETURNED -> {
                local?.let { if (!it.done) MyTaskStore.delete(it.id) }
                recv.remove(t.id)
            }
            t.status == Team.DONE || t.status == Team.CHECKED -> local?.let { if (!it.done) MyTaskStore.setDone(it.id, true) }
        }
        if (t.open && t.deliveredAt == 0L) TeamRepo.markDelivered(t)

        // What he hears about: all in the task's one notification.
        val was = statusOf(before)
        val from = t.fromFirst
        when {
            before == null && t.open && t.status != Team.REOPENED ->
                tell(c, t, from, (if (t.urgent) "🔴 Urgent · " else "") + if (t.due > 0L) "📅 Deadline ${TeamUi.shortWhen(t.due)}"
                    else "📅 Pick your deadline" + if (t.deadline > 0L) " · Latest ${TeamUi.shortWhen(t.deadline)}" else "",
                    head = (if (t.urgent) "🔴 Urgent task" else "New task") + " from $from")
            was != null && was != t.status && t.status == Team.REOPENED ->
                tell(c, t, from, "↩ Not done yet" + (if (t.reason.isBlank()) "" else " · “${t.reason}”") + if (t.due == 0L) " · pick a new deadline" else "")
            was != null && was != t.status && t.status == Team.CHECKED -> tell(c, t, from, "✓ Received, thanks")
            was != null && was != t.status && t.status == Team.CANCELLED -> {
                clearTold(c, t.id)
                notify(c, "x:${t.id}", "Task removed by $from", t.title, mine = null)
            }
            moreOf(before) > 0L && t.moreAt == 0L && t.due == t.moreDue -> tell(c, t, from, "📅 Approved · Deadline ${TeamUi.shortWhen(t.due)}")
            moreOf(before) > 0L && t.moreAt == 0L && dueOf(before) == t.due -> tell(c, t, from, "📅 Kept the deadline · ${TeamUi.shortWhen(t.due)}")
            before != null && remoteMoved && t.dateBy == Team.GIVER_DATE && t.moreAt == 0L && moreOf(before) == 0L && t.due > 0L ->
                tell(c, t, from, "📅 Deadline set · ${TeamUi.shortWhen(t.due)}")
        }
    }

    /** Your side: your follow-up (call-back) follows his date; Done waits for you to check it. */
    private fun giverSide(c: Context, t: TeamTask, before: String?, given: MutableMap<String, String>) {
        val link = given[t.id]?.toLongOrNull()
        if (link != null) {
            val calls = ReminderStore.items.value.filter { it.linkId == link }
            val pending = calls.firstOrNull { !it.done }
            // Your follow-up for it was deleted on this phone (an older version, or a path that didn't ask): it goes
            // for him too. A Done one is checked.
            val orphan = calls.isEmpty() && ToScheduleStore.items.value.none { it.linkId == link } &&
                SmsStore.items.value.none { it.linkId == link } && System.currentTimeMillis() - t.createdAt > 2 * 60_000L
            if (orphan && (t.open || t.status == Team.DONE)) {
                if (t.open) TeamRepo.cancel(t) else TeamRepo.check(t)
                given.remove(t.id)
                return
            }
            when {
                // He has to pick a new deadline (Not done yet · He picks): your call-back waits at your Latest.
                t.open && t.due == 0L && pending != null && (dueOf(before) ?: 0L) > 0L && t.deadline > 0L && pending.dueAt != t.deadline ->
                    ReminderStore.reschedule(pending.id, t.deadline, "Waiting for ${t.toFirst}'s deadline:")
                // Older versions parked it in To schedule: it moves to Follow-ups.
                t.open && t.due == 0L && calls.isEmpty() ->
                    ToScheduleStore.items.value.firstOrNull { it.linkId == link && it.callOn && it.number.isNotBlank() }
                        ?.let { FollowUps.setTime(c, it, waitAt(c, t.deadline)) }
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
        val who = t.toFirst
        when {
            was != t.status && t.status == Team.DONE ->
                tell(c, t, who, "✅ Done, check it" + if (t.passedTo.isNotBlank()) " (by ${t.passedTo})" else "")
            moreOf(before) == 0L && t.moreAt > 0L ->
                notify(c, "more:${t.id}", "📅 $who asks to extend · ${t.title}".take(80),
                    "${TeamUi.shortWhen(t.due)} → ${TeamUi.shortWhen(t.moreDue)}" + if (t.moreReason.isBlank()) "" else " · “${t.moreReason}”", mine = null, taskId = t.id)
            t.hisDate && (dueOf(before) ?: 0L) == 0L && t.due > 0L -> tell(c, t, who, "📅 Deadline set · ${TeamUi.shortWhen(t.due)}")
            (dueOf(before) ?: 0L) > 0L && t.due > 0L && dueOf(before) != t.due && moreOf(before) == 0L && t.dateBy == Team.HIS_DATE ->
                tell(c, t, who, "📅 Deadline ${TeamUi.shortWhen(dueOf(before)!!)} → ${TeamUi.shortWhen(t.due)}")
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
                    // His reminder is his own (a line on the Timeline); it never moves the deadline.
                    t.open && t.due > 0L -> TeamRepo.setReminder(t, if (local.dueAt == t.due) 0L else local.dueAt)
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

    /** Where your follow-up waits until he picks his deadline: your Latest, else the start of the next working day. */
    private fun waitAt(c: Context, latest: Long): Long =
        latest.takeIf { it > System.currentTimeMillis() } ?: ReviewData.nextWorkStart(c)

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
        // A given task always lives in Follow-ups: his date not picked yet, it waits at your Latest.
        FollowUps.setTime(c, item, myTime?.takeIf { it > 0L } ?: waitAt(c, deadline))
        synchronized(lock) {
            val given = TeamLinks.map(c, "given")
            given[id] = link.toString()
            TeamLinks.putMap(c, "given", given)
        }
        return link
    }

    /**
     * His My task from the team was moved to Follow-ups: a true move. The same task (Timeline, photos, the locked
     * deadline) now follows his follow-up, and the giver sees who does it and when he calls them.
     */
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
            val callAt = ReminderStore.items.value.firstOrNull { it.linkId == link && !it.done }?.dueAt ?: 0L
            TeamRepo.tasks.value.firstOrNull { it.id == taskId }?.let { TeamRepo.passedDown(it, Names.first(toName) ?: toName, callAt) }
        }
        version.value++
    }

    /**
     * Your Not done yet on his Done: your call-back leaves Replied. With [newDue] it rings then; with 0 (he picks)
     * it waits in Follow-ups at your Latest ("⏳ His deadline?") until he sets one.
     */
    fun giverNotDone(c: Context, t: TeamTask, newDue: Long) {
        initStores(c)
        val link = TeamLinks.map(c, "given")[t.id]?.toLongOrNull() ?: return
        val call = ReminderStore.items.value.filter { it.linkId == link }.let { l -> l.firstOrNull { !it.done } ?: l.maxByOrNull { it.id } } ?: return
        if (newDue > 0L) ReminderStore.reschedule(call.id, newDue, "Not done yet · new deadline")
        else {
            if (call.done) ReminderStore.reopen(call.id)
            ReminderStore.reschedule(call.id, waitAt(c, t.deadline), "Not done yet · waiting for ${t.toFirst}'s deadline")
        }
    }

    /** Remove member › give his tasks to someone else: your follow-up now names the new person. */
    fun relinkPerson(c: Context, t: TeamTask, to: TeamMember) {
        initStores(c)
        val link = TeamLinks.map(c, "given")[t.id]?.toLongOrNull() ?: return
        ReminderStore.items.value.filter { it.linkId == link && !it.done }.forEach { r ->
            ReminderStore.edit(r.id, to.name, to.phone, r.note, r.dueAt, r.repeat)
        }
        ToScheduleStore.items.value.filter { it.linkId == link }.forEach { ToScheduleStore.put(it.copy(name = to.name, number = to.phone)) }
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

    /**
     * The task's one notification, updated with every new Timeline line: "Basharat · 3 new on Timeline" and the
     * newest lines. Opening the Timeline clears it. ❓ Ask, ⚠ Stuck and 📅 extend requests come on their own.
     */
    private fun tell(c: Context, t: TeamTask, who: String, line: String, head: String? = null) {
        val counts = TeamLinks.map(c, "nc")
        val lines = TeamLinks.map(c, "nl")
        val n = (counts[t.id]?.toIntOrNull() ?: 0) + 1
        val all = (lines[t.id]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()) + line.lineSequence().first().take(100)
        counts[t.id] = n.toString()
        lines[t.id] = all.takeLast(5).joinToString("\n")
        TeamLinks.putMap(c, "nc", counts)
        TeamLinks.putMap(c, "nl", lines)
        val title = if (n == 1 && head != null) head else "$who · $n new on Timeline"
        notify(c, "tl:${t.id}", title, t.title + " · " + all.last() + "\n" + all.dropLast(1).reversed().joinToString("\n"), mine = null, taskId = t.id)
    }

    /** The Timeline was opened (or the task went): its notifications go and the count starts again. */
    fun clearTold(c: Context, taskId: String) {
        val counts = TeamLinks.map(c, "nc")
        val lines = TeamLinks.map(c, "nl")
        if (counts.remove(taskId) != null) TeamLinks.putMap(c, "nc", counts)
        if (lines.remove(taskId) != null) TeamLinks.putMap(c, "nl", lines)
        val nm = androidx.core.app.NotificationManagerCompat.from(c)
        listOf("tl:", "ask:", "stuck:", "more:").forEach { nm.cancel(NotifIds.team(it + taskId)) }
    }

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
