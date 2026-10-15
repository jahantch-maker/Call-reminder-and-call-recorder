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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

class MainActivity : ComponentActivity() {

    companion object {
        /** Which screen a notification wants open, e.g. [OPEN_MESSAGES]. */
        const val EXTRA_OPEN = "open_screen"
        const val OPEN_MESSAGES = "messages"
        /** "Review now" on the review reminder: open whatever is waiting. */
        const val OPEN_REVIEW = "review"
        /** A text message reminder to open in Edit (from the "Not done" button after no reply). */
        const val EXTRA_EDIT_SMS = "edit_sms"

        /** The text message a notification is about: Messages opens on it. */
        const val EXTRA_SHOW_SMS = "show_sms"

        /** A My tasks notification: the One-time list. */
        const val OPEN_MY_TASKS = "my_tasks"
        /** A dossier notification: the Dossier list. */
        const val OPEN_DOSSIER = "dossier"
        /** The separate dossier reminder: To review › Dossier. */
        const val OPEN_DOSSIER_REVIEW = "dossier_review"
        /** The morning summary: the "Your day" page. */
        const val OPEN_MY_DAY = "my_day"
        /** The caller card's "Show all his tasks": the person page, with [EXTRA_PERSON] as the key. */
        const val OPEN_PERSON = "person"
        const val EXTRA_PERSON = "person_key"
    }

    // Bumped each time a notification asks for the Messages screen.
    private val openMessagesTick = mutableIntStateOf(0)

    // A notification asked for a bottom-bar tab (bumped each time, with the tab to show).
    private val openTabTick = mutableIntStateOf(0)
    private var openTab = Tab.HOME
    // A page to open over the tab (e.g. To review › Dossier), read once.
    private var openScreen: Int? = null

