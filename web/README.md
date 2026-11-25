# Rafiq Builders portal

The company web portal at https://rafiqbuilders.web.app (it grew out of the owner-only team meeting page).

Sections (each person sees only what his access allows; `None / View / Edit` per section is set in Team › Access):

- **Team Performance** (view only): Overview (cards, team score, one table: tasks · dossier · score, person report) and Person (All · My tasks · Follow-ups · Assigned · Dossier · Calendar, Timeline per task). Settings › Team Performance sets the numbers.
- **Meetings**: start a daily or weekly meeting, Actions, Minutes. Access *View* = only the minutes he was in and the actions on him.
- **Team**: People (one list, each official Gmail is a seat), Chart (drag to change the head, impact check first), Designations, Access, Waiting to join, Offboarding.
- **Plot files**: being rebuilt as tracking only (no money, cheques, items or prints). The menu is hidden until the new list and file page are ready.
- **SOP import** (owner only, `plotsync.js`): the master SOP is the Google Sheet "plot-files-sop-master-v2". "Get changes from Google Sheet" reads it through the sheet's private Apps Script link (`sop/master-sheet/sync-web-app.gs` in the project files). Tabs read: Steps, Checklist (incl. Detail EN/UR), If something goes wrong (long + Short EN/UR), Flow, Fields, Stages, Roles, Forms, RG-03 list, Words, SOP info. Every problem blocks Publish. Publish writes everything to `config/plotsop` (one document, read by the web and the phone).
- **Documents**: My reading list, Work documents, Policies; owners also get SOPs, Job descriptions and Reading progress. Every file is a Google Drive link.
- **Settings**: late-reason list, red lines, meeting steps, ranking, reading deadline, Change log (owners).

Files: `core.js` (data, sign-in, access, seats), `calc.js` (numbers), `shell.js` (layout and routing), `meetings.js`, `tpcalc.js` + `tp.js` + `tp.css` (Team Performance), `team.js`, `docs.js`, `settings.js`, `portal.css` + `style.css` (look), `demo.js` (sample data).

- `?demo` shows sample data (nothing is read or saved). `?demo&as=m0` previews a staff view (m0 Adeel: only Documents), `&as=m5` a site manager, `&as=m6` the GM.
- Security is in `firestore.rules` (Spark plan, no Cloud Functions): paste it in Firebase console › Firestore › Rules after every change.
- Publishing: `.github/workflows/web.yml` publishes `web/` to Firebase Hosting on every merge to main that changes it (needs the `FIREBASE_SERVICE_ACCOUNT` secret). Each publish writes `version.json`; an open page checks it every 5 minutes and shows an Update bar.
- `config.js` holds the Firebase web settings. They are not secret: they identify the project, and the rules guard the data.
- Plot files SOP pages (`plotsop.js`): being rebuilt for the v2 sheet. Until then the opened SOP shows only SOP settings and Versions.
- SOP import lives in Docs > SOPs (owner): master Google Sheet link, Get changes from Google Sheet, draft with What changed and checks, Publish, Versions, and "Delete imported SOP". The Plot files section has only the Files tab; new plot files can be opened only after an SOP is imported, and follow it.

**Settings > Holidays.** The office's weekly off day (Friday) and its list of holiday dates are kept here, in `config/holidays`, and only the owner changes them. Every SOP counts its working days from this setting, so nothing about holidays goes in a SOP's Google Sheet.

**Drive link SOP rows.** Open and Upload new version stay on the row. Tap the row to open a window with Edit and Delete.

