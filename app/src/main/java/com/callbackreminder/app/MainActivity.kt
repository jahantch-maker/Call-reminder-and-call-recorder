package com.callbackreminder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    // Bumped every time the app comes back to the front, so permission status refreshes
    // right after the user returns from a system settings page.
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReminderStore.init(this)
        RecordingStore.init(this)
        setContent {
            CallbackTheme {
                AppRoot(resumeKey = resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue = resumeTick.intValue + 1
    }
}

@Composable
private fun AppRoot(resumeKey: Int) {
    // 0 = home, 1 = settings, 2 = recordings
    var screen by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = screen != 0) { screen = 0 }

    when (screen) {
        1 -> SettingsScreen(
            resumeKey = resumeKey,
            onBack = { screen = 0 },
            onOpenRecordings = { screen = 2 }
        )
        2 -> RecordingsScreen(
            resumeKey = resumeKey,
            onBack = { screen = 0 },
            onOpenSettings = { screen = 1 }
        )
        else -> HomeScreen(
            resumeKey = resumeKey,
            onOpenSettings = { screen = 1 },
            onOpenRecordings = { screen = 2 }
        )
    }
}
