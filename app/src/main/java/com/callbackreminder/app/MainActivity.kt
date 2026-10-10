package com.callbackreminder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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

/** The app's screens. Settings and its sub-pages slide in from the right like a phone's own settings app. */
private object Screen {
    const val HOME = 0
    const val SETTINGS = 1
    const val RECORDINGS = 2
    const val QUICK_TIMES = 3
    const val SETUP = 4
    const val POPUP_SETTINGS = 5
    const val RECORDING_SETTINGS = 6
    const val REMINDER_SETTINGS = 7
}

@Composable
private fun AppRoot(resumeKey: Int) {
    // Screens opened so far, newest last. Going back pops the last one.
    var stack by rememberSaveable { mutableStateOf(listOf(Screen.HOME)) }

    fun open(screen: Int) {
        val i = stack.indexOf(screen)
        // Already open further back (e.g. Settings → Recordings → Settings): go back to it instead of stacking a copy.
        stack = if (i >= 0) stack.take(i + 1) else stack + screen
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { back() }

    AnimatedContent(
        targetState = stack,
        contentKey = { it.last() },
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            val duration = 320
            if (forward) {
                (slideInHorizontally(tween(duration)) { it } togetherWith
                    slideOutHorizontally(tween(duration)) { -it / 4 } + fadeOut(tween(duration), 0.6f))
                    .apply { targetContentZIndex = 1f }
            } else {
                (slideInHorizontally(tween(duration)) { -it / 4 } + fadeIn(tween(duration), 0.6f) togetherWith
                    slideOutHorizontally(tween(duration)) { it })
                    .apply { targetContentZIndex = -1f }
            }
        },
        label = "screens"
    ) { s ->
        // Each screen gets a solid background so the one underneath doesn't show through mid-slide.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (s.last()) {
                Screen.SETTINGS -> SettingsScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenSetup = { open(Screen.SETUP) },
                    onOpenPopup = { open(Screen.POPUP_SETTINGS) },
                    onOpenQuickTimes = { open(Screen.QUICK_TIMES) },
                    onOpenRecording = { open(Screen.RECORDING_SETTINGS) },
                    onOpenReminder = { open(Screen.REMINDER_SETTINGS) }
                )
                Screen.RECORDINGS -> RecordingsScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenSettings = { open(Screen.RECORDING_SETTINGS) }
                )
                Screen.QUICK_TIMES -> QuickTimesScreen(onBack = { back() })
                Screen.SETUP -> SetupSettingsScreen(resumeKey = resumeKey, onBack = { back() })
                Screen.POPUP_SETTINGS -> PopupSettingsScreen(onBack = { back() })
                Screen.RECORDING_SETTINGS -> RecordingSettingsScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenRecordings = { open(Screen.RECORDINGS) }
                )
                Screen.REMINDER_SETTINGS -> ReminderSettingsScreen(onBack = { back() })
                else -> HomeScreen(
                    resumeKey = resumeKey,
                    onOpenSettings = { open(Screen.SETTINGS) },
                    onOpenSetup = { open(Screen.SETUP) },
                    onOpenRecordingSettings = { open(Screen.RECORDING_SETTINGS) },
                    onOpenRecordings = { open(Screen.RECORDINGS) }
                )
            }
        }
    }
}
