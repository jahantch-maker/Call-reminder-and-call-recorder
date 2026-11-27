package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.ZoneId

/**
 * Alarms and notifications for My tasks: a one-time task's due time, a dossier task's due time
 * (a notification with only a Done button) and its "remind me before", and the separate dossier
 * reminder from Settings › Dossier.
 */
object TaskAlarms {
    const val ACTION_TASK_DUE = "com.callbackreminder.app.TASK_DUE"
    const val ACTION_TASK_DONE = "com.callbackreminder.app.TASK_DONE"
    const val ACTION_DOSSIER = "com.callbackreminder.app.DOSSIER"
    const val ACTION_DOSSIER_DONE = "com.callbackreminder.app.DOSSIER_DONE"
    const val ACTION_DOSSIER_NUDGE = "com.callbackreminder.app.DOSSIER_NUDGE"
    const val ACTION_PLOT_COMES = "com.callbackreminder.app.PLOT_COMES"
    const val EXTRA_ID = "task_id"
    const val EXTRA_DATE = "date"
    const val EXTRA_BEFORE = "before"
    const val EXTRA_AT = "at"

    private const val TASK_BASE = 1_100_000
    private const val DOSSIER_BASE = 1_200_000
    private const val BEFORE_BASE = 1_300_000
    private const val TASK_BEFORE_BASE = 1_400_000
    private const val NUDGE_ID = 1_099_001
    private const val COMES_BASE = 1_500_000
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    /** Opens the app on [open]; with [task] the notification's own task is scrolled to and outlined. */
    private fun openApp(ctx: Context, open: String, req: Int, task: Long = -1L): PendingIntent = PendingIntent.getActivity(
        ctx,
        req,
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, open)
            .apply { if (task >= 0) putExtra(MainActivity.EXTRA_TASK, task) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        FLAGS
    )

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent, open: String) {
        am(ctx).setClockSafely(AlarmManager.AlarmClockInfo(at, openApp(ctx, open, 0)), pi)
    }

    // ---- One-time tasks ----

    private fun taskAlarm(ctx: Context, id: Long, at: Long = 0L, before: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        TASK_BASE + id.toInt(),
        Intent(ctx, TaskReceiver::class.java)
            .setAction(ACTION_TASK_DUE)
            .setData(Uri.parse("callback://task/$id"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_AT, at)
            .putExtra(EXTRA_BEFORE, before),
        FLAGS
    )

    /** The task's next alarm after [now]: one of its "remind before" times, or the due time. One alarm at a time. */
    fun scheduleTask(ctx: Context, t: MyTask, now: Long = System.currentTimeMillis()) {
        if (t.done || t.dueAt <= now) {
            am(ctx).cancel(taskAlarm(ctx, t.id))
            return
        }
        val at = (before(ctx, t).map { t.dueAt - it * 60_000L } + t.dueAt).filter { it > now }.min()
        setAlarm(ctx, at, taskAlarm(ctx, t.id, at, before = at != t.dueAt), MainActivity.OPEN_MY_TASKS)
    }

    /** A plot file step: one of his My tasks made from it (Settings › Notifications › Plot files decides its alerts). */
    private fun plot(ctx: Context, t: MyTask) = t.id.toString() in TeamLinks.set(ctx, "plotLocal")

    /** 27 Nov A plot step's heads-up always follows the office's newest "Heads-up before due" (Off, or how long before). */
    private fun before(ctx: Context, t: MyTask): List<Int> =
        if (!plot(ctx, t)) t.remindBefore
        else if (WebRules.style(ctx, WebRules.N_PLOT_HEADS) == WebRules.OFF) emptyList()
        else listOf(WebRules.int(ctx, "plotHeadsMin")).filter { it > 0 }

    /** The plot step's full-screen popup (Full screen on the website), opening its task in My tasks. */
    private fun plotPopup(ctx: Context, t: MyTask, head: String, b: NotificationCompat.Builder?, req: Int) =
        TaskAlertActivity.show(ctx, head, t.title,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MY_TASKS).putExtra(MainActivity.EXTRA_TASK, t.id),
            due = "Due ${Fmt.dueLabel(t.dueAt)}", fallback = b, req = req)

    fun cancelTask(ctx: Context, id: Long) = am(ctx).cancel(taskAlarm(ctx, id))

    // ---- N6 Plot owner said he would come: a reminder on this phone (works offline) ----

    private fun comesAlarm(ctx: Context, id: String, title: String = "", text: String = "", call: String = ""): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        COMES_BASE + (id.hashCode() and 0xFFFFF),
        Intent(ctx, TaskReceiver::class.java)
            .setAction(ACTION_PLOT_COMES)
            .setData(Uri.parse("callback://comes/$id"))
            .putExtra("tid", id).putExtra("title", title).putExtra("text", text).putExtra("call", call),
        FLAGS
    )

    /** Date only = the start of his duty that day; date + time = exactly then. Nothing when it has passed or he isn't waiting. */
    fun scheduleComes(ctx: Context, t: TeamTask, now: Long = System.currentTimeMillis()) {
        if (t.waitAt == 0L || t.waitUntil == 0L) return cancelComes(ctx, t.id)
        val day = java.time.Instant.ofEpochMilli(t.waitUntil).atZone(ZoneId.systemDefault()).toLocalDate()
        val at = if (t.waitTimed) t.waitUntil
            else TeamRules.duty(ctx, day)?.first ?: CalendarData.at(day, if (WorkHours.isSet(ctx)) WorkHours.start(ctx) else 11 * 60)
        if (at <= now) return cancelComes(ctx, t.id)
        val who = t.ownerName.ifBlank { "The plot owner" }
        setAlarm(ctx, at, comesAlarm(ctx, t.id, "⏳ Plot ${t.plotNo} · $who said he would come " + if (t.waitTimed) "now" else "today",
            "${t.waitReason}\nStep ${t.step} · ${t.title}", t.ownerWa), MainActivity.OPEN_TEAM)
    }

    fun cancelComes(ctx: Context, id: String) = am(ctx).cancel(comesAlarm(ctx, id))

    /** One-time tasks that came due after [since] (while the phone was off), so they never showed. */
    fun missedSince(since: Long, now: Long = System.currentTimeMillis()): List<MyTask> =
        MyTaskStore.items.value.filter { !it.done && it.timed && it.dueAt in (since + 1)..now }

    fun cancelTaskNotification(ctx: Context, id: Long) {
        NotificationManagerCompat.from(ctx).cancel(NotifIds.task(id))
        NotificationManagerCompat.from(ctx).cancel(NotifIds.taskBefore(id))
    }

    /** "1 hour before", "1 day before" and the like. */
    fun beforeLabel(min: Int): String = when {
        min % 1440 == 0 -> (min / 1440).let { if (it == 1) "1 day" else "$it days" }
        min % 60 == 0 -> (min / 60).let { if (it == 1) "1 hour" else "$it hours" }
        else -> "$min min"
    }

    /** The heads-up before a one-time task is due. */
    @SuppressLint("MissingPermission")
    fun showTaskBefore(ctx: Context, t: MyTask) {
        val style = if (plot(ctx, t)) WebRules.style(ctx, WebRules.N_PLOT_HEADS) else WebRules.NORMAL
        if (style == WebRules.OFF) return
        if (!Notifier.canNotify(ctx)) return
        val text = listOfNotNull(
            "Due ${Fmt.dueLabel(t.dueAt)}",
            t.personTitle.takeIf { t.hasPerson }?.let { "with $it" },
        ).joinToString(" · ")
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(t.title)
            .setContentText(text)
            .setSubText("Reminder before")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, MainActivity.OPEN_MY_TASKS, TASK_BEFORE_BASE + t.id.toInt(), t.id))
            .setAutoCancel(true)
        if (style == WebRules.FULL) plotPopup(ctx, t, "Plot file step soon", b, TASK_BEFORE_BASE + 70_000 + t.id.toInt())
        Notify.post(ctx, NotifType.MY_TASK, NotifIds.taskBefore(t.id), b)
    }

    @SuppressLint("MissingPermission")
    fun showTask(ctx: Context, t: MyTask) {
        // 27 Nov (S2.1) A plot file step that is due follows the office's "Step due": Full screen (default) = the ringing
        // popup with its notification, Normal = the notification, Off = nothing.
        val style = if (plot(ctx, t)) WebRules.style(ctx, WebRules.N_PLOT_DUE) else WebRules.NORMAL
        if (style == WebRules.OFF) return
        if (!Notifier.canNotify(ctx)) {
            if (style == WebRules.FULL) plotPopup(ctx, t, "Plot file step due", null, 0)
            return
        }
        NotificationManagerCompat.from(ctx).cancel(NotifIds.taskBefore(t.id))
        val text = listOfNotNull(
            t.note.takeIf { it.isNotBlank() },
            t.personTitle.takeIf { t.hasPerson }?.let { "with $it" },
        ).joinToString(" · ").ifBlank { "Due ${Fmt.timeOnly(t.dueAt)}" }
        val done = PendingIntent.getBroadcast(
            ctx,
            TASK_BASE + 50_000 + t.id.toInt(),
            Intent(ctx, TaskReceiver::class.java)
                .setAction(ACTION_TASK_DONE)
                .setData(Uri.parse("callback://task-done/${t.id}"))
                .putExtra(EXTRA_ID, t.id),
            FLAGS
        )
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, MainActivity.OPEN_MY_TASKS, TASK_BASE + t.id.toInt(), t.id))
            .setAutoCancel(true)
            .setWhen(t.dueAt)
            .setShowWhen(true)
        if (t.hasPerson) {
            val call = PendingIntent.getActivity(
                ctx,
                TASK_BASE + 60_000 + t.id.toInt(),
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", t.number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                FLAGS
            )
            b.addAction(0, "Call ${t.personTitle}", call)
        }
        b.addAction(0, "Done", done)
        if (style == WebRules.FULL) plotPopup(ctx, t, "Plot file step due", b, TASK_BASE + 70_000 + t.id.toInt())
        Notify.post(ctx, NotifType.MY_TASK, NotifIds.task(t.id), b)
    }

    // ---- Dossier tasks ----

    private data class Event(val at: Long, val before: Boolean, val date: LocalDate)

    /** The task's next notification after [now]: its "remind me before", or its due time. */
    private fun nextEvent(t: DossierTask, now: Long): Event? {
        var d = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
        repeat(70) {
            if (t.isOn(d)) {
                val due = t.dueAt(d)
                if (t.remindBefore > 0) {
                    val before = due - t.remindBefore * 60_000L
                    if (before > now) return Event(before, true, d)
                }
                if (due > now) return Event(due, false, d)
            }
            d = d.plusDays(1)
        }
        return null
    }

    private fun dossierAlarm(ctx: Context, id: Long, e: Event?): PendingIntent {
        val i = Intent(ctx, TaskReceiver::class.java)
            .setAction(ACTION_DOSSIER)
            .setData(Uri.parse("callback://dossier/$id"))
            .putExtra(EXTRA_ID, id)
        if (e != null) {
            i.putExtra(EXTRA_DATE, e.date.toString())
                .putExtra(EXTRA_BEFORE, e.before)
                .putExtra(EXTRA_AT, e.at)
        }
        return PendingIntent.getBroadcast(ctx, DOSSIER_BASE + id.toInt(), i, FLAGS)
    }

    fun scheduleDossier(ctx: Context, t: DossierTask, now: Long = System.currentTimeMillis()) {
        val e = nextEvent(t, now)
        if (e == null) {
            cancelDossier(ctx, t.id)
            return
        }
        setAlarm(ctx, e.at, dossierAlarm(ctx, t.id, e), MainActivity.OPEN_DOSSIER)
    }

    fun cancelDossier(ctx: Context, id: Long) = am(ctx).cancel(dossierAlarm(ctx, id, null))

    fun cancelDossierNotification(ctx: Context, id: Long) {
        NotificationManagerCompat.from(ctx).cancel(NotifIds.dossier(id))
        NotificationManagerCompat.from(ctx).cancel(NotifIds.dossierBefore(id))
    }

    /** At the due time: the task with only a Done button. No popup. */
    @SuppressLint("MissingPermission")
    fun showDossierDue(ctx: Context, t: DossierTask, date: LocalDate) {
        if (!Notifier.canNotify(ctx)) return
        NotificationManagerCompat.from(ctx).cancel(NotifIds.dossierBefore(t.id))
        val text = "Due ${t.timeLabel} · ${t.frequency}"
        val done = PendingIntent.getBroadcast(
            ctx,
            DOSSIER_BASE + 50_000 + t.id.toInt(),
            Intent(ctx, TaskReceiver::class.java)
                .setAction(ACTION_DOSSIER_DONE)
                .setData(Uri.parse("callback://dossier-done/${t.id}/$date"))
                .putExtra(EXTRA_ID, t.id)
                .putExtra(EXTRA_DATE, date.toString()),
            FLAGS
        )
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(t.title)
            .setContentText(text)
            .setSubText("Dossier")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, MainActivity.OPEN_DOSSIER, DOSSIER_BASE + t.id.toInt(), t.id))
            .setAutoCancel(true)
            .addAction(0, "✓ Done", done)
        Notify.post(ctx, NotifType.DOSSIER, NotifIds.dossier(t.id), b)
    }

    /** The extra heads-up before a dossier task. */
    @SuppressLint("MissingPermission")
    fun showDossierBefore(ctx: Context, t: DossierTask) {
        if (!Notifier.canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle("${t.title} at ${t.timeLabel}")
            .setContentText("Reminder before your dossier task")
            .setSubText("Dossier")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx, MainActivity.OPEN_DOSSIER, BEFORE_BASE + t.id.toInt(), t.id))
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.DOSSIER_BEFORE, NotifIds.dossierBefore(t.id), b)
    }

    // ---- Separate dossier reminder (Settings › Dossier) ----

    private fun nudgeAlarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        NUDGE_ID,
        Intent(ctx, TaskReceiver::class.java)
            .setAction(ACTION_DOSSIER_NUDGE)
            .setData(Uri.parse("callback://dossier-nudge")),
        FLAGS
    )

    fun scheduleNudge(ctx: Context, now: Long = System.currentTimeMillis()) {
        // 27 Nov (S4) A team member's dossier reminder is set by the office: up to 2 times from his duty end, saved on the phone.
        if (WebRules.locked(ctx, "dossier")) {
            val today = LocalDate.now(ZoneId.systemDefault())
            val at = (0..7).asSequence().flatMap { d -> WebRules.reminderTimes(ctx, "dos", today.plusDays(d.toLong())) }.firstOrNull { it > now }
            if (at == null) am(ctx).cancel(nudgeAlarm(ctx)) else setAlarm(ctx, at, nudgeAlarm(ctx), MainActivity.OPEN_DOSSIER_REVIEW)
            return
        }
        if (!DossierSettings.separate(ctx)) {
            am(ctx).cancel(nudgeAlarm(ctx))
            return
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val times = DossierSettings.times(ctx)
        val at = (0..1).flatMap { d ->
            times.map { m -> today.plusDays(d.toLong()).atTime(m / 60, m % 60).atZone(zone).toInstant().toEpochMilli() }
        }.filter { it > now }.minOrNull()
        if (at == null) {
            am(ctx).cancel(nudgeAlarm(ctx))
            return
        }
        setAlarm(ctx, at, nudgeAlarm(ctx), MainActivity.OPEN_DOSSIER_REVIEW)
    }

    @SuppressLint("MissingPermission")
    fun fireNudge(ctx: Context) {
        scheduleNudge(ctx, System.currentTimeMillis() + 1_000L)
        if (WebRules.locked(ctx, "dossier")) return officeNudge(ctx)
        if (!DossierSettings.separate(ctx) || !Notifier.canNotify(ctx)) return
        val todays = DossierStore.todays()
        if (todays.isEmpty()) return
        val left = DossierStore.leftNow()
        if (left == 0 && DossierSettings.skipWhenAllDone(ctx)) return
        val title = if (left == 0) "Dossier: all done ✓" else "Dossier: $left not ticked"
        val text = if (left == 0) "Every task due so far is ticked."
        else "Tick them, or say why not. Today locks at 3:00 AM."
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, MainActivity.OPEN_DOSSIER_REVIEW, NUDGE_ID))
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.DOSSIER_NUDGE, NUDGE_ID, b)
    }

    /**
     * 27 Nov (S4.4, S4.6) The office's dossier reminder: only when something due so far is unticked, always the
     * full-screen popup (the list, Open dossier, Skip, Silence), with a notification behind it.
     */
    @SuppressLint("MissingPermission")
    private fun officeNudge(ctx: Context) {
        val now = System.currentTimeMillis()
        val day = Dossier.openDay(now)
        val left = DossierStore.todays(now).filter { DossierStore.record(it.id, day)?.done != true && it.dueAt(day) <= now }
        if (left.isEmpty()) return
        val open = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOSSIER_REVIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val b = if (!Notifier.canNotify(ctx)) null else NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle("Dossier: ${left.size} not ticked")
            .setContentText("Tick them, or say why not. Today locks at 3:00 AM.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, MainActivity.OPEN_DOSSIER_REVIEW, NUDGE_ID))
            .setAutoCancel(true)
        TaskAlertActivity.show(ctx, "Dossier reminder", "${left.size} dossier task" + (if (left.size == 1) "" else "s") + " not ticked",
            open, due = "Tick them, or say why not", lines = left.map { it.title }, dossier = true, fallback = b, req = NUDGE_ID + 1)
        b?.let { NotificationManagerCompat.from(ctx).notify(NUDGE_ID, it.build()) }
    }

    /** After a restart, an app update or on opening the app: set every task alarm again. */
    fun rescheduleAll(ctx: Context) {
        MyTaskStore.init(ctx)
        DossierStore.init(ctx)
        MyTaskStore.items.value.filter { !it.done }.forEach { scheduleTask(ctx, it) }
        DossierStore.tasks.value.forEach { scheduleDossier(ctx, it) }
        scheduleNudge(ctx)
        MorningSummary.schedule(ctx)
        DailyList.schedule(ctx)
        Backup.schedule(ctx)
    }
}

