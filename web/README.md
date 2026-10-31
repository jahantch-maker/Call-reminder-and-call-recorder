# Rafiq Builders portal

The company web portal at https://rafiqbuilders.web.app (it grew out of the owner-only team meeting page).

Sections (each person sees only what his access allows; `None / View / Edit` per section is set in Team › Access):

- **App view**: Overview, Person (Day · Tasks · Calendar · Dossier · Late reasons · Timeline), Calendar, Late reasons.
- **Meetings**: start a daily or weekly meeting, Actions, Minutes. Access *View* = only the minutes he was in and the actions on him.
- **Team**: People (one list, each official Gmail is a seat), Chart (drag to change the head, impact check first), Designations, Access, Waiting to join, Offboarding.
- **Documents**: My reading list, Work documents, Policies; owners also get SOPs, Job descriptions and Reading progress. Every file is a Google Drive link.
- **Settings**: late-reason list, red lines, meeting steps, ranking, reading deadline, Change log (owners).

Files: `core.js` (data, sign-in, access, seats), `calc.js` (numbers), `shell.js` (layout and routing), `meetings.js`, `appview.js`, `team.js`, `docs.js`, `settings.js`, `portal.css` + `style.css` (look), `demo.js` (sample data).

- `?demo` shows sample data (nothing is read or saved). `?demo&as=m0` previews a staff view (m0 Adeel: only Documents), `&as=m5` a site manager, `&as=m6` the GM.
- Security is in `firestore.rules` (Spark plan, no Cloud Functions): paste it in Firebase console › Firestore › Rules after every change.
- Publishing: `.github/workflows/web.yml` publishes `web/` to Firebase Hosting on every merge to main that changes it (needs the `FIREBASE_SERVICE_ACCOUNT` secret). Each publish writes `version.json`; an open page checks it every 5 minutes and shows an Update bar.
- `config.js` holds the Firebase web settings. They are not secret: they identify the project, and the rules guard the data.
