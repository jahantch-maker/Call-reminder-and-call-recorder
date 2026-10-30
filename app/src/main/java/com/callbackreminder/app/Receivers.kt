package com.callbackreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telephony.TelephonyManager
import java.util.concurrent.Executors

/** Fires when a reminder's time arrives and alerts the user in the style they chose. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.init(context)
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val r = ReminderStore.get(id)?.takeIf { !it.done } ?: return
        if (r.repeat == null) {
            ReminderStore.logEvent(r.id, "Rang")
            ReminderStyle.alert(context, r)
            return
        }
        // Repeating: if the next repeat has arrived, move on to it. Then set the alarm for the one after.
        val next = r.nextOccurrence()
        val current = if (next != null && next <= System.currentTimeMillis() + 1_000L) {
            ReminderStore.rollOver(id) ?: return
        } else {
            r
        }
        Scheduler.schedule(context, current)
        ReminderStore.logEvent(current.id, "Rang")
        ReminderStyle.alert(context, current)
    }
}

/** Handles the Snooze and Done buttons on the reminder notification. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SNOOZE = "com.callbackreminder.app.SNOOZE"
        const val ACTION_DONE = "com.callbackreminder.app.DONE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_MINUTES = "minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.init(context)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        when (intent.action) {
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 60)
                ReminderStore.reschedule(id, System.currentTimeMillis() + minutes * 60_000L, "Snoozed to")
            }
            ACTION_DONE -> ReminderStore.markDone(id)
        }
    }
}

/**
 * After a restart or app update, put all the alarms back. Nothing that already rang rings again: after an update
 * nothing old rings at all, after a restart only what came due while the phone was off (one rings, several give
 * one notification).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.init(context)
        MyTaskStore.init(context)
        val restart = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        val since = if (restart) PhoneOff.offSince(context) else null
        val calls = Scheduler.restoreAll(context, since)
        val tasks = if (since != null) TaskAlarms.missedSince(since) else emptyList()
        PhoneOff.ringMissed(context, calls, tasks)
        SmsStore.init(context)
        SmsScheduler.rescheduleAll(context)
        BankingPause.resume(context)
        ReviewReminder.schedule(context)
        TaskAlarms.rescheduleAll(context)
        ReviewSession.rearm(context)
    }
}

/** When the phone went off, so a restart knows what came due meanwhile. */
object PhoneOff {
    const val MISSED_EVENT = "Came due while the phone was off"
    private const val REQ_OPEN = 1_099_501
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("phone_off", Context.MODE_PRIVATE)

    /** The last time the app was seen running before this start (read before [markAlive] overwrites it). */
    @Volatile private var aliveBefore = 0L

    /** Called whenever the app starts (alarms, the screen, a call): the phone was on until at least now. */
    fun markAlive(c: Context) {
        val s = p(c)
        if (aliveBefore == 0L) aliveBefore = s.getLong("alive", 0L)
        s.edit().putLong("alive", System.currentTimeMillis()).apply()
    }

    fun markShutdown(c: Context) = p(c).edit().putLong("shutdown", System.currentTimeMillis()).commit()

    /** When the phone went off: the shutdown, or the last sign of life when the battery died. 12 h back if unknown. */
    fun offSince(c: Context): Long {
        val now = System.currentTimeMillis()
        val boot = now - android.os.SystemClock.elapsedRealtime()
        val known = maxOf(p(c).getLong("shutdown", 0L), aliveBefore).takeIf { it in 1..boot }
        return known ?: (boot - 12 * 60 * 60_000L)
    }

    /** One missed item rings as usual; two or more give one notification that opens Review. */
    @android.annotation.SuppressLint("MissingPermission")
    fun ringMissed(ctx: Context, calls: List<Reminder>, tasks: List<MyTask>) {
        val total = calls.size + tasks.size
        if (total == 0) return
        if (total == 1) {
            calls.firstOrNull()?.let { Scheduler.ringSoon(ctx, it) }
            tasks.firstOrNull()?.let { TaskAlarms.showTask(ctx, it) }
            return
        }
        calls.forEach { ReminderStore.logEvent(it.id, MISSED_EVENT) }
        if (!Notifier.canNotify(ctx)) return
        Notifier.createChannels(ctx)
        val open = android.app.PendingIntent.getActivity(
            ctx, REQ_OPEN, ReviewReminder.reviewIntent(ctx),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val what = if (tasks.isEmpty()) "follow-ups" else if (calls.isEmpty()) "tasks" else "follow-ups and tasks"
        val b = androidx.core.app.NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("$total $what came due while your phone was off")
            .setContentText((calls.map { it.note.ifBlank { "Call back" } } + tasks.map { it.title }).take(4).joinToString(" · "))
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.CALLBACK, NotifIds.MISSED_WHILE_OFF, b)
    }
}

/** The phone is switching off: remember when. */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PhoneOff.markShutdown(context)
    }
}

/**
 * Watches the phone's call state.
 *
 * A "session" starts when the phone first becomes busy (a call rings or is dialled) and ends
 * when it is completely idle again. Call waiting can put several calls into one session, so we
 * remember where the call log stood when the session began. When the session ends we read every
 * call that was added since then, work out who you really talked to, and show one popup per person.
 */
class CallEndReceiver : BroadcastReceiver() {

    private data class Session(
        val baseline: Long,
        val start: Long,
        val sawOffhook: Boolean,
        val rings: Int,
        val outgoingStart: Boolean,
    )

