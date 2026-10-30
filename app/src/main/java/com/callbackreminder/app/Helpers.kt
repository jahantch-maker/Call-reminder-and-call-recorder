package com.callbackreminder.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as ContactPhone
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Simple on/off switches and choices saved on the phone. */
object AppSettings {
    const val INCOMING = "popup_incoming"
    const val OUTGOING = "popup_outgoing"
    const val MISSED = "popup_missed"
    const val WHATSAPP = "popup_whatsapp"
    const val RINGTONE = "reminder_ringtone"
    const val RINGTONE_REPEAT = "reminder_ringtone_repeat"
    const val BANNER_SOUND = "banner_sound"
    const val REMINDER_STYLE = "reminder_style"
    const val POPUP_SECONDS = "reminder_popup_seconds"

    // Call recording
    const val REC_PHONE = "rec_phone"
    const val REC_WHATSAPP = "rec_whatsapp"
    const val REC_SOURCE = "rec_source"
    const val REC_AUTO_INDEX = "rec_auto_index"
    const val REC_KEEP_DAYS = "rec_keep_days"
    const val REC_STATUS = "rec_status"
    const val REC_SPEED = "rec_speed"

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun get(c: Context, key: String, default: Boolean = true) = p(c).getBoolean(key, default)
    fun set(c: Context, key: String, value: Boolean) = p(c).edit().putBoolean(key, value).apply()
    fun getString(c: Context, key: String, default: String = ""): String =
        p(c).getString(key, default) ?: default

    fun setString(c: Context, key: String, value: String) = p(c).edit().putString(key, value).apply()
}

/** The short ping sounds built into the app (res/raw). Saved as "app:<key>". */
object AppSounds {
    class Sound(val key: String, val title: String, val res: Int)

    val ALL = listOf(
        Sound("ding_dong", "Ding Dong", R.raw.ping_ding_dong),
        Sound("electronic", "Electronic Ping", R.raw.ping_electronic),
        Sound("new_notification", "New Notification", R.raw.ping_new_notification),
        Sound("bright", "Bright", R.raw.ping_bright),
        Sound("message", "Message Ping", R.raw.ping_message),
        Sound("sleek_chime", "Sleek Chime", R.raw.ping_sleek_chime),
        Sound("ui_ding", "UI Ding", R.raw.ping_ui_ding),
        Sound("metallic_echo", "Metallic Echo", R.raw.ping_metallic_echo),
        Sound("classic_ding", "Classic Ding", R.raw.ping_classic_ding),
        Sound("quick_ding", "Quick Ding", R.raw.ping_quick_ding),
    )

    private const val PREFIX = "app:"

    fun value(s: Sound) = PREFIX + s.key

    fun find(saved: String): Sound? =
        if (saved.startsWith(PREFIX)) ALL.firstOrNull { it.key == saved.removePrefix(PREFIX) } else null

    /** By name, not number, so the link stays right after an app update (notification channels keep it). */
    fun uri(ctx: Context, s: Sound): Uri =
        Uri.parse("android.resource://${ctx.packageName}/raw/${ctx.resources.getResourceEntryName(s.res)}")
}

/**
 * One sound choice. Saved as "" (the phone's own sound), "app:<key>" (a built-in ping) or the
 * content link of a sound picked from the phone.
 */
class SoundSetting(
    private val key: String,
    private val default: String,
    val phoneTitle: String,
    val phoneType: Int,
) {
    fun saved(ctx: Context): String = AppSettings.getString(ctx, key, default)

    fun set(ctx: Context, value: String) = AppSettings.setString(ctx, key, value)

    fun builtIn(ctx: Context): AppSounds.Sound? = AppSounds.find(saved(ctx))

    fun isPhoneDefault(ctx: Context) = saved(ctx).isBlank()

    fun uri(ctx: Context): Uri? {
        val saved = saved(ctx)
        AppSounds.find(saved)?.let { return AppSounds.uri(ctx, it) }
        return if (saved.isBlank()) RingtoneManager.getDefaultUri(phoneType) else Uri.parse(saved)
    }

    fun title(ctx: Context): String {
        val saved = saved(ctx)
        AppSounds.find(saved)?.let { return it.title }
        if (saved.isBlank()) return phoneTitle
        return try {
            RingtoneManager.getRingtone(ctx, Uri.parse(saved))?.getTitle(ctx) ?: "Custom sound"
        } catch (e: Exception) {
            "Custom sound"
        }
    }
}