    // Bumped every time the app comes back to the front, so permission status refreshes
    // right after the user returns from a system settings page.
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReminderStore.init(this)
        RecordingStore.init(this)
        SmsStore.init(this)
        ToScheduleStore.init(this)
        MyTaskStore.init(this)
        DossierStore.init(this)
        // Setting alarms must never stop the app from opening.
        CrashLog.guard(this, "Setting reminders at start") {
            ReviewReminder.schedule(this)
            TaskAlarms.rescheduleAll(this)
            Backup.rearmIfRestored(this)
        }
        handleOpen(intent)
        setContent {
            CallbackTheme {
                AppRoot(
                    resumeKey = resumeTick.intValue,
                    openMessagesKey = openMessagesTick.intValue,
                    openTabKey = openTabTick.intValue,
                    openTab = { openTab },
                    openScreen = { openScreen.also { openScreen = null } },
                )
            }
        }
        CrashLog.offer(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    private fun showTab(tab: Int, screen: Int? = null) {
        openTab = tab
        openScreen = screen
        openTabTick.intValue = openTabTick.intValue + 1
    }

    private fun handleOpen(intent: Intent?) {
        val asked = intent?.getStringExtra(EXTRA_OPEN)
        when (asked) {
            OPEN_MY_TASKS, OPEN_DOSSIER -> {
                MyTasksUi.tabRequest.value = if (asked == OPEN_DOSSIER) MyTasksUi.DOSSIER else MyTasksUi.ONE_TIME
                intent?.removeExtra(EXTRA_OPEN)
                showTab(Tab.MY_TASKS)
                return
            }
            OPEN_DOSSIER_REVIEW -> {
                intent?.removeExtra(EXTRA_OPEN)
                showTab(Tab.HOME, Screen.DOSSIER_REVIEW)
                return
            }
            OPEN_PERSON -> {
                val key = intent?.getStringExtra(EXTRA_PERSON)
                intent?.removeExtra(EXTRA_OPEN)
                if (!key.isNullOrEmpty()) {
                    PeopleUi.current.value = key
                    showTab(Tab.HOME, Screen.PERSON)
                }
                return
            }
            OPEN_MY_DAY -> {
                intent?.removeExtra(EXTRA_OPEN)
                MorningSummary.cancelNotification(this)
                showTab(Tab.HOME, Screen.MY_DAY)
                return
            }
        }
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_REVIEW) {
            intent.removeExtra(EXTRA_OPEN)
            ReviewReminder.cancelNotification(this)
            val c = ReviewReminder.counts(this)
            val calls = c.overdueCalls + c.callsToReview
            // Today's dossier opens its own To review page, which links to calls and texts waiting too.
            if (c.dossierLeft > 0 || c.needsTime > 0) {
                showTab(Tab.HOME, Screen.DOSSIER_REVIEW)
                return
            }
            openTab = when {
                calls > 0 && c.textsToReview > 0 -> Tab.HOME
                // To schedule lives on Home.
                c.toSchedule > 0 -> Tab.HOME
                c.textsToReview > 0 -> {
                    SmsUi.tabRequest.value = 1
                    textsTab()
                }
                calls > 0 -> {
                    CallsUi.tabRequest.value = if (c.callsToReview > 0) CallsUi.TO_REVIEW else CallsUi.OVERDUE
                    callsTab()
                }
                else -> Tab.HOME
            }
            openTabTick.intValue = openTabTick.intValue + 1
            return
        }
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
 * The app's screens. HOME is the bottom-bar page (Home, My tasks, Calls, Texts). Recordings, Settings
 * (the icons at the top right) and setting pages slide in over it from the right like a phone's own
 * settings app.
 */
private object Screen {
    const val HOME = 0
    const val QUICK_TIMES = 3
    const val SETUP = 4
    const val POPUP_SETTINGS = 5
    const val RECORDING_SETTINGS = 6
    const val REMINDER_SETTINGS = 7
    const val TEXT_SETTINGS = 9
    const val REVIEW_SETTINGS = 10
    const val TO_SCHEDULE = 11
    const val RECORDINGS = 12
    const val SETTINGS = 13
    const val DOSSIER_SETTINGS = 14
    const val DOSSIER_REVIEW = 15
    const val PEOPLE = 16
    const val PERSON = 17
    const val MY_DAY = 18
    const val MORNING_SETTINGS = 19
    const val NOTIFICATION_SETTINGS = 20
    const val DAILY_LIST_SETTINGS = 21
    const val BACKUP_SETTINGS = 22
    const val VOICE_SETTINGS = 23
    const val OFF_DAYS = 24
    const val SEARCH = 25
}

/** The tab (and the list inside it) that shows a task from Home, People, a person page or Your day. */
private fun tabFor(item: WorkItem): Int = when (item.kind) {
    WorkItem.Kind.CALL -> {
        CallsUi.tabRequest.value = when {
            item.inReview -> CallsUi.TO_REVIEW
            item.overdue -> CallsUi.OVERDUE
            else -> CallsUi.TO_CALL
        }
        callsTab()
    }
    WorkItem.Kind.TEXT -> {
        SmsUi.showRequest.value = item.id
        textsTab()
    }
    WorkItem.Kind.MY_TASK -> {
        MyTasksUi.tabRequest.value = MyTasksUi.ONE_TIME
        Tab.MY_TASKS
    }
    WorkItem.Kind.DOSSIER -> {
        MyTasksUi.tabRequest.value = MyTasksUi.DOSSIER
        Tab.MY_TASKS
    }
}

/** The bottom bar's tabs. */
private object Tab {
    const val HOME = 0
    const val MY_TASKS = 1
    const val FOLLOW_UPS = 2
    const val PEOPLE = 3
    const val PROJECTS = 4
    const val COUNT = 5
}

/** The Follow-ups tab, opened on its Calls list. */
private fun callsTab(): Int {
    FollowUpsUi.view.value = FollowUpsUi.CALLS
    return Tab.FOLLOW_UPS
}

/** The Follow-ups tab, opened on its Texts list. */
private fun textsTab(): Int {
    FollowUpsUi.view.value = FollowUpsUi.TEXTS
    return Tab.FOLLOW_UPS
}

@Composable
private fun AppRoot(resumeKey: Int, openMessagesKey: Int, openTabKey: Int, openTab: () -> Int, openScreen: () -> Int?) {
    // Screens opened so far, newest last. Going back pops the last one.
    var stack by rememberSaveable { mutableStateOf(listOf(Screen.HOME)) }
    var tab by rememberSaveable { mutableIntStateOf(Tab.HOME) }
    // Keeps each open screen's scroll spot while a page on top of it is showing, so Back lands where you were.
    val screenStates = rememberSaveableStateHolder()

    fun setStack(next: List<Int>) {
        // A screen that is closed starts fresh (at the top) the next time it opens.
        (stack - next.toSet()).forEach { screenStates.removeState(it) }
        stack = next
    }

    fun open(screen: Int) {
        val i = stack.indexOf(screen)
        // Already open further back (e.g. Settings → Recordings → Settings): go back to it instead of stacking a copy.
        setStack(if (i >= 0) stack.take(i + 1) else stack + screen)
    }

    fun back() {
        if (stack.size > 1) setStack(stack.dropLast(1))
    }

    fun showTab(t: Int) {
        setStack(listOf(Screen.HOME))
        tab = t
    }

    // Back closes a settings page first, then returns to the Home tab, then leaves the app.
    BackHandler(enabled = stack.size > 1 || tab != Tab.HOME) {
        if (stack.size > 1) back() else tab = Tab.HOME
    }

    // A text-message notification was tapped: show the Texts tab.
    LaunchedEffect(openMessagesKey) {
        if (openMessagesKey > 0) showTab(textsTab())
    }

    // "Review now" on the review reminder.
    LaunchedEffect(openTabKey) {
        if (openTabKey > 0) {
            showTab(openTab())
            openScreen()?.let { open(it) }
        }
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
            screenStates.SaveableStateProvider(s.last()) {
            when (s.last()) {
                Screen.QUICK_TIMES -> TimePickerSettingsScreen(onBack = { back() })
                Screen.SETUP -> SetupSettingsScreen(resumeKey = resumeKey, onBack = { back() })
                Screen.POPUP_SETTINGS -> PopupSettingsScreen(onBack = { back() })
                Screen.RECORDING_SETTINGS -> RecordingSettingsScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenRecordings = { open(Screen.RECORDINGS) }
                )
                Screen.RECORDINGS -> RecordingsScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenSettings = { open(Screen.RECORDING_SETTINGS) }
                )
                Screen.SETTINGS -> SettingsScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenSetup = { open(Screen.SETUP) },
                    onOpenPopup = { open(Screen.POPUP_SETTINGS) },
                    onOpenQuickTimes = { open(Screen.QUICK_TIMES) },
                    onOpenRecording = { open(Screen.RECORDING_SETTINGS) },
                    onOpenReminder = { open(Screen.REMINDER_SETTINGS) },
                    onOpenTextMessages = { open(Screen.TEXT_SETTINGS) },
                    onOpenReview = { open(Screen.REVIEW_SETTINGS) },
                    onOpenDossier = { open(Screen.DOSSIER_SETTINGS) },
                    onOpenMorningSummary = { open(Screen.MORNING_SETTINGS) },
                    onOpenNotifications = { open(Screen.NOTIFICATION_SETTINGS) },
                    onOpenDailyList = { open(Screen.DAILY_LIST_SETTINGS) },
                    onOpenBackup = { open(Screen.BACKUP_SETTINGS) },
                    onOpenVoiceTyping = { open(Screen.VOICE_SETTINGS) },
                    onOpenOffDays = { open(Screen.OFF_DAYS) },
                )
                Screen.NOTIFICATION_SETTINGS -> NotificationSettingsScreen(onBack = { back() })
                Screen.DAILY_LIST_SETTINGS -> DailyListSettingsScreen(onBack = { back() }, onOpenPeople = { open(Screen.PEOPLE) })
                Screen.BACKUP_SETTINGS -> BackupSettingsScreen(onBack = { back() })
                Screen.VOICE_SETTINGS -> VoiceTypingSettingsScreen(onBack = { back() })
                Screen.OFF_DAYS -> WeeklyOffDayScreen(onBack = { back() })
                Screen.SEARCH -> SearchScreen(
                    onBack = { back() },
                    onOpenCalls = { showTab(callsTab()) },
                    onOpenTexts = { showTab(textsTab()) },
                    onOpenMyTasks = { showTab(Tab.MY_TASKS) },
                    onOpenPerson = {
                        PeopleUi.current.value = it
                        open(Screen.PERSON)
                    },
                    onOpenRecordings = { open(Screen.RECORDINGS) },
                    onOpenSetting = { key ->
                        when (key) {
                            "off_days" -> Screen.OFF_DAYS
                            "setup" -> Screen.SETUP
                            "popup" -> Screen.POPUP_SETTINGS
                            "quick_times" -> Screen.QUICK_TIMES
                            "recording" -> Screen.RECORDING_SETTINGS
                            "reminder" -> Screen.REMINDER_SETTINGS
                            "texts" -> Screen.TEXT_SETTINGS
                            "review" -> Screen.REVIEW_SETTINGS
                            "dossier" -> Screen.DOSSIER_SETTINGS
                            "morning" -> Screen.MORNING_SETTINGS
                            "notifications" -> Screen.NOTIFICATION_SETTINGS
                            "daily_list" -> Screen.DAILY_LIST_SETTINGS
                            "backup" -> Screen.BACKUP_SETTINGS
                            "voice" -> Screen.VOICE_SETTINGS
                            else -> null
                        }?.let { open(it) }
                    },
                )
                Screen.MORNING_SETTINGS -> MorningSummarySettingsScreen(onBack = { back() })
                Screen.PEOPLE -> PeopleScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenPerson = {
                        PeopleUi.current.value = it
                        open(Screen.PERSON)
                    },
                )
                Screen.PERSON -> PersonScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenItem = { showTab(tabFor(it)) },
                )
                Screen.MY_DAY -> MyDayScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onStart = { showTab(Tab.HOME) },
                    onOpenItem = { showTab(tabFor(it)) },
                )
                Screen.DOSSIER_SETTINGS -> DossierSettingsScreen(onBack = { back() })
                Screen.DOSSIER_REVIEW -> DossierReviewScreen(
                    resumeKey = resumeKey,
                    onBack = { back() },
                    onOpenCalls = {
                        CallsUi.tabRequest.value = CallsUi.TO_REVIEW
                        showTab(callsTab())
                    },
                    onOpenOverdueCalls = {
                        CallsUi.tabRequest.value = CallsUi.OVERDUE
                        showTab(callsTab())
                    },
                    onOpenTexts = {
                        SmsUi.tabRequest.value = 1
                        showTab(textsTab())
                    },
                )
                Screen.REMINDER_SETTINGS -> ReminderSettingsScreen(onBack = { back() })
                Screen.TEXT_SETTINGS -> TextMessageSettingsScreen(resumeKey = resumeKey, onBack = { back() })
                Screen.REVIEW_SETTINGS -> ReviewSettingsScreen(onBack = { back() })
                Screen.TO_SCHEDULE -> ToScheduleScreen(onBack = { back() })
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
}

