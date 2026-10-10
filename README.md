# Callback Reminder

An Android app that makes sure you never forget to call someone back.

When a call ends, a small popup appears instantly and asks **"Remind me to call back"**. Tap a time and the
phone rings like a call when it is due, with a one-tap **Call back** button. The app can also **record your
calls** and keep them in a simple Recordings screen.

Built for personal use on a Samsung Galaxy phone (tested on a Galaxy S25 Ultra, One UI 8.5, Android 16).
Everything stays on the phone: no account, no server, and the app has no internet permission.

## Features

### After-call popup
- Appears the moment a call ends (phone calls and WhatsApp voice/video calls).
- 12 one-tap reminder times in a 3 × 4 grid:

  | | | |
  |---|---|---|
  | 15 min | 30 min | 45 min |
  | 1 hour | 2 hours | 3 hours |
  | Tomorrow 10 am | Tomorrow 4 pm | Tomorrow, same time as now |
  | In 2 days 10 am | In 2 days 4 pm | In 2 days, same time as now |

- Add a note ("What did they promise?") and optionally pick any date and time.
- Smart with call waiting: if you switch between several calls, you get one popup per person you actually
  talked to. Callers who rang while you were busy and went unanswered are left out.
- Choose which calls show the popup: outgoing, incoming, missed, WhatsApp.

### Reminders
- At the due time a **full-screen ringing reminder** opens, even on the lock screen. It rings at your phone's
  ringtone volume (silent when the phone is silent), loops until you act, and you can choose the ringtone.
- Buttons: **Call back**, **Snooze**, **Done**.
- **Reminder style** (Settings → Reminder style & ringtone): full screen until you react, full screen for 10/15/30/60 seconds and then a
  notification you can find later, or a plain banner notification.
- **Repeating reminders**, like Google Tasks: daily, weekly, monthly, yearly or custom (every N days/weeks/
  months/years, chosen weekdays), ending never, on a date, or after a number of times. Done moves a repeating
  reminder to its next date; a forgotten one moves on by itself when the next date arrives.
- **Call back dials immediately** on your SIM, with no dialer screen (needs the "Call directly" permission).
- **New reminder**: pick who to call from **Contacts** or **Recent calls** (shown as a contact card with name
  and number), add a note, and set the date & time in a Google Tasks style window (calendar, Set time,
  Repeat). The same window is used for Change time and Snooze → Pick date & time.
- Home screen lists what is overdue, later today and upcoming, plus a Done tab.

### Call recording
- Records phone calls automatically, and optionally WhatsApp calls.
- **Recordings screen** (microphone icon on the home screen): play, seek, **playback speed 1x / 1.25x / 1.5x**,
  rename, share, delete.
- Files are saved as `.m4a` in the phone's **Recordings / Call Recorder** folder, so they also show in the
  Files app. The contact's name and number are taken from the call log after the call.
- Automatic deletion after 30 / 60 / 90 days or 6 months (or keep forever).
- Accidental recordings shorter than 3 seconds are discarded.

#### How recording works, and its limits
Android does not let normal apps record the phone line itself. This app records **through the microphone**.

- To stop Android from silencing the microphone during a call, switch on the app's **Accessibility service**
  once (*Settings → Call recording → Accessibility service*). The service does nothing else: it ignores all
  events and cannot read the screen.
- The other person is only heard clearly if you use the **speaker** during the call.
- **Recording source** (Settings → Call recording) is *Automatic* by default. If a call comes out silent, the next call tries the
  next source. You can also choose one yourself. *Test the microphone* records 5 seconds as a quick check.
- Results depend on the phone and Android version. Recording laws differ between countries, so make sure
  recording is allowed where you are.

## Install

1. On your phone, open this repository's **Releases** page and download **`CallbackReminder.apk`**
   from the release named **Latest build**.
2. Open the file and allow installing from this source when Android asks.
3. Every push to `main` rebuilds this APK. Updates install over the old version and keep your data.

## First-time setup

Open the app and go to **Settings → Setup**. The list shows what is still needed:

| Permission | Why |
|---|---|
| Phone state | Detect when a call ends |
| Call log | Know who you just talked to |
| Notifications | Remind you at the right time |
| Display over other apps | Show the popup right after a call |
| Full-screen alerts (Android 14+) | Open the ringing reminder on a locked screen |
| Call directly | "Call back" dials straight away |
| Notification access | Detect WhatsApp calls |
| Contacts | Find the number of a WhatsApp contact |
| Battery: Unrestricted | Stop Samsung from putting the app to sleep |
| Microphone | Record calls |
| Accessibility service | Keep the microphone working during calls |

> **If a permission or the Accessibility switch is greyed out:** open **App info**, tap the **⋮** menu
> (top right) and choose **Allow restricted settings**, then try again. Android applies this to apps installed
> from outside the Play Store.

On Samsung phones, also set the app's battery to **Unrestricted** (App info → Battery) so reminders and
call detection are never delayed.

## Privacy

- All data (reminders, notes, recordings list) is stored on the phone.
- The app has **no internet permission**, so it cannot upload anything.
- Recordings are normal audio files in the shared Recordings folder. Apps you have given "Music and audio"
  access can read them.

## Build from source

Requirements: JDK 17, Gradle 8.10.2, Android SDK 35.

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions (`.github/workflows/build.yml`) does the same on every push to `main` and publishes the APK
as the **Latest build** release.

## Tech

- Kotlin, Jetpack Compose (Material 3), `minSdk 26`, `targetSdk 35`
- Phone-state broadcast receiver for call detection, notification listener for WhatsApp calls
- Foreground service for the ringing reminder, `AlarmManager` for exact reminder times
- `MediaRecorder` + `MediaStore` for recordings, an Accessibility service to keep the microphone alive in calls

```
app/src/main/java/com/callbackreminder/app/
  PostCallScreen.kt / PostCallActivity.kt   after-call popup
  Receivers.kt                              call start/end detection, reminder alarms
  WhatsAppCallListener.kt                   WhatsApp call detection
  RingService.kt / ReminderAlertActivity.kt ringing full-screen reminder
  ReminderStore.kt / Scheduler.kt           reminders and their alarms
  Repeat.kt / RepeatPicker.kt               repeat rules and the full-screen Repeat page
  AddReminderDialog.kt / DateTimeDialog.kt  New reminder popup, Google Tasks style date & time window
  ReminderStyle.kt                          full screen, timed full screen, or banner
  CallRecorder.kt / Recording.kt            call recording engine, storage, playback
  RecordingsScreen.kt                       recordings list and player
  HomeScreen.kt / SettingsScreen.kt         main screens
```

## Changelog

### 0.5.1
- Redesigned **New reminder**: Contacts / Recent calls instead of typing, a contact card with the name, and one
  Date & time button that opens a Google Tasks style calendar with Set time and a full-screen Repeat page.

### 0.5.0
- **Reminder style** setting: full screen until you react, full screen then notification, or banner only.
- **Repeating reminders** (daily, weekly, monthly, yearly, custom) with an end date or a number of times.
- Choose your own quick times for the popup and Snooze menu, and reorder them by dragging.

### 0.4.0
- **Call recording**, Recordings screen with playback speed (1x / 1.25x / 1.5x), recording settings and
  microphone test.
- After-call popup now has 12 reminder times (3 × 4 grid) and appears with no delay after the call ends.

### 0.3.1
- Fixed a second, blank "Unknown caller" popup after WhatsApp calls.

### 0.3.0
- **Call directly** from the reminder, without opening the dialer.
- **WhatsApp calls** get the same after-call popup (outgoing, incoming, missed).
