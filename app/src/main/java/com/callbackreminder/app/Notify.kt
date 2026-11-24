package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** How one kind of notification shows: not at all, quietly, or with your Notification ringtone. */
enum class NotifLevel(val label: String) { OFF("Off"), SILENT("Silent"), SOUND("Sound") }

/**
 * Every kind of notification the app posts, grouped as on Settings › Notifications. [locked] ones can't
 * be changed (the reason is shown instead of the switch).
 */
enum class NotifType(
    val group: String,
    val title: String,
    val description: String,
    val default: NotifLevel,
    val locked: String? = null,
) {
    CALLBACK(
        "Calls", "Call-back reminder", "The reminder itself, in your Reminder style", NotifLevel.SOUND,
        locked = "Always on: without it the app can't remind you."
    ),
    MISSED("Calls", "Unanswered reminder", "Left behind when a full-screen reminder times out", NotifLevel.SILENT),
    TEXT_REPLIED("Calls", "Reply to a moved text", "Someone texted back the number of a text you turned into a call", NotifLevel.SOUND),

    TEXT_SENT("Texts", "Each text sent", "\"Sent to Ali ✓\", also every text of a burst", NotifLevel.SILENT),
    TEXT_REVIEW("Texts", "Text to review", "A Done reply came in, or every text went out with no reply", NotifLevel.SOUND),
    TEXT_FAILED(
        "Texts", "Text failed or not sent", "After every retry failed", NotifLevel.SOUND,
        locked = "Always on: a text that fails quietly is the worst surprise."
    ),
    TAP_TO_SEND(
        "Texts", "Tap to send", "SMS permission is off, so you send it yourself", NotifLevel.SOUND,
        locked = "Always on: otherwise the text never goes out."
    ),
    DAILY_LIST("Texts", "Daily list sent", "\"Sent the list to Ali\"", NotifLevel.SILENT),
    DAILY_LIST_WA("Texts", "Daily lists for WhatsApp", "\"Daily lists ready\": you press Send for each one", NotifLevel.SOUND),

    MY_TASK("My tasks", "One-time task due", "With a Done button", NotifLevel.SOUND),
    DOSSIER("My tasks", "Dossier task due", "With a Done button", NotifLevel.SOUND),
    DOSSIER_BEFORE("My tasks", "Dossier early reminder", "The \"remind me before\" one", NotifLevel.SOUND),
    DOSSIER_NUDGE("My tasks", "Dossier reminder", "Your own dossier reminder times", NotifLevel.SOUND),

    REVIEW("Review", "Review reminder", "1 PM and 6 PM: what is waiting for you", NotifLevel.SOUND),
    MORNING("Review", "Morning summary", "Today's plan at 8 AM", NotifLevel.SOUND),

    RECORDING(
        "Recording and backup", "Recording in progress", "Shown while a call is recorded", NotifLevel.SILENT,
        locked = "Android needs it while the microphone records. It is already silent."
    ),
    BANKING("Recording and backup", "Recording is off", "Every 10 min after \"Switch off for banking\"", NotifLevel.SOUND),
    TEAM("Team", "Team tasks", "New task, Done, more time asked, date picked, not seen", NotifLevel.SOUND),

    BACKUP_FAILED(
        "Recording and backup", "Backup failed", "The nightly backup couldn't be saved", NotifLevel.SOUND,
        locked = "Always on: you need to know when your data isn't safe."
    ),
}

/**
 * v0.42: what a notification is about, with its own fixed colour and emoji. Notifications of one kind sit together
 * in the shade (an Android group: ⌄ opens it, each one keeps its buttons), the urgent kinds on top.
 */
enum class NotifKind(val emoji: String, val label: String, val color: Int, val urgent: Boolean = false) {
    STUCK("⚠", "Stuck", 0xFFE5484D.toInt(), true),
    ASKED("❓", "Questions", 0xFFF5B400.toInt(), true),
    DONE_CHECK("✅", "Done, check it", 0xFF22B573.toInt(), true),
    MY_TASKS("☑", "My tasks", 0xFF4C6FFF.toInt()),
    DOSSIER("📋", "Dossier", 0xFF9B5DE5.toInt()),
    FOLLOW_UPS("📞", "Follow-ups", 0xFFFF7A1A.toInt()),
    MESSAGES("💬", "Team messages", 0xFF1FB5A3.toInt());

    val group get() = "kind_$name"
    val summaryId get() = 38_000_000 + ordinal

    companion object {
        fun of(t: NotifType): NotifKind? = when (t) {
            NotifType.CALLBACK, NotifType.MISSED -> FOLLOW_UPS
            NotifType.MY_TASK -> MY_TASKS
            NotifType.DOSSIER, NotifType.DOSSIER_BEFORE, NotifType.DOSSIER_NUDGE -> DOSSIER
            else -> null
        }
    }
}

object Notify {
    /** For notifications set to Silent: shows in the shade, no sound or vibration. */
    const val CH_QUIET = "quiet_alerts"

    private fun p(c: Context) = c.getSharedPreferences("notif_levels", Context.MODE_PRIVATE)

    fun level(ctx: Context, t: NotifType): NotifLevel {
        if (t.locked != null) return t.default
        return p(ctx).getString(t.name, null)?.let { s -> NotifLevel.values().firstOrNull { it.name == s } } ?: t.default
    }

    fun setLevel(ctx: Context, t: NotifType, l: NotifLevel) {
        if (t.locked != null) return
        p(ctx).edit().putString(t.name, l.name).apply()
    }

    /** v0.44: each category ([NotifKind]) also has its own Off · Silent · Sound, on top of the kind's own level. */
    fun kindLevel(ctx: Context, k: NotifKind): NotifLevel =
        p(ctx).getString("kind_${k.name}", null)?.let { s -> NotifLevel.values().firstOrNull { it.name == s } } ?: NotifLevel.SOUND

