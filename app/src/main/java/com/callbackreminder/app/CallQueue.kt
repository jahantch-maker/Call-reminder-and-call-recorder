package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.provider.CallLog

/** Packs a list of calls into an Intent so the popup knows who to ask about. */
object CallBatch {
    private const val IDS = "call_ids"
    private const val NUMBERS = "call_numbers"
    private const val NAMES = "call_names"
    private const val TYPES = "call_types"
    private const val DURATIONS = "call_durations"
    private const val WHATSAPP = "call_whatsapp"

    fun putInto(intent: Intent, calls: List<CallInfo>): Intent {
        intent.putExtra(IDS, calls.map { it.id }.toLongArray())
        intent.putExtra(NUMBERS, calls.map { it.number }.toTypedArray())
        intent.putExtra(NAMES, calls.map { it.name }.toTypedArray())
        intent.putExtra(TYPES, calls.map { it.type }.toIntArray())
        intent.putExtra(DURATIONS, calls.map { it.durationSec }.toLongArray())
        intent.putExtra(WHATSAPP, calls.map { it.viaWhatsApp }.toBooleanArray())
        return intent
    }

    fun read(intent: Intent): List<CallInfo> {
        val ids = intent.getLongArrayExtra(IDS) ?: return emptyList()
        val numbers = intent.getStringArrayExtra(NUMBERS) ?: return emptyList()
        val names = intent.getStringArrayExtra(NAMES) ?: return emptyList()
        val types = intent.getIntArrayExtra(TYPES) ?: return emptyList()
        val durations = intent.getLongArrayExtra(DURATIONS) ?: return emptyList()
        val whatsApp = intent.getBooleanArrayExtra(WHATSAPP)
        val now = System.currentTimeMillis()
        val count = minOf(ids.size, numbers.size, names.size, types.size, durations.size)
        return (0 until count).map { i ->
            CallInfo(
                id = ids[i],
                number = numbers[i],
                name = names[i],
                type = types[i],
                durationSec = durations[i],
                endedAt = now,
                viaWhatsApp = whatsApp?.getOrNull(i) ?: false,
            )
        }
    }
}

/**
 * Decides who gets an after-call popup once ALL calls are over.
 *
 * With call waiting, several calls can belong to one phone session: the person you talked to,
 * plus callers who rang while you were busy. We look at every call in the session and keep only
 * the ones you actually talked to (or dialled). Callers you never answered stay quiet.
 */
object CallSession {
    private const val MAX_POPUPS = 5

    fun buildQueue(
        ctx: Context,
        entries: List<CallInfo>,
        sawOffhook: Boolean,
        rings: Int,
    ): List<CallInfo> {
        val allowIncoming = AppSettings.get(ctx, AppSettings.INCOMING)
        val allowOutgoing = AppSettings.get(ctx, AppSettings.OUTGOING)
        val allowMissed = AppSettings.get(ctx, AppSettings.MISSED)

        if (entries.isEmpty()) {
            // The phone did not give us the call (log not written yet, or not readable).
            // Still offer a popup, with the caller left blank.
            val type = when {
                sawOffhook && rings > 0 -> CallLog.Calls.INCOMING_TYPE
                sawOffhook -> CallLog.Calls.OUTGOING_TYPE
                else -> CallLog.Calls.MISSED_TYPE
            }
            val allowed = when (type) {
                CallLog.Calls.INCOMING_TYPE -> allowIncoming
                CallLog.Calls.OUTGOING_TYPE -> allowOutgoing
                else -> allowMissed
            }
            return if (allowed) {
                listOf(CallInfo(-1L, "", "", type, 0L, System.currentTimeMillis()))
            } else {
                emptyList()
            }
        }

        val talked = entries.filter {
            it.type == CallLog.Calls.INCOMING_TYPE || it.type == CallLog.Calls.OUTGOING_TYPE
        }

        val chosen = if (talked.isNotEmpty()) {
            // Anyone who only rang while you were busy (missed or declined) is left out.
            talked.filter {
                if (it.type == CallLog.Calls.INCOMING_TYPE) allowIncoming else allowOutgoing
            }
        } else if (allowMissed) {
            entries.filter {
                it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE
            }
        } else {
            emptyList()
        }
        return chosen.takeLast(MAX_POPUPS)
    }
}
