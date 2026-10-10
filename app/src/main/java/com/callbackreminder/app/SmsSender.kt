package com.callbackreminder.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Sets and cancels the alarms that send scheduled text messages. Same reliable alarm type as call-backs. */
object SmsScheduler {
    const val ACTION_SEND = "com.callbackreminder.app.SMS_DUE"
    const val EXTRA_ID = "sms_id"

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val intent = Intent(ctx, SmsAlarmReceiver::class.java)
            .setAction(ACTION_SEND)
            .setData(Uri.parse("callback://sms/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx,
            id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun schedule(ctx: Context, r: SmsReminder, notBefore: Long = 0L) {
        if (r.done) {
            cancel(ctx, r.id)
            return
        }
        val at = maxOf(r.alarmAt, notBefore)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, open), pending(ctx, r.id))
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
    }

    /**
     * After a restart all alarms are gone, so set them again. Messages whose time passed while the
     * phone was off fire right away; the sender then decides to send late or skip (see [SmsSender.LATE_LIMIT_MS]).
     */
    fun rescheduleAll(ctx: Context) {
        val soon = System.currentTimeMillis() + 5_000L
        SmsStore.items.value.filter { !it.done }.forEach { schedule(ctx, it, notBefore = soon) }
    }
}

/** Sends the messages from your own SIM and follows up on the result. */
object SmsSender {
    /** A message more than this late (the phone was off) is skipped, and a repeat moves on to its next time. */
    const val LATE_LIMIT_MS = 12 * 60 * 60_000L

    /** Tries after the first failure, 15 minutes apart, before we give up and alert you. */
    const val MAX_RETRIES = 3
    const val RETRY_GAP_MS = 15 * 60_000L

    const val ACTION_SENT = "com.callbackreminder.app.SMS_SENT"
    const val ACTION_DELIVERED = "com.callbackreminder.app.SMS_DELIVERED"
    const val EXTRA_ID = "sms_id"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_PARTS = "parts"

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** How many texts the message is split into (160 letters each, or 70 for Urdu and emoji). */
    fun partsFor(message: String): Int = try {
        SmsMessage.calculateLength(message, false)[0].coerceAtLeast(1)
    } catch (e: Exception) {
        1
    }

    /**
     * The alarm fired, or you tapped Send now ([manual]). Sends the current message, or hands it to
     * you to send from Messages when the SMS permission is off.
     */
    fun sendDue(ctx: Context, id: Long, manual: Boolean = false) {
        SmsStore.init(ctx)
        val r = SmsStore.get(id)?.takeIf { !it.done } ?: return
        val now = System.currentTimeMillis()

        if (!manual && r.retryAt == 0L && now - r.dueAt > LATE_LIMIT_MS) {
            SmsStore.finishOccurrence(id, SmsLogEntry(now, SmsLogEntry.Status.SKIPPED, "Was due ${Fmt.dueLabel(r.dueAt)}"))
            return
        }

        if (!hasPermission(ctx)) {
            if (manual) {
                openComposer(ctx, r)
            } else {
                SmsNotifier.showTapToSend(ctx, r)
            }
            SmsStore.finishOccurrence(id, SmsLogEntry(now, SmsLogEntry.Status.TAP_TO_SEND))
            return
        }

        // A send for this message is still waiting for its result: don't send it twice.
        if (InFlight.busy(ctx, id, now)) return

        send(ctx, r)
    }

    @SuppressLint("MissingPermission")
    private fun send(ctx: Context, r: SmsReminder) {
        val token = System.currentTimeMillis()
        try {
            val (manager, sim) = managerForDefaultSim(ctx)
            val parts = manager.divideMessage(r.message)
            InFlight.start(ctx, r.id, token, parts.size, sim)
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val sent = ArrayList<PendingIntent>()
            parts.indices.forEach { i ->
                val intent = Intent(ctx, SmsResultReceiver::class.java)
                    .setAction(ACTION_SENT)
                    .setData(Uri.parse("callback://sms-sent/${r.id}/$token/$i"))
                    .putExtra(EXTRA_ID, r.id)
                    .putExtra(EXTRA_TOKEN, token)
                    .putExtra(EXTRA_PARTS, parts.size)
                sent += PendingIntent.getBroadcast(ctx, 0, intent, flags)
            }
            val deliveredIntent = Intent(ctx, SmsResultReceiver::class.java)
                .setAction(ACTION_DELIVERED)
                .setData(Uri.parse("callback://sms-delivered/${r.id}/$token"))
                .putExtra(EXTRA_ID, r.id)
                .putExtra(EXTRA_TOKEN, token)
            // Only the last part asks for a delivery report: one "Delivered" per message is enough.
            val delivered = ArrayList<PendingIntent?>(parts.map { null })
            delivered[delivered.size - 1] = PendingIntent.getBroadcast(ctx, 0, deliveredIntent, flags)
            manager.sendMultipartTextMessage(r.number, null, parts, sent, delivered)
        } catch (e: Exception) {
            InFlight.clear(ctx, r.id)
            onFailed(ctx, r.id, e.message?.takeIf { it.isNotBlank() } ?: "Could not send")
        }
    }