/**
 * The bottom-bar page. Swiping left or right slides to the next or previous tab; tapping the bar jumps
 * straight to a tab.
 */
@Composable
private fun MainTabs(tab: Int, onTab: (Int) -> Unit, resumeKey: Int, open: (Int) -> Unit) {
    val calls by ReminderStore.items.collectAsState()
    val texts by SmsStore.items.collectAsState()
    val toSchedule by ToScheduleStore.items.collectAsState()
    val pager = rememberPagerState(initialPage = tab.coerceIn(0, Tab.COUNT - 1)) { Tab.COUNT }
    val currentTab by rememberUpdatedState(tab)
    // The bar or a notification picked a tab: jump straight there, without sliding past the pages between.
    LaunchedEffect(tab) {
        if (pager.currentPage != tab && !pager.isScrollInProgress) pager.scrollToPage(tab)
    }
    // A swipe came to rest on a page: that page is now the tab.
    LaunchedEffect(pager) {
        // The first value is the page restored when coming back from a page on top; the bar's tab wins over it.
        snapshotFlow { pager.settledPage }.drop(1).collect { if (it != currentTab) onTab(it) }
    }
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
    val myTasks by MyTaskStore.items.collectAsState()
    val dossierTasks by DossierStore.tasks.collectAsState()
    val dossierRecords by DossierStore.records.collectAsState()
    val myTasksBadge = myTasks.count { !it.done && it.timed && it.dueAt <= now } +
        remember(dossierTasks, dossierRecords, now) { DossierStore.leftNow(now) }

    // Once, after this update: where Calls and Texts went.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showIntro by remember { mutableStateOf(!FollowUps.introSeen(ctx)) }
    if (showIntro) {
        WhatMovedDialog(onDismiss = {
            FollowUps.setIntroSeen(ctx)
            showIntro = false
        })
    }

    // Recordings and Settings at the top right of every tab.
    val topActions: @Composable () -> Unit = {
        IconButton(onClick = { open(Screen.RECORDINGS) }) {
            Icon(Icons.Rounded.Mic, contentDescription = "Recordings")
        }
        IconButton(onClick = { open(Screen.SETTINGS) }) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                val shown = pager.currentPage
                TabItem(shown, Tab.HOME, Icons.Rounded.Home, "Home", toSchedule.size, onTab)
                TabItem(shown, Tab.MY_TASKS, Icons.Rounded.TaskAlt, "My tasks", myTasksBadge, onTab)
                TabItem(shown, Tab.FOLLOW_UPS, Icons.Rounded.PhoneCallback, "Follow-ups", callsBadge + textsBadge, onTab)
                TabItem(shown, Tab.PEOPLE, Icons.Rounded.People, "People", 0, onTab)
                TabItem(shown, Tab.PROJECTS, Icons.Rounded.Folder, "Projects", 0, onTab, tag = "Soon")
            }
        }
    ) { padding ->
        // The bar already handles the bottom inset, so each tab's own Scaffold must not add it again.
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            key = { it },
            // Swiping sideways is for Done / Snooze on rows; pages change from the bottom bar only.
            userScrollEnabled = false,
        ) { page ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
              CompositionLocalProvider(LocalTopActions provides topActions) {
                when (page) {
                    Tab.MY_TASKS -> MyTasksScreen(resumeKey = resumeKey)
                    Tab.FOLLOW_UPS -> FollowUpsScreen(
                        resumeKey = resumeKey,
                        onOpenTextSettings = { open(Screen.TEXT_SETTINGS) },
                        onOpenToSchedule = { open(Screen.TO_SCHEDULE) },
                    )
                    Tab.PEOPLE -> PeopleScreen(
                        resumeKey = resumeKey,
                        onBack = null,
                        onOpenPerson = {
                            PeopleUi.current.value = it
                            open(Screen.PERSON)
                        },
                    )
                    Tab.PROJECTS -> ProjectsScreen()
                    else -> HomeScreen(
                        resumeKey = resumeKey,
                        onOpenCalls = { onTab(callsTab()) },
                        onOpenTexts = { t, showId ->
                            if (showId != null) SmsUi.showRequest.value = showId else SmsUi.tabRequest.value = t
                            onTab(textsTab())
                        },
                        onOpenSetup = { open(Screen.SETUP) },
                        onOpenRecordingSettings = { open(Screen.RECORDING_SETTINGS) },
                        onOpenToSchedule = { open(Screen.TO_SCHEDULE) },
                        onOpenMyTasks = { sub ->
                            MyTasksUi.tabRequest.value = sub
                            onTab(Tab.MY_TASKS)
                        },
                        onOpenDossierReview = { open(Screen.DOSSIER_REVIEW) },
                        onOpenPeople = { open(Screen.PEOPLE) },
                        onOpenPerson = {
                            PeopleUi.current.value = it
                            open(Screen.PERSON)
                        },
                        onOpenItem = { onTab(tabFor(it)) },
                        onOpenSearch = {
                            SearchUi.initial.value = it
                            open(Screen.SEARCH)
                        },
                    )
                }
              }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(
    current: Int,
    tab: Int,
    icon: ImageVector,
    label: String,
    badge: Int,
    onTab: (Int) -> Unit,
    tag: String? = null,
) {
    NavigationBarItem(
        selected = current == tab,
        onClick = { onTab(tab) },
        icon = {
            BadgedBox(
                badge = {
                    when {
                        tag != null -> Badge(containerColor = MaterialTheme.colorScheme.tertiary) { Text(tag) }
                        badge > 0 -> Badge { Text(if (badge > 99) "99+" else "$badge") }
                    }
                }
            ) {
                Icon(icon, contentDescription = label)
            }
        },
        label = { Text(label, maxLines = 1) }
    )
}
