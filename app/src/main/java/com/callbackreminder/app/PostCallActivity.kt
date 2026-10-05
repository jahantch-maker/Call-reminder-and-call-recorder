package com.callbackreminder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** The small sheet that slides up right after a call: "When should I remind you to call back?" */
class PostCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEST = "test"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReminderStore.init(this)
        Notifier.cancelPostCallPrompt(this)

        val kind = intent.getStringExtra(EXTRA_KIND) ?: "outgoing"
        val test = intent.getBooleanExtra(EXTRA_TEST, false)

        setContent {
            CallbackTheme {
                PostCallScreen(kind = kind, test = test, onClose = { finish() })
            }
        }
    }
}
