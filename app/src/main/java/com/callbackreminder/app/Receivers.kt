package com.callbackreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telephony.TelephonyManager
import java.util.concurrent.Executors

/** Fires when a reminder's time arrives and starts the ringing screen. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.init(context)
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val r = ReminderStore.get(id)?.takeIf { !it.done } ?: return
        RingController.start(context, r)
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
        private const val SETTLE_MS = 1_500L
        private const val POLL_MS = 600L
        private const val POLL_TRIES = 7
        private val lock = Any()
        private val worker = Executors.newSingleThreadExecutor()

        private fun prefs(c: Context) = c.getSharedPreferences("callstate", Context.MODE_PRIVATE)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val p = prefs(context)

        var sessionOpen = false
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
                }
            }
            e.apply()
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
        // Give the phone a moment: if another call starts right away (call waiting hand-over),
        // the session simply carries on, and the popups wait until the very end.
        Thread.sleep(SETTLE_MS)

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
        var entries: List<CallInfo> = emptyList()
        var previousCount = -1
        for (i in 0 until POLL_TRIES) {
            entries = CallLookup.since(ctx, session.baseline, notBefore)
            if (entries.size >= needed && entries.size == previousCount) break
            previousCount = entries.size
            Thread.sleep(POLL_MS)
        }

        val queue = CallSession.buildQueue(ctx, entries, session.sawOffhook, session.rings)
        if (queue.isEmpty()) return

        val popup = PostCallActivity.intentFor(ctx, queue)
        // Backup prompt first; the popup removes it as soon as it opens.
        Notifier.showPostCallPrompt(ctx, popup)
        if (Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(popup)
            } catch (e: Exception) {
                // The backup notification is already showing.
            }
        }
    }
}
