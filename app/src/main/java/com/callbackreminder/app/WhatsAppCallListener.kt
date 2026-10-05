package com.callbackreminder.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * Shows the same "When should I remind you to call back?" popup after WhatsApp calls.
 *
 * WhatsApp calls never appear in the phone's call log, so we watch WhatsApp's call notifications
 * instead (this needs "Notification access" to be switched on for the app):
 *  - while a call is ringing or running, WhatsApp shows a call notification; when that notification
 *    goes away the call is over, and we open the popup.
 *  - when you miss a call, WhatsApp posts a "Missed call" notification; we open the popup for that.
 *
 * WhatsApp only gives us the contact's NAME, so we look the number up in your contacts. The call
 * back itself is then made on your SIM, like any other reminder.
 */
class WhatsAppCallListener : NotificationListenerService() {

    private enum class Kind { LIVE_CALL, MISSED_CALL, OTHER }

    /** The WhatsApp call that is going on right now. */
    private data class LiveCall(
        val name: String,
        val type: Int,
        val connectedAt: Long,
    )

    companion object {
        private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private const val UNKNOWN_TYPE = 0
        private const val END_DELAY_MS = 2_500L
        private const val MAX_REMEMBERED = 20

        private val worker = Executors.newSingleThreadExecutor()
        private val callWord = Regex("\\bcalls?\\b", RegexOption.IGNORE_CASE)
        private val hiddenMarks = "\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

        // Shared with CallEndReceiver (same file) so it can tell that a WhatsApp call just happened.
        private const val STATE_PREFS = "callstate"
        private const val KEY_LAST_ACTIVITY = "wa_last"

        /** True if a WhatsApp call was ringing, running or ending at or after [sinceMs]. */
        fun wasActiveSince(ctx: Context, sinceMs: Long): Boolean =
            ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_ACTIVITY, 0L) >= sinceMs
    }

    /** Leaves a timestamp so the SIM-call detector does not add a second, blank popup. */
    private fun markActivity() {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())
            .apply()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val endCheck = Runnable { checkCallEnded() }
    private var live: LiveCall? = null
    private val handledMissed = ArrayDeque<String>()

    // ---- Notification events (always delivered on the main thread) ----

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in PACKAGES) return
        val n: Notification = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val title = text(n, Notification.EXTRA_TITLE)
        val body = text(n, Notification.EXTRA_TEXT)
        when (classify(n, title, body)) {
            Kind.LIVE_CALL -> onLiveCall(n, title, body)
            Kind.MISSED_CALL -> onMissedCall(n, title, body)
            Kind.OTHER -> Unit
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in PACKAGES || live == null) return
        // WhatsApp swaps the "incoming" notification for an "ongoing" one when you answer, so give
        // it a moment before deciding that the call is really over.
        handler.removeCallbacks(endCheck)
        handler.postDelayed(endCheck, END_DELAY_MS)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                requestRebind(ComponentName(this, WhatsAppCallListener::class.java))
            } catch (e: Exception) {
                // The phone will reconnect us the next time notification access is toggled.
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(endCheck)
        super.onDestroy()
    }

    // ---- Working out what a notification is ----

    private fun classify(n: Notification, title: String, body: String): Kind {
        // A chat message that happens to say "missed your call" is not a call.
        if (n.category == Notification.CATEGORY_MESSAGE) return Kind.OTHER

        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val all = "$title $body".lowercase()
        if (n.category == "missed_call" ||
            (!ongoing && "missed" in all && callWord.containsMatchIn(all))
        ) {
            return Kind.MISSED_CALL
        }

        val channel = n.channelId?.lowercase().orEmpty()
        if (n.category == Notification.CATEGORY_CALL || "voip" in channel || "call" in channel) {
            return Kind.LIVE_CALL
        }
        return Kind.OTHER
    }

    private fun text(n: Notification, key: String): String =
        n.extras?.getCharSequence(key)?.toString().orEmpty()
            .filter { it !in hiddenMarks }
            .trim()

    /** The contact's name: whichever of title / text is not just a "voice call" label. */
    private fun pickName(title: String, body: String): String = when {
        title.isNotBlank() && !isLabel(title) -> title
        body.isNotBlank() && !isLabel(body) -> body
        else -> ""
    }

    private fun isLabel(s: String): Boolean =
        callWord.containsMatchIn(s) || s.equals("whatsapp", ignoreCase = true)

    // ---- A call is ringing or running ----

    private fun onLiveCall(n: Notification, title: String, body: String) {
        handler.removeCallbacks(endCheck)
        markActivity()
        val all = "$title $body".lowercase()
        val prev = live
        val now = System.currentTimeMillis()

        val connected = n.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true ||
            "ongoing" in all
        val type = when {
            "incoming" in all -> CallLog.Calls.INCOMING_TYPE
            "calling" in all -> CallLog.Calls.OUTGOING_TYPE
            else -> prev?.type ?: UNKNOWN_TYPE
        }
        live = LiveCall(
            name = pickName(title, body).ifBlank { prev?.name.orEmpty() },
            type = type,
            connectedAt = when {
                prev != null && prev.connectedAt > 0L -> prev.connectedAt
                connected -> now
                else -> 0L
            },
        )
    }

    /** Runs a moment after a WhatsApp notification disappeared. */
    private fun checkCallEnded() {
        val call = live ?: return
        val stillOn = try {
            activeNotifications?.any { sbn ->
                sbn.packageName in PACKAGES &&
                    classify(
                        sbn.notification,
                        text(sbn.notification, Notification.EXTRA_TITLE),
                        text(sbn.notification, Notification.EXTRA_TEXT)
                    ) == Kind.LIVE_CALL
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (stillOn) return
        live = null
        markActivity()

        val answered = call.connectedAt > 0L
        val type = when {
            answered -> call.type
            // You rang and nobody picked up: worth a call-back reminder.
            call.type == CallLog.Calls.OUTGOING_TYPE -> CallLog.Calls.OUTGOING_TYPE
            // An incoming call you never answered: the "Missed call" notification handles it.
            else -> return
        }
        val seconds = if (answered) (System.currentTimeMillis() - call.connectedAt) / 1000L else 0L
        offerPopup(call.name, type, seconds)
    }

    // ---- A missed call ----

    private fun onMissedCall(n: Notification, title: String, body: String) {
        // WhatsApp re-posts the same notification when other things change; only react once.
        val signature = "$title|$body|${n.`when`}"
        if (signature in handledMissed) return
        handledMissed.addLast(signature)
        while (handledMissed.size > MAX_REMEMBERED) handledMissed.removeFirst()

        offerPopup(pickName(title, body), CallLog.Calls.MISSED_TYPE, 0L)
    }

    // ---- Opening the popup ----

    private fun offerPopup(name: String, type: Int, durationSec: Long) {
        val app = applicationContext
        worker.execute {
            try {
                showPopupFor(app, name, type, durationSec)
            } catch (e: Exception) {
                // Nothing more we can do; the next call starts clean.
            }
        }
    }

    private fun showPopupFor(ctx: Context, name: String, type: Int, durationSec: Long) {
        if (!AppSettings.get(ctx, AppSettings.WHATSAPP)) return

        val incoming = AppSettings.get(ctx, AppSettings.INCOMING)
        val outgoing = AppSettings.get(ctx, AppSettings.OUTGOING)
        val missed = AppSettings.get(ctx, AppSettings.MISSED)
        val allowed = when (type) {
            CallLog.Calls.INCOMING_TYPE -> incoming
            CallLog.Calls.OUTGOING_TYPE -> outgoing
            CallLog.Calls.MISSED_TYPE -> missed
            else -> incoming || outgoing
        }
        if (!allowed) return

        val call = CallInfo(
            id = -1L,
            number = ContactLookup.numberFor(ctx, name),
            name = name,
            type = type,
            durationSec = durationSec,
            endedAt = System.currentTimeMillis(),
            viaWhatsApp = true,
        )
        showPostCallPopup(ctx, listOf(call))
    }
}
