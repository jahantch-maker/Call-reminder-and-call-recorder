package com.callbackreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager

/** Fires when a reminder's time arrives and shows the notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderStore.init(context)
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val r = ReminderStore.get(id)?.takeIf { !it.done } ?: return
        Notifier.showReminder(context, r)
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
 * Watches the phone's call state. When a call finishes (incoming, outgoing or missed)
 * it opens the "set a follow-up" popup.
 */
class CallEndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        val p = context.getSharedPreferences("callstate", Context.MODE_PRIVATE)
        val last = p.getString("last", TelephonyManager.EXTRA_STATE_IDLE)
        // Android (especially on dual-SIM phones) can send the same state twice.
        if (state == last) return

        val e = p.edit().putString("last", state)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING ->
                e.putBoolean("ringing", true).putBoolean("offhook", false)

            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                e.putBoolean("offhook", true)

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val wasRinging = p.getBoolean("ringing", false)
                val wasOffhook = p.getBoolean("offhook", false)
                e.putBoolean("ringing", false).putBoolean("offhook", false).apply()
                if (wasRinging || wasOffhook) {
                    val kind = when {
                        wasRinging && !wasOffhook -> "missed"
                        wasRinging -> "incoming"
                        else -> "outgoing"
                    }
                    onCallEnded(context, kind)
                }
                return
            }
        }
        e.apply()
    }

    private fun onCallEnded(context: Context, kind: String) {
        val key = when (kind) {
            "incoming" -> AppSettings.INCOMING
            "missed" -> AppSettings.MISSED
            else -> AppSettings.OUTGOING
        }
        if (!AppSettings.get(context, key)) return

        val pending = goAsync()
        // A short pause lets the phone app finish closing its own call screen first.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Backup prompt first; the popup removes it as soon as it opens.
                Notifier.showPostCallPrompt(context, kind)
                if (Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        Intent(context, PostCallActivity::class.java)
                            .putExtra(PostCallActivity.EXTRA_KIND, kind)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                }
            } catch (e: Exception) {
                // The backup notification is already showing.
            } finally {
                pending.finish()
            }
        }, 800L)
    }
}