/** My tasks' alarms and the Done buttons on their notifications. */
class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MyTaskStore.init(context)
        DossierStore.init(context)
        val id = intent.getLongExtra(TaskAlarms.EXTRA_ID, -1L)
        when (intent.action) {
            TaskAlarms.ACTION_TASK_DUE -> {
                val t = MyTaskStore.get(id)?.takeIf { !it.done } ?: return
                if (intent.getBooleanExtra(TaskAlarms.EXTRA_BEFORE, false)) {
                    // A "remind before": show it, then set the next one (another heads-up, or the due time).
                    val at = intent.getLongExtra(TaskAlarms.EXTRA_AT, 0L)
                    TaskAlarms.scheduleTask(context, t, maxOf(System.currentTimeMillis(), at) + 1_000L)
                    TaskAlarms.showTaskBefore(context, t)
                } else {
                    TaskAlarms.showTask(context, t)
                }
            }
            TaskAlarms.ACTION_TASK_DONE -> MyTaskStore.setDone(id, true)
            TaskAlarms.ACTION_DOSSIER -> {
                val t = DossierStore.get(id) ?: return
                val date = intent.getStringExtra(TaskAlarms.EXTRA_DATE)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val at = intent.getLongExtra(TaskAlarms.EXTRA_AT, 0L)
                // Set the next one first, so a problem below never stops the alarms.
                TaskAlarms.scheduleDossier(context, t, maxOf(System.currentTimeMillis(), at) + 1_000L)
                if (date == null || DossierStore.record(t.id, date)?.done == true) return
                if (Dossier.isLocked(date)) return
                if (intent.getBooleanExtra(TaskAlarms.EXTRA_BEFORE, false)) TaskAlarms.showDossierBefore(context, t)
                else TaskAlarms.showDossierDue(context, t, date)
            }
            TaskAlarms.ACTION_DOSSIER_DONE -> {
                val date = intent.getStringExtra(TaskAlarms.EXTRA_DATE)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
                // A 📷 team block waits for its photo: the notification stays until he adds it in the app.
                if (DossierStore.tick(id, date) || !TeamDossier.needsProof(context, id)) TaskAlarms.cancelDossierNotification(context, id)
            }
            TaskAlarms.ACTION_DOSSIER_NUDGE -> TaskAlarms.fireNudge(context)
            TaskAlarms.ACTION_PLOT_COMES -> {
                val tid = intent.getStringExtra("tid") ?: return
                TeamSync.notify(context, "pc:$tid", intent.getStringExtra("title").orEmpty() + " · 📞 Call", intent.getStringExtra("text").orEmpty(),
                    mine = null, taskId = tid, kind = NotifKind.MY_TASKS, open = TeamSync.Open.CARD,
                    call = intent.getStringExtra("call")?.takeIf { it.isNotBlank() }, web = WebRules.N_PLOT_WAIT, timed = true)
            }
            MorningSummary.ACTION_FIRE -> MorningSummary.fire(context)
            MorningSummary.ACTION_LATER -> MorningSummary.later(context)
            DailyList.ACTION_FIRE -> {
                val pending = goAsync()
                Thread {
                    try {
                        DailyList.fire(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            DailyList.ACTION_SENT -> DailyList.onResult(context, intent, resultCode)
            DailyList.ACTION_FLUSH -> {
                val pending = goAsync()
                Thread {
                    try {
                        DailyList.flush(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            DailyList.ACTION_WA_LATER -> DailyList.later(context)
            DailyList.ACTION_WA_REMIND -> DailyList.showReady(context)
            Backup.ACTION_FIRE -> {
                val pending = goAsync()
                Thread {
                    try {
                        Backup.fire(context)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