/**
 * The sounds reminders use. [POPUP] rings the full-screen reminder (the phone's ringtone unless
 * changed). [BANNER] is for notification banners: call-back banners and text-message alerts.
 */
object ReminderSound {
    val POPUP = SoundSetting(AppSettings.RINGTONE, "", "Phone ringtone (default)", RingtoneManager.TYPE_RINGTONE)
    val BANNER = SoundSetting(
        AppSettings.BANNER_SOUND,
        "app:message",
        "Phone notification sound",
        RingtoneManager.TYPE_NOTIFICATION
    )

    fun uri(ctx: Context): Uri? = POPUP.uri(ctx)

    fun title(ctx: Context): String = POPUP.title(ctx)

    /** Popup sound plays again and again until you react (on), or just once (off). */
    fun repeats(ctx: Context): Boolean = AppSettings.get(ctx, AppSettings.RINGTONE_REPEAT, true)

    fun setRepeats(ctx: Context, on: Boolean) = AppSettings.set(ctx, AppSettings.RINGTONE_REPEAT, on)
}

/** Turns times into friendly text like "Tomorrow, 10:00 AM". */
object Fmt {
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun dueLabel(ms: Long): String {
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(ms).atZone(zone)
        val today = LocalDate.now(zone)
        val t = dt.format(timeFmt)
        return when (dt.toLocalDate()) {
            today -> "Today, $t"
            today.plusDays(1) -> "Tomorrow, $t"
            today.minusDays(1) -> "Yesterday, $t"
            else -> "${dt.format(dayFmt)}, $t"
        }
    }

    /** Like "4:30 PM". */
    fun timeOnly(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

    /** Like "Fri 16 Oct, 5:00 PM": for text messages, where "Today" would be wrong by the time it is read. */
    fun fullDate(ms: Long): String {
        val dt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return "${dt.format(dayFmt)}, ${dt.format(timeFmt)}"
    }

    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = ms - now
        val mins = kotlin.math.abs(diff) / 60_000L
        if (mins < 1) return "Due now"
        val text = when {
            mins < 60 -> "$mins min"
            mins < 60 * 24 -> "${mins / 60} h"
            else -> "${mins / (60 * 24)} d"
        }
        return if (diff < 0) "Overdue by $text" else "In $text"
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60} min"
    }
}

data class QuickOption(val title: String, val whenMs: Long, val subtitleText: String? = null) {
    val subtitle: String get() = subtitleText ?: Fmt.dueLabel(whenMs)
}

/** The one-tap choices shown in the popup. */
fun quickOptions(now: Long = System.currentTimeMillis()): List<QuickOption> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun at(date: LocalDate, hour: Int) =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    return listOf(
        QuickOption("In 1 hour", now + 3_600_000L),
        QuickOption("In 3 hours", now + 3 * 3_600_000L),
        QuickOption("Tomorrow morning", at(today.plusDays(1), 10)),
        QuickOption("Tomorrow evening", at(today.plusDays(1), 16)),
        QuickOption("In 2 days", at(today.plusDays(2), 10)),
        QuickOption("Next Monday", at(nextMonday, 10)),
    )
}

fun hasCallPhonePermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Calls [number] right away on the SIM, with no dialer screen in between. This also works from the
 * lock screen: the call is placed and the phone's in-call screen shows over the lock screen.
 * If the Call permission has not been given yet, it falls back to opening the dialer.
 */
