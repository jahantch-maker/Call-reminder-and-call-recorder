# Rafiq Builders portal

The company web portal at https://rafiqbuilders.web.app (it grew out of the owner-only team meeting page).

Sections (each person sees only what his access allows; `None / View / Edit` per section is set in Team › Access):

- **App view**: Overview, Person (Day · Tasks · Calendar · Dossier · Late reasons · Timeline), Calendar, Late reasons.
- **Meetings**: start a daily or weekly meeting, Actions, Minutes. Access *View* = only the minutes he was in and the actions on him.
- **Team**: People (one list, each official Gmail is a seat), Chart (drag to change the head, impact check first), Designations, Access, Waiting to join, Offboarding.
- **Plot files** (own module: `plotfiles.js`, `plotsteps.js`, `plotfiles.css`): one file per plot from opening to possession and discount cheques. Files list (Open files, Late, Waiting for plot owner), one file page (Steps, Items, Cheques, Timeline), Rules and timing (owners). Cheques and amounts live in `plotmoney`, readable only by owners and people with the "See money details" switch.
- **Plot files > SOP versions** (owner only, `plotsync.js`): the master SOP is the Google Sheet "Plot files SOP · master". "Get changes from Google Sheet" reads it through the sheet's private Apps Script link (`sop/master-sheet/sync-web-app.gs`, one-time deploy), builds a draft, shows What changed and Checks (any problem blocks Publish), then Publish makes version N (`config/plotsop` for everyone, `plotsopv/*` copies for the owner). Until the first publish the built-in register in `plotsteps.js` is used. Test files (owner only): flagged `test`, "Act as" a role on the file page, "Delete all test files" on the Files list.
- **Documents**: My reading list, Work documents, Policies; owners also get SOPs, Job descriptions and Reading progress. Every file is a Google Drive link.
- **Settings**: late-reason list, red lines, meeting steps, ranking, reading deadline, Change log (owners).

Files: `core.js` (data, sign-in, access, seats), `calc.js` (numbers), `shell.js` (layout and routing), `meetings.js`, `appview.js`, `team.js`, `docs.js`, `settings.js`, `portal.css` + `style.css` (look), `demo.js` (sample data).

- `?demo` shows sample data (nothing is read or saved). `?demo&as=m0` previews a staff view (m0 Adeel: only Documents), `&as=m5` a site manager, `&as=m6` the GM.
- Security is in `firestore.rules` (Spark plan, no Cloud Functions): paste it in Firebase console › Firestore › Rules after every change.
- Publishing: `.github/workflows/web.yml` publishes `web/` to Firebase Hosting on every merge to main that changes it (needs the `FIREBASE_SERVICE_ACCOUNT` secret). Each publish writes `version.json`; an open page checks it every 5 minutes and shows an Update bar.
- `config.js` holds the Firebase web settings. They are not secret: they identify the project, and the rules guard the data.
- Plot files SOP pages (`plotsop.js`): the written SOP (main SOP and one page per role, English and Urdu) is built live from the published Google Sheet (`config/plotsopx`, written at Publish together with the steps). It shows in Docs > SOPs for everyone with Docs access, with Print / Save PDF; the step list has a "How to do this" window and clickable form chips. Publish a new version from the sheet and every page follows.
- SOP import lives in Docs > SOPs (owner): master Google Sheet link, Get changes from Google Sheet, draft with What changed and checks, Publish, Versions, and "Delete imported SOP". The Plot files section has only the Files tab; new plot files can be opened only after an SOP is imported, and follow it.

**Settings > Holidays.** The office's weekly off day (Friday) and its list of holiday dates are kept here, in `config/holidays`, and only the owner changes them. Every SOP counts its working days from this setting, so nothing about holidays goes in a SOP's Google Sheet.

**Drive link SOP rows.** Open and Upload new version stay on the row. Tap the row to open a window with Edit and Delete.

**Form previews.** Docs > SOPs > Forms shows each app-printed form beside its details. RG-03 is drawn live from the SOP's Form items (the same code prints it from a plot file's Items tab, `plotforms.js`). RG-05, RG-07, RG-13, RG-14 and RG-15 show their locked sample (`web/forms/*-sample.html`) until their phone tasks are built.

**Cheque lines and RG-05 (locked 17 Nov, option A).** On a plot file's Cheques tab the Accounts Officer adds this plot's cheque lines (+ Add cheque line). The kinds of lines and their boxes come from the SOP's RG-05 "Repeating" form items. Until the Accounts Head approves on the phone, the owner or a manager can approve on the web ("from portal"). Approval locks the lines and adds the RG-05 rows to the Items tab; Print RG-05 then opens. Front Desk records each cheque against its line; a date or amount that differs shows "Check". Amounts are stored only in `plotmoney`. Steps whose forms include RG-14 (2.01, 2.07) have "Print Still pending slip". On the RG-05 print, an item that uses a money variable (Rs {cashTotal}) gets breakdown rows: the other Rs variables of the same Variables group, shown only when they add up exactly to it.