    fun setKindLevel(ctx: Context, k: NotifKind, l: NotifLevel) {
        p(ctx).edit().putString("kind_${k.name}", l.name).apply()
    }

    /** The quieter of the kind's level and its category's (a locked kind is never silenced by its category). */
    private fun effective(ctx: Context, t: NotifType, kind: NotifKind?): NotifLevel {
        val own = level(ctx, t)
        if (kind == null || t.locked != null) return own
        return minOf(own, kindLevel(ctx, kind))
    }

    fun on(ctx: Context, t: NotifType) = level(ctx, t) != NotifLevel.OFF
    fun loud(ctx: Context, t: NotifType) = level(ctx, t) == NotifLevel.SOUND

    /** Like "12 with sound · 3 silent · 1 off". */
    fun summary(ctx: Context): String {
        val all = NotifType.values().map { level(ctx, it) }
        return listOfNotNull(
            all.count { it == NotifLevel.SOUND }.let { "$it with sound" },
            all.count { it == NotifLevel.SILENT }.takeIf { it > 0 }?.let { "$it silent" },
            all.count { it == NotifLevel.OFF }.takeIf { it > 0 }?.let { "$it off" },
        ).joinToString(" · ")
    }

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_QUIET, "Silent notifications", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifications you set to Silent in Settings › Notifications"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * Posts [b] as [id] the way Settings › Notifications says: skipped when Off, moved to the quiet
     * channel when Silent, and given the sound channel when Sound (for kinds that are quiet by default).
     */
    @SuppressLint("MissingPermission")
    fun post(ctx: Context, t: NotifType, id: Int, b: NotificationCompat.Builder, kind: NotifKind? = NotifKind.of(t)) {
        if (!Notifier.canNotify(ctx)) return
        when (effective(ctx, t, kind)) {
            NotifLevel.OFF -> return
            NotifLevel.SILENT -> {
                createChannel(ctx)
                b.setChannelId(CH_QUIET).setSilent(true).setDefaults(0).setPriority(NotificationCompat.PRIORITY_LOW)
            }
            NotifLevel.SOUND -> if (t.default == NotifLevel.SILENT) {
                b.setChannelId(Notifier.bannerChannel(ctx)).setSilent(false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL)
            }
        }
        if (kind == null) {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
            return
        }
        // "Callback Reminder · My tasks": Android always shows the app name, the category sits right after it.
        b.setSubText(kind.label)
        b.setColor(kind.color).setGroup(kind.group).setSortKey(if (kind.urgent) "0" else "1")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        if (kind.urgent && effective(ctx, t, kind) == NotifLevel.SOUND) b.setPriority(NotificationCompat.PRIORITY_MAX)
        val n = b.build()
        n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()?.let { title ->
            // "☑ Call bank manager": a title that starts with its own emoji (🔴, 📅…) keeps it.
            if (title.firstOrNull()?.isLetterOrDigit() == true) {
                b.setContentTitle("${kind.emoji} $title")
            }
        }
        val nm = NotificationManagerCompat.from(ctx)
        nm.notify(id, b.build())
        summary(ctx, kind, id, n.channelId)
    }

    /** The group's header: only once two of a kind are showing (one alone shows as it is). Silent, its children ring. */
    @SuppressLint("MissingPermission")
    private fun summary(ctx: Context, kind: NotifKind, id: Int, channel: String?) {
        val shown = runCatching {
            ctx.getSystemService(NotificationManager::class.java).activeNotifications
                .count { it.notification.group == kind.group && it.id != kind.summaryId && it.id != id }
        }.getOrDefault(0) + 1
        if (shown < 2) return
        val b = NotificationCompat.Builder(ctx, channel ?: CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_rb)
            .setColor(kind.color)
            .setContentTitle("${kind.emoji} ${kind.label} · $shown new")
            .setSubText(kind.label)
            .setGroup(kind.group)
            .setGroupSummary(true)
            .setSortKey(if (kind.urgent) "0" else "1")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
        NotificationManagerCompat.from(ctx).notify(kind.summaryId, b.build())
    }

    /** A group header left with nothing under it goes (the app was opened, or its notifications were cleared). */
    fun tidy(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val active = nm.activeNotifications
            NotifKind.values().forEach { k ->
                if (active.any { it.id == k.summaryId } && active.none { it.notification.group == k.group && it.id != k.summaryId })
                    nm.cancel(k.summaryId)
            }
        }
    }
}

/**
 * Every notification's number. Ids are small counters (1, 2, 3…) shared by calls, texts and My tasks, so each kind
 * gets its own range far above the fixed numbers (1, 42, 77_001, 1_099_xxx, 1_501_xxx); two kinds can never
 * replace each other.
 */
object NotifIds {
    fun call(id: Long) = 10_000_000 + id.toInt()
    fun text(id: Long) = 20_000_000 + id.toInt()
    fun task(id: Long) = 30_000_000 + id.toInt()
    fun taskBefore(id: Long) = 31_000_000 + id.toInt()
    fun dossier(id: Long) = 32_000_000 + id.toInt()
    fun dossierBefore(id: Long) = 33_000_000 + id.toInt()
    fun replied(id: Long) = 34_000_000 + id.toInt()
    fun dailyList(key: String) = 35_000_000 + (key.hashCode() and 0xFFFF)
    fun replyNote(code: String) = 36_000_000 + (code.hashCode() and 0xFFFF)
    fun team(key: String) = 37_000_000 + (key.hashCode() and 0xFFFF)
    /** "N follow-ups came due while your phone was off". */
    const val MISSED_WHILE_OFF = 1_099_500
}