    /** The SMS manager for the SIM the phone uses for texts, and a short label like "SIM 1 · Jazz". */
    @SuppressLint("MissingPermission")
    private fun managerForDefaultSim(ctx: Context): Pair<SmsManager, String> {
        var subId = SmsManager.getDefaultSmsSubscriptionId()
        var label = ""
        try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            val active = sm?.activeSubscriptionInfoList.orEmpty()
            // "Ask every time" on a dual-SIM phone leaves no default: use the first SIM.
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subId = active.firstOrNull()?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            val info = active.firstOrNull { it.subscriptionId == subId }
            if (info != null && active.size > 1) {
                label = "SIM ${info.simSlotIndex + 1}" +
                    (info.displayName?.toString()?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            }
        } catch (e: Exception) {
            // No phone-state permission: fall back to the phone's default below.
        }
        val manager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        } else if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        return manager to label
    }

    fun onSent(ctx: Context, id: Long, sim: String) {
        SmsStore.init(ctx)
        val now = System.currentTimeMillis()
        SmsStore.finishOccurrence(id, SmsLogEntry(now, SmsLogEntry.Status.SENT, sim))
        SmsStore.get(id)?.let { SmsNotifier.showSent(ctx, it) }
    }

    /** Try again in 15 minutes, up to [MAX_RETRIES] times, then give up on this one and alert you. */
    fun onFailed(ctx: Context, id: Long, reason: String) {
        SmsStore.init(ctx)
        val r = SmsStore.get(id)?.takeIf { !it.done } ?: return
        val now = System.currentTimeMillis()
        val attempts = r.attempts + 1
        if (attempts <= MAX_RETRIES) {
            SmsStore.setRetry(
                id,
                attempts,
                now + RETRY_GAP_MS,
                SmsLogEntry(now, SmsLogEntry.Status.RETRYING, "$reason · retry $attempts of $MAX_RETRIES")
            )
        } else {
            SmsNotifier.showFailed(ctx, r, reason)
            SmsStore.finishOccurrence(id, SmsLogEntry(now, SmsLogEntry.Status.FAILED, reason))
        }
    }

    fun reasonFor(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No signal"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile network off (airplane mode?)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Message could not be prepared"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Network refused it (check balance or SIM)"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Too many texts sent, phone paused sending"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Phone blocked sending to this number"
        else -> "Error $resultCode"
    }

    /** Opens your Messages app with the number and text filled in; you tap Send. */
    fun openComposer(ctx: Context, r: SmsReminder) {
        ctx.startActivity(composerIntent(r).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun composerIntent(r: SmsReminder): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", r.number, null))
            .putExtra("sms_body", r.message)

    /** Remembers a send that is waiting for its result, so parts can be counted and repeats ignored. */
    object InFlight {
        private fun p(c: Context) = c.getSharedPreferences("sms_inflight", Context.MODE_PRIVATE)

        fun start(c: Context, id: Long, token: Long, parts: Int, sim: String) {
            p(c).edit()
                .putLong("$id.token", token)
                .putInt("$id.parts", parts)
                .putInt("$id.ok", 0)
                .putString("$id.sim", sim)
                .apply()
        }

        /** A send started in the last 2 minutes has not reported back yet. */
        fun busy(c: Context, id: Long, now: Long): Boolean {
            val token = p(c).getLong("$id.token", 0L)
            return token > 0L && now - token < 2 * 60_000L
        }

        fun clear(c: Context, id: Long) {
            p(c).edit().remove("$id.token").remove("$id.parts").remove("$id.ok").remove("$id.sim").apply()
        }

        /**
         * One part went out. Returns the SIM label once every part of this send is out, else null.
         * Results from an older send (wrong [token]) are ignored.
         */
        @Synchronized
        fun partSent(c: Context, id: Long, token: Long): String? {
            val prefs = p(c)
            if (prefs.getLong("$id.token", 0L) != token) return null
            val ok = prefs.getInt("$id.ok", 0) + 1
            val parts = prefs.getInt("$id.parts", 1)
            if (ok < parts) {
                prefs.edit().putInt("$id.ok", ok).commit()
                return null
            }
            val sim = prefs.getString("$id.sim", "").orEmpty()
            clear(c, id)
            return sim
        }

        /** A part failed. True the first time for this send, so the failure is handled once. */
        @Synchronized
        fun partFailed(c: Context, id: Long, token: Long): Boolean {
            if (p(c).getLong("$id.token", 0L) != token) return false
            clear(c, id)
            return true
        }
    }
}

/** Fires when a scheduled message is due (or a retry is due). */
class SmsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(SmsScheduler.EXTRA_ID, -1L)
        if (id < 0) return
        SmsSender.sendDue(context, id)
    }
}

