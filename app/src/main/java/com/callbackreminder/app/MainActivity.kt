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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    companion object {
        /** Which screen a notification wants open, e.g. [OPEN_MESSAGES]. */
        const val EXTRA_OPEN = "open_screen"
        const val OPEN_MESSAGES = "messages"
        /** A text message reminder to open in Edit (from the "Not done" button after no reply). */
        const val EXTRA_EDIT_SMS = "edit_sms"

        /** The text message a notification is about: Messages opens on it. */
        const val EXTRA_SHOW_SMS = "show_sms"
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
            val showId = intent.getLongExtra(EXTRA_SHOW_SMS, -1L)
            if (showId >= 0) SmsUi.showRequest.value = showId
            val editId = intent.getLongExtra(EXTRA_EDIT_SMS, -1L)
            if (editId >= 0) {
                SmsStore.noteNotDone(editId)
                SmsUi.editRequest.value = editId
                SmsNotifier.cancel(this, editId)
            }
            openMessagesTick.intValue = openMessagesTick.intValue + 1
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue = resumeTick.intValue + 1
    }
}

/**
 * The app's screens. HOME is the bottom-bar page (Home, Calls, Texts, Recordings, Settings); setting
 * pages slide in over it from the right like a phone's own settings app.
 */
private object Screen {
    const val HOME = 0
    const val QUICK_TIMES = 3
    const val SETUP = 4
    const val POPUP_SETTINGS = 5
    const val RECORDING_SETTINGS = 6
    const val REMINDER_SETTINGS = 7
    const val TEXT_SETTINGS = 9
}

/** The bottom bar's tabs. */
private object Tab {
    const val HOME = 0
    const val CALLS = 1
    const val TEXTS = 2
    const val RECORDINGS = 3
    const val SETTINGS = 4
}

@Composable
private fun AppRoot(resumeKey: Int, openMessagesKey: Int) {
    // Screens opened so far, newest last. Going back pops the last one.
    var stack by rememberSaveable { mutableStateOf(listOf(Screen.HOME)) }
    var tab by rememberSaveable { mutableIntStateOf(Tab.HOME) }

    fun open(screen: Int) {
        val i = stack.indexOf(screen)
        // Already open further back (e.g. Settings → Recordings → Settings): go back to it instead of stacking a copy.
        stack = if (i >= 0) stack.take(i + 1) else stack + screen
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun showTab(t: Int) {
        stack = listOf(Screen.HOME)
        tab = t
    }

    // Back closes a settings page first, then returns to the Home tab, then leaves the app.
    BackHandler(enabled = stack.size > 1 || tab != Tab.HOME) {
        if (stack.size > 1) back() else tab = Tab.HOME
    }

    // A text-message notification was tapped: show the Texts tab.
    LaunchedEffect(openMessagesKey) {
        if (openMessagesKey > 0) showTab(Tab.TEXTS)
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
                Screen.QUICK_TIMES -> QuickTimesScreen(onBack = { back() })
                Screen.SETUP -> SetupSettingsScreen(resumeKey = resumeKey, onBack = { back() })
                Screen.POPUP_SETTINGS -> PopupSettingsScreen(onBack = { back() })
                Screen.RECORDING_SETTINGS -> RecordingSettingsScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenRecordings = { showTab(Tab.RECORDINGS) }
                )
                Screen.REMINDER_SETTINGS -> ReminderSettingsScreen(onBack = { back() })
                Screen.TEXT_SETTINGS -> TextMessageSettingsScreen(resumeKey = resumeKey, onBack = { back() })
                else -> MainTabs(
                    tab = tab,
                    onTab = { tab = it },
                    resumeKey = resumeKey,
                    open = { open(it) },
                )
            }
        }
    }
}

/** The bottom-bar page. Switching tabs is instant; only setting pages slide. */
@Composable
private fun MainTabs(tab: Int, onTab: (Int) -> Unit, resumeKey: Int, open: (Int) -> Unit) {
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(resumeKey) { now = System.currentTimeMillis() }
    val callsBadge = calls.count { !it.done && it.dueAt <= now }
    val textsBadge = texts.count { !it.done && it.review != null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                TabItem(tab, Tab.HOME, Icons.Rounded.Home, "Home", 0, onTab)
                TabItem(tab, Tab.CALLS, Icons.Rounded.PhoneCallback, "Calls", callsBadge, onTab)
                TabItem(tab, Tab.TEXTS, Icons.Rounded.Sms, "Texts", textsBadge, onTab)
                TabItem(tab, Tab.RECORDINGS, Icons.Rounded.Mic, "Recordings", 0, onTab)
                TabItem(tab, Tab.SETTINGS, Icons.Rounded.Settings, "Settings", 0, onTab)
            }
        }
    ) { padding ->
        // The bar already handles the bottom inset, so each tab's own Scaffold must not add it again.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                Tab.CALLS -> CallsScreen(resumeKey = resumeKey)
                Tab.TEXTS -> MessagesScreen(
                    resumeKey = resumeKey,
                    onBack = null,
                    onOpenSettings = { open(Screen.TEXT_SETTINGS) }
                )
                Tab.RECORDINGS -> RecordingsScreen(
                    resumeKey = resumeKey,
                    onBack = null,
                    onOpenSettings = { open(Screen.RECORDING_SETTINGS) }
                )
                Tab.SETTINGS -> SettingsScreen(
                    resumeKey = resumeKey,
                    onBack = null,
                    onOpenSetup = { open(Screen.SETUP) },
                    onOpenPopup = { open(Screen.POPUP_SETTINGS) },
                    onOpenQuickTimes = { open(Screen.QUICK_TIMES) },
                    onOpenRecording = { open(Screen.RECORDING_SETTINGS) },
                    onOpenReminder = { open(Screen.REMINDER_SETTINGS) },
                    onOpenTextMessages = { open(Screen.TEXT_SETTINGS) }
                )
                else -> HomeScreen(
                    resumeKey = resumeKey,
                    onOpenCalls = { onTab(Tab.CALLS) },
                    onOpenTexts = { t, showId ->
                        if (showId != null) SmsUi.showRequest.value = showId else SmsUi.tabRequest.value = t
                        onTab(Tab.TEXTS)
                    },
                    onOpenSetup = { open(Screen.SETUP) },
                    onOpenRecordingSettings = { open(Screen.RECORDING_SETTINGS) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(current: Int, tab: Int, icon: ImageVector, label: String, badge: Int, onTab: (Int) -> Unit) {
    NavigationBarItem(
        selected = current == tab,
        onClick = { onTab(tab) },
        icon = {
            BadgedBox(badge = { if (badge > 0) Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
                Icon(icon, contentDescription = label)
            }
        },
        label = { Text(label, maxLines = 1) }
    )
}
