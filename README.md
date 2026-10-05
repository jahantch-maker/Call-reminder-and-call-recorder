# Callback Reminder

Android app: after every phone call a small popup asks "When should I remind you to call back?".
Pick a time in one tap and the phone reminds you then.

- Native Android (Kotlin + Jetpack Compose), everything stored on the phone
- Built in the cloud by GitHub Actions: every push to `main` produces `CallbackReminder.apk`
  on the **Releases → Latest build** page

## What's new in 0.3.0

- **Call directly.** "Call back" on the reminder now places the call straight away. No dialer screen,
  and on a locked phone no unlocking is needed (the call starts and the in-call screen shows over the
  lock screen). Needs the **Call directly** permission in Settings; without it the app falls back to
  opening the dialer, like before.
- **WhatsApp calls.** You now get the same after-call popup after WhatsApp voice/video calls
  (outgoing, incoming and missed). Needs **Notification access** (Settings) and, to find the number of a
  contact, the **Contacts** permission. The call-back itself is made on your SIM.
  Turn it on or off with the "WhatsApp calls" switch under *After-call popup*.