/** Hears back from the phone whether each part went out, and later whether it was delivered. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SmsStore.init(context)
        val id = intent.getLongExtra(SmsSender.EXTRA_ID, -1L)
        val token = intent.getLongExtra(SmsSender.EXTRA_TOKEN, 0L)
        if (id < 0) return
        when (intent.action) {
            SmsSender.ACTION_SENT -> {
                if (resultCode == Activity.RESULT_OK) {
                    val sim = SmsSender.InFlight.partSent(context, id, token) ?: return
                    SmsSender.onSent(context, id, sim)
                } else if (SmsSender.InFlight.partFailed(context, id, token)) {
                    SmsSender.onFailed(context, id, SmsSender.reasonFor(resultCode))
                }
            }
            SmsSender.ACTION_DELIVERED -> {
                if (delivered(intent)) {
                    SmsStore.addLog(id, SmsLogEntry(System.currentTimeMillis(), SmsLogEntry.Status.DELIVERED))
                }
            }
        }
    }

    /** Reads the delivery report. Some networks send none at all; then nothing is logged. */
    private fun delivered(intent: Intent): Boolean {
        val pdu = intent.getByteArrayExtra("pdu") ?: return resultCode == Activity.RESULT_OK
        return try {
            val msg = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"))
            // 0 means "received" for GSM; CDMA reports 0 too for success.
            msg == null || msg.status == 0
        } catch (e: Exception) {
            true
        }
    }
}

/** Buttons on the "couldn't send" notification. */
class SmsActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SEND_NOW = "com.callbackreminder.app.SMS_SEND_NOW"
        const val EXTRA_ID = "sms_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        SmsNotifier.cancel(context, id)
        if (intent.action == ACTION_SEND_NOW) SmsSender.sendDue(context, id, manual = true)
    }
}

object SmsNotifier {
    const val CH_SMS_SENT = "sms_sent"
    private const val ID_BASE = 700_000
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_SMS_SENT, "Sent text messages", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note each time a scheduled text message goes out"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun openMessages(ctx: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        ctx,
        ID_BASE + id.toInt(),
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        FLAGS
    )

    private fun composer(ctx: Context, r: SmsReminder): PendingIntent = PendingIntent.getActivity(
        ctx,
        ID_BASE + 100_000 + r.id.toInt(),
        SmsSender.composerIntent(r),
        FLAGS
    )

    /** "Sent to Ali ✓", no sound. */
    @SuppressLint("MissingPermission")
    fun showSent(ctx: Context, r: SmsReminder) {
        if (!Notifier.canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, CH_SMS_SENT)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Sent to ${r.title} ✓")
            .setContentText(r.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.message))
            .setContentIntent(openMessages(ctx, r.id))
            .setAutoCancel(true)
            .setSilent(true)
        NotificationManagerCompat.from(ctx).notify(ID_BASE + r.id.toInt(), b.build())
    }

    /** Gave up after the retries: alerts you with sound, with Try again and Open in Messages. */
    @SuppressLint("MissingPermission")
    fun showFailed(ctx: Context, r: SmsReminder, reason: String) {
        if (!Notifier.canNotify(ctx)) return
        val retry = PendingIntent.getBroadcast(
            ctx,
            ID_BASE + r.id.toInt(),
            Intent(ctx, SmsActionReceiver::class.java)
                .setAction(SmsActionReceiver.ACTION_SEND_NOW)
                .setData(Uri.parse("callback://sms-action/${r.id}"))
                .putExtra(SmsActionReceiver.EXTRA_ID, r.id),
            FLAGS
        )
        val text = "$reason. \"${r.message}\""
        val b = NotificationCompat.Builder(ctx, Notifier.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Couldn't send text to ${r.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openMessages(ctx, r.id))
            .setAutoCancel(true)
            .addAction(0, "Try again", retry)
            .addAction(0, "Open in Messages", composer(ctx, r))
        NotificationManagerCompat.from(ctx).notify(ID_BASE + r.id.toInt(), b.build())
    }

    /** SMS permission is off: tap to send it yourself from your Messages app. */
    @SuppressLint("MissingPermission")
    fun showTapToSend(ctx: Context, r: SmsReminder) {
        if (!Notifier.canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, Notifier.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Text ${r.title} now")
            .setContentText(r.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(composer(ctx, r))
            .setAutoCancel(true)
            .addAction(0, "Open in Messages", composer(ctx, r))
        NotificationManagerCompat.from(ctx).notify(ID_BASE + r.id.toInt(), b.build())
    }

    fun cancel(ctx: Context, id: Long) {
        NotificationManagerCompat.from(ctx).cancel(ID_BASE + id.toInt())
    }
}