    companion object {
        private const val STALE_MS = 6 * 60 * 60 * 1000L
        // No settle wait: the popup should appear the moment the call ends.
        private const val POLL_MS = 100L
        private const val POLL_TRIES = 30
        // A WhatsApp call counts as part of a phone session if it was seen this long before it began.
        private const val WHATSAPP_OVERLAP_MS = 2 * 60_000L
        private val lock = Any()
        private val worker = Executors.newSingleThreadExecutor()

        private fun prefs(c: Context) = c.getSharedPreferences("callstate", Context.MODE_PRIVATE)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        // Android sends each state twice, once with the number; the caller card wants the one with it.
        @Suppress("DEPRECATION")
        CallerCard.onPhoneState(context, state, intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
        val p = prefs(context)

        var sessionOpen = false
        var recordKey = -1L
        synchronized(lock) {
            val last = p.getString("last", TelephonyManager.EXTRA_STATE_IDLE)
            // Android (especially on dual-SIM phones) can send the same state twice.
            if (state == last) return

            val e = p.edit().putString("last", state)
            if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                sessionOpen = p.getBoolean("active", false)
            } else {
                val now = System.currentTimeMillis()
                val active = p.getBoolean("active", false)
                val stale = now - p.getLong("start", 0L) > STALE_MS
                val fresh = !active || stale
                if (fresh) {
                    e.putBoolean("active", true)
                        .putLong("start", now)
                        .putLong("baseline", CallLookup.maxId(context))
                        .putBoolean("offhook", false)
                        .putInt("rings", 0)
                        .putBoolean("outgoingStart", state == TelephonyManager.EXTRA_STATE_OFFHOOK)
                }
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    val before = if (fresh) 0 else p.getInt("rings", 0)
                    e.putInt("rings", before + 1)
                }
                if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                    e.putBoolean("offhook", true)
                    // The call is connected (or being dialled): this is when recording starts.
                    recordKey = if (fresh) now else p.getLong("start", now)
                }
            }
            e.apply()
        }

        val recCtx = context.applicationContext
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            // Every call is over: stop recording straight away.
            CallRecorder.stopAsync(recCtx)
        }
        if (recordKey > 0L && AppSettings.get(recCtx, AppSettings.REC_PHONE)) {
            val pendingRec = goAsync()
            CallRecorder.startAsync(
                ctx = recCtx,
                sessionKey = recordKey,
                whatsApp = false,
                type = 0,
                name = "",
                number = "",
                done = { pendingRec.finish() }
            )
        }

        if (sessionOpen) {
            val app = context.applicationContext
            val pending = goAsync()
            worker.execute {
                try {
                    finishSession(app)
                } catch (e: Exception) {
                    // Nothing more we can do; the next call starts a clean session.
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /** Runs a moment after the phone went idle. */
    private fun finishSession(ctx: Context) {
        val p = prefs(ctx)
        val session: Session = synchronized(lock) {
            if (!p.getBoolean("active", false)) return
            if (p.getString("last", TelephonyManager.EXTRA_STATE_IDLE) != TelephonyManager.EXTRA_STATE_IDLE) return
            val s = Session(
                baseline = p.getLong("baseline", 0L),
                start = p.getLong("start", System.currentTimeMillis()),
                sawOffhook = p.getBoolean("offhook", false),
                rings = p.getInt("rings", 0),
                outgoingStart = p.getBoolean("outgoingStart", false),
            )
            p.edit().putBoolean("active", false).commit()
            s
        }

        // The phone writes calls into its log a little after hang-up. We expect at least one
        // entry per ringing call (plus the call we dialled, if any), so we wait until they appear.
        val needed = (session.rings + if (session.outgoingStart) 1 else 0).coerceAtLeast(1)
        val notBefore = session.start - 2 * 60_000L
        // The first check runs immediately; we only keep polling (every 100 ms) if the log is not ready yet.
        var entries: List<CallInfo> = emptyList()
        for (i in 0 until POLL_TRIES) {
            entries = CallLookup.since(ctx, session.baseline, notBefore)
            if (entries.size >= needed) break
            Thread.sleep(POLL_MS)
        }

        // Put the real name and number on the recording made during this call.
        RecordingStore.attachCalls(session.start, entries)

        // One outgoing call and nothing else: cut the ringing from the start of its recording.
        val only = entries.singleOrNull()
        if (session.outgoingStart && session.rings == 0 && only != null &&
            only.type == CallLog.Calls.OUTGOING_TYPE && only.durationSec > 0L
        ) {
            CallRecorder.trimRingingAsync(ctx, session.start, only.durationSec)
        }

        // You tapped Call back on a due reminder (or Call now in To review): this popup asks how that task went.
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        val task = FollowUp.take(ctx, session.start)
        val queue = CallSession.buildQueue(ctx, entries, session.sawOffhook, session.rings)
        // "Call again" on the popup: it comes back with the note and task kept.
        val again = CallAgain.take(ctx, session.start, entries, queue)

        // A WhatsApp call never shows up in the call log, so the lookup above comes back empty.
        // WhatsAppCallListener already shows the proper popup for it; a blank "Unknown caller"
        // popup from here would only be a duplicate.
        if (task == null && again == null && entries.isEmpty() &&
            WhatsAppCallListener.wasActiveSince(ctx, session.start - WHATSAPP_OVERLAP_MS)
        ) {
            return
        }

        showPostCallPopup(
            ctx,
            when {
                again != null -> again
                task != null -> FollowUp.attach(task, entries, queue)
                // A SIM with its popup switched off: only numbers with an open task still ask.
                else -> queue.filter { SimPopup.allowed(ctx, it) }
            }
        )
    }
}
