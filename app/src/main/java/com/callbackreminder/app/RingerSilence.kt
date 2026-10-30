package com.callbackreminder.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Keeps a call's ringtone out of a voice note: while you record, Do Not Disturb (alarms only) is on and your own
 * setting is put back when you stop, and again at the next start of the app if it was left on. It needs Do Not
 * Disturb access once; without it a ringing call pauses the recording instead ([watchRinging]).
 */
object RingerSilence {
    private const val KEY = "voice_silence_calls"
    private const val SAVED = "voice_saved_filter"

    private fun prefs(c: Context) = c.getSharedPreferences("voice_notes", Context.MODE_PRIVATE)
    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun enabled(c: Context) = AppSettings.get(c, KEY, true)
    fun setEnabled(c: Context, on: Boolean) = AppSettings.set(c, KEY, on)
    fun hasAccess(c: Context): Boolean = runCatching { nm(c).isNotificationPolicyAccessGranted }.getOrDefault(false)

    /** Opens the phone's Do Not Disturb access page for this app. */
    fun openAccessSettings(c: Context) {
        runCatching {
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun summary(c: Context): String = when {
        !enabled(c) -> "Calls ring while you record"
        hasAccess(c) -> "Calls are silenced while you record"
        else -> "Needs Do Not Disturb access"
    }

    /** Silences the ringer. True when it is silenced (the recording can ignore a call). */
    fun begin(c: Context): Boolean {
        if (!enabled(c) || !hasAccess(c)) return false
        return runCatching {
            val p = prefs(c)
            // A setting left over from a crash stays the one to go back to.
            if (!p.contains(SAVED)) p.edit().putInt(SAVED, nm(c).currentInterruptionFilter).apply()
            nm(c).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            true
        }.getOrDefault(false)
    }

    /** Puts your own Do Not Disturb setting back (safe to call any time). */
    fun end(c: Context) {
        val p = prefs(c)
        if (!p.contains(SAVED)) return
        val saved = p.getInt(SAVED, NotificationManager.INTERRUPTION_FILTER_ALL)
        runCatching {
            if (hasAccess(c)) {
                nm(c).setInterruptionFilter(
                    if (saved == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else saved
                )
            }
        }
        p.edit().remove(SAVED).apply()
    }

    /** Calls [onRing] when a call starts ringing, until the returned function is called. Needs READ_PHONE_STATE. */
    fun watchRinging(c: Context, onRing: () -> Unit): () -> Unit {
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return {}
        val granted = ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return {}
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { if (state == TelephonyManager.CALL_STATE_RINGING) onRing() }
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(c), cb)
                val undo: () -> Unit = { runCatching { tm.unregisterTelephonyCallback(cb) } }
                undo
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (state == TelephonyManager.CALL_STATE_RINGING) onRing()
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                val undo: () -> Unit = { @Suppress("DEPRECATION") runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) } }
                undo
            }
        }.getOrDefault({})
    }
}
