package com.callbackreminder.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CallLog
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Simple on/off switches saved on the phone. */
object AppSettings {
    const val INCOMING = "popup_incoming"
    const val OUTGOING = "popup_outgoing"
    const val MISSED = "popup_missed"

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun get(c: Context, key: String, default: Boolean = true) = p(c).getBoolean(key, default)
    fun set(c: Context, key: String, value: Boolean) = p(c).edit().putBoolean(key, value).apply()
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

data class QuickOption(val title: String, val whenMs: Long) {
    val subtitle: String get() = Fmt.dueLabel(whenMs)
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

fun dial(ctx: Context, number: String) {
    if (number.isBlank()) return
    ctx.startActivity(
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** What we learned about the call that just ended. */
data class CallInfo(
    val number: String,
    val name: String,
    val type: Int,
    val durationSec: Long,
    val endedAt: Long,
)

object CallLookup {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** The most recent call in the phone's call log, if it ended in the last couple of minutes. */
    fun latest(ctx: Context, maxAgeMs: Long = 120_000L): CallInfo? {
        if (!hasPermission(ctx)) return null
        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
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
                if (c.moveToFirst()) {
                    val duration = c.getLong(3)
                    val endedAt = c.getLong(4) + duration * 1000L
                    if (System.currentTimeMillis() - endedAt <= maxAgeMs) {
                        CallInfo(
                            number = c.getString(0) ?: "",
                            name = c.getString(1) ?: "",
                            type = c.getInt(2),
                            durationSec = duration,
                            endedAt = endedAt,
                        )
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Which permissions are already given. */
data class SetupStatus(
    val phone: Boolean,
    val callLog: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
) {
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
            return SetupStatus(
                phone = granted(ctx, Manifest.permission.READ_PHONE_STATE),
                callLog = granted(ctx, Manifest.permission.READ_CALL_LOG),
                notifications = notif,
                overlay = Settings.canDrawOverlays(ctx),
                battery = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            )
        }
    }
}
