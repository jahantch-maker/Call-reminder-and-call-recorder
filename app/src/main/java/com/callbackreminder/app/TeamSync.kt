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
        OwnShare.changed(c)
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
            // Plot files: staff never open the New plot file screen, so the SOP is read here once a plot step reaches the phone.
            if (PlotFiles.sop.value == null && tasks.any { it.plot && it.toUid == me }) TeamRepo.scope.launch { PlotFiles.loadSop() }
            for (t in tasks) {
                val before = last[t.id]
                // Plot files: the step's due time follows the doer's own working hours and off day.
                if (t.plot && t.toUid == me && t.open && !t.dueSet) TeamRepo.setPlotDue(t, PlotFiles.dueOf(c, t.timer, t.days, t.hours, t.createdAt))
                // Plot files: CC on this step (SOP Notify columns): one notification for Started, Late and Done.
                PlotUi.ccStatus(t, me, System.currentTimeMillis())?.let { st ->
                    val told = TeamLinks.set(c, "ccTold")
                    if (told.add("${t.id}:$st")) {
                        TeamLinks.putSet(c, "ccTold", told)
                        notify(c, "cc:${t.id}", "👁 Plot ${t.plotNo} · Step ${t.step.substringAfter('.')} · $st", "${t.title}\n${t.toFirst}", mine = null, taskId = t.id,
                            kind = NotifKind.FOLLOW_UPS, open = Open.TIMELINE, web = WebRules.N_PLOT_FOLLOW)
                    }
                }
                if (t.toUid == me) receive(c, t, before, foreground, recv, recvFu)
                if (t.fromUid == me) giverSide(c, t, before, given)
                // ✓ Done, ➜ next part: your part was received and the task went on to someone else. Told once;
                // your own copy stays done in your history.
                if (t.partFrom == me && t.toUid != me && t.fromUid != me) {
                    val told = TeamLinks.set(c, "partTold")
                    if (t.id !in told) {
                        told += t.id
                        TeamLinks.putSet(c, "partTold", told)
                        notify(c, "part:${t.id}", "✓ ${t.fromFirst}: Received, thanks", t.title, mine = null, taskId = t.id,
                            kind = NotifKind.MY_TASKS, open = Open.TIMELINE, web = WebRules.N_DONE_CHECK)
                    }
                }
                // A new message from someone else on the task: ❓ and ⚠ on their own, the rest in the task's one notification.
                if (before != null && t.lastMsgAt > msgOf(before) && t.lastMsgBy != me && t.lastMsgBy.isNotEmpty()) {
                    val who = TeamRepo.member(t.lastMsgBy)?.first ?: if (t.lastMsgBy == t.fromUid) t.fromFirst else t.toFirst
                    when {
                        t.stuck && t.stuckAt == t.lastMsgAt ->
                            notify(c, "stuck:${t.id}", "⚠ $who is stuck · ${t.title}".take(80), t.lastMsgText, mine = null, taskId = t.id, kind = NotifKind.STUCK)
                        else -> tell(c, t, who, "💬 ${t.lastMsgText}", web = if (mentionsMe(t.lastMsgText)) WebRules.N_MENTION else WebRules.N_TIMELINE)
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
                    notify(c, "gone:$id", "Task given to someone else", local.title, mine = null, kind = NotifKind.MY_TASKS, web = WebRules.N_NEW_TASK)
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
            // Not done yet on a task he passed to someone outside the team: his finished call with them comes back,
            // or his sync would see it done and mark the task done again at once.
            if (before != null && t.status == Team.REOPENED && statusOf(before) != Team.REOPENED) {
                ReminderStore.items.value.filter { it.linkId == passed }.maxByOrNull { it.id }?.takeIf { it.done }?.let { last ->
                    ReminderStore.reopen(last.id)
                    if (t.due > 0L) ReminderStore.reschedule(last.id, t.due, "Not done yet · ${t.fromFirst}:")
                }
            }
            if (t.status == Team.CANCELLED || t.status == Team.RETURNED) {
                FollowUps.finishCall(passed)
                FollowUps.stopTexts(passed)
                recvFu.remove(t.id)
            }
        } else when {
            t.open -> {
                if (local == null) {
                    val made = MyTaskStore.add(
                        title = t.title, note = "", dueAt = if (t.plot) t.due else 0L, name = "", number = "",
                        starred = t.urgent,
                        // 27 Nov (S2.1) A plot step also gets the office's heads-up before it is due (default 1 hour).
                        remindBefore = if (t.plot && WebRules.style(c, WebRules.N_PLOT_HEADS) != WebRules.OFF) listOf(WebRules.int(c, "plotHeadsMin")).filter { it > 0 } else emptyList(),
                    )
                    recv[t.id] = made.id.toString()
                    if (t.plot) TeamLinks.putSet(c, "plotLocal", TeamLinks.set(c, "plotLocal") + made.id.toString())
                } else {
                    // Plot files: the step's due time is its time on this phone (Overdue, Next up, the alarm).
                    if (t.plot && !local.done && t.due > 0L && local.dueAt != t.due) MyTaskStore.setDue(local.id, t.due)
                    if (local.done && t.status == Team.REOPENED) MyTaskStore.setDone(local.id, false)
                    if (urgentOf(before)?.let { it != t.urgent } == true && local.starred != t.urgent) MyTaskStore.setStar(local.id, t.urgent)
                    // His task waits in To schedule until he sets his own reminder (⏰ My reminder): a deadline never
                    // sets it. Not done yet clears his old reminder too, so he sets it again.
                    if (t.status == Team.REOPENED && statusOf(before) != Team.REOPENED && local.dueAt != 0L)
                        MyTaskStore.setDue(local.id, 0L)
                }
            }
            t.status == Team.CANCELLED || t.status == Team.RETURNED -> {
                local?.let { if (!it.done) MyTaskStore.delete(it.id) }
                recv.remove(t.id)
            }
            t.status == Team.DONE || t.status == Team.CHECKED -> local?.let { if (!it.done) MyTaskStore.setDone(it.id, true, force = true) }
        }
        if (t.open && t.deliveredAt == 0L) TeamRepo.markDelivered(t)

        // What he hears about: all in the task's one notification.
        val was = statusOf(before)
        val from = t.fromFirst
        when {
            before == null && t.open && t.status != Team.REOPENED ->
                tell(c, t, from, (if (t.urgent) "🔴 Urgent · " else "") + if (t.due > 0L) "📅 Deadline ${TeamUi.shortWhen(t.due)}"
                    else "📅 Pick your deadline" + if (t.deadline > 0L) " · Latest ${TeamUi.shortWhen(t.deadline)}" else "",
                    head = (if (t.urgent) "🔴 Urgent task" else "New task") + " from $from", web = WebRules.N_NEW_TASK)
            was != null && was != t.status && t.status == Team.REOPENED ->
                tell(c, t, from, "↩ Not done yet" + (if (t.reason.isBlank()) "" else " · “${t.reason}”") + if (t.due == 0L) " · pick a new deadline" else "")
            was != null && was != t.status && t.status == Team.CHECKED -> tell(c, t, from, "✓ Received, thanks")
            was != null && was != t.status && t.status == Team.CANCELLED -> {
                clearTold(c, t.id)
                notify(c, "x:${t.id}", "Task removed by $from", t.title, mine = null, kind = NotifKind.MY_TASKS, web = WebRules.N_NEW_TASK)
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
                // He has to pick a (new) deadline: your call-back has no real time yet, so it waits in To schedule
                // until he picks (older versions left it in Follow-ups at your Latest).
                t.open && t.due == 0L && pending != null &&
                    ((dueOf(before) ?: 0L) > 0L || (t.deadline > 0L && pending.dueAt == t.deadline)) ->
                    waitForHim(c, pending, t.toFirst)
                t.open && t.due > 0L -> {
                    // Waiting in To schedule (his date, or after Not done yet): it follows his deadline now.
                    // Only when his deadline is new, so a time you took away yourself stays away.
                    if (pending == null) {
                        if (before == null || dueOf(before) != t.due || calls.isEmpty())
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
            // On its own, with ✓ Received: tapping it opens the card in Needs you › Replied.
            was != t.status && t.status == Team.DONE ->
                notify(c, "done:${t.id}", "✅ $who: Done, check it" + if (t.passedTo.isNotBlank()) " (by ${t.passedTo})" else "",
                    t.title, mine = null, taskId = t.id, kind = NotifKind.DONE_CHECK, open = Open.CHECK, web = WebRules.N_DONE_CHECK)
            was == Team.DONE && t.status != Team.DONE ->
                androidx.core.app.NotificationManagerCompat.from(c).cancel(NotifIds.team("done:${t.id}"))
            moreOf(before) == 0L && t.moreAt > 0L ->
                notify(c, "more:${t.id}", "📅 $who asks to extend · ${t.title}".take(80),
                    "${TeamUi.shortWhen(t.due)} → ${TeamUi.shortWhen(t.moreDue)}" + if (t.moreReason.isBlank()) "" else " · “${t.moreReason}”", mine = null, taskId = t.id, kind = NotifKind.ASKED, web = WebRules.N_EXTEND)
            t.hisDate && (dueOf(before) ?: 0L) == 0L && t.due > 0L -> tell(c, t, who, "📅 Deadline set · ${TeamUi.shortWhen(t.due)}")
            (dueOf(before) ?: 0L) > 0L && t.due > 0L && dueOf(before) != t.due && moreOf(before) == 0L && t.dateBy == Team.HIS_DATE ->
                tell(c, t, who, "📅 Deadline ${TeamUi.shortWhen(dueOf(before)!!)} → ${TeamUi.shortWhen(t.due)}")
        }
    }

    /** Urgent with no date → one notification; not seen for N working hours → one direct SMS. On the giver's phone. */
    private fun rules(c: Context, tasks: List<TeamTask>, me: String) {
        val urgentDone = TeamLinks.set(c, "urgent")
        val smsDone = TeamLinks.set(c, "sms")
        // Not opened: "sent texts|time of the last step" per task (1 = first SMS, 2 = second, 3 = you were told).
        val stage = TeamLinks.map(c, "smsStage")
        val now = System.currentTimeMillis()
        // v0.55 Last reminder per task for "no deadline yet": "g:<id>" on your phone (you gave it), "h:<id>" on his.
        val chase = TeamLinks.map(c, "chaseAt")
        val chaseOnce = TeamLinks.set(c, "chaseOnce")
        // 27 Nov (S11.1) Only inside the notification window (30 min before duty to 1 hour after): nothing at night.
        val inHours = !TeamRules.quietNow(c, now)
        // 27 Nov (S6.2) Urgent with no date: the receiver's own web rule (after N working hours, N times, same gap).
        val urgentDue = mutableListOf<TeamTask>()
        // Plot step tasks have their own rules (the SOP's time allowed): no "no date" reminders or SMS.
        for (t in tasks.filter { it.fromUid == me && it.open && !it.plot }) {
            val waited = TeamRules.minutesSince(c, t.createdAt, now)
            if (t.urgent && t.due == 0L && !t.stuck && inHours) {
                val u = TeamRules.urgentOf(TeamRepo.member(t.toUid)?.setPerms)
                val k = "gu:${t.id}"
                val n = chase[k]?.toIntOrNull() ?: 0
                if (n < u.times && waited >= (n + 1L) * u.gapMin) {
                    chase[k] = (n + 1).toString()
                    urgentDone += t.id
                    urgentDue += t
                    // Backup only: the task never reached his phone, so a text goes from your SIM (S6.2).
                    if (t.deliveredAt == 0L && t.toPhone.isNotBlank() && "urgsms:${t.id}" !in smsDone && SmsSender.hasPermission(c)) {
                        val ok = runCatching {
                            SmsSender.managerForDefaultSim(c).first.sendTextMessage(t.toPhone, null,
                                "Urgent task from ${t.fromFirst}: ${t.title.take(90)}. Please open RB360 and set your deadline.", null, null)
                        }.isSuccess
                        if (ok) {
                            smsDone += "urgsms:${t.id}"
                            TeamRepo.note(t, "Urgent, not on his phone yet: text sent to ${t.toFirst}")
                        }
                    }
                }
            }
            if (inHours) smsRule(c, t, waited, now, smsDone, stage)
            deadlineSms(c, t, waited, now, inHours, smsDone)
        }
        // Several at once: one notification.
        when (urgentDue.size) {
            0 -> Unit
            1 -> urgentDue[0].let { t -> notify(c, "urg:${t.id}", "🔴 ${t.toFirst} hasn't set a date", "${t.title}\nSet the date yourself in Team",
                mine = null, taskId = t.id, open = Open.CARD, web = WebRules.N_NO_DATE) }
            else -> notify(c, "urg:many", "🔴 ${urgentDue.size} urgent tasks still have no date · " + urgentDue.map { it.toFirst }.distinct().joinToString(", "),
                urgentDue.joinToString("\n") { "${it.toFirst}: ${it.title}" }, mine = null, open = Open.CARD, web = WebRules.N_NO_DATE)
        }
        // His phone: an urgent task waiting for his deadline reminds him with his web rule; a normal one once, the next morning.
        val mine = TeamRules.urgentMine(c)
        val pickDue = mutableListOf<TeamTask>()
        for (t in tasks.filter { it.toUid == me && it.open && !it.plot && it.hisDate && it.due == 0L && !it.stuck }) {
            if (t.urgent) {
                val k = "hu:${t.id}"
                val n = chase[k]?.toIntOrNull() ?: 0
                if (inHours && mine.style != WebRules.OFF && n < mine.times && TeamRules.minutesSince(c, t.createdAt, now) >= (n + 1L) * mine.gapMin) {
                    chase[k] = (n + 1).toString()
                    pickDue += t
                }
            } else if ("pick:${t.id}" !in chaseOnce && inHours && now >= ReviewData.nextWorkStart(c, t.createdAt)) {
                chaseOnce += "pick:${t.id}"
                notify(c, "pick:${t.id}", "📅 Pick your deadline · ${t.title}".take(80), "From ${t.fromFirst}",
                    mine = null, taskId = t.id, kind = NotifKind.MY_TASKS, open = Open.TIMELINE, web = WebRules.N_NO_DATE)
            }
        }
        if (pickDue.isNotEmpty()) {
            val one = pickDue.singleOrNull()
            val title = one?.let { "🔴 Pick your deadline · ${it.title}".take(80) } ?: "🔴 ${pickDue.size} urgent tasks need your deadline"
            val text = one?.let { "From ${it.fromFirst}. It is urgent: set your deadline now." } ?: pickDue.joinToString("\n") { "${it.title} · from ${it.fromFirst}" }
            notify(c, one?.let { "pick:${it.id}" } ?: "pick:many", title, text, mine = null, taskId = one?.id, kind = NotifKind.MY_TASKS,
                open = Open.TIMELINE, web = WebRules.N_NO_DATE, style = mine.style)
        }
        // Plot files: a step waiting for the plot owner whose SOP says "every working day": one reminder a working day, in working hours.
        for (t in tasks.filter { it.toUid == me && it.plot && it.open && it.waitAt > 0 }) {
            val every = PlotFiles.sop.value?.step(t.step)?.reminder.orEmpty()
            if (!Regex("every|working day|daily", RegexOption.IGNORE_CASE).containsMatchIn(every)) continue
            val k = "pw:${t.id}"
            val last = chase[k]?.toLongOrNull() ?: t.waitAt
            if (inHours && now - last >= 20 * 3600_000L) {
                chase[k] = now.toString()
                notify(c, "pw:${t.id}", "⏳ Plot ${t.plotNo} is still waiting for the plot owner · 📞 Call", "${t.title}\nStep ${t.step}",
                    mine = null, taskId = t.id, kind = NotifKind.MY_TASKS, open = Open.CARD, call = t.ownerWa, web = WebRules.N_PLOT_WAIT)
            }
        }
        val live = tasks.filter { it.open }.map { it.id }.toSet()
        TeamLinks.putMap(c, "chaseAt", chase.filterKeys { k -> k.substringAfter(':') in live })
        TeamLinks.putSet(c, "chaseOnce", chaseOnce.filter { k -> k.substringAfter(':') in live }.toSet())
        TeamLinks.putSet(c, "urgent", urgentDone)
        TeamLinks.putSet(c, "sms", smsDone)
        TeamLinks.putMap(c, "smsStage", stage.filterKeys { k -> tasks.any { it.id == k && it.open } })
        if (inHours) deliverHeld(c)
    }

    /**
     * v0.55 He opened the task but still has no deadline: one SMS to him, after the second reminder went unanswered
     * (urgent: two reminder gaps, normal: a working day). Inside working hours only. The reminders themselves carry on.
     */
    private fun deadlineSms(c: Context, t: TeamTask, waited: Long, now: Long, inHours: Boolean, smsDone: MutableSet<String>) {
        if (!t.hisDate || t.due != 0L || t.stuck || t.toPhone.isBlank() || "dl:${t.id}" in smsDone) return
        val seen = t.seenAt > 0L || Ticks.seen(t)
        if (!seen || !inHours || TeamRules.smsMin(c) <= 0) return
        val after = if (t.urgent) 2L * TeamRules.urgentOf(TeamRepo.member(t.toUid)?.setPerms).gapMin else TeamRules.dayMin(c)
        if (waited < after || !SmsSender.hasPermission(c)) return
        val ok = runCatching {
            SmsSender.managerForDefaultSim(c).first.sendTextMessage(
                t.toPhone, null, "Reminder from ${t.fromFirst}: please set your deadline for: ${t.title.take(90)}. Open RB360.", null, null
            )
        }.isSuccess
        if (ok) {
            smsDone += "dl:${t.id}"
            TeamRepo.note(t, "No deadline yet: text sent to ${t.toFirst}")
        }
    }

    /**
     * Not opened yet (whatever its state, also when you set the deadline): one SMS after N working hours, a second
     * at the start of his next working day, then you are told to call him. No SMS permission: tried again on the
     * next check, and you are told once why it didn't go.
     */
    private fun smsRule(c: Context, t: TeamTask, waited: Long, now: Long, smsDone: MutableSet<String>, stage: MutableMap<String, String>) {
        val smsMin = TeamRules.smsMin(c)
        if (smsMin <= 0 || t.seenAt > 0L || Ticks.seen(t) || t.toPhone.isBlank()) return
        // Before v0.42 one text was the whole rule ([smsDone]).
        val st = stage[t.id] ?: if (t.id in smsDone) "1|${t.createdAt}" else "0|0"
        val n = st.substringBefore('|').toIntOrNull() ?: 0
        val at = st.substringAfter('|').toLongOrNull() ?: 0L
        fun text(second: Boolean): Boolean {
            if (!SmsSender.hasPermission(c)) {
                if ("perm:${t.id}" !in smsDone) {
                    smsDone += "perm:${t.id}"
                    notify(c, "smsperm:${t.id}", "✉ Couldn't text ${t.toFirst}", "${t.title}\nSMS permission is off: turn it on in Settings › Setup", mine = null, taskId = t.id, open = Open.CARD, web = WebRules.N_NO_DATE)
                }
                return false
            }
            val ok = runCatching {
                SmsSender.managerForDefaultSim(c).first.sendTextMessage(
                    t.toPhone, null,
                    (if (second) "Reminder: new task from ${t.fromFirst}: " else "New task from ${t.fromFirst}: ") +
                        "${t.title.take(90)}. Please open RB360.", null, null
                )
            }.isSuccess
            if (ok) TeamRepo.note(t, if (second) "Still not seen: second text sent to ${t.toFirst}" else "Not seen in ${TeamRules.label(smsMin)}: text sent to ${t.toFirst}")
            return ok
        }
        when {
            n == 0 && waited >= smsMin -> if (text(false)) { stage[t.id] = "1|$now"; smsDone += t.id }
            n == 1 && now >= ReviewData.nextWorkStart(c, at) && TeamRules.minutesSince(c, now - 60_000L, now) > 0L ->
                if (text(true)) stage[t.id] = "2|$now"
            n == 2 && TeamRules.minutesSince(c, at, now) >= smsMin -> {
                stage[t.id] = "3|$now"
                notify(c, "unseen:${t.id}", "👁 ${t.toFirst} still hasn't seen it · 📞 Call",
                    "${t.title}\nTwo texts sent, not opened yet", mine = null, taskId = t.id, open = Open.CARD, call = t.toPhone, web = WebRules.N_NO_DATE)
            }
        }
    }

    /** When the task was last sent back with "Not done yet" (0 if never). */
    private fun reopenedAt(t: TeamTask): Long =
        if (t.status != Team.REOPENED) 0L else t.events.filter { it.text.contains("Not done yet") }.maxOfOrNull { it.at } ?: 0L

    /** v0.51 A Done (or finished call) from before the latest "Not done yet": it must never close the task again. */
    private fun staleDone(t: TeamTask, doneAt: Long): Boolean {
        val back = reopenedAt(t)
        return back > 0L && doneAt in 1..back
    }

    /** When this phone last sent Done for a task: a second Done within a few minutes, without a new tap, is dropped. */
    private val donePushed = HashMap<String, Long>()

    private fun markDoneOnce(t: TeamTask) {
        val now = System.currentTimeMillis()
        val last = donePushed[t.id] ?: 0L
        if (now - last < 3 * 60_000L && reopenedAt(t) < last) return
        donePushed[t.id] = now
        TeamRepo.markDone(t)
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
                        if (last != null && staleDone(t, last.doneAt)) {
                            // v0.51 His call was finished before "Not done yet": open it again, never send the old Done back.
                            ReminderStore.reopen(last.id)
                        } else if (FollowUps.wasMoved(last, null)) {
                            // Moved back to My tasks: the link follows it there.
                            MyTaskStore.items.value.filter { !it.done && it.title == FollowUps.taskOf(last, null) }.maxByOrNull { it.id }?.let {
                                recv[t.id] = it.id.toString()
                                recvFu.remove(t.id)
                                changed = true
                            }
                        } else markDoneOnce(t)
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
                    // v0.51 A Done ticked before the latest "Not done yet" is old: reopen his copy instead of sending it again.
                    local.done && t.open && staleDone(t, local.doneAt) -> MyTaskStore.setDone(local.id, false)
                    local.done && t.open -> markDoneOnce(t)
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
                    !FollowUps.wasMoved(calls.maxByOrNull { it.id }, null) &&
                    !staleDone(t, calls.maxOf { it.doneAt })
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
    internal fun waitAt(c: Context, latest: Long): Long =
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
        // Your time: it goes to Follow-ups then. His date: it waits in To schedule until he picks one.
        myTime?.takeIf { it > 0L }?.let { FollowUps.setTime(c, item, it) }
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
    fun onMovedToFollowUps(c: Context, myTaskId: Long, link: Long, toName: String, inTeam: Boolean = false) {
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
            TeamRepo.tasks.value.firstOrNull { it.id == taskId }?.let { TeamRepo.passedDown(it, Names.first(toName) ?: toName, callAt, inTeam) }
        }
        version.value++
    }

    /**
     * Your Not done yet on his Done: your call-back leaves Replied. With [newDue] it rings then; with 0 (he picks)
     * it waits in To schedule ("⏳ His deadline?") until he sets one, then follows his deadline by itself.
     */
    fun giverNotDone(c: Context, t: TeamTask, newDue: Long) {
        initStores(c)
        val link = TeamLinks.map(c, "given")[t.id]?.toLongOrNull() ?: return
        val call = ReminderStore.items.value.filter { it.linkId == link }.let { l -> l.firstOrNull { !it.done } ?: l.maxByOrNull { it.id } } ?: return
        if (newDue > 0L) ReminderStore.reschedule(call.id, newDue, "Not done yet · new deadline")
        else waitForHim(c, call, t.toFirst, "Not done yet · ")
    }

    /**
     * Your call-back for a task whose deadline he picks: its time goes and it waits in To schedule with the 📞 switch
     * kept on. When he picks, [giverSide] gives it his deadline and it moves to Follow-ups.
     */
    internal fun waitForHim(c: Context, call: Reminder, who: String, lead: String = "") {
        if (call.done) ReminderStore.reopen(call.id)
        val open = ReminderStore.get(call.id)?.takeIf { !it.done } ?: return
        FollowUps.removeTime(c, open, FollowUps.text(open.linkId), why = "${lead}Waiting for $who's deadline · To schedule")
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
        val nums = m.numbers
        if (nums.isEmpty()) return 0
        fun his(n: String) = n.isNotBlank() && nums.any { SmsStore.sameNumber(it, n) }
        initStores(c)
        var n = 0
        synchronized(lock) {
            val given = TeamLinks.map(c, "given")
            val taken = given.values.toSet()
            ReminderStore.items.value
                .filter { !it.done && !it.review && it.repeat == null && his(it.number) }
                .forEach { r ->
                    val link = FollowUps.ensureLink(c, r, FollowUps.text(r.linkId))
                    if (link.toString() in taken) return@forEach
                    val id = TeamRepo.give(m, r.note.ifBlank { "Call back" }, 0L, r.starred, r.dueAt) ?: return@forEach
                    FollowUps.stopTexts(link)
                    given[id] = link.toString()
                    n++
                }
            ToScheduleStore.items.value
                .filter { his(it.number) }
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
                val given = recv[t.id.toString()]?.let { fromOf[it] }
                mapOf(
                    "title" to t.title, "due" to t.dueAt, "done" to t.done, "doneAt" to t.doneAt,
                    "from" to (given?.fromFirst ?: ""),
                    // v0.47 the on-time mark, as his own card shows it.
                    "mark" to ((if (given != null) OnTime.chip(given)?.text else OnTime.label(t)) ?: ""),
                )
            }
        val fus = ReminderStore.items.value
            .filter { (!it.done && it.dueAt < end) || (it.done && it.doneAt >= start && !it.review) }
            .sortedWith(compareBy({ it.done }, { it.dueAt }))
            .take(60)
            .map { r -> mapOf("title" to r.note.ifBlank { "Call back" }, "name" to r.title, "due" to r.dueAt, "done" to r.done, "doneAt" to r.doneAt) }
        // v0.49.2 Today's Google Calendar meetings (the calendars he switched on), for the website's Day view.
        val google = GoogleCal.events(c, today).filter { !it.allDay }.take(30).map { e -> mapOf("title" to e.title, "start" to e.start, "end" to e.end) }
        val open = Dossier.openDay(System.currentTimeMillis())
        val dossier = DossierStore.todays().map { d ->
            val rec = DossierStore.record(d.id, open)
            mapOf(
                "title" to d.title, "time" to d.rangeLabel, "frequency" to d.frequency, "doneAt" to (rec?.doneAt ?: 0L),
                "reason" to (rec?.reason ?: ""), "due" to d.dueAt(open), "date" to open.toString(),
                "blockId" to (TeamDossier.blockOf(c, d.id) ?: ""), "proof" to TeamDossier.needsProof(c, d.id),
                "photo" to TeamDossier.sent(c, d.id, open, TeamDossier.PHOTO), "voice" to TeamDossier.sent(c, d.id, open, TeamDossier.VOICE),
                "afterReview" to d.afterReview,
            )
        }
        // v0.59 Team Performance on the website: every open follow-up and the done ones of the last 9 weeks (with their
        // moves), the Google meetings of last week to two weeks ahead, and each Dossier day of the last 9 weeks.
        val now = System.currentTimeMillis()
        val keepFrom = now - 63 * 86_400_000L
        val fuAll = ReminderStore.items.value
            .filter { !it.done || it.doneAt >= keepFrom }
            .sortedByDescending { it.dueAt }
            .take(400)
            .map { r ->
                mapOf(
                    "id" to r.id.toString(), "title" to r.note.ifBlank { "Call back" }, "name" to r.title, "created" to r.createdAt,
                    "due" to r.dueAt, "first" to (r.delays.firstOrNull()?.wasDue ?: r.dueAt), "done" to r.done, "doneAt" to r.doneAt,
                    "star" to r.starred,
                    "moves" to r.delays.map { d -> mapOf("at" to d.at, "from" to d.wasDue, "to" to d.newDue, "r" to d.reason) },
                    "events" to r.events.takeLast(20).map { e -> mapOf("at" to e.at, "text" to e.label, "note" to e.note) },
                )
            } + ToScheduleStore.items.value.take(200).map { s ->
                // v0.59.1 follow-ups still in To schedule (no time yet), e.g. the check-up on a task you gave.
                mapOf(
                    "id" to "s${s.id}", "title" to s.note.ifBlank { "Call back" }, "name" to s.title, "created" to s.createdAt,
                    "due" to 0L, "first" to 0L, "done" to false, "doneAt" to 0L, "star" to s.starred,
                    "moves" to emptyList<Any>(), "events" to emptyList<Any>(),
                )
            }
        val googleWeek = (0..20).flatMap { i -> GoogleCal.events(c, today.minusDays(7).plusDays(i.toLong())).filter { !it.allDay } }
            .distinctBy { it.id to it.start }
            .take(200)
            .map { e -> mapOf("title" to e.title, "start" to e.start, "end" to e.end) }
        val dosDays = (0..62).mapNotNull { back ->
            val d = open.minusDays(back.toLong())
            val on = DossierStore.tasks.value.filter { it.isOn(d) }.sortedBy { it.minute }
            if (on.isEmpty()) return@mapNotNull null
            mapOf(
                "d" to d.toString(),
                "i" to on.map { t ->
                    val rec = DossierStore.record(t.id, d)
                    mapOf(
                        "t" to t.title, "m" to t.minute, "e" to t.endMinute,
                        "s" to (if (rec?.done == true) "D" else if (Dossier.isLocked(d, now)) "M" else "O"),
                        "at" to (rec?.doneAt ?: 0L), "r" to (rec?.reason ?: ""),
                        "p" to TeamDossier.sent(c, t.id, d, TeamDossier.PHOTO),
                    )
                },
            )
        }
        return mapOf(
            "managers" to managers,
            "updatedAt" to System.currentTimeMillis(),
            "fuAll" to fuAll,
            "googleWeek" to googleWeek,
            "dosDays" to dosDays,
            "tasks" to tasks,
            "followUps" to fus,
            "google" to google,
            "dossier" to dossier,
            // 📋 The last week's evening reviews, for "Review done 5 of 6 days this week" on his day.
            "reports" to DayClose.recentMaps(c),
            "privateCount" to TeamLinks.privateCount(c),
        )
    }

    // ------------------------------------------------------------ notifications

    /**
     * The task's one notification, updated with every new Timeline line: "Basharat · 3 new on Timeline" and the
     * newest lines. Opening the Timeline clears it. ❓ Ask, ⚠ Stuck and 📅 extend requests come on their own.
     */
    private fun tell(c: Context, t: TeamTask, who: String, line: String, head: String? = null, web: String = WebRules.N_TIMELINE) {
        val counts = TeamLinks.map(c, "nc")
        val lines = TeamLinks.map(c, "nl")
        val n = (counts[t.id]?.toIntOrNull() ?: 0) + 1
        val all = (lines[t.id]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()) + line.lineSequence().first().take(100)
        counts[t.id] = n.toString()
        lines[t.id] = all.takeLast(5).joinToString("\n")
        TeamLinks.putMap(c, "nc", counts)
        TeamLinks.putMap(c, "nl", lines)
        val title = if (n == 1 && head != null) head else "$who · $n new on Timeline"
        // A message opens the Timeline; a new task, a date or a Not done yet opens the card where it lives.
        val talk = line.startsWith("💬")
        notify(c, "tl:${t.id}", title, t.title + " · " + all.last() + "\n" + all.dropLast(1).reversed().joinToString("\n"), mine = null, taskId = t.id,
            kind = if (talk) NotifKind.MESSAGES else if (t.fromUid == TeamRepo.uid) NotifKind.FOLLOW_UPS else NotifKind.MY_TASKS,
            open = if (talk) Open.TIMELINE else Open.CARD, web = web)
    }

    /** An @mention of you in a Timeline message (it rings even outside your duty window). */
    private fun mentionsMe(text: String): Boolean {
        val me = TeamRepo.me.value ?: return false
        val tag = memberTag(me, TeamRepo.members.value)
        return tag.isNotBlank() && Regex("@" + Regex.escape(tag) + "(?![\\w.])", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /** 27 Nov (S13.2) Automatic notifications outside the duty window wait here, then come as ONE notification. */
    fun holdLine(c: Context, line: String) = hold(c, line)

    private fun hold(c: Context, line: String) {
        val p = c.getSharedPreferences("team_held", Context.MODE_PRIVATE)
        val all = (p.getString("lines", "").orEmpty().split('\n').filter { it.isNotBlank() } + line.take(100)).takeLast(30)
        p.edit().putString("lines", all.joinToString("\n")).putInt("n", p.getInt("n", 0) + 1).apply()
    }

    private fun deliverHeld(c: Context) {
        val p = c.getSharedPreferences("team_held", Context.MODE_PRIVATE)
        val n = p.getInt("n", 0)
        if (n == 0) return
        val lines = p.getString("lines", "").orEmpty()
        p.edit().clear().apply()
        notify(c, "held", "🌙 $n update" + (if (n == 1) "" else "s") + " while you were off", lines.lines().reversed().joinToString("\n"),
            mine = null, kind = NotifKind.MESSAGES, timed = true)
    }

    /** The Timeline was opened (or the task went): its notifications go and the count starts again. */
    fun clearTold(c: Context, taskId: String) {
        val counts = TeamLinks.map(c, "nc")
        val lines = TeamLinks.map(c, "nl")
        if (counts.remove(taskId) != null) TeamLinks.putMap(c, "nc", counts)
        if (lines.remove(taskId) != null) TeamLinks.putMap(c, "nl", lines)
        val nm = androidx.core.app.NotificationManagerCompat.from(c)
        listOf("tl:", "ask:", "stuck:", "more:", "done:").forEach { nm.cancel(NotifIds.team(it + taskId)) }
    }

    /** Where tapping a team notification goes: the Timeline (talk), the task's card where it lives, or Needs you › Replied. */
    enum class Open { TIMELINE, CARD, CHECK }

    internal fun notify(
        c: Context, key: String, title: String, text: String, mine: Long?, taskId: String? = null,
        kind: NotifKind = if (mine != null) NotifKind.MY_TASKS else NotifKind.FOLLOW_UPS,
        open: Open = Open.TIMELINE, call: String? = null,
        /** 27 Nov (S2.3) Which website row decides it: Full screen, Normal or Off. */
        web: String = WebRules.N_TIMELINE, style: String? = null,
        /** Something with a set time: it always comes, day or night (S13.1). */
        timed: Boolean = false,
    ) {
        val st = style ?: WebRules.style(c, web)
        if (st == WebRules.OFF) return
        val quiet = !timed && TeamRules.quietNow(c)
        // Outside the window: Timeline messages come silently, an @mention still rings, the rest wait for the morning.
        if (quiet && web != WebRules.N_TIMELINE && web != WebRules.N_MENTION) return hold(c, title)
        val intent = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (taskId != null) intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TEAM).putExtra(MainActivity.EXTRA_TEAM_TASK, taskId)
            .putExtra(MainActivity.EXTRA_TEAM_WHERE, open.name)
        else if (mine != null) intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MY_TASKS).putExtra(MainActivity.EXTRA_TASK, mine)
        else intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TEAM)
        val tap = PendingIntent.getActivity(c, NotifIds.team(key), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(c, Notifier.bannerChannel(c))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("Team")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
        if (open == Open.CHECK && taskId != null) {
            val rcv = Intent(c, TeamActionReceiver::class.java).setAction(TeamActionReceiver.ACTION_RECEIVED)
                .putExtra(TeamActionReceiver.EXTRA_TASK, taskId).putExtra(TeamActionReceiver.EXTRA_KEY, key)
            b.addAction(0, "✓ Received",
                PendingIntent.getBroadcast(c, NotifIds.team("rcv:$key"), rcv, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (!call.isNullOrBlank()) {
            val dial = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", call, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.addAction(0, "📞 Call",
                PendingIntent.getActivity(c, NotifIds.team("call:$key"), dial, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (quiet && web == WebRules.N_TIMELINE) b.setSilent(true)
        Notify.post(c, NotifType.TEAM, NotifIds.team(key), b, kind)
        if (st == WebRules.FULL && !quiet) TaskAlertActivity.show(c, head = kind.label, title = title, open = intent,
            due = text.lineSequence().first(), call = call)
    }
}

/** ✓ Received on a "Done, check it" notification: the same as Received, thanks on its card. */
class TeamActionReceiver : android.content.BroadcastReceiver() {
    companion object {
        const val ACTION_RECEIVED = "com.callbackreminder.app.TEAM_RECEIVED"
        const val EXTRA_TASK = "task"
        const val EXTRA_KEY = "key"
    }

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != ACTION_RECEIVED) return
        val id = intent.getStringExtra(EXTRA_TASK) ?: return
        val key = intent.getStringExtra(EXTRA_KEY) ?: "done:$id"
        androidx.core.app.NotificationManagerCompat.from(c).cancel(NotifIds.team(key))
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                TeamRepo.init(c)
                if (TeamRepo.tasks.value.none { it.id == id }) TeamRepo.fetchOnce(c)
                TeamRepo.tasks.value.firstOrNull { it.id == id && it.doneCheck }?.let { TeamRepo.check(it) }
                Notify.tidy(c)
            } catch (e: Throwable) {
                CrashLog.record(c, e, "Team · Received from notification")
            } finally {
                pending.finish()
            }
        }
    }
}

/** The 15-minute check while the app is closed (the free plan has no instant push). */
class TeamWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { TeamRepo.fetchOnce(applicationContext) }
            .onFailure { CrashLog.record(applicationContext, it, "Team check") }
        runCatching { ReadingReminders.check(applicationContext) }
            .onFailure { CrashLog.record(applicationContext, it, "Reading reminder") }
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
