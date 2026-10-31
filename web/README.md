# Team Meeting web app

Owner-only page for the daily and weekly team meeting. Lives at https://rafiqbuilders.web.app.

- Sign in with the same Gmail as the team owner in the phone app. Firestore rules (`firestore.rules`) let only the owner read the team's tasks here.
- `?demo` shows sample data; nothing is read or saved.
- Publishing: `.github/workflows/web.yml` publishes `web/` to Firebase Hosting on every merge to main that changes it (needs the `FIREBASE_SERVICE_ACCOUNT` secret). Each publish writes `version.json`; an open page checks it every 5 minutes and shows an Update bar.
- `config.js` holds the Firebase web settings. They are not secret: they identify the project, and the rules guard the data.
