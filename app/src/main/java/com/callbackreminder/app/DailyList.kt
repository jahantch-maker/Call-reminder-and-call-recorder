package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The daily list: at each send time (the same for everyone, 9 AM, 1 PM and 6 PM to start with) each person
 * switched on (on their person page) gets every follow-up they still owe you, calls and texts alike, each with
 * its task number: late ones first, then ones with no time yet, then the rest by date (the person page's Pending
 * order). It is only a reminder: no reply is asked for. Nobody with nothing open gets one. My tasks and dossier are
 * never sent.
 * By SMS it goes out by itself and you get a quiet "Sent the list to Ali" (or an alert if it failed).
 * By WhatsApp the app can't send (no auto-send, so no ban risk): you get one "Daily lists ready" notification
 * and a send page that opens each chat with the list typed, and you press Send.
 */
object DailyList {
    const val ACTION_FIRE = "com.callbackreminder.app.DAILY_LIST"
    const val ACTION_SENT = "com.callbackreminder.app.DAILY_LIST_SENT"
    const val EXTRA_KEY = "person_key"
    const val EXTRA_NAME = "person_name"
    private const val REQ_DAILY = 1_099_200
    private const val DAY = 86_400_000L

    const val ACTION_WA_LATER = "com.callbackreminder.app.DAILY_LIST_WA_LATER"
    const val ACTION_WA_REMIND = "com.callbackreminder.app.DAILY_LIST_WA_REMIND"
    private const val NOTIF_WA = 1_099_250
    private const val REQ_WA_LATER = 1_099_251
    const val WA_LATER_MINUTES = 15

    const val VIA_SMS = "sms"
    const val VIA_WA = "wa"
    const val VIA_BOTH = "both"

    private fun p(c: Context) = c.getSharedPreferences("daily_list", Context.MODE_PRIVATE)

    // ---- Settings ----

    fun people(c: Context): Set<String> = p(c).getStringSet("people", emptySet()).orEmpty()
    fun isOn(c: Context, key: String) = key in people(c)
    fun setOn(c: Context, key: String, on: Boolean) {
        val now = people(c).toMutableSet().apply { if (on) add(key) else remove(key) }
        p(c).edit().putStringSet("people", now).apply()
        schedule(c)
    }

    val DEFAULT_TIMES = listOf(9 * 60, 13 * 60, 18 * 60)