fun dial(ctx: Context, number: String) {
    if (number.isBlank()) return
    CallerCard.dialling(number)
    val uri = Uri.fromParts("tel", number.trim(), null)

    if (hasCallPhonePermission(ctx)) {
        try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
            return
        } catch (e: Exception) {
            // Try the plain call intent next.
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Fall back to the dialer below.
        }
    }
    ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** One call from the phone's call log. [id] is -1 when we could not find the call in the log. */
data class CallInfo(
    val id: Long,
    val number: String,
    val name: String,
    val type: Int,
    val durationSec: Long,
    val endedAt: Long,
    val viaWhatsApp: Boolean = false,
    /** The task this call followed up on (Call back on a due reminder), or -1. */
    val followUpId: Long = -1L,
    /** The text task this call followed up on (Call now on a text in To review), or -1. */
    val textTaskId: Long = -1L,
    /** "Call again" from the popup: the note typed before calling again, kept for the popup that follows. */
    val prefillNote: String = "",
    /** "Call again": the lengths (seconds) of the earlier calls in this round, comma separated. */
    val earlier: String = "",
    /** The call log's PHONE_ACCOUNT_ID: which SIM the call went through ("" when unknown). */
    val accountId: String = "",
) {
    /** Every call in this round, oldest first: the earlier ones, then this one. */
    val roundDurations: List<Long>
        get() = earlier.split(",").mapNotNull { it.trim().toLongOrNull() } + durationSec
}

