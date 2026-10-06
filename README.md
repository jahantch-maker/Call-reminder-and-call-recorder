# Callback Reminder

Android app: after every phone call a small popup asks "When should I remind you to call back?".
Pick a time in one tap and the phone reminds you then.

- Native Android (Kotlin + Jetpack Compose), everything stored on the phone
- Built in the cloud by GitHub Actions: every push to `main` produces `CallbackReminder.apk`
  on the **Releases → Latest build** page

## What's new in 0.4.0

- **Call recording.** Phone calls (and, optionally, WhatsApp calls) are recorded automatically and listed
  on the new **Recordings** screen (microphone icon on the home screen): play, rename, share, delete.
  Files are saved as `.m4a` in the phone's **Recordings / Call Recorder** folder (visible in the Files app).
  - Android does not let normal apps record the phone line itself, so recording goes through the
    microphone. To stop Android silencing the microphone during calls, switch on the
    **Callback Reminder call recording** Accessibility service once (Settings → Call recording).
    It does not read the screen.
  - For the other person to be heard clearly, use the speaker during the call.
  - **Recording source** is "Automatic" by default: if a call comes out silent, the next call tries the next
    source. You can also pick one yourself in Settings. "Test the microphone" records 5 seconds as a check.
  - Recordings can be deleted automatically after 30 / 60 / 90 days / 6 months (Settings → Keep recordings).

## What's new in 0.3.1

- Fixed a second, blank "Unknown caller" popup appearing after WhatsApp calls.

## What's new in 0.3.0

- **Call directly.** "Call back" on the reminder now places the call straight away. No dialer screen,
  and on a locked phone no unlocking is needed (the call starts and the in-call screen shows over the
  lock screen). Needs the **Call directly** permission in Settings; without it the app falls back to
  opening the dialer, like before.
- **WhatsApp calls.** You now get the same after-call popup after WhatsApp voice/video calls
  (outgoing, incoming and missed). Needs **Notification access** (Settings) and, to find the number of a
  contact, the **Contacts** permission. The call-back itself is made on your SIM.
  Turn it on or off with the "WhatsApp calls" switch under *After-call popup*.
