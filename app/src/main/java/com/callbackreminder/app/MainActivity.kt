package com.callbackreminder.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

class MainActivity : ComponentActivity() {

    companion object {
        /** Which screen a notification wants open, e.g. [OPEN_MESSAGES]. */
        const val EXTRA_OPEN = "open_screen"
        const val OPEN_MESSAGES = "messages"
    }

    // Bumped each time a notification asks for the Messages screen.
    private val openMessagesTick = mutableIntStateOf(0)

    // Bumped every time the app comes back to the front, so permission status refreshes
    // right after the user returns from a system settings page.
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReminderStore.init(this)
        RecordingStore.init(this)
        SmsStore.init(this)
        handleOpen(intent)
        setContent {
            CallbackTheme {
                AppRoot(resumeKey = resumeTick.intValue, openMessagesKey = openMessagesTick.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    private fun handleOpen(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_MESSAGES) {
            openMessagesTick.intValue = openMessagesTick.intValue + 1
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
    const val MESSAGES = 8
}

@Composable
private fun AppRoot(resumeKey: Int, openMessagesKey: Int) {
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

    // A text-message notification was tapped: show the Messages screen.
    LaunchedEffect(openMessagesKey) {
        if (openMessagesKey > 0) stack = listOf(Screen.HOME, Screen.MESSAGES)
    }

    AnimatedContent(
        targetState = stack,
        contentKey = { it.last() },
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            // 1.75x faster than the first version (320 ms). No fade: fading a whole screen
            // forces an extra offscreen pass every frame, which caused stutter on some phones.
            val spec = tween<IntOffset>(durationMillis = 180, easing = FastOutSlowInEasing)
            if (forward) {
                (slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 4 })
                    .apply { targetContentZIndex = 1f }
            } else {
                (slideInHorizontally(spec) { -it / 4 } togetherWith slideOutHorizontally(spec) { it })
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
                Screen.MESSAGES -> MessagesScreen(resumeKey = resumeKey, onBack = { back() })
                else -> HomeScreen(
                    resumeKey = resumeKey,
                    onOpenSettings = { open(Screen.SETTINGS) },
                    onOpenSetup = { open(Screen.SETUP) },
                    onOpenRecordingSettings = { open(Screen.RECORDING_SETTINGS) },
                    onOpenRecordings = { open(Screen.RECORDINGS) },
                    onOpenMessages = { open(Screen.MESSAGES) }
                )
            }
        }
    }
}
