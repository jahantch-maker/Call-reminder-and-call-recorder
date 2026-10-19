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
 * The daily list: at each send time (the same for everyone, 9 AM, 1 PM and 6 PM to start with) the app texts
 * each person switched on (on their person page) every follow-up they still owe you, calls and texts alike,
 * each with its task number, overdue ones first. A note in Urdu at the end says to text back the task
 * number when it's done. Nobody with nothing open gets a text. My tasks and dossier are never sent.
 * You only get a quiet "Sent the list to Ali" (or an alert if it failed).
 */
object DailyList {
    const val ACTION_FIRE = "com.callbackreminder.app.DAILY_LIST"
    const val ACTION_SENT = "com.callbackreminder.app.DAILY_LIST_SENT"
    const val EXTRA_KEY = "person_key"
    const val EXTRA_NAME = "person_name"
    private const val REQ_DAILY = 1_099_200
    private const val NOTIF_BASE = 1_099_300
    private const val DAY = 86_400_000L

    /** Approved by Jahanzaib (2026-10-19): "After the work is done, text the task number to this number. Example: …" */
    const val URDU_NOTE = "کام مکمل ہونے کے بعد اسی نمبر پر ٹاسک نمبر بھیج دیں۔ مثال: "

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

    fun timesLabel(c: Context): String = times(c).joinToString(", ") { Dossier.timeLabel(it) }.ifEmpty { "no times" }

    fun summary(c: Context): String {
        val n = people(c).size
        return timesLabel(c) + " · " + ReviewReminder.daysLabel(days(c)) + " · " +
            if (n == 0) "nobody yet" else "$n ${if (n == 1) "person" else "people"}"
    }

    // ---- The text ----

    private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dueFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())

    /** The list for [key], or null when they owe nothing. */
    fun build(ctx: Context, key: String, now: Long = System.currentTimeMillis()): String? {
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        val zone = ZoneId.systemDefault()
        val calls = ReminderStore.items.value.filter { !it.done && RecentPeople.key(it.number) == key }
        // A text they already answered with its number is with you now; one with no reply is still theirs.
        val texts = SmsStore.items.value.filter {
            !it.done && RecentPeople.key(it.number) == key && it.review != SmsReminder.Review.REPLIED
        }
        data class Line(val due: Long, val text: String, val code: String)
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
        if (lines.isEmpty()) return null
        val name = (calls.map { it.createdAt to it.name } + texts.map { it.createdAt to it.name })
            .filter { it.second.isNotBlank() }.maxByOrNull { it.first }?.second
        // Overdue first (oldest first), then the rest by due time.
        val sorted = lines.sortedWith(compareBy<Line>({ if (it.due < now) 0 else 1 }, { it.due }))
        val body = sorted.mapIndexed { i, l ->
            val day = Instant.ofEpochMilli(l.due).atZone(zone)
            "${i + 1}) ${l.code} | ${l.text} | Due ${day.format(dueFmt)}" + if (l.due < now) " (late)" else ""
        }
        return buildString {
            append(name?.let { "$it, your" } ?: "Your").append(" open tasks (")
                .append(LocalDate.now(zone).format(dayFmt)).append("):\n")
            append(body.joinToString("\n"))
            append("\n\n").append(URDU_NOTE).append(sorted.first().code)
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
        people(ctx).forEach { key ->
            val text = build(ctx, key) ?: return@forEach
            send(ctx, key, text)
        }
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
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Couldn't send the daily list to $name")
            .setContentText(reason)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.TEXT_FAILED, notifId(key), b)
    }

    private fun notifId(key: String) = NOTIF_BASE + (key.hashCode() and 0xFFFF)
}
