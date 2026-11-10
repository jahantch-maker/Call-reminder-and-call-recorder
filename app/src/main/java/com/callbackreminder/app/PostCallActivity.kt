package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CallLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf

/**
 * The small sheet that slides up after your calls: "When should I remind you to call back?"
 * If several calls ended together (call waiting), it asks about each person one after the other.
 */
class PostCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TEST = "test"

        fun intentFor(ctx: Context, calls: List<CallInfo>): Intent =
            CallBatch.putInto(Intent(ctx, PostCallActivity::class.java), calls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private val queue = mutableStateListOf<CallInfo>()

    // This popup is for a call started from the review session's Call button.
    private var fromSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReminderStore.init(this)
        SmsStore.init(this)
        ToScheduleStore.init(this)
        MyTaskStore.init(this)
        addFrom(intent)
        if (queue.isEmpty()) {
            finish()
            return
        }
        setContent {
            CallbackTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalSavedOutside provides true) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        PostCallScreen(queue = queue, onFinished = { done() })
                        // v0.51 the popup shrinks into "✓ Saved · View · Edit", then closes.
                        SavedChipHost(outside = true, onGone = { if (waitingForChip || queue.isEmpty()) finish() })
                    }
                }
            }
        }
    }

    /**
     * Closing the popup: a call made from the running review session takes you back to its Next card.
     * Any other call (your dialer, an incoming one) just closes the popup; a paused session waits on Home.
     */
    private var waitingForChip = false

    private fun done() {
        val s = ReviewSession.init(this)
        if (fromSession && s.status == ReviewSession.Status.RUNNING && s.awaiting.isNotEmpty()) {
            ReviewSession.popupClosed(this)
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SESSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (e: Exception) {
                // The session waits on Home.
            }
            finish()
            return
        }
        if (holdForSavedChip()) {
            waitingForChip = true
            return
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        addFrom(intent)
    }

    private fun addFrom(i: Intent) {
        Notifier.cancelPostCallPrompt(this)
        if (i.getBooleanExtra(EXTRA_TEST, false)) {
            queue.add(
                CallInfo(-100L, "0300 1234567", "Test Caller", CallLog.Calls.OUTGOING_TYPE, 125L, System.currentTimeMillis())
            )
            return
        }
        val batch = CallBatch.read(i)
        // A call you didn't start from the review session (like an incoming one) pauses it.
        val s = ReviewSession.init(this)
        if (s.status == ReviewSession.Status.RUNNING && batch.isNotEmpty()) {
            val fromSession = s.awaiting.isNotEmpty() && batch.any { it.followUpId > 0 || it.textTaskId > 0 }
            if (fromSession) this.fromSession = true else ReviewSession.pause(this)
        }
        for (c in batch) {
            val alreadyThere = c.id > 0 && queue.any { it.id == c.id }
            if (!alreadyThere) queue.add(c)
        }
    }
}
