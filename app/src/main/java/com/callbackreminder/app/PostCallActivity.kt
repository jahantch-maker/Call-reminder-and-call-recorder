package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CallLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                PostCallScreen(queue = queue, onFinished = { finish() })
            }
        }
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
        for (c in CallBatch.read(i)) {
            val alreadyThere = c.id > 0 && queue.any { it.id == c.id }
            if (!alreadyThere) queue.add(c)
        }
    }
}
