package com.callbackreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                ReminderStore.reschedule(id, System.currentTimeMillis() + minutes * 60_000L)
            }
            ACTION_DONE -> ReminderStore.markDone(id)
        }
    }
}

/** After a restart or app update, put all the alarms back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.init(context)
        Scheduler.rescheduleAll(context)
        SmsStore.init(context)
        SmsScheduler.rescheduleAll(context)
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

        // A WhatsApp call never shows up in the call log, so the lookup above comes back empty.
        // WhatsAppCallListener already shows the proper popup for it; a blank "Unknown caller"
        // popup from here would only be a duplicate.
        if (entries.isEmpty() &&
            WhatsAppCallListener.wasActiveSince(ctx, session.start - WHATSAPP_OVERLAP_MS)
        ) {
            return
        }

        val queue = CallSession.buildQueue(ctx, entries, session.sawOffhook, session.rings)
        showPostCallPopup(ctx, queue)
    }
}
