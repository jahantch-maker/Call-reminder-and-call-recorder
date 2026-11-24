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
import android.provider.Telephony
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
        if (!r.active) {
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
        am.setClockSafely(AlarmManager.AlarmClockInfo(at, open), pending(ctx, r.id))
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
        SmsStore.items.value.filter { it.active }.forEach { schedule(ctx, it, notBefore = soon) }
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
    const val EXTRA_INDEX = "index"

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** How many texts the message is split into (160 letters each, or 70 for Urdu and emoji). */
    fun partsFor(message: String): Int = try {
        SmsMessage.calculateLength(message, false)[0].coerceAtLeast(1)
    } catch (e: Exception) {
        1
    }

    /** A burst text this much past its time (the phone was off) is skipped. */
    private const val BURST_GRACE_MS = 2 * 60_000L

    /**
     * The alarm fired, or you tapped Send now ([manual]). Sends the current text, or hands it to you to
     * send from Messages when the SMS permission is off. Send now on a burst starts the whole burst now.
     */
    fun sendDue(ctx: Context, id: Long, manual: Boolean = false) {
        SmsStore.init(ctx)
        var r = SmsStore.get(id)?.takeIf { it.active } ?: return
        val now = System.currentTimeMillis()

        if (r.isBurst) {
            if (manual) {
                // A follow-up's texts at set times: Send now sends the next one, the rest keep their times.
                if (r.slots.isEmpty()) r = SmsStore.restartBurstNow(id) ?: return
            } else {
                // Texts whose time passed while the phone was off are skipped; carry on from the next one.
                // (Not when it was only waiting for room under Android's limit.)
                val late = if (SmsQuota.waiting(ctx, id)) 0
                else (r.burstIndex until r.burstCount).count { i -> r.textAt(i) < now - BURST_GRACE_MS }
                if (late > 0) {
                    val first = r.burstIndex + 1
                    val last = r.burstIndex + late
                    val which = if (late == 1) "Reminder $first" else "Reminder $first to $last"
                    SmsStore.advanceBurst(id, SmsLogEntry(now, SmsLogEntry.Status.SKIPPED, which), handled = late)
                    return
                }
            }
        } else if (!manual && r.retryAt == 0L && now - r.dueAt > LATE_LIMIT_MS) {
            SmsStore.finishOccurrence(id, SmsLogEntry(now, SmsLogEntry.Status.SKIPPED, "Was due ${Fmt.dueLabel(r.dueAt)}"))
            return
        }

        // The first tracked text going out gives the reminder its task number (untracked ones never need one).
        if (r.tracked) r = SmsStore.ensureCode(id) ?: return
        val index = r.burstIndex
        val text = r.textFor(index)
        if (!hasPermission(ctx)) {
            if (manual) openComposer(ctx, r, text) else SmsNotifier.showTapToSend(ctx, r, text)
            val entry = SmsLogEntry(now, SmsLogEntry.Status.TAP_TO_SEND, if (r.isBurst) "Reminder ${index + 1}" else "")
            if (r.isBurst) SmsStore.advanceBurst(id, entry) else SmsStore.finishOccurrence(id, entry)
            return
        }

        // A single text still waiting for its result: don't send it twice.
        if (!r.isBurst && InFlight.busy(ctx, id, now)) return

        // Android's limit: wait for room (and let queued daily lists go first). Send now goes at once.
        val parts = partsFor(text)
        if (!manual && (SmsQuota.room(ctx, now) < parts || DailyList.queued(ctx))) {
            val at = maxOf(SmsQuota.roomAt(ctx, parts, now), if (DailyList.queued(ctx)) now + 60_000L else now)
            SmsQuota.markWaiting(ctx, id)
            SmsScheduler.schedule(ctx, r, notBefore = at)
            SmsQuota.tipOnce(ctx)
            return
        }
        SmsQuota.clearWaiting(ctx, id)
        SmsQuota.record(ctx, parts, now)
        send(ctx, r, text, if (r.isBurst) index else -1)
        // A burst moves on as soon as a text is handed to the phone; its result is only logged, so a lost
        // result can never hold up the rest of the burst.
        if (r.isBurst) SmsStore.advanceBurst(id, null)
    }

    /** [index] is the burst text's number from 0, or -1 for a single text. */
    @SuppressLint("MissingPermission")
    private fun send(ctx: Context, r: SmsReminder, text: String, index: Int) {
        val token = System.currentTimeMillis()
        try {
            val (manager, sim) = managerForDefaultSim(ctx)
            val parts = manager.divideMessage(text)
            InFlight.start(ctx, r.id, token, parts.size, sim, index)
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
                .putExtra(EXTRA_INDEX, index)
            // Only the last part asks for a delivery report: one "Delivered" per message is enough.
            val delivered = ArrayList<PendingIntent?>(parts.map { null })
            delivered[delivered.size - 1] = PendingIntent.getBroadcast(ctx, 0, deliveredIntent, flags)
            manager.sendMultipartTextMessage(r.number, null, parts, sent, delivered)
        } catch (e: Exception) {
            InFlight.clear(ctx, r.id)
            onFailed(ctx, r.id, e.message?.takeIf { it.isNotBlank() } ?: "Could not send", index)
        }
    }

    /** The SMS manager for the SIM the phone uses for texts, and a short label like "SIM 1 · Jazz". */
    @SuppressLint("MissingPermission")
    internal fun managerForDefaultSim(ctx: Context): Pair<SmsManager, String> {
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

    /** [index] is the burst text's number from 0, or -1 for a single text. */
    fun onSent(ctx: Context, id: Long, sim: String, index: Int) {
        SmsStore.init(ctx)
        val now = System.currentTimeMillis()
        if (index >= 0) {
            SmsStore.addLog(id, SmsLogEntry(now, SmsLogEntry.Status.SENT, listOf("Reminder ${index + 1}", sim).filter { it.isNotBlank() }.joinToString(" · ")))
            SmsStore.get(id)?.takeIf { it.review == null }?.let { SmsNotifier.showSent(ctx, it, index) }
            return
        }
        SmsStore.finishOccurrence(id, SmsLogEntry(now, SmsLogEntry.Status.SENT, sim))
        SmsStore.get(id)?.takeIf { it.review == null }?.let { SmsNotifier.showSent(ctx, it, -1) }
    }

    /**
     * A single text tries again in 15 minutes, up to [MAX_RETRIES] times, then gives up and alerts you.
     * A burst text is not retried: it is logged and the burst carries on.
     */
    fun onFailed(ctx: Context, id: Long, reason: String, index: Int) {
        SmsStore.init(ctx)
        val now = System.currentTimeMillis()
        if (index >= 0) {
            SmsStore.addLog(id, SmsLogEntry(now, SmsLogEntry.Status.FAILED, "Reminder ${index + 1} · $reason"))
            return
        }
        val r = SmsStore.get(id)?.takeIf { it.active } ?: return
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
    fun openComposer(ctx: Context, r: SmsReminder, text: String = r.message) {
        ctx.startActivity(composerIntent(r, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun composerIntent(r: SmsReminder, text: String = r.message): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", r.number, null))
            .putExtra("sms_body", text)

    /** Remembers a send that is waiting for its result, so parts can be counted and repeats ignored. */
    object InFlight {
        private fun p(c: Context) = c.getSharedPreferences("sms_inflight", Context.MODE_PRIVATE)

        fun start(c: Context, id: Long, token: Long, parts: Int, sim: String, index: Int) {
            p(c).edit()
                .putLong("$id.token", token)
                .putInt("$id.index", index)
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
            p(c).edit().remove("$id.token").remove("$id.parts").remove("$id.ok").remove("$id.sim").remove("$id.index").apply()
        }

        /** Which burst text the waiting send is (-1 for a single text). Read before [partSent] clears it. */
        fun index(c: Context, id: Long): Int = p(c).getInt("$id.index", -1)

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
                val index = SmsSender.InFlight.index(context, id)
                if (resultCode == Activity.RESULT_OK) {
                    val sim = SmsSender.InFlight.partSent(context, id, token) ?: return
                    SmsSender.onSent(context, id, sim, index)
                } else if (SmsSender.InFlight.partFailed(context, id, token)) {
                    SmsSender.onFailed(context, id, SmsSender.reasonFor(resultCode), index)
                }
            }
            SmsSender.ACTION_DELIVERED -> {
                if (delivered(intent)) {
                    val index = intent.getIntExtra(SmsSender.EXTRA_INDEX, -1)
                    SmsStore.addLog(
                        id,
                        SmsLogEntry(
                            System.currentTimeMillis(),
                            SmsLogEntry.Status.DELIVERED,
                            if (index >= 0) "Reminder ${index + 1}" else ""
                        )
                    )
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
/** Buttons on the text-message notifications: Try again, Mark done, Not done. */
class SmsActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SEND_NOW = "com.callbackreminder.app.SMS_SEND_NOW"
        const val ACTION_MARK_DONE = "com.callbackreminder.app.SMS_MARK_DONE"
        const val ACTION_NOT_DONE = "com.callbackreminder.app.SMS_NOT_DONE"
        const val EXTRA_ID = "sms_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        SmsStore.init(context)
        SmsNotifier.cancel(context, id)
        when (intent.action) {
            ACTION_SEND_NOW -> SmsSender.sendDue(context, id, manual = true)
            ACTION_MARK_DONE -> SmsStore.markDone(id)
            ACTION_NOT_DONE -> SmsStore.resume(id)
        }
    }
}

/**
 * Hears incoming texts, only when "Stop when the person texts back the task number" is on. Only texts from a
 * person with an open task number in them count:
 *  - the number alone, or with a "done" word (done, ok, ho gaya, kar diya, ✓ …): that task goes to To review;
 *  - the number with anything else ("26102003 material nahi aya"): kept as a reply to read (see [ReplyNotes]),
 *    never taken as Done;
 *  - no open number in it: ignored, and nothing about it is read or kept.
 */
class SmsReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!BurstTemplates.stopOnReply(context)) return
        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            null
        } ?: return
        val from = parts.firstOrNull()?.originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        TaskReplies.handle(context, from, body)
    }
}

object TaskReplies {
    /** Words that mean "done" around a task number. Longer ones first, so "ho gaya" goes before "ho". */
    private val DONE_WORDS = listOf(
        "kar diya", "kar dia", "kr diya", "kr dia", "kardiya", "kardia", "krdiya", "krdia",
        "ho gaya", "ho gya", "ho gia", "hogaya", "hogya", "hogia",
        "done", "okay", "ok", "okk", "jee", "ji",
    )

    /** The task numbers in [body], or null when it holds anything but digits, spaces and "done" words. */
    fun digitsOf(body: String): String? {
        var t = body.lowercase()
        DONE_WORDS.forEach { w -> t = t.replace(Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])"), " ") }
        // Punctuation, ✓, ✅ and other emoji around the number don't change what it says.
        t = t.filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
        if (t.isEmpty() || !t.all { it.isDigit() || it.isWhitespace() }) return null
        return t.filter { it.isDigit() }.takeIf { it.length >= 8 }
    }

    /** Open task numbers written anywhere in [body] (spaces inside a number are fine). */
    fun numbersIn(body: String, open: Set<String>): List<String> =
        Regex("\\d[\\d ]*\\d").findAll(body).toList().flatMap { m ->
            val digits = m.value.filter { it.isDigit() }
            if (digits.length < 8) emptyList()
            else split(digits, open) ?: open.filter { digits.contains(it) }
        }.distinct()

    /** Cuts [digits] into numbers from [open], all of it; null when some part isn't one of them. */
    fun split(digits: String, open: Set<String>): List<String>? {
        val best = arrayOfNulls<List<String>>(digits.length + 1)
        best[0] = emptyList()
        for (i in 0 until digits.length) {
            val so = best[i] ?: continue
            open.forEach { c -> if (c.isNotEmpty() && digits.startsWith(c, i) && best[i + c.length] == null) best[i + c.length] = so + c }
        }
        return best[digits.length]?.distinct()
    }

    /** Every open task number of the person texting from [from]: texts, calls and old moved texts. */
    fun openCodes(ctx: Context, from: String): Set<String> {
        SmsStore.init(ctx)
        ReminderStore.init(ctx)
        return (SmsStore.items.value.filter { !it.done && it.review != SmsReminder.Review.REPLIED && it.code.isNotEmpty() && SmsStore.sameNumber(it.number, from) }.map { it.code } +
            ReminderStore.items.value.filter { !it.done && it.code.isNotEmpty() && SmsStore.sameNumber(it.number, from) }.map { it.code } +
            TextToCall.codesFor(ctx, from)).toSet()
    }

    fun handle(ctx: Context, from: String, body: String) {
        val open = openCodes(ctx, from)
        if (open.isEmpty()) return
        val codes = digitsOf(body)?.let { split(it, open) }
        if (codes != null) {
            codes.forEach { ReplyNotes.clear(ctx, it); close(ctx, from, it) }
            return
        }
        // More than a "done": keep it for you to read, never as Done.
        numbersIn(body, open).forEach { ReplyNotes.add(ctx, from, it, body.trim()) }
    }

    private fun close(ctx: Context, from: String, code: String) {
        val replied = SmsStore.markReplied(from, code)
        if (replied != null) {
            SmsNotifier.showReview(ctx, replied)
            FollowUps.onReplied(ctx, replied)
            return
        }
        val call = ReminderStore.items.value.firstOrNull { !it.done && it.code == code && SmsStore.sameNumber(it.number, from) }
        if (call != null) {
            ReminderStore.logEvent(call.id, "Texted back task number $code")
            ReminderStore.markDone(call.id, review = true)
            TextToCall.notifyReplied(ctx, call, code)
            return
        }
        // The text was changed to a call (Call instead): its old number still counts.
        TextToCall.replied(ctx, from, code)
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

    /** Opens the Text messages screen; with [editId], opens that reminder's Edit window too. */
    private fun openMessages(ctx: Context, id: Long, editId: Long = -1L): PendingIntent = PendingIntent.getActivity(
        ctx,
        ID_BASE + id.toInt() + if (editId >= 0) 200_000 else 0,
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
            .putExtra(MainActivity.EXTRA_EDIT_SMS, editId)
            .putExtra(MainActivity.EXTRA_SHOW_SMS, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        FLAGS
    )

    private fun composer(ctx: Context, r: SmsReminder, text: String = r.message): PendingIntent = PendingIntent.getActivity(
        ctx,
        ID_BASE + 100_000 + r.id.toInt(),
        SmsSender.composerIntent(r, text),
        FLAGS
    )

    private fun action(ctx: Context, r: SmsReminder, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        ID_BASE + code + r.id.toInt(),
        Intent(ctx, SmsActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("callback://sms-action/$action/${r.id}"))
            .putExtra(SmsActionReceiver.EXTRA_ID, r.id),
        FLAGS
    )

    /** "Sent to Ali ✓" (or "Sent to Ali · 3 of 5 today" in a burst), no sound, updated in place. */
    @SuppressLint("MissingPermission")
    fun showSent(ctx: Context, r: SmsReminder, index: Int) {
        if (!Notifier.canNotify(ctx)) return
        // Task first: the message is the title; who it went to, the task number and progress under it.
        val text = listOfNotNull(
            r.title,
            r.code.takeIf { r.tracked && it.isNotEmpty() }?.let { "# $it" },
            if (index >= 0) "text ${index + 1} of ${r.burstCount} sent" else "sent ✓",
        ).joinToString(" · ")
        val b = NotificationCompat.Builder(ctx, CH_SMS_SENT)
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(r.message)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text).setBigContentTitle(r.message))
            .setContentIntent(openMessages(ctx, r.id))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        Notify.post(ctx, NotifType.TEXT_SENT, NotifIds.text(r.id), b)
    }

    /** Gave up after the retries: alerts you with sound, with Try again and Open in Messages. */
    @SuppressLint("MissingPermission")
    fun showFailed(ctx: Context, r: SmsReminder, reason: String) {
        if (!Notifier.canNotify(ctx)) return
        val text = "$reason. \"${r.message}\""
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle("Couldn't send text to ${r.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openMessages(ctx, r.id))
            .setAutoCancel(true)
            .addAction(0, "Try again", action(ctx, r, SmsActionReceiver.ACTION_SEND_NOW, 0))
            .addAction(0, "Open in Messages", composer(ctx, r))
        Notify.post(ctx, NotifType.TEXT_FAILED, NotifIds.text(r.id), b)
    }

    /** SMS permission is off: tap to send it yourself from your Messages app. */
    @SuppressLint("MissingPermission")
    fun showTapToSend(ctx: Context, r: SmsReminder, text: String) {
        if (!Notifier.canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(r.message)
            .setContentText("Tap to text ${r.title} now")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to text ${r.title} now:\n$text"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(composer(ctx, r, text))
            .setAutoCancel(true)
            .addAction(0, "Open in Messages", composer(ctx, r, text))
        Notify.post(ctx, NotifType.TAP_TO_SEND, NotifIds.text(r.id), b)
    }

    /**
     * Banner with sound when a reminder lands in To review: the person replied Done, or every text went
     * out with no reply. Mark done moves it to Done. Not done carries on (after a reply) or opens Edit
     * to set new days (after no reply).
     */
    @SuppressLint("MissingPermission")
    fun showReview(ctx: Context, r: SmsReminder) {
        if (!Notifier.canNotify(ctx)) return
        val replied = r.review == SmsReminder.Review.REPLIED
        val title = when {
            replied -> "${r.title} replied ${r.doneReply}"
            r.review == SmsReminder.Review.NOT_SENT -> "Text to ${r.title} not sent"
            r.tracked -> "No reply from ${r.title}"
            else -> "Text sent to ${r.title}"
        }
        val text = when {
            replied -> "Check the work: "
            r.review == SmsReminder.Review.NOT_SENT -> "Check it, or set a new time: "
            r.isBurst || r.repeat != null -> "All texts sent. Check the work: "
            else -> "Check the work: "
        } + r.message
        val notDone = if (replied) action(ctx, r, SmsActionReceiver.ACTION_NOT_DONE, 300_000)
        else openMessages(ctx, r.id, editId = r.id)
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openMessages(ctx, r.id))
            .setAutoCancel(true)
            .addAction(0, "Mark done", action(ctx, r, SmsActionReceiver.ACTION_MARK_DONE, 400_000))
            .addAction(0, "Not done", notDone)
        Notify.post(ctx, if (r.review == SmsReminder.Review.NOT_SENT) NotifType.TEXT_FAILED else NotifType.TEXT_REVIEW, NotifIds.text(r.id), b)
    }

    fun cancel(ctx: Context, id: Long) {
        NotificationManagerCompat.from(ctx).cancel(NotifIds.text(id))
    }
}

/**
 * Android lets an app send about 30 texts in 30 minutes; past that it asks "allow this app to send a large number of
 * messages?" or blocks them. Every part sent is counted here, and texts wait for room instead of going all at once.
 * Daily lists go first: a reminder text waits while lists are queued.
 */
object SmsQuota {
    const val LIMIT = 28
    private const val WINDOW = 30 * 60_000L
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("sms_quota", Context.MODE_PRIVATE)

    @Synchronized
    private fun log(c: Context, now: Long): List<Long> =
        p(c).getString("log", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { it > now - WINDOW }

    /** How many more parts can go now. */
    fun room(c: Context, now: Long = System.currentTimeMillis()) = LIMIT - log(c, now).size

    @Synchronized
    fun record(c: Context, parts: Int, now: Long = System.currentTimeMillis()) {
        val all = log(c, now) + List(parts.coerceAtLeast(1)) { now }
        p(c).edit().putString("log", all.joinToString(",")).apply()
    }

    /** When [parts] will fit (a few seconds after enough old ones leave the window). */
    fun roomAt(c: Context, parts: Int, now: Long = System.currentTimeMillis()): Long {
        val l = log(c, now).sorted()
        val over = l.size + parts - LIMIT
        if (over <= 0) return now
        return (l.getOrNull(over - 1) ?: now) + WINDOW + 5_000L
    }

    /** A burst text that waits for room is not "late" when its alarm comes back. */
    fun markWaiting(c: Context, id: Long) = p(c).edit().putLong("wait_$id", System.currentTimeMillis()).apply()
    fun waiting(c: Context, id: Long) = System.currentTimeMillis() - p(c).getLong("wait_$id", 0L) < WINDOW + 10 * 60_000L
    fun clearWaiting(c: Context, id: Long) = p(c).edit().remove("wait_$id").apply()

    /** The one-time "tick Always allow" tip, shown the first time texts have to wait. */
    @SuppressLint("MissingPermission")
    fun tipOnce(ctx: Context) {
        if (p(ctx).getBoolean("tip_shown", false)) return
        p(ctx).edit().putBoolean("tip_shown", true).apply()
        if (!Notifier.canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setContentTitle("Some texts are waiting a few minutes")
            .setContentText("Android limits how many texts an app sends. If it asks, tick \"Always allow\".")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Android lets an app send about 30 texts in 30 minutes, so the rest go out a little later by themselves. " +
                    "If Android asks \"allow this app to send a large number of messages?\", tick \"Always allow\" and tap Allow."
            ))
            .setAutoCancel(true)
        NotificationManagerCompat.from(ctx).notify(NOTIF_TIP, b.build())
    }

    private const val NOTIF_TIP = 1_099_510
}
