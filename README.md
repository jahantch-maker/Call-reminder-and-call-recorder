<div align="center">

# Callback Reminder

**Never forget to call someone back.**

An Android app that asks *"When should I remind you to call back?"* the moment a call ends, rings like a real
call when the time comes, sends follow-up text messages for you, and can record your calls too. Everything stays on your phone.

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![No internet permission](https://img.shields.io/badge/privacy-no%20internet%20permission-0FA68E)
![Version](https://img.shields.io/badge/version-0.8.0-2F5BEA)

<img src="docs/screenshots/01-never-forget.jpg" width="200" alt="Call-backs list" />
<img src="docs/screenshots/02-after-call-popup.jpg" width="200" alt="After-call popup" />
<img src="docs/screenshots/03-rings-like-a-call.jpg" width="200" alt="Ringing reminder" />
<img src="docs/screenshots/10-text-messages.jpg" width="200" alt="Scheduled text messages" />

</div>

---

## Contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Install](#install)
- [First-time setup](#first-time-setup)
- [How call recording works](#how-call-recording-works)
- [Privacy](#privacy)
- [Build from source](#build-from-source)
- [How it works inside](#how-it-works-inside)
- [Changelog](#changelog)

## Features

### 📞 After-call popup
- Slides up **the moment a call ends**: phone calls *and* WhatsApp voice/video calls.
- **One-tap reminder times**, each showing the exact time it will ring. These are the defaults; change, reorder
  or reset them in Settings → Quick times (1 to 12):

  | | | |
  |---|---|---|
  | 15 min | 30 min | 45 min |
  | 1 hour | 2 hours | 3 hours |
  | Tomorrow 10 am | Tomorrow 4 pm | Tomorrow, same time as now |
  | In 2 days 10 am | In 2 days 4 pm | In 2 days, same time as now |

- Add a note (*"What did they promise?"*), or **pick any date and time**.
- Unknown number? Type who it was.
- **Smart with call waiting**: if several calls overlap you get one popup per person you actually talked to
  ("Call 1 of 2"). Callers who only rang while you were busy are left out.
- Choose which calls show the popup: **outgoing, incoming, missed, WhatsApp**.
- If Android blocks the popup, a notification ("Set a follow-up call?") opens it instead.

### ⏰ Reminders that ring like a call
- At the due time a **full-screen reminder** opens and the phone rings, **even on the lock screen**.
- It rings at your **ringtone volume**, vibrates in vibrate mode and stays quiet in silent mode. It keeps ringing until
  you act (with a 30-minute safety stop), and stays quiet if you are already on a call.
- **Reminder style** (Settings → Reminder style & ringtone): full screen until you react, full screen for
  10/15/30/60 seconds and then a notification, or a plain banner notification.
- **Call back** dials immediately on your SIM, with no dialer screen and no unlocking (needs the "Call directly"
  permission; otherwise the dialer opens).
- **Snooze** to one of your quick times or any date and time. **Done** files it away.
- **Repeating reminders**, like Google Tasks: daily, weekly, monthly, yearly or custom (every N days/weeks/
  months/years, chosen weekdays), ending never, on a date, or after a number of times. Done moves a repeating
  reminder to its next date; a forgotten one moves on by itself when the next date arrives.
- Several reminders due at once are shown together on one screen.
- Pick your own **reminder ringtone**.

### 📋 Call-backs list
- Grouped into **Overdue**, **Later today** and **Upcoming**, with live "In 1 h" / "Overdue by 45 min" labels.
- Call, Snooze or Done right from each card; change the time or delete from the ⋮ menu.
- **Done** tab with your finished call-backs, and "Call again" / "Reschedule".
- **New reminder**: pick who to call from **Contacts** or **Recent calls** (shown as a contact card with name
  and number), add a note, and set the date & time in a Google Tasks style window (calendar, Set time,
  Repeat).
- Reminders survive a phone restart or app update.

### 💬 Scheduled text messages (SMS)
- **Text messages** screen (message icon on the home screen): schedule an SMS to someone, once or on a repeat
  (same Google Tasks style repeats), e.g. a weekly "please wash the solar panels".
- Sent **automatically** at the set time from your SIM (the phone's default SMS SIM), even when the phone is locked.
  It shows up in your normal Messages app. A quiet "Sent to … ✓" notification follows.
- If it can't go out (no signal, no balance) it retries every 15 minutes up to 3 times, then alerts you with
  **Try again** and **Open in Messages**.
- If the phone was off at send time, it sends when the phone is back on if less than 12 hours late; otherwise it
  skips to the next repeat.
- **Tracking number** (switch in New text message, on by default): each reminder gets a task number like
  **261011-01** (the date of its first text, then that day's count), given when the first text goes out and never
  changed. Texts read "(261011-01) …". Turn it off for people outside your team, like a vendor: the texts then go
  out exactly as you typed them, and you mark the task done yourself.
- **Bursts**: turn on Burst and pick one of your templates (Settings › Text messages, up to 20, e.g.
  "5 texts, 5 min apart", at least 5 minutes apart). Each text starts with its number: "Reminder 1 (261011-01): …",
  "Reminder 2 (261011-01): …" (just "Reminder 1: …" with tracking off). Combine it with a repeat, e.g. daily for
  10 days, so one reminder replaces many. Burst texts are not retried; a missed one is skipped.
- The Tracking number and Burst switches each have an **ⓘ** button that explains them.
- New messages start at **8:00 AM**; you pick the date.
- Three tabs: **Scheduled**, **To review** and **Done**.
  - When the person replies exactly **Done 261011-01** (their task number; switch in Settings › Text messages),
    only that reminder stops and moves to To review with a banner. A plain "Done" is ignored, and replies to
    untracked texts are not checked. **Mark done** moves it to Done; **Not done** carries on sending.
  - Every message that finishes goes to To review for you to check, whether it is a single text, a burst or a repeat
    ("No reply", "Sent", or "Not sent" when it failed or the phone was off). Only your own **Mark done** moves it to
    Done; **Not done** lets you set a new time.
  - Tap **Done** on a card when you already know the work is done.
- Each message keeps a **History** (every text sent, delivered, failed or skipped, replies and your decisions).
- Needs the SMS permission. On Android 15+ an app installed outside Play Store must first get
  App info › ⋮ › **Allow restricted settings**; the app walks you through it. Without the permission you get a
  notification at the time and send it yourself from Messages.

### 🎙️ Call recording
- Records **phone calls automatically**, and optionally **WhatsApp calls**.
- **Recordings screen** (microphone icon on the home screen): play, seek, **playback speed 1x / 1.25x / 1.5x**,
  rename, share, delete.
- Saved as `.m4a` in the phone's **Recordings / Call Recorder** folder, so they also show in the Files app.
  The contact's name and number are added from the call log after the call.
- **Recording source**: *Automatic* (default) moves to the next source by itself if a call comes out silent, or
  pick Voice recognition, Voice communication, Microphone, Call audio or Unprocessed.
- **Test the microphone**: records 5 seconds and tells you if sound was captured.
- **Auto-delete** after 30 / 60 / 90 days or 6 months, or keep forever.
- Accidental recordings shorter than 3 seconds are discarded.

### ⚙️ Settings
Settings is a short menu; each row opens its own page:

| Page | What's there |
|---|---|
| **Setup** | Every permission with a green tick or an **Allow** button |
| **After-call popup** | Which calls show the popup (outgoing, incoming, missed, WhatsApp) and a preview |
| **Quick times** | Your 1 to 12 one-tap times: add, remove, drag to reorder, reset to default |
| **Call recording** | Microphone and Accessibility setup, what to record, recording source, auto-delete, microphone test |
| **Reminder style & ringtone** | Full screen / full screen then notification / banner, how many seconds, ringtone, test reminder |
| **Text messages** | Burst templates (up to 20, with a "What is a burst?" guide) and the stop-on-"Done <number>"-reply switch |

### 🧪 Try it out
Settings has **Preview the after-call popup** and **Ring a test reminder in 10 seconds**, so you can test everything
without making a real call.

## Screenshots

| | | |
|:-:|:-:|:-:|
| <img src="docs/screenshots/01-never-forget.jpg" width="250"/> | <img src="docs/screenshots/02-after-call-popup.jpg" width="250"/> | <img src="docs/screenshots/03-rings-like-a-call.jpg" width="250"/> |
| <img src="docs/screenshots/04-reminder-style.jpg" width="250"/> | <img src="docs/screenshots/05-new-reminder.jpg" width="250"/> | <img src="docs/screenshots/06-recent-calls.jpg" width="250"/> |
| <img src="docs/screenshots/07-date-and-time.jpg" width="250"/> | <img src="docs/screenshots/08-repeating-reminders.jpg" width="250"/> | <img src="docs/screenshots/09-quick-times.jpg" width="250"/> |
| <img src="docs/screenshots/10-text-messages.jpg" width="250"/> | <img src="docs/screenshots/11-sms-bursts.jpg" width="250"/> | <img src="docs/screenshots/12-burst-templates.jpg" width="250"/> |
| <img src="docs/screenshots/13-done-reply.jpg" width="250"/> | <img src="docs/screenshots/14-whatsapp-call-waiting.jpg" width="250"/> | <img src="docs/screenshots/15-call-recording.jpg" width="250"/> |
| <img src="docs/screenshots/16-settings.jpg" width="250"/> | <img src="docs/screenshots/17-private-setup.jpg" width="250"/> |  |

<sub>Screens are recreated from the app's UI code (v0.7.0) with sample names and numbers.</sub>

## Install

1. On your phone, open this repository's **[Releases](../../releases)** page and download **`CallbackReminder.apk`**
   from the release named **Latest build**.
2. Open the file and allow installing from this source when Android asks.
3. Every push to `main` rebuilds this APK. Updates install over the old version and keep your data.

Built and tested on a Samsung Galaxy S25 Ultra (One UI 8.5, Android 16). Works on Android 8.0 and newer.

## First-time setup

Open the app and go to **⚙ Settings → Setup**. It shows a green tick for everything that is done and an
**Allow** button for everything that is not:

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
| SMS (on the Text messages screen) | Send your scheduled text messages |
| Receive SMS (optional) | Notice a "Done <task number>" reply so that reminder can stop |

> **If a permission or the Accessibility switch is greyed out:** open **App info**, tap the **⋮** menu
> (top right), choose **Allow restricted settings**, then try again. Android does this for apps installed
> from outside the Play Store.

On Samsung phones, also set the app's battery to **Unrestricted** (App info → Battery) so reminders and call
detection are never delayed.

## How call recording works

Android does not let normal apps record the phone line itself, so the app records **through the microphone**.

- Switch on the app's **Accessibility service** once (*Settings → Call recording → Accessibility service*). Without
  it Android silences the microphone during calls. The service does nothing else: it ignores all events and cannot
  read the screen.
- The other person is only heard clearly if you use the **speaker** during the call.
- If a call comes out silent, the recording shows a warning and *Automatic* tries the next source on the next call.
- Results depend on the phone and Android version. **Recording laws differ between countries**, so make sure
  recording is allowed where you are, and tell the other person when the law requires it.

## Privacy

- All data (reminders, notes, the recordings list) is stored on the phone.
- The app has **no internet permission**, so it cannot upload anything. The only thing it sends is the text
  messages you schedule, from your own SIM.
- No account, no ads, no analytics.
- Recordings are normal audio files in the shared Recordings folder. Apps you have given "Music and audio" access
  can read them.

## Build from source

Requirements: JDK 17, Gradle 8.10.2, Android SDK 35.

```bash
gradle assembleRelease
# APK: app/build/outputs/apk/full/release/app-full-release.apk (main) and .../cr/release/app-cr-release.apk (CR Test)  (about 2 MB, R8-minified)
```

GitHub Actions ([`.github/workflows/build.yml`](.github/workflows/build.yml)) does the same on every push to `main`
and publishes the APK as the **Latest build** release.

> The APK is signed with a shared test key (`keystore/callback-test.jks`) so each build installs as an update.
> Create your own private key before publishing to the Play Store.

## How it works inside

- **Kotlin + Jetpack Compose (Material 3)**, `minSdk 26`, `targetSdk 35`. No third-party libraries beyond AndroidX
  and coroutines.
- **Call detection**: a `PHONE_STATE` broadcast receiver groups ringing / off-hook / idle changes into a session,
  then reads the call log entries added during that session to decide who gets a popup.
- **WhatsApp calls**: a notification listener watches WhatsApp's call notifications (they never reach the call log)
  and looks the caller's number up in your contacts.
- **Reminders**: exact alarms via `AlarmManager.setAlarmClock`, a foreground service that plays the ringtone, and
  a full-screen activity shown over the lock screen. A boot receiver re-arms alarms after a restart.
- **Recording**: `MediaRecorder` writing `.m4a` files through `MediaStore`, kept alive during calls by an
  Accessibility service (or a microphone foreground service as fallback).

```
app/src/main/java/com/callbackreminder/app/
  PostCallScreen.kt / PostCallActivity.kt   after-call popup
  CallQueue.kt                              who gets a popup (call waiting logic)
  Receivers.kt                              call start/end detection, reminder alarms, boot
  WhatsAppCallListener.kt                   WhatsApp call detection
  RingService.kt / ReminderAlertActivity.kt ringing full-screen reminder
  ReminderStore.kt / Scheduler.kt           reminders and their alarms
  Repeat.kt / RepeatPicker.kt               repeat rules and the full-screen Repeat page
  DateTimeDialog.kt                         Google Tasks style date & time window
  ReminderStyle.kt                          full screen, timed full screen, or banner
  SmsStore.kt / SmsSender.kt                scheduled text messages, sending, retries
  MessagesScreen.kt / SmsReminderDialog.kt  Text messages screen (Scheduled / To review / Done), New message popup
  Notifier.kt                               notification channels and backup notifications
  CallRecorder.kt / Recording.kt            recording engine, storage, playback
  RecordingsScreen.kt                       recordings list and player
  HomeScreen.kt / SettingsScreen.kt         main screens
  AddReminderDialog.kt / Components.kt      new-reminder dialog, contact card, recent calls, avatars
  QuickTimes.kt / QuickTimesSettings.kt     your one-tap times and the Quick times page
  Helpers.kt / Theme.kt                     settings, formatting, permissions status, colours
```

## Changelog

### 0.8.0
- **Task numbers** for text messages (like 261011-01). Only an exact "Done 261011-01" reply stops that one
  reminder; a plain "Done" is ignored. New **Tracking number** switch for plain texts to people outside your team,
  and ⓘ help for Tracking number and Burst.

### 0.7.0
- **Bursts** for text messages with your own templates, "Reminder N:" numbering, 8:00 AM default time,
  **To review** tab, stop on a "Done" reply, and Mark done / Not done.

### 0.6.0
- **Scheduled text messages**: automatic SMS from your SIM at a set time, once or repeating, with retries,
  a sent/delivered history, and a tap-to-send fallback when SMS permission is off.

### 0.5.3
- The published APK is now an optimized release build (about 2 MB instead of 16 MB) with smoother animations.
  It is signed with the same key, so it installs as an update.

### 0.5.2
- Settings split into section pages: Setup, After-call popup, Quick times, Call recording, and Reminder style &
  ringtone, each sliding in as its own page. Home banners open the right page directly.

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