object CallLookup {
    private val COLUMNS = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.TYPE,
        CallLog.Calls.DURATION,
        CallLog.Calls.DATE,
        CallLog.Calls.PHONE_ACCOUNT_ID,
    )

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** The highest call-log row number right now (0 when the log is empty or unreadable). */
    fun maxId(ctx: Context): Long {
        if (!hasPermission(ctx)) return 0L
        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                "${CallLog.Calls._ID} DESC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Every call logged after row [afterId] (and not older than [notBefore]), oldest first. */
    fun since(ctx: Context, afterId: Long, notBefore: Long): List<CallInfo> {
        if (!hasPermission(ctx)) return emptyList()
        return try {
            val out = mutableListOf<CallInfo>()
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                COLUMNS,
                "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(afterId.toString(), notBefore.toString()),
                "${CallLog.Calls.DATE} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val duration = c.getLong(4)
                    out.add(
                        CallInfo(
                            id = c.getLong(0),
                            number = c.getString(1) ?: "",
                            name = c.getString(2) ?: "",
                            type = c.getInt(3),
                            durationSec = duration,
                            endedAt = c.getLong(5) + duration * 1000L,
                            accountId = c.getString(6) ?: "",
                        )
                    )
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** The newest [limit] calls in the phone's call log, newest first (empty without permission). */
fun CallLookup.recent(ctx: Context, limit: Int): List<CallInfo> {
    if (!hasPermission(ctx)) return emptyList()
    return try {
        val out = mutableListOf<CallInfo>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.add(
                    CallInfo(
                        id = c.getLong(0),
                        number = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        type = c.getInt(3),
                        durationSec = c.getLong(4),
                        endedAt = c.getLong(5),
                    )
                )
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

/** Opens the after-call popup (and a backup notification in case the phone blocks the popup). */
fun showPostCallPopup(ctx: Context, queue: List<CallInfo>) {
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

/** Finds a phone number for a contact name (WhatsApp shows the name, not the number). */
object ContactLookup {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** WhatsApp shows the number itself for people who are not in your contacts. */
    private fun looksLikeNumber(s: String): Boolean =
        s.count { it.isDigit() } >= 6 && s.all { it.isDigit() || it in "+-() \u00A0" }

    /** The number for [name], or "" when it cannot be found (or contacts are not allowed). */
    fun numberFor(ctx: Context, name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return ""
        if (looksLikeNumber(n)) return n
        if (!hasPermission(ctx)) return ""
        return try {
            ctx.contentResolver.query(
                ContactPhone.CONTENT_URI,
                arrayOf(ContactPhone.NUMBER, ContactPhone.TYPE),
                "${ContactPhone.DISPLAY_NAME} = ?",
                arrayOf(n),
                null
            )?.use { c ->
                var first = ""
                var mobile = ""
                while (c.moveToNext()) {
                    val num = c.getString(0)?.trim().orEmpty()
                    if (num.isEmpty()) continue
                    if (first.isEmpty()) first = num
                    if (mobile.isEmpty() && c.getInt(1) == ContactPhone.TYPE_MOBILE) mobile = num
                }
                mobile.ifEmpty { first }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

/** The saved contact name for [number], or "" when it is not in your contacts (or contacts are not allowed). */
fun ContactLookup.nameFor(ctx: Context, number: String): String {
    if (number.isBlank() || !hasPermission(ctx)) return ""
    return try {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number.trim())),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" } ?: ""
    } catch (e: Exception) {
        ""
    }
}

/** Which permissions are already given. */
data class SetupStatus(
    val phone: Boolean,
    val callLog: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
    val fullScreen: Boolean = true,
    val callPhone: Boolean = true,
    val contacts: Boolean = true,
    val notificationAccess: Boolean = true,
    val microphone: Boolean = true,
    val accessibility: Boolean = true,
) {
    /** True when everything call recording needs is switched on. */
    val recordingReady: Boolean get() = microphone && accessibility

    val requiredOk: Boolean get() = phone && callLog && notifications && overlay
    val missingRequired: Int get() = listOf(phone, callLog, notifications, overlay).count { !it }

    companion object {
        private fun granted(ctx: Context, perm: String) =
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

        fun read(ctx: Context): SetupStatus {
            val notif = if (Build.VERSION.SDK_INT >= 33) {
                granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            }
            val pm = ctx.getSystemService(PowerManager::class.java)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val fullScreen = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
            return SetupStatus(
                phone = granted(ctx, Manifest.permission.READ_PHONE_STATE),
                callLog = granted(ctx, Manifest.permission.READ_CALL_LOG),
                notifications = notif,
                overlay = Settings.canDrawOverlays(ctx),
                battery = pm.isIgnoringBatteryOptimizations(ctx.packageName),
                fullScreen = fullScreen,
                callPhone = granted(ctx, Manifest.permission.CALL_PHONE),
                contacts = granted(ctx, Manifest.permission.READ_CONTACTS),
                notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(ctx)
                    .contains(ctx.packageName),
                microphone = granted(ctx, Manifest.permission.RECORD_AUDIO),
                accessibility = RecorderAccessibilityService.isEnabled(ctx),
            )
        }
    }
}

/** A person's first name for labels ("Text Sana", "Sana's texts") instead of "him" / "his". */
object Names {
    // Titles saved in front of a name ("Mr Ali", "Engr Kamran", "Haji Aslam") are skipped.
    private val TITLES = setOf(
        "mr", "mrs", "ms", "miss", "sir", "madam", "mam", "maam", "dr", "doctor", "engr", "eng", "prof",
        "haji", "hajji", "hafiz", "janab", "mohtarma", "bhai", "baji",
    )

    /** The first name, or null when there is none (then say "Send text", "Show all tasks" and the like). */
    fun first(name: String?): String? {
        val words = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val word = words.firstOrNull { it.trimEnd('.', ',').lowercase() !in TITLES } ?: words.first()
        // A number saved as the name is not a name.
        return word.trimEnd('.', ',').takeIf { w -> w.any { it.isLetter() } }
    }

    /** "Sana's", or [none] without a name. */
    fun possessive(name: String?, none: String = "their"): String = first(name)?.let { "$it's" } ?: none
}
