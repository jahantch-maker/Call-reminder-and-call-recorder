# CLAUDE.md

## Two Claude projects work on this one repo

This repo (phone app `app/` + web portal `web/` at rafiqbuilders.web.app + `firestore.rules`) is changed from **two separate Claude projects** owned by Jahanzaib:

| Project | Works on | PR title prefix |
|---|---|---|
| **Callback Reminder** (main project) | Phone app, web portal bug fixes and features, manager-review roadmap | `[App]` or `[Web]` |
| **Plot files** (Rafiq Builders plot possession → construction → completion → closed files) | New "Plot files" section inside the same web portal, plus any phone tasks it creates | `[Plot files]` |

Both projects ship to the **same website, the same Firebase project (rafiq-builders), the same Firestore rules file and the same phone app**. Neither project can see the other's chat or memory. This file is the only thing both always see.

### Clash check: run before every build, PR or merge

1. Fetch open PRs: `gh api "repos/jahantch-maker/Call-reminder-and-call-recorder/pulls?state=open"` (GraphQL is blocked, use REST).
2. For each open PR **not started by your own thread**, list its files (`.../pulls/N/files`) and compare them with the files you will touch.
3. Treat these as **shared hot spots**. Any overlap is a clash:
   - `web/` (especially `web/index.html`, `web/shell.js` (menu and layout), `web/core.js`, `web/config.js`, `web/style.css`, `web/portal.css`, `web/version.json`)
   - `firestore.rules`, `database.rules.json`, `firebase.json`
   - `app/build.gradle.kts` (versionCode/versionName)
   - `app/src/main/java/com/callbackreminder/app/ChangeLog.kt`
   - Firestore data shapes under `teams/{t}/...` (seats, config, tasks)
4. If there is a clash, **stop and warn Jahanzaib in plain English before writing code**. Name the other PR (number + title + which project from its prefix), say which files overlap, and recommend one of these: wait for it to merge, build on top of it after it merges, or split the work so files don't overlap.
5. Even with no open PR, warn him if the work changes something the other project relies on: the portal's left menu or home screen, the seat/designation/access data model, or Firestore rules.

### Rules both projects follow
- One build at a time on `web/` or `firestore.rules`. After a PR merges, the next one rebases on the latest `main` first.
- **Firestore rules:** there is one rules file for the whole Firebase project. Always start from the latest `main` copy and add to it, never replace it. Say clearly in the PR when Jahanzaib must paste new rules into the Firebase console.
- **Versions:** before bumping the app version, check `main` and go past the highest version there. Every version-bumping PR adds a "What's new" entry at the top of `entries` in `ChangeLog.kt` (general wording, no personal names).
- Start every PR title with the project prefix above, so the other project can tell whose it is.
- Every push to `main` republishes the "Latest build" APK release. Every merge touching `web/` republishes the website.
- Talk to Jahanzaib in English, even if he writes in Urdu (voice typing may show Hindi script).

### Build notes
- No Android SDK in the cloud. App PRs rely on the GitHub Actions build check (`build.yml`); flavors are `full` and `cr`.
- Web preview: `cd web && python3 -m http.server 8123`, then open `http://localhost:8123/index.html?demo`.
