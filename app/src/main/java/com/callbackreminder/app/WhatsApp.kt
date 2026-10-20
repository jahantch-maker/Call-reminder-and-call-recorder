package com.callbackreminder.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WhatsApp's own "click to chat": his chat opens with the message already typed and you press Send yourself.
 * The app never sends anything on WhatsApp (no auto-send, so no ban risk). Settings › WhatsApp picks the app
 * (WhatsApp or WhatsApp Business) once and holds the message, with {number} {task} {due} {name} filled in.
 */
object WhatsApp {
    const val PKG = "com.whatsapp"
    const val PKG_BUSINESS = "com.whatsapp.w4b"

    const val DEFAULT_MESSAGE = "Task {number}: {task}\nDue: {due}\nPlease update."
    val PLACEHOLDERS = listOf("{number}", "{task}", "{due}", "{name}")

    private fun p(c: Context) = c.getSharedPreferences("whatsapp", Context.MODE_PRIVATE)

    fun installed(c: Context, pkg: String): Boolean = try {
        c.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The chosen app; until you pick one, whichever is installed (normal WhatsApp first). */
    fun app(c: Context): String = p(c).getString("app", null)
        ?: if (!installed(c, PKG) && installed(c, PKG_BUSINESS)) PKG_BUSINESS else PKG

    fun setApp(c: Context, pkg: String) = p(c).edit().putString("app", pkg).apply()

    fun appLabel(pkg: String) = if (pkg == PKG_BUSINESS) "WhatsApp Business" else "WhatsApp"

    fun template(c: Context): String = p(c).getString("message", null) ?: DEFAULT_MESSAGE
    fun setTemplate(c: Context, text: String) = p(c).edit().putString("message", text).apply()

    private val dueFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())

    fun dueLabel(at: Long): String =
        if (at <= 0L) "No time" else Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(dueFmt)

    /** The reminder with your wording. A missing task number drops its "Task {number}:" part. */
    fun message(c: Context, code: String, task: String, due: Long, name: String): String {
        var t = template(c)
        if (code.isBlank()) t = t.replace("Task {number}: ", "").replace("{number}", "")
        return t.replace("{number}", code)
            .replace("{task}", task.trim())
            .replace("{due}", dueLabel(due))
            .replace("{name}", name.trim())
            .trim()
    }

    /** "0300 1234567" → "923001234567" (country from the SIM, Pakistan if unknown). */
    fun international(c: Context, number: String): String {
        val digits = number.filter { it.isDigit() }
        if (number.trim().startsWith("+")) return digits
        if (digits.startsWith("00")) return digits.drop(2)
        val iso = runCatching { c.getSystemService(TelephonyManager::class.java)?.simCountryIso }
            .getOrNull()?.uppercase(Locale.ROOT)?.ifBlank { null } ?: "PK"
        val e164 = runCatching { PhoneNumberUtils.formatNumberToE164(number, iso) }.getOrNull()
        return e164?.filter { it.isDigit() } ?: digits
    }

    /**
     * Opens his chat with [text] typed, on the number that has WhatsApp when he has two or more ([WaNumbers]):
     * asks once when the app can't tell. Returns false (with a short note) if it couldn't.
     */
    fun open(c: Context, number: String, text: String): Boolean {
        if (number.isBlank()) {
            Toast.makeText(c, "No number for WhatsApp", Toast.LENGTH_SHORT).show()
            return false
        }
        val to = WaNumbers.resolve(c, number) { picked ->
            Toast.makeText(c, "Opening WhatsApp on $picked", Toast.LENGTH_SHORT).show()
        }
        if (to == null) {
            WaNumberActivity.pickAndOpen(c, number, text)
            return true
        }
        return launch(c, to, text)
    }

    /** Opens the chat on exactly [number], no questions. */
    fun launch(c: Context, number: String, text: String): Boolean {
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${international(c, number)}&text=${Uri.encode(text)}")
        val pkg = app(c)
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(if (installed(c, pkg)) Intent(base).setPackage(pkg) else base)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(c, "${appLabel(pkg)} is not installed", Toast.LENGTH_SHORT).show()
            false
        }
    }

    // ---- What a reminder says ----

    private fun callCode(c: Context, r: Reminder): String =
        r.code.ifEmpty { TaskCodes.next(c).first().also { ReminderStore.setCode(r.id, it) } }

    private fun textCode(t: SmsReminder): String =
        t.code.ifEmpty { SmsStore.newCode().also { SmsStore.setCode(t.id, it) } }

    /** A follow-up's call (and its texts): one task, one number. */
    fun forCall(c: Context, r: Reminder): Boolean {
        val code = callCode(c, r)
        return open(c, r.number, message(c, code, r.note.ifBlank { "Call back" }, r.dueAt, r.name))
    }

    fun forText(c: Context, t: SmsReminder): Boolean {
        val call = if (t.linkId != 0L) ReminderStore.items.value.firstOrNull { it.linkId == t.linkId } else null
        if (call != null) return forCall(c, call)
        val due = t.taskDue.takeIf { it > 0L } ?: if (t.review != null) t.reviewAt else t.nextTextAt
        return open(c, t.number, message(c, textCode(t), t.message, due, t.name))
    }

    /** A row on the Review page or the session card. */
    fun forItem(c: Context, item: ReviewItem): Boolean = when (item.kind) {
        ReviewItem.Kind.CALL, ReviewItem.Kind.CALL_REVIEW -> ReminderStore.get(item.id)?.let { forCall(c, it) } ?: false
        ReviewItem.Kind.TEXT, ReviewItem.Kind.TEXT_REVIEW -> SmsStore.get(item.id)?.let { forText(c, it) } ?: false
        else -> open(c, item.number, message(c, "", item.title, item.at, item.person))
    }
}
