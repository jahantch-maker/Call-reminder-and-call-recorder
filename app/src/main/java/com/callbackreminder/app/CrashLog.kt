package com.callbackreminder.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the details of the last crash in a small file, and on the next open offers to share them,
 * so a crash on someone's phone can be fixed without connecting it to a computer.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, error, "The app closed")
            previous?.uncaughtException(thread, error)
        }
    }

    /** Runs [block], and if it fails, saves the details instead of closing the app. */
    inline fun guard(ctx: Context, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            record(ctx, e, what)
        }
    }

    fun record(ctx: Context, error: Throwable, what: String) {
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = buildString {
                appendLine("$what · $time")
                appendLine("App ${BuildConfigVersion.name(ctx)}")
                appendLine("Phone ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine()
                append(trace)
            }
            file(ctx).writeText(text.take(20_000))
        }
    }

    /** If something went wrong last time, asks whether to share the details. */
    fun offer(activity: Activity) {
        val f = file(activity)
        val text = runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        f.delete()
        AlertDialog.Builder(activity)
            .setTitle("Something went wrong last time")
            .setMessage("The app ran into a problem. Share the details so it can be fixed?")
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Callback Reminder problem report")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { activity.startActivity(Intent.createChooser(send, "Share the problem report")) }
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
