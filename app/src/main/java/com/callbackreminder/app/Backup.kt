package com.callbackreminder.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Nightly backup: at 11 PM every day the app writes one file with all its lists and settings (calls,
 * texts, My tasks, dossier, history, recording notes) to a folder you picked once. Recordings themselves
 * are left out. The newest 14 files are kept. Restore reads one back and restarts the app.
 * On from the start: until you pick a folder, the last 7 nights go to the app's own folder on the phone
 * (Android's own Google backup of the app copies them too).
 */
object Backup {
    const val ACTION_FIRE = "com.callbackreminder.app.BACKUP"
    private const val REQ = 1_099_400
    private const val NOTIF_ID = 1_099_401
    const val KEEP = 14
    private const val KEEP_LOCAL = 7
    const val PREFIX = "callback-backup-"
    private const val MIME = "application/json"
    private const val HOUR = 23

    /** Phone-call bookkeeping and the backup's own settings never go into (or come back from) a file. */
    private val SKIP = setOf("callstate", "sms_inflight", "backup", "caller_card_runtime")

    private fun p(c: Context) = c.getSharedPreferences("backup", Context.MODE_PRIVATE)

    // ---- Settings ----

    fun folder(c: Context): Uri? = p(c).getString("folder", null)?.let { Uri.parse(it) }

    fun setFolder(c: Context, tree: Uri) {
        try {
            c.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Some folder pickers give a one-time permission only; the next backup will say so.
        }
        p(c).edit().putString("folder", tree.toString()).apply()
        schedule(c)
    }

    /** Until a folder is picked: the app's own folder on the phone. */
    private fun localDir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "backups").apply { mkdirs() }

    fun folderLabel(c: Context): String {
        val tree = folder(c) ?: return "On this phone (app folder) · choose a folder to keep it safer"
        return try {
            val id = DocumentsContract.getTreeDocumentId(tree)
            val provider = tree.authority.orEmpty()
            val where = when {
                "externalstorage" in provider -> id.substringAfter(':').ifBlank { "Phone storage" }.let { "Phone › $it" }
                "google" in provider -> "Google Drive › " + id.substringAfterLast('/').ifBlank { "folder" }
                else -> id.substringAfterLast(':').substringAfterLast('/').ifBlank { "Chosen folder" }
            }
            where
        } catch (e: Exception) {
            "Chosen folder"
        }
    }

    fun lastAt(c: Context) = p(c).getLong("last_at", 0L)
    fun lastError(c: Context): String = p(c).getString("last_error", "").orEmpty()
    fun lastFile(c: Context): Uri? = p(c).getString("last_file", null)?.let { Uri.parse(it) }

    fun summary(c: Context): String = when {
        lastError(c).isNotEmpty() -> "Last backup failed"
        folder(c) == null -> "Every night at 11 PM · on this phone"
        lastAt(c) > 0L -> "Every night at 11 PM · last ${Fmt.dueLabel(lastAt(c))}"
        else -> "Every night at 11 PM"
    }

    // ---- Alarm ----

    private fun alarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQ,
        Intent(ctx, TaskReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("callback://backup")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarm(ctx)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        var at = LocalDate.now(zone).atTime(HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        if (at <= now + 30_000L) at += 86_400_000L
        // An inexact alarm is fine for a backup and doesn't show the alarm icon in the status bar.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun fire(ctx: Context) {
        schedule(ctx)
        val error = backUpNow(ctx)
        if (error != null) notifyFailed(ctx, error)
    }

    // ---- Writing ----

    private fun prefsNames(ctx: Context): List<String> {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .filter { it !in SKIP }
            .sorted()
    }

    /** Every list and setting as one JSON object. */
    fun export(ctx: Context): JSONObject {
        val all = JSONObject()
        prefsNames(ctx).forEach { name ->
            val values = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                val typed = when (v) {
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                }
                if (typed != null) values.put(k, typed)
            }
            all.put(name, values)
        }
        return JSONObject()
            .put("app", "Callback Reminder")
            .put("format", 1)
            .put("version", BuildConfigVersion.name(ctx))
            .put("created", System.currentTimeMillis())
            .put("prefs", all)
            .put("db", Db.export(ctx))
    }

    /** Writes a backup now. Returns null when it worked, or what went wrong. */
    fun backUpNow(ctx: Context): String? {
        val tree = folder(ctx) ?: return backUpLocal(ctx)
        return try {
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.US))
            val file = DocumentsContract.createDocument(ctx.contentResolver, dir, MIME, "$PREFIX$stamp.json")
                ?: return "Couldn't create a file in the backup folder"
            ctx.contentResolver.openOutputStream(file)?.use { out ->
                out.write(export(ctx).toString().toByteArray(Charsets.UTF_8))
            } ?: return "Couldn't write to the backup folder"
            p(ctx).edit()
                .putLong("last_at", System.currentTimeMillis())
                .putString("last_error", "")
                .putString("last_file", file.toString())
                .apply()
            prune(ctx, tree)
            null
        } catch (e: SecurityException) {
            fail(ctx, "The app lost access to the backup folder. Choose it again in Settings › Backup.")
        } catch (e: Exception) {
            fail(ctx, e.message?.takeIf { it.isNotBlank() } ?: "Backup failed")
        }
    }

    private fun backUpLocal(ctx: Context): String? = try {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.US))
        val file = File(localDir(ctx), "$PREFIX$stamp.json")
        file.writeText(export(ctx).toString(), Charsets.UTF_8)
        p(ctx).edit()
            .putLong("last_at", System.currentTimeMillis())
            .putString("last_error", "")
            .putString("last_file", Uri.fromFile(file).toString())
            .apply()
        localDir(ctx).listFiles().orEmpty().filter { it.name.startsWith(PREFIX) }.sortedByDescending { it.name }
            .drop(KEEP_LOCAL).forEach { it.delete() }
        null
    } catch (e: Exception) {
        fail(ctx, e.message?.takeIf { it.isNotBlank() } ?: "Backup failed")
    }

    private fun fail(ctx: Context, why: String): String {
        p(ctx).edit().putString("last_error", why).apply()
        return why
    }

    /** One backup file in the folder. */
    data class Item(val uri: Uri, val name: String, val modified: Long)

    /** Backup files in the folder, newest first. */
    fun list(ctx: Context): List<Item> {
        val tree = folder(ctx) ?: return localDir(ctx).listFiles().orEmpty().filter { it.name.startsWith(PREFIX) }
            .map { Item(Uri.fromFile(it), it.name, it.lastModified()) }.sortedByDescending { it.name }
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val out = mutableListOf<Item>()
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    if (!name.startsWith(PREFIX)) continue
                    out += Item(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(2))
                }
            }
            out.sortedByDescending { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun prune(ctx: Context, tree: Uri) {
        list(ctx).drop(KEEP).forEach { old ->
            try {
                DocumentsContract.deleteDocument(ctx.contentResolver, old.uri)
            } catch (e: Exception) {
                // Leave it; the next night tries again.
            }
        }
    }

    // ---- Restoring ----

    /** Reads a backup file and puts everything back. Returns null when it worked, or what went wrong. */
    fun restore(ctx: Context, file: Uri): String? {
        val text = try {
            ctx.contentResolver.openInputStream(file)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } ?: return "Couldn't open that file"
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return "That isn't a Callback Reminder backup"
        }
        val all = root.optJSONObject("prefs") ?: return "That isn't a Callback Reminder backup"
        // Everything not in the file is cleared too, so the app matches the backup exactly.
        prefsNames(ctx).forEach { name ->
            if (!all.has(name)) ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        all.keys().forEach { name ->
            if (name in SKIP) return@forEach
            val values = all.optJSONObject(name) ?: return@forEach
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { k ->
                val o = values.optJSONObject(k)
                if (o != null) {
                    when (o.optString("t")) {
                        "s" -> e.putString(k, o.optString("v"))
                        "i" -> e.putInt(k, o.optInt("v"))
                        "l" -> e.putLong(k, o.optLong("v"))
                        "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                        "b" -> e.putBoolean(k, o.optBoolean("v"))
                        "set" -> {
                            val arr = o.optJSONArray("v") ?: JSONArray()
                            e.putStringSet(k, (0 until arr.length()).map { arr.optString(it) }.toSet())
                        }
                    }
                }
            }
            e.commit()
        }
        // The tasks themselves; an older file (no "db") is copied in again from its lists on the restart.
        Db.restore(ctx, root.optJSONObject("db"))
        p(ctx).edit().putBoolean("rearm", true).commit()
        return null
    }

    /** After a restore the app restarts; this sets every alarm again from the restored lists. */
    fun rearmIfRestored(ctx: Context) {
        if (!p(ctx).getBoolean("rearm", false)) return
        p(ctx).edit().putBoolean("rearm", false).apply()
        Scheduler.restoreAll(ctx, null)
        SmsScheduler.rescheduleAll(ctx)
        ReviewReminder.schedule(ctx)
        TaskAlarms.rescheduleAll(ctx)
    }

    /** Closes and reopens the app so every list is read again from the restored data. */
    fun restartApp(ctx: Context) {
        val launch = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(ctx, REQ + 5, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 400L, pi)
        Runtime.getRuntime().exit(0)
    }

    /** Opens the share sheet for the newest backup, e.g. to send it to Drive or to yourself on WhatsApp. */
    fun shareIntent(ctx: Context): Intent? {
        val file = lastFile(ctx) ?: list(ctx).firstOrNull()?.uri ?: return null
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(MIME)
                .putExtra(Intent.EXTRA_STREAM, file)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Save the backup to…"
        )
    }

    private fun notifyFailed(ctx: Context, why: String) {
        val open = PendingIntent.getActivity(
            ctx, NOTIF_ID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, Notifier.bannerChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle("Backup failed")
            .setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(why))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        Notify.post(ctx, NotifType.BACKUP_FAILED, NOTIF_ID, b)
    }
}

/** The app's version name, for the backup file. */
object BuildConfigVersion {
    fun name(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
