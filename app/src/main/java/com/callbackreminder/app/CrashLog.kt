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
            write(app, error, "The app closed") // the app really closed: always kept, whatever the cause
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

    /**
     * v0.51 No signal or the server out of reach (Firestore "client is offline", no internet, a timeout): not a
     * crash. It tries again by itself, so it is never saved or shown.
     */
    fun isOffline(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 8) {
            when (e) {
                is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException,
                is java.net.NoRouteToHostException, is javax.net.ssl.SSLException -> return true
                is com.google.firebase.FirebaseNetworkException -> return true
                is com.google.firebase.firestore.FirebaseFirestoreException ->
                    if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE ||
                        e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) return true
                else -> {}
            }
            val m = e.message.orEmpty().lowercase(Locale.US)
            if ("client is offline" in m || "unable to resolve host" in m || "network is unreachable" in m) return true
            e = e.cause
            depth++
        }
        return false
    }

    /**
     * v0.60.3 Work that Android stopped on purpose ("Job was cancelled"): the screen closed, the app went to the
     * background, or the phone stopped a background check (e.g. the internet dropped). Not a crash, so never shown.
     */
    fun isStopped(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 8) {
            if (e is java.util.concurrent.CancellationException) return true // kotlinx "Job was cancelled" is this type too
            if (e is com.google.firebase.firestore.FirebaseFirestoreException &&
                e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.CANCELLED) return true
            e = e.cause
            depth++
        }
        return false
    }

    fun record(ctx: Context, error: Throwable, what: String) {
        if (isOffline(error) || isStopped(error)) {
            android.util.Log.i("CrashLog", "$what · offline or stopped, not a crash: ${error.message}")
            return
        }
        write(ctx, error, what)
    }

    private fun write(ctx: Context, error: Throwable, what: String) {
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
        // v0.51 the same problem is offered at most once a day (what failed + the error's first line).
        val sign = text.lineSequence().first().substringBefore(" · ") + "|" +
            text.lineSequence().drop(4).firstOrNull { it.isNotBlank() }.orEmpty().take(200)
        val prefs = activity.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (prefs.getString("last_sign", null) == sign && now - prefs.getLong("last_at", 0L) < 24 * 60 * 60_000L) return
        prefs.edit().putString("last_sign", sign).putLong("last_at", now).apply()
        AlertDialog.Builder(activity)
            .setTitle("Something went wrong last time")
            .setMessage("The app ran into a problem. Share the details so it can be fixed?")
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "RB360 problem report")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { activity.startActivity(Intent.createChooser(send, "Share the problem report")) }
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
