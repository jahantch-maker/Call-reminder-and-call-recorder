package com.callbackreminder.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * v0.44: the after-call popup can be switched off per SIM (SIM 1 personal, SIM 2 work…), on each person's own
 * phone. A SIM switched off stays silent, except for numbers that still have an open task with you, so a work
 * call made from the personal SIM is never lost.
 */
object SimPopup {
    data class Sim(val slot: Int, val subId: Int, val iccId: String, val label: String)

    private fun key(slot: Int) = "popup_sim_$slot"

    fun on(c: Context, slot: Int) = AppSettings.get(c, key(slot))
    fun set(c: Context, slot: Int, value: Boolean) = AppSettings.set(c, key(slot), value)

    /** The phone's active SIMs, "SIM 1 · Jazz"; empty without the phone permission. */
    @SuppressLint("MissingPermission")
    fun sims(c: Context): List<Sim> {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            c.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map { s ->
                val slot = s.simSlotIndex + 1
                val name = (s.displayName ?: s.carrierName)?.toString().orEmpty().trim()
                Sim(slot, s.subscriptionId, runCatching { s.iccId }.getOrNull().orEmpty(), if (name.isEmpty()) "SIM $slot" else "SIM $slot · $name")
            }.sortedBy { it.slot }
        }.getOrDefault(emptyList())
    }

    /** Which SIM a call-log row went through (its PHONE_ACCOUNT_ID is the subscription id or the SIM's ICCID). */
    fun slotOf(c: Context, accountId: String): Int? {
        if (accountId.isBlank()) return null
        return sims(c).firstOrNull { it.subId.toString() == accountId || (it.iccId.isNotEmpty() && accountId.startsWith(it.iccId)) }?.slot
    }

    /**
     * 27 Nov (S7.1) "After-call popup only in duty hours" (on to start with): outside the duty window (30 min before
     * to 1 hour after) only a number with an open task asks. The office can stop him changing it (then it stays on).
     */
    private const val DUTY = "popup_duty_only"
    fun dutyLocked(c: Context) = !WebRules.app(c, "popupHours", true)
    fun dutyOnly(c: Context) = dutyLocked(c) || AppSettings.get(c, DUTY, true)
    fun setDutyOnly(c: Context, on: Boolean) = AppSettings.set(c, DUTY, on)

    /** 27 Nov (S15a) "Close by itself" after this many seconds (0 = off), and (S15b) "Hold to call" for this many (0 = off). */
    fun autoClose(c: Context) = AppSettings.getString(c, "popup_autoclose").toIntOrNull() ?: 0
    fun setAutoClose(c: Context, s: Int) = AppSettings.setString(c, "popup_autoclose", s.toString())
    fun holdToCall(c: Context) = AppSettings.getString(c, "popup_hold").toIntOrNull() ?: 0
    fun setHoldToCall(c: Context, s: Int) = AppSettings.setString(c, "popup_hold", s.toString())

    /** False when the call went through a SIM switched off (or came outside duty hours) and the number has no open task with you. */
    fun allowed(c: Context, call: CallInfo): Boolean {
        if (dutyOnly(c) && !TeamRules.inWindow(c)) return hasOpenTask(call.number)
        val slot = slotOf(c, call.accountId) ?: return true
        if (on(c, slot)) return true
        return hasOpenTask(call.number)
    }

    private fun hasOpenTask(number: String): Boolean {
        val k = RecentPeople.key(number)
        if (k.length < 7) return false
        fun same(n: String) = RecentPeople.key(n) == k
        return ReminderStore.items.value.any { !it.done && same(it.number) } ||
            SmsStore.items.value.any { !it.done && same(it.number) } ||
            MyTaskStore.items.value.any { !it.done && it.hasPerson && same(it.number) } ||
            ToScheduleStore.items.value.any { same(it.number) }
    }
}