    /** Send times, minutes after midnight, earliest first. */
    fun times(c: Context): List<Int> {
        val raw = p(c).getString("times", null) ?: return DEFAULT_TIMES
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until 24 * 60 }.distinct().sorted()
    }

    fun setTimes(c: Context, list: List<Int>) {
        p(c).edit().putString("times", list.distinct().sorted().joinToString(",")).apply()
        schedule(c)
    }

    /** Bit 0 = Monday … bit 6 = Sunday. Monday to Saturday to start with. */
    fun days(c: Context) = p(c).getInt("days", 0b0111111)
    fun setDays(c: Context, mask: Int) {
        p(c).edit().putInt("days", mask).apply()
        schedule(c)
    }

    fun lastSent(c: Context, key: String) = p(c).getLong("sent_$key", 0L)

    /** How this person gets the list: SMS (as before), WhatsApp or both. */
    fun via(c: Context, key: String): String = p(c).getString("via_$key", VIA_SMS) ?: VIA_SMS
    fun setVia(c: Context, key: String, via: String) = p(c).edit().putString("via_$key", via).apply()
    fun bySms(c: Context, key: String) = via(c, key) != VIA_WA
    fun byWhatsApp(c: Context, key: String) = via(c, key) != VIA_SMS

    fun timesLabel(c: Context): String = times(c).joinToString(", ") { Dossier.timeLabel(it) }.ifEmpty { "no times" }

    fun summary(c: Context): String {
        val n = people(c).size
        return timesLabel(c) + " · " + ReviewReminder.daysLabel(days(c)) + " · " +
            if (n == 0) "nobody yet" else "$n ${if (n == 1) "person" else "people"}"
    }

    // ---- The text ----

    private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dueFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())

    /** The list for [key], or null when they owe nothing. On [whatsApp] the heading is bold. */
    fun build(ctx: Context, key: String, now: Long = System.currentTimeMillis(), whatsApp: Boolean = false): String? {
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        ToScheduleStore.init(ctx)
        val zone = ZoneId.systemDefault()
        // The same person as on the person page: all their numbers (a team member's too).
        fun mine(number: String) = RecentPeople.key(number) == key || Work.personKey(number) == key
        val calls = ReminderStore.items.value.filter { !it.done && mine(it.number) }
        // A text they already answered with its number is with you now; one with no reply is still theirs.
        val texts = SmsStore.items.value.filter {
            !it.done && mine(it.number) && it.review != SmsReminder.Review.REPLIED
        }
        // Follow-ups still waiting in To schedule (no time yet) are theirs too: they are in the person's Pending list.
        val waiting = ToScheduleStore.items.value.filter { mine(it.number) }
        /** [noTime]: no date; [due] then holds when it was saved, only to order them. */
        data class Line(val due: Long, val text: String, val code: String, val noTime: Boolean = false)
        val lines = mutableListOf<Line>()
        val links = mutableSetOf<Long>()
        calls.forEach { r ->
            val code = r.code.ifEmpty { TaskCodes.next(ctx).first().also { ReminderStore.setCode(r.id, it) } }
            if (r.linkId != 0L) links += r.linkId
            lines += Line(r.dueAt, r.note.ifBlank { "Call back" }, code)
        }
        // A follow-up's texts are the same task as its call: one line.
        texts.filter { it.linkId == 0L || it.linkId !in links }.groupBy { if (it.linkId != 0L) "l${it.linkId}" else "t${it.id}" }.values.forEach { g ->
            val t = g.first()
            val code = t.code.ifEmpty { SmsStore.newCode().also { c -> SmsStore.setCode(t.id, c) } }
            val due = t.taskDue.takeIf { it > 0L } ?: if (t.review != null) t.reviewAt else t.nextTextAt
            lines += Line(due, t.message, code)
        }
        waiting.forEach { w ->
            val code = w.code.ifEmpty { TaskCodes.next(ctx).first().also { ToScheduleStore.put(w.copy(code = it)) } }
            lines += Line(w.createdAt, w.headline, code, noTime = true)
        }
        if (lines.isEmpty()) return null
        val name = (calls.map { it.createdAt to it.name } + texts.map { it.createdAt to it.name } + waiting.map { it.createdAt to it.name })
            .filter { it.second.isNotBlank() }.maxByOrNull { it.first }?.second
        // Late first (oldest first), then No time yet (oldest first), then the rest by due time: like the person page.
        val sorted = lines.sortedWith(compareBy<Line>({ if (it.noTime) 1 else if (it.due < now) 0 else 2 }, { it.due }))
        val body = sorted.mapIndexed { i, l ->
            if (l.noTime) "${i + 1}) ${l.code} | ${l.text} | No time yet"
            else {
                val day = Instant.ofEpochMilli(l.due).atZone(zone)
                "${i + 1}) ${l.code} | ${l.text} | Due ${day.format(dueFmt)}" + if (l.due < now) " (late)" else ""
            }
        }
        val head = (name?.let { "$it, your" } ?: "Your") + " open tasks (" + LocalDate.now(zone).format(dayFmt) + "):"
        return buildString {
            append(if (whatsApp) "*$head*" else head).append("\n")
            append(body.joinToString("\n"))
        }
    }

    // ---- Alarm ----

    private fun alarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQ_DAILY,
        Intent(ctx, TaskReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("callback://daily-list")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context, now: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarm(ctx)
        if (people(ctx).isEmpty()) {
            am.cancel(pi)
            return
        }
        val zone = ZoneId.systemDefault()
        val mins = times(ctx)
        val mask = days(ctx)
        val at = (0..7).map { LocalDate.now(zone).plusDays(it.toLong()) }
            .filter { mask and Dossier.dayOfWeekBit(it.dayOfWeek) != 0 && !OffDays.isOff(it) }
            .flatMap { d -> mins.map { m -> d.atTime(m / 60, m % 60).atZone(zone).toInstant().toEpochMilli() } }
            .sorted()
            .firstOrNull { it > now }
        if (at == null) {
            am.cancel(pi)
            return
        }
        val show = PendingIntent.getActivity(
            ctx, REQ_DAILY, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setClockSafely(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    fun fire(ctx: Context) {
        schedule(ctx, System.currentTimeMillis() + 60_000L)
        val wa = mutableListOf<String>()
        val sms = mutableListOf<String>()
        people(ctx).forEach { key ->
            // On the team now: the app brings him his tasks, no list.
            if (TeamDossier.isMemberKey(key)) return@forEach
            build(ctx, key) ?: return@forEach
            if (bySms(ctx, key)) sms += key
            if (byWhatsApp(ctx, key)) wa += key
        }
        if (sms.isNotEmpty()) startSmsRound(ctx, sms)
        if (wa.isNotEmpty()) startRound(ctx, wa, sms)
    }

    // ---- SMS: a queue under Android's limit, with "sent · waiting · failed" ----

    const val ACTION_FLUSH = "com.callbackreminder.app.DAILY_LIST_FLUSH"
    private const val REQ_FLUSH = 1_099_260

    /** This round's SMS lists: who, who went through, who failed. Waiting = the rest. */
    data class SmsRound(val at: Long, val keys: List<String>, val sent: Set<String>, val failed: Set<String>, val queue: List<String>) {
        val waiting: Int get() = keys.count { it !in sent && it !in failed }
    }

    private fun list(ctx: Context, k: String) = p(ctx).getString(k, "").orEmpty().split(',').filter { it.isNotBlank() }

    fun smsRound(ctx: Context) = SmsRound(
        p(ctx).getLong("sms_at", 0L), list(ctx, "sms_keys"), list(ctx, "sms_sent").toSet(), list(ctx, "sms_failed").toSet(),
        list(ctx, "sms_queue"),
    )

    /** Daily lists still waiting to go out (reminder texts wait for them). */
    fun queued(ctx: Context) = list(ctx, "sms_queue").isNotEmpty()

    private fun startSmsRound(ctx: Context, keys: List<String>) {
        p(ctx).edit()
            .putLong("sms_at", System.currentTimeMillis())
            .putString("sms_keys", keys.joinToString(","))
            .putString("sms_sent", "")
            .putString("sms_failed", "")
            .putString("sms_queue", keys.joinToString(","))
            .commit()
        flush(ctx)
    }

    /** Retry on the Daily list page: the failed ones go again. */
    fun retryFailed(ctx: Context) {
        val r = smsRound(ctx)
        if (r.failed.isEmpty()) return
        p(ctx).edit()
            .putString("sms_failed", "")
            .putString("sms_queue", (r.queue + r.failed).distinct().joinToString(","))
            .commit()
        flush(ctx)
    }

    /** Sends what fits under the limit now; the rest waits for an alarm when there is room. */
    @Synchronized
    fun flush(ctx: Context) {
        val queue = list(ctx, "sms_queue").toMutableList()
        while (queue.isNotEmpty()) {
            val key = queue.first()
            val text = build(ctx, key)
            if (text == null) {
                // Nothing open any more: nothing to send.
                queue.removeAt(0)
                mark(ctx, "sms_sent", key)
                continue
            }
            val parts = SmsSender.partsFor(text)
            if (SmsQuota.room(ctx) < parts && SmsSender.hasPermission(ctx)) {
                val at = SmsQuota.roomAt(ctx, parts)
                val show = PendingIntent.getActivity(
                    ctx, REQ_FLUSH, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                ctx.getSystemService(AlarmManager::class.java).setClockSafely(AlarmManager.AlarmClockInfo(at, show), flushAlarm(ctx))
                SmsQuota.tipOnce(ctx)
                break
            }
            queue.removeAt(0)
            p(ctx).edit().putString("sms_queue", queue.joinToString(",")).commit()
            if (SmsSender.hasPermission(ctx)) SmsQuota.record(ctx, parts)
            send(ctx, key, text)
        }
        p(ctx).edit().putString("sms_queue", queue.joinToString(",")).commit()
    }

    private fun flushAlarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQ_FLUSH,
        Intent(ctx, TaskReceiver::class.java).setAction(ACTION_FLUSH).setData(Uri.parse("callback://daily-list-flush")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @Synchronized
    private fun mark(ctx: Context, set: String, key: String) {
        val r = smsRound(ctx)
        if (key !in r.keys) return
        val now = (list(ctx, set) + key).distinct()
        val e = p(ctx).edit().putString(set, now.joinToString(","))
        // Sent after a retry: no longer failed.
        if (set == "sms_sent") e.putString("sms_failed", (r.failed - key).joinToString(","))
        e.commit()
    }

    // ---- WhatsApp: you send each one yourself ----

    /** This round's WhatsApp people (in order), who is sent, and who got it by SMS at the same time. */
    data class Round(val at: Long, val keys: List<String>, val sent: Set<String>, val sms: List<String>) {
        val left: List<String> get() = keys.filter { it !in sent }
    }

    private fun startRound(ctx: Context, wa: List<String>, sms: List<String>) {
        p(ctx).edit()
            .putLong("wa_at", System.currentTimeMillis())
            .putString("wa_keys", wa.joinToString(","))
            .putString("wa_sent", "")
            .putString("wa_sms", sms.joinToString(","))
            .apply()
        showReady(ctx)
    }

    fun round(ctx: Context): Round {
        fun list(k: String) = p(ctx).getString(k, "").orEmpty().split(',').filter { it.isNotBlank() }
        return Round(p(ctx).getLong("wa_at", 0L), list("wa_keys"), list("wa_sent").toSet(), list("wa_sms"))
    }

    /** Back from his chat: ticked. When everyone is done the notification goes. */
    fun markSent(ctx: Context, key: String) {
        val r = round(ctx)
        if (key !in r.keys || key in r.sent) return
        p(ctx).edit()
            .putString("wa_sent", (r.sent + key).joinToString(","))
            .putLong("sent_$key", System.currentTimeMillis())
            .apply()
        if (round(ctx).left.isEmpty()) cancelReady(ctx)
    }

    fun nameFor(ctx: Context, key: String): String = numberFor(ctx, key)?.first ?: key

    /** Opens his WhatsApp chat with the list typed. */
    fun openWhatsApp(ctx: Context, key: String): Boolean {
        val (_, number) = numberFor(ctx, key) ?: return false
        val text = build(ctx, key, whatsApp = true) ?: return false
        return WhatsApp.open(ctx, number, text)
    }

    @SuppressLint("MissingPermission")
    fun showReady(ctx: Context) {
        val r = round(ctx)
        val left = r.left
        if (left.isEmpty()) return
        val names = left.map { nameFor(ctx, it).substringBefore(' ') }
        val who = names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
        val open = PendingIntent.getActivity(
            ctx,
            NOTIF_WA,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DAILY_WA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val later = PendingIntent.getBroadcast(
            ctx,
            REQ_WA_LATER + 10,
            Intent(ctx, TaskReceiver::class.java).setAction(ACTION_WA_LATER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val time = Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val smsPart = if (r.sms.isNotEmpty()) " · ${r.sms.size} SMS sent" else ""
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Daily lists ready · $time")
            .setContentText("${left.size} on WhatsApp: $who$smsPart")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(false)
            .addAction(0, "Send on WhatsApp", open)
            .addAction(0, "Later $WA_LATER_MINUTES min", later)
        Notify.post(ctx, NotifType.DAILY_LIST_WA, NOTIF_WA, b)
    }

    fun cancelReady(ctx: Context) = androidx.core.app.NotificationManagerCompat.from(ctx).cancel(NOTIF_WA)

    /** "Later 15 min" on the notification: it comes back then if anyone is still waiting. */
    fun later(ctx: Context) {
        cancelReady(ctx)
        val at = System.currentTimeMillis() + WA_LATER_MINUTES * 60_000L
        val pi = PendingIntent.getBroadcast(
            ctx,
            REQ_WA_LATER,
            Intent(ctx, TaskReceiver::class.java).setAction(ACTION_WA_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val show = PendingIntent.getActivity(
            ctx, REQ_WA_LATER, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        ctx.getSystemService(AlarmManager::class.java).setClockSafely(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    // ---- Sending ----

    private fun numberFor(ctx: Context, key: String): Pair<String, String>? {
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        return (ReminderStore.items.value.map { Triple(it.createdAt, it.name, it.number) } +
            SmsStore.items.value.map { Triple(it.createdAt, it.name, it.number) })
            .filter { RecentPeople.key(it.third) == key }
            .maxByOrNull { it.first }
            ?.let { it.second.ifBlank { it.third } to it.third }
    }

    @SuppressLint("MissingPermission")
    fun send(ctx: Context, key: String, text: String) {
        val (name, number) = numberFor(ctx, key) ?: return
        if (!SmsSender.hasPermission(ctx)) {
            val compose = PendingIntent.getActivity(
                ctx,
                notifId(key),
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", text),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle("Send the daily list to $name")
                .setContentText("SMS permission is off. Tap to send it from Messages.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(compose)
                .setAutoCancel(true)
            Notify.post(ctx, NotifType.TAP_TO_SEND, notifId(key), b)
            // You send this one yourself: it no longer waits here.
            mark(ctx, "sms_sent", key)
            return
        }
        try {
            val (manager, _) = SmsSender.managerForDefaultSim(ctx)
            val parts = manager.divideMessage(text)
            val result = PendingIntent.getBroadcast(
                ctx,
                0,
                Intent(ctx, TaskReceiver::class.java)
                    .setAction(ACTION_SENT)
                    .setData(Uri.parse("callback://daily-list-sent/$key/${System.currentTimeMillis()}"))
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_NAME, name),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // Only the last part reports back: one result per list is enough.
            val sent = ArrayList<PendingIntent?>(parts.map { null })
            sent[sent.size - 1] = result
            manager.sendMultipartTextMessage(number, null, parts, sent, null)
        } catch (e: Exception) {
            failed(ctx, key, name, e.message?.takeIf { it.isNotBlank() } ?: "Could not send")
        }
    }

    /** The phone reported back on the last part of a list. */
    fun onResult(ctx: Context, intent: Intent, resultCode: Int) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (resultCode == Activity.RESULT_OK) {
            p(ctx).edit().putLong("sent_$key", System.currentTimeMillis()).apply()
            mark(ctx, "sms_sent", key)
            val b = NotificationCompat.Builder(ctx, Notify.CH_QUIET)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle("Sent the list to $name")
                .setContentText("All their open tasks")
                .setAutoCancel(true)
            Notify.post(ctx, NotifType.DAILY_LIST, notifId(key), b)
        } else {
            failed(ctx, key, name, SmsSender.reasonFor(resultCode))
        }
    }

    private fun failed(ctx: Context, key: String, name: String, reason: String) {
        mark(ctx, "sms_failed", key)
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Couldn't send the daily list to $name")
            .setContentText(reason)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.TEXT_FAILED, notifId(key), b)
    }

    private fun notifId(key: String) = NotifIds.dailyList(key)
}
