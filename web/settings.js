/* Settings: late-reason list, red lines, meeting steps, ranking, reading deadline, office holidays and the change log (owners). */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  /** The five tabs the owner can tick per person (RB.SET_TABS); Change log is for owners, Updates and Appearance for everyone. */
  const TABS = RB.SET_TABS;
  // ------------------------------------------------------------------ shared look (Settings redesign, locked 27 Nov, J1-J5)
  /** 28 Nov (SETTINGS-2COL) The ⓘ right after a row's name: its full explanation in a small popup. */
  RB.stInfo = (t) => (t ? ` <button class="accinfo" data-info="${esc(t)}" aria-label="What this does">i</button>` : "");
  /** A labelled row: name and ⓘ on the left, the box on the right. One line, so more rows fit on the screen. */
  RB.stField = (lbl, hint, box) => `<div class="st-f"><div class="l">${lbl}${RB.stInfo(hint)}</div><div class="v">${box}</div></div>`;
  /** A switch card: icon, title, one line, switch. attrs = the data-* the page listens to. */
  RB.stSwitch = (ic, t, d, attrs, on) => `<div class="st-sw"><span class="ic">${RB.lineIcon(ic)}</span><span class="w"><b>${esc(t)}${RB.stInfo(d)}</b></span><button class="sw ${on ? "on" : ""}" role="switch" aria-checked="${!!on}" aria-label="${esc(t)}" ${attrs}></button></div>`;
  /** "Saved ✓" for switches and ticks, which save the moment they are clicked (J3). */
  RB.stSaved = (t) => {
    let el = document.getElementById("stok");
    if (!el) { el = document.createElement("div"); el.id = "stok"; el.className = "st-ok"; el.setAttribute("role", "status"); document.body.appendChild(el); }
    el.textContent = "✓ " + (t || "Saved"); el.classList.add("on"); clearTimeout(RB._stok); RB._stok = setTimeout(() => el.classList.remove("on"), 1600);
  };
  /** Numbers and choices wait for the fixed Save bar (J3). Typed values are kept per tab in S.stDirty, so a redraw when
   *  cloud data arrives never wipes them, and the bar comes back if you leave the tab and return. */
  S.stDirty = S.stDirty || {};
  const SAVEBAR = '<div class="st-save" id="stsave" role="region" aria-label="Unsaved changes"><span class="g" id="stsn"></span><button class="un" id="stun">Undo</button><button class="sv" id="stsv">Save</button></div>';
  function draftBind(root, tab) {
    const bar = root.querySelector("#stsave"); if (!bar) return;
    const d = (S.stDirty[tab] = S.stDirty[tab] || {});
    const show = () => { const n = Object.keys(d).length; bar.classList.toggle("on", n > 0); root.querySelector("#stsn").textContent = n + (n === 1 ? " change" : " changes") + " not saved yet"; };
    root.querySelectorAll("[data-st]").forEach((i) => {
      const k = i.dataset.st, row = i.closest(".st-f,.rrow");
      if (k in d) { i.value = d[k]; if (row) row.classList.add("chg"); }
      const was = i.defaultValue !== undefined && i.tagName === "INPUT" ? i.defaultValue : [...i.options].find((o) => o.defaultSelected)?.value;
      i.addEventListener("input", () => { if (i.value === was) delete d[k]; else d[k] = i.value; if (row) row.classList.toggle("chg", k in d); show(); });
    });
    show();
    root.querySelector("#stun").onclick = () => { delete S.stDirty[tab]; RB.render(); };
    root.querySelector("#stsv").onclick = () => { const b = root.querySelector("[data-st-save]"); if (b) b.click(); };
  }
  /** Called by a page's save once it worked: the bar goes, the rows calm down, "Saved ✓" shows. */
  RB.stDone = () => { delete S.stDirty[S.tab.settings]; RB.render(); RB.stSaved(); };

  /** Team Performance settings (config/meeting.perf, read by tpcalc.js). */
  function perfHtml() {
    const c = RB.tp.cfg(), num = (id, val, lbl, unit, hint) => RB.stField(lbl, hint, `<input class="num" type="number" id="${id}" data-st="${id}" value="${val}" aria-label="${esc(lbl)}"><span>${unit || ""}</span>`);
    const SHOW = [["oldOver", "Oldest overdue tasks", "Tasks overdue the longest"], ["oldUns", "Not scheduled list", "Tasks with no date for too long"], ["moved", "Moved " + c.movedN + "+ list", "Tasks whose date was moved again and again"], ["needs", "Needs help box", "People with the lowest score"], ["trend", "Score line (weeks)", "The score week by week"], ["dosBars", "Dossier day bars", "One bar per day for dossier work"], ["byType", "By task type", "Tasks split by type"]];
    const sws = (i) => SHOW.filter(([k]) => c.show[k][i] !== null).map(([k, l, d]) => RB.stSwitch("chart", l, d, `data-tpshow="${k}:${i}"`, c.show[k][i])).join("");
    const ppl = RB.tp.people(true);
    return `<div class="stcols"><div class="card st-card"><h4 class="st-h">Score</h4>
      ${num("tpwon", c.wOn, "Weight: tasks on time", "%", "How much the on time % counts in the score. Score = on time % × this + dossier % × the dossier weight, divided by both added.")}${num("tpwdos", c.wDos, "Weight: dossier", "%", "Score = on time % × this + dossier % × this, divided by both added")}
      ${num("tpred", c.redBelow, "Red row when the score is under", "%", "A person's row turns red on Team Performance when the score is under this. Example: 70 = a score of 69 shows red.")}${num("tpweak", c.weakDay, "Red dossier day when under", "%", "A day on the dossier calendar turns red when less than this share of that day's dossier tasks was ticked.")}</div>
      <div class="card st-card"><h4 class="st-h">Lists</h4>
      ${num("tpgrace", c.grace, "Not scheduled counts after", "days", "A task with no date for longer than this")}${num("tpolder", c.older, "Oldest overdue list shows tasks over", "days", "The Oldest overdue list shows only tasks overdue for more days than this.")}
      ${num("tpmoved", c.movedN, "Moved list: date moved at least", "times", "A task shows in the Moved list once its date was moved this many times or more.")}${num("tpoldn", c.oldOverN, "Oldest overdue: rows before Show all", "rows", "How many tasks the Oldest overdue list shows before a Show all link.")}${num("tpneedn", c.needsN, "Needs help: people shown", "people", "How many people with the lowest score the Needs help box shows.")}
      ${num("tpweeks", c.weeks, "Score line shows the last", "weeks", "How many weeks back the score line (week by week) goes.")}
      ${RB.stField("Period when the page opens", "The time span Team Performance shows when it first opens. You can still change it on the page.", `<select id="tpperiod" data-st="tpperiod" aria-label="Period when the page opens">${RB.tp.PERIODS.map(([k, l]) => `<option value="${k}" ${c.period === k ? "selected" : ""}>${l}</option>`).join("")}</select>`)}
      <button class="edit-only st-hidden" id="tpsave" data-st-save="1">Save</button></div>
      <div class="card st-card"><h4 class="st-h">Main screen · <span>show or hide</span></h4>${sws(0)}</div>
      <div class="card st-card"><h4 class="st-h">Person report · <span>show or hide</span></h4>${sws(1)}</div>
      <div class="card st-card"><h4 class="st-h">Hide people · <span>ticked people are left out (for example the owner or a driver)</span></h4>
      <div class="st-tk">${ppl.map((m) => `<label class="${c.hidden.includes(m.uid) ? "on" : ""}"><input type="checkbox" data-tphide="${esc(m.uid)}" ${c.hidden.includes(m.uid) ? "checked" : ""}>${RB.lineIcon("user")}${esc(RB.first(m.name))}</label>`).join("")}</div></div>
      <div class="card st-card"><div class="tp-box"><b>How the numbers work</b><div class="hint" style="line-height:1.6;margin-top:4px">· <b>On time %</b> = done on time ÷ (on time + late + overdue + not scheduled). The first due date counts; moving a date later doesn't reset it. Late with an outside reason (material, payment, client) counts nowhere.<br>· Tasks given by a manager use their current deadline, because only the giver or a manager can change it.<br>· <b>Dossier %</b> = ticked ÷ due over the days. Today never pulls it down until the day ends. No dossier counts as 0.<br>· <b>Score</b> = both together by the weights above; the rank always follows the score.<br>· The week starts on Saturday. Weekly off days come from each person's timetable, holidays from Settings › Holidays.</div></div></div></div>`;
  }

  // ------------------------------------------------------------------ Holidays (office closed days; locked 25 Nov: Import Pakistan holidays,
  // 🌙 moon dates confirmed 3 days before, a "next year is ready" notice every 1 December). config/holidays { dates: [{ d, name, moon, ok }] }.
  // The import asks the cloud (functions/ pkHolidays) through config/holimport, because a browser can't read Google's calendar itself.
  const pad = (n) => String(n).padStart(2, "0");
  const isoOf = (dt) => dt.getFullYear() + "-" + pad(dt.getMonth() + 1) + "-" + pad(dt.getDate());
  const plus = (d, n) => { const dt = new Date(d + "T12:00:00"); dt.setDate(dt.getDate() + n); return isoOf(dt); };
  const hfmt = (d) => new Date(d + "T12:00:00").toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
  const hdates = () => ((D.holidays && D.holidays.dates) || []).slice().sort((a, b) => (a.d < b.d ? -1 : a.d > b.d ? 1 : 0));
  /** Week days everyone has off (from all timetables; Friday until there are none). */
  const commonOff = () => { const tts = RB.timetables ? RB.timetables() : []; return tts.length ? [0, 1, 2, 3, 4, 5, 6].filter((d) => tts.every((t) => (t.off || []).includes(d))) : [5]; };
  const DN = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  const offTag = (d) => { const w = new Date(d + "T12:00:00").getDay(); return commonOff().includes(w) ? `<span class="hz-tag fri">${DN[w]}, already off</span>` : ""; };
  const base = (n) => String(n || "").replace(/\(.*?\)|·.*$|\bholidays?\b|\bday\s*\d+\b/gi, "").replace(/\s+/g, " ").trim() || n;
  /** 🌙 Moon holidays not confirmed yet that start within 3 days, grouped (Eid day 1 + day 2 = one question). */
  RB.moonDue = () => {
    const today = isoOf(new Date()), limit = plus(today, 3), out = [];
    hdates().filter((h) => h.moon && !h.ok && h.d >= today && h.d <= limit).forEach((h) => {
      const g = out.find((x) => x.base === base(h.name) && plus(x.dates[x.dates.length - 1].d, 1) >= h.d);
      if (g) g.dates.push(h); else out.push({ base: base(h.name), dates: [h] });
    });
    return out;
  };
  /** From 1 December: next year's holidays wait to be imported until any date of that year is in the list. */
  RB.holReady = () => { const n = new Date(); return n.getMonth() === 11 && !hdates().some((h) => h.d.startsWith(String(n.getFullYear() + 1))) ? n.getFullYear() + 1 : 0; };
  RB.holNotices = () => {
    if (!RB.isOwner()) return [];
    const out = RB.moonDue().map((g) => ["settings", "holidays", "🌙 " + g.base + " is set for " + g.dates.map((h) => hfmt(h.d)).join(" and "), "Moon date: confirm it once the moon is announced"]);
    const y = RB.holReady(); if (y) out.unshift(["settings", "holidays", "🗓 " + y + " Pakistan holidays are ready", "Tick the days the office is closed, then Add", () => openImport(y)]);
    return out;
  };
  const hsave = (dates, note) => { RB.hold = true; return RB.Store.saveConfig("holidays", Object.assign({}, D.holidays || {}, { dates })).then(() => RB.log(note)).catch((e) => console.warn("log", e)).finally(() => { RB.hold = false; RB.render(); }); };
  function openImport(year) {
    S.hImp = { year, askedAt: Date.now(), picks: null, moved: {}, editing: "" };
    S.tab.settings = "holidays";
    RB.Store.put("config", "holimport", { year, askedAt: S.hImp.askedAt }, true, true);
    RB.go("settings", "holidays");
  }
  function holidays() {
    const own = RB.isOwner(), list = hdates(), y = own && RB.holReady();
    const tag = (h) => (h.moon ? (h.ok ? '<span class="hz-tag ok">✓ Moon date confirmed</span>' : '<span class="hz-tag moon">🌙 Moon date, confirm</span>') : offTag(h.d));
    let yr = "";
    const rows = list.map((h) => { const head = h.d.slice(0, 4) !== yr ? ((yr = h.d.slice(0, 4)), `<div class="hz-yh">${yr}</div>`) : "";
      return head + `<div class="hz-row"><span class="d">${esc(hfmt(h.d))}</span><b>${esc(h.name || "")}</b><span>${tag(h)}</span><span style="display:flex;gap:6px">${own ? `<button class="btnt" data-hedit="${esc(h.d)}">Edit</button><button class="btnt" data-hdel="${esc(h.d)}">Remove</button>` : ""}</span></div>`; }).join("");
    const moon = own ? RB.moonDue().map((g, i) => `<div class="hz-bar">🌙<span class="t"><b>${esc(g.base)} is set for ${g.dates.map((h) => esc(hfmt(h.d))).join(" and ")}.</b> Moon date: confirm once Ruet-e-Hilal announces.</span><button class="btnt" data-hmoon="${i}:same">Same</button><button class="btnt" data-hmoon="${i}:1">1 day later</button><button class="btnt" data-hmoon="${i}:-1">1 day earlier</button><button class="btnt" data-hmoon="${i}:add">+ Add a day</button></div>`).join("") : "";
    const ready = y ? `<div class="hz-bar">🗓<span class="t"><b>${y} Pakistan holidays are ready.</b> Tick the days the office is closed, then Add.</span><button class="btnp" data-himp="${y}">Review and add ›</button></div>` : "";
    const html = `<div class="card" style="max-width:${S.hImp ? 640 : 820}px"><div class="stickhead"><div class="subhead"><h3>${list.length} ${list.length === 1 ? "day" : "days"}</h3><div class="sp"></div>${own ? `<button class="btnp" data-himp="${new Date().getFullYear()}">⬇ Import Pakistan holidays</button>` : ""}</div></div>
      ${ready}${moon}${rows || '<div class="muted">No holidays added yet.</div>'}
      ${own ? `<div class="rrow" style="flex-wrap:wrap;gap:8px;margin-top:12px"><input type="date" id="hdate"><input id="hname" placeholder="Name, e.g. Eid ul Fitr" style="flex:1;min-width:160px"><button class="btnp" id="hadd">Add</button></div>` : '<div class="foot">Only the owner changes holidays.</div>'}</div>`;
    return { html, side: own && S.hImp ? impPanel() : "" };
  }
  function impPanel() {
    const I = S.hImp, R = D.holimport || {}, now = new Date().getFullYear();
    const yrs = `<div class="hz-yr">${[now, now + 1].map((y) => `<button class="dz-chip ${I.year === y ? "on" : ""}" data-himpy="${y}">${y}</button>`).join("")}</div>`;
    const head = `<div class="ttpanel hzpanel"><h3>Import Pakistan holidays</h3><div class="sub">From Google's public Pakistan holidays calendar. Tick the days the office is closed.</div>${yrs}`;
    const foot = (btn) => `<div class="ttfoot"><div class="sp"></div><button class="link" id="hicancel">Cancel</button>${btn || ""}</div></div>`;
    const ready = R.year === I.year && (R.doneAt || 0) >= I.askedAt;
    if (!ready) return head + `<div class="muted" style="margin:14px 0">Reading Google's Pakistan holidays for ${I.year}…</div>` + foot();
    if (R.error) return head + `<div class="err" style="margin:12px 0">Couldn't read Google's calendar: ${esc(R.error)}</div><button class="btnt" data-himpy="${I.year}">Try again</button>` + foot();
    const have = new Set(hdates().map((h) => h.d)), found = (R.found || []).filter((h) => h.d && h.name);
    if (!found.length) return head + `<div class="muted" style="margin:14px 0">Google has no Pakistan holidays for ${I.year} yet. Try again later, or add them by hand.</div>` + foot();
    if (!I.picks) { I.picks = {}; found.forEach((h) => (I.picks[h.d + h.name] = !h.optional && !have.has(h.d))); }
    const n = found.filter((h) => I.picks[h.d + h.name] && !have.has(h.d)).length;
    const row = (h) => { const k = h.d + h.name, d = I.moved[k] || h.d, isIn = have.has(h.d) && !I.moved[k];
      return `<label class="hz-p ${I.picks[k] && !isIn ? "" : "off"}"><input type="checkbox" data-hpick="${esc(k)}" ${I.picks[k] && !isIn ? "checked" : ""} ${isIn ? "disabled" : ""} aria-label="${esc(h.name)}"><b>${esc(hfmt(d))}</b><span class="n"><b>${esc(h.name)}</b>${isIn ? '<span class="hz-tag ok">Already added</span>' : ""}${h.moon ? '<span class="hz-tag moon">🌙 Moon date</span>' : ""}${h.optional ? '<span class="hz-tag opt">Optional</span>' : ""}${offTag(d)}</span>${isIn ? "<span></span>" : `<button class="ed" data-hmove="${esc(k)}" title="Change date" aria-label="Change date of ${esc(h.name)}">✎</button>`}${I.editing === k ? `<input type="date" data-hdate="${esc(k)}" value="${esc(d)}">` : ""}</label>`; };
    return head + (found.some((h) => h.moon) ? '<div class="hz-moonnote">🌙 Eid, Ashura and Eid Milad follow the moon. Google\'s date is a best guess, so you\'ll be asked to confirm 3 days before.</div>' : "")
      + `<div class="hz-tk"><button data-hall="1">Tick all</button><button data-hall="0">Untick all</button><span>${n} of ${found.length} ticked</span></div>${found.map(row).join("")}<div class="sub" style="margin-top:6px">✎ changes a date before adding. You can also change it later with Edit.</div>`
      + foot(`<button class="btnp" id="hiadd" ${n ? "" : "disabled"}>Add ${n} holiday${n === 1 ? "" : "s"}</button>`);
  }
  function holBind(root, side) {
    const own = RB.isOwner(); if (!own) return;
    root.querySelectorAll("[data-himp]").forEach((b) => (b.onclick = () => openImport(Number(b.dataset.himp))));
    const ha = root.querySelector("#hadd");
    if (ha) ha.onclick = () => {
      const d = root.querySelector("#hdate").value, name = root.querySelector("#hname").value.trim();
      if (!d) return RB.alertBox("Pick a date first.");
      hsave(hdates().filter((h) => h.d !== d).concat([{ d, name }]), "Holiday added: " + d + (name ? " (" + name + ")" : ""));
    };
    root.querySelectorAll("[data-hdel]").forEach((b) => (b.onclick = () => { const d = b.dataset.hdel; hsave(hdates().filter((h) => h.d !== d), "Holiday removed: " + d); }));
    root.querySelectorAll("[data-hedit]").forEach((b) => (b.onclick = () => {
      const h = hdates().find((x) => x.d === b.dataset.hedit); if (!h) return;
      RB.form("Edit holiday", [{ k: "d", label: "DATE", type: "date", value: h.d }, { k: "name", label: "NAME", value: h.name || "" }], "Save", async (v) => {
        if (!v.d) return "Pick a date.";
        if (v.d !== h.d && hdates().some((x) => x.d === v.d)) return "That date is already a holiday.";
        // A moon date you set yourself counts as confirmed.
        await hsave(hdates().filter((x) => x.d !== h.d).concat([Object.assign({}, h, { d: v.d, name: v.name.trim() }, h.moon && v.d !== h.d ? { ok: true } : {})]), "Holiday changed: " + h.d + " " + (h.name || "") + " → " + v.d + " " + v.name.trim());
      });
    }));
    root.querySelectorAll("[data-hmoon]").forEach((b) => (b.onclick = () => {
      const [i, act] = b.dataset.hmoon.split(":"), g = RB.moonDue()[Number(i)]; if (!g) return;
      const ds = g.dates.map((h) => h.d), rest = hdates().filter((h) => !ds.includes(h.d));
      let mine = g.dates.map((h) => Object.assign({}, h, { ok: true }));
      if (act === "1" || act === "-1") mine = mine.map((h) => Object.assign(h, { d: plus(h.d, Number(act)) }));
      if (act === "add") { const last = ds[ds.length - 1]; mine.push({ d: plus(last, 1), name: g.base + " (extra day)", moon: true, ok: true }); }
      const clash = mine.find((h) => rest.some((x) => x.d === h.d));
      if (clash) return RB.alertBox(esc(hfmt(clash.d)) + " is already a holiday. Use Edit on the row instead.");
      hsave(rest.concat(mine), "Moon date confirmed: " + g.base + " · " + mine.map((h) => h.d).join(", "));
    }));
    const P = side && side.querySelector(".hzpanel"), I = S.hImp; if (!P || !I) return;
    P.querySelector("#hicancel").onclick = () => { S.hImp = null; RB.render(); };
    P.querySelectorAll("[data-himpy]").forEach((b) => (b.onclick = () => openImport(Number(b.dataset.himpy))));
    P.querySelectorAll("[data-hpick]").forEach((c) => (c.onchange = () => { I.picks[c.dataset.hpick] = c.checked; RB.render(); }));
    P.querySelectorAll("[data-hall]").forEach((b) => (b.onclick = () => { Object.keys(I.picks).forEach((k) => (I.picks[k] = b.dataset.hall === "1")); RB.render(); }));
    P.querySelectorAll("[data-hmove]").forEach((b) => (b.onclick = (e) => { e.preventDefault(); I.editing = I.editing === b.dataset.hmove ? "" : b.dataset.hmove; RB.render(); }));
    P.querySelectorAll("[data-hdate]").forEach((c) => (c.onchange = () => { if (c.value) I.moved[c.dataset.hdate] = c.value; I.editing = ""; RB.render(); }));
    const add = P.querySelector("#hiadd");
    if (add) add.onclick = () => {
      const have = new Set(hdates().map((h) => h.d)), cur = hdates(), pick = [];
      ((D.holimport || {}).found || []).forEach((h) => { const k = h.d + h.name, d = I.moved[k] || h.d; if (I.picks[k] && !(have.has(h.d) && !I.moved[k]) && !cur.some((x) => x.d === d) && !pick.some((x) => x.d === d)) pick.push(Object.assign({ d, name: h.name }, h.moon ? { moon: true } : {})); });
      if (!pick.length) return;
      S.hImp = null;
      hsave(cur.concat(pick), "Holidays imported for " + I.year + ": " + pick.map((h) => h.d + " " + h.name).join(", "));
    };
  }
  if (RB.DEMO) RB.demoHolImport = (q) => setTimeout(() => {
    const y = q.year, f = [["02-05", "Kashmir Solidarity Day"], ["03-10", "Eid al-Fitr"], ["03-11", "Eid al-Fitr Holiday"], ["03-12", "Eid al-Fitr Holiday"], ["03-23", "Pakistan Day"], ["03-28", "Easter Sunday", 1], ["05-01", "Labour Day"], ["05-17", "Eid al-Adha"], ["05-18", "Eid al-Adha Holiday"], ["05-28", "Youm-e-Takbeer"], ["06-14", "Ashura (9 Muharram)"], ["06-15", "Ashura (10 Muharram)"], ["08-14", "Independence Day"], ["08-15", "Eid Milad un-Nabi"], ["10-29", "Diwali", 1], ["11-09", "Iqbal Day"], ["12-25", "Quaid-e-Azam Day / Christmas"]];
    D.holimport = Object.assign({}, q, { found: f.map(([md, name, opt]) => ({ d: y + "-" + md, name, moon: /eid|ashura|milad/i.test(name), optional: !!opt })), error: "", doneAt: Date.now() });
    RB.render();
  }, 700);
  // Left menu (J1): [group, [[tab, label, icon, one line shown under the big name]]]. Only tabs this person may open show.
  const GROUPS = [
    ["Team work", [["reasons", "Late reasons", "late", "The reasons staff pick on the phone when a task is late. The phones get this list the next time the app opens.<span class=\"phtag\">📱 Used on phones: the late reason list on a task</span>"], ["red", "Red lines", "red", "When a number on the Meetings screens turns red."], ["ranking", "Ranking", "rank", "One fair score and rank for the team."], ["perf", "Team Performance", "chart", "These numbers decide how Team Performance counts and colours. Changes show for everyone at once."]]],
    ["Meetings and reading", [["steps", "Meeting steps", "steps", "The steps of the daily and weekly meeting, in order, with the minutes for each."], ["docs", "Reading deadline", "read", "How many days new people get to finish their reading list."]]],
    ["Office", [["holidays", "Holidays", "cal", "Public holidays and other days the office is closed. Due times in every SOP and dossier days skip these dates. Weekly days off are set per timetable, in Team › Timetables.<span class=\"phtag\">📱 Used on phones: due times, reminders and dossier days skip these dates</span>"]]],
    ["Help", [["rules", "Rules", "rules", "How every rule of the app and the website works, in plain words. Read only. Updated with every new version."], ["log", "Change log", "log", "Every access, chart, seat and offboarding change. Only owners see it and nobody can edit or delete a line."], ["updates", "Updates", "upd", "A new version is published after every change. When one is ready, a bar at the bottom of the screen shows <b>Update</b> and stays until you press it."]]],
    ["My view", [["look", "Appearance", "look", "Your own choice. It follows you to any laptop you sign in on. “Same as device” follows your computer's light or dark setting."]]],
  ];
  /** 28 Nov (B3) Only the owner sees it: his own phone's settings follow the website like the team's. */
  const ownLock = () => {
    const me = RB.isOwner() && RB.seatOfUid(RB.uidOf());
    if (!me) return "";
    const on = me.ownerLock === true;
    return `<div class="ownlock"><div class="w"><b>🔒 Lock my phone like the team<button class="accinfo" data-info="On: your own phone's settings follow what you set for yourself here (Settings › Phone app settings, your name picked), and the 🔒 Locked rows are read only on your phone, exactly like the team's. Off: your phone keeps its own settings." aria-label="What this does">i</button></b><span>Only you see this</span></div><div class="sw ${on ? "on" : ""}" id="ownlock" role="switch" aria-checked="${on}" aria-label="Lock my phone like the team" tabindex="0"></div></div>`;
  };
  RB.ownLockBind = () => {
    const b = document.getElementById("ownlock"); if (!b) return;
    const flip = async () => {
      const me = RB.seatOfUid(RB.uidOf()); if (!me || b.dataset.busy) return; b.dataset.busy = 1;
      const v = me.ownerLock !== true; me.ownerLock = v; b.classList.toggle("on", v); b.setAttribute("aria-checked", v);
      try { await RB.Store.put("seats", me.id, { ownerLock: v }, true); await RB.log("Lock my phone like the team " + (v ? "ON" : "OFF")); await RB.sync(); } finally { delete b.dataset.busy; RB.render(); }
    };
    b.onclick = flip; b.onkeydown = (ev) => { if (ev.key === " " || ev.key === "Enter") { ev.preventDefault(); flip(); } };
    const i = b.parentElement.querySelector("[data-info]"); if (i) i.onclick = (ev) => { ev.stopPropagation(); RB.infoPop(i); };
  };
  RB.sections.settings = {
    navTop: ownLock,
    // 28 Nov (SETTINGS-REGROUP) Phone app settings (named like the phone's Settings) and Permissions sit first, owners only.
    // 28 Nov (B5, his idea) Left menu has one "Phone app settings" and one "Permissions"; their pages sit in a second menu beside the page.
    groups: (tabs) => [["Phone and access", [[S.phLast || "ph_" + RB.PHONE[0].id, "Phone app settings", "phone", ""], [S.permLast || "acc_team", "Permissions", "people", ""]]]].concat(GROUPS).map(([g, it]) => [g, it.filter((x) => tabs.some((t) => t[0] === x[0])).map(([k, l, ic, d]) => [k, l, RB.lineIcon(ic), d])]).filter((g) => g[1].length),
    tabs: () => { const ok = RB.settingsTabs(); return (RB.isOwner() ? RB.PHONE.map((p) => ["ph_" + p.id, p.title]).concat(RB.access.menu().map((x) => [x[0], x[1]])) : []).concat(TABS.filter((t) => ok.includes(t[0])).concat([["holidays", "Holidays"]]).concat(RB.mySwitch("seeRules") ? [["rules", "Rules"]] : []).concat(RB.isOwner() ? [["log", "Change log"]] : []).concat([["updates", "Updates"], ["look", "Appearance"]])); },
    render(tab) {
      let html, side = "";
      if (tab.startsWith("ph_") || tab.startsWith("acc_")) {
        if (tab.startsWith("ph_")) S.phLast = tab; else S.permLast = tab;
        const p = tab.startsWith("ph_") ? RB.access.phone(tab.slice(3)) : RB.access.page(tab.slice(4));
        return { html: p.html, side: p.side, sideCards: p.sideCards, title: "Settings", sub: "Changes reach the phones in a few seconds · owners only" };
      }
      if (tab === "look") {
        const cur = RB.theme.get(), card = (k, n, bg, cd) => `<button class="thm ${cur === k ? "on" : ""}" data-theme-pick="${k}"><div class="thp" style="background:${bg}"><i style="background:${cd}"></i><i style="background:${cd};width:85%"></i></div><b>${n}${cur === k ? " ✓" : ""}</b></button>`;
        html = `<div class="card st-card">
          <div class="thms">${card("light", "Light", "#F4F4F2", "#fff")}${card("dark", "Dark", "#17181B", "#2E3035")}${card("auto", "Same as device", "linear-gradient(90deg,#F4F4F2 50%,#17181B 50%)", "#8f9198")}</div></div>`;
      } else if (tab === "updates") {
        const vi = RB.versionInfo ? RB.versionInfo() : {};
        html = `<div class="card st-card">
          ${RB.stField("This page is running", "The version of this website open in your browser. Check for updates looks for a newer one.", `<b>${esc(vi.version || "—")}</b>${vi.at ? `<span>published ${esc(RB.fmtWhen(Date.parse(vi.at)))}</span>` : ""}`)}
          <div style="display:flex;align-items:center;gap:14px;margin-top:12px"><button class="btnp" id="updchk">Check for updates</button><span class="sub" id="updres"></span></div></div>`;
      } else if (tab === "holidays") {
        const r = holidays();
        html = r.html; side = r.side;
      } else if (tab === "log") {
        const q = (S.q || "").toLowerCase();
        const list = D.changelog.filter((c) => !q || c.text.toLowerCase().includes(q) || (c.byName || "").toLowerCase().includes(q));
        html = `<div class="card st-card"><div class="subhead"><h3>${list.length} ${list.length === 1 ? "change" : "changes"}</h3></div>
          ${list.map((c) => `<div class="tlr"><span class="t" style="width:120px">${esc(RB.fmtWhen(c.at))}</span><div><b>${esc(c.text)}</b><p>by ${esc(c.byName || "")}</p></div></div>`).join("") || '<div class="muted">Nothing logged yet.</div>'}</div>`;
      } else if (tab === "docs") {
        const days = (D.cfg && D.cfg.readingDays) || 7;
        html = `<div class="card st-card">${RB.stField("New people finish their reading in", "Counted from the day he starts in the seat. The phone reminds him before it.", `<input class="num" type="number" min="1" max="60" id="rdays" data-st="rdays" value="${days}" aria-label="Days"><span>days</span>`)}<button class="edit-only st-hidden" id="rsave" data-st-save="1">Save</button></div>`;
      } else if (tab === "perf") html = perfHtml();
      else if (tab === "rules") html = RB.rulesHtml();
      else { S.settingsTab = tab; html = RB.meet.html("settings"); }
      if (["look", "updates", "docs"].includes(tab)) html = `<div class="stcols one">${html}</div>`;
      return { html: html + SAVEBAR, side, title: "Settings", sub: "Only people with access can change these" };
    },
    bind(root, side) {
      if (S.tab.settings && (S.tab.settings.startsWith("acc_") || S.tab.settings.startsWith("ph_"))) return RB.access.bind(root, side);
      root.querySelectorAll("[data-info]").forEach((b) => (b.onclick = (ev) => { ev.stopPropagation(); RB.infoPop(b); }));
      root.querySelectorAll("[data-theme-pick]").forEach((b) => (b.onclick = () => { RB.theme.set(b.dataset.themePick); RB.render(); }));
      const sv = root.querySelector("#rsave");
      if (sv) sv.onclick = async () => { const n = Math.max(1, Math.min(60, Number(root.querySelector("#rdays").value) || 7)); await RB.Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { readingDays: n })); await RB.log("Reading deadline set to " + n + " days"); RB.stDone(); };
      const uc = root.querySelector("#updchk");
      if (uc) uc.onclick = async () => {
        const out = root.querySelector("#updres"); uc.disabled = true; out.textContent = "Checking…";
        const r = RB.checkUpdate ? await RB.checkUpdate() : "offline";
        uc.disabled = false;
        out.textContent = r === "new" ? "A new version is ready. Press Update in the bar at the bottom." : r === "same" ? "You have the latest version." : "Couldn't check. Are you online?";
      };
      if (S.tab.settings === "holidays") holBind(root, side);
      const ps = root.querySelector("#tpsave");
      if (ps) ps.onclick = async () => {
        const v = (id, lo, hi) => Math.max(lo, Math.min(hi, Math.round(Number(root.querySelector(id).value)) || 0));
        const show = RB.tp.cfg().show;
        const perf = { wOn: v("#tpwon", 0, 100), wDos: v("#tpwdos", 0, 100), grace: v("#tpgrace", 1, 60), older: v("#tpolder", 1, 60), movedN: v("#tpmoved", 1, 20), redBelow: v("#tpred", 0, 100), weakDay: v("#tpweak", 0, 100),
          weeks: v("#tpweeks", 4, 26), oldOverN: v("#tpoldn", 1, 20), needsN: v("#tpneedn", 1, 20), period: root.querySelector("#tpperiod").value, show,
          hidden: [...root.querySelectorAll("[data-tphide]")].filter((b) => b.checked).map((b) => b.dataset.tphide) };
        if (perf.wOn + perf.wDos === 0) return RB.alertBox("At least one weight must be more than 0.");
        await RB.Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { perf }));
        await RB.log("Team Performance settings changed");
        RB.stDone();
      };
      // Show/hide switches and Hide people ticks save at once (J3), next to the numbers as last saved.
      const tpQuick = async (perf, note) => { await RB.Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { perf: Object.assign({}, RB.tp.cfg(), perf) })); await RB.log(note); RB.render(); RB.stSaved(); };
      root.querySelectorAll("[data-tpshow]").forEach((b) => (b.onclick = () => {
        const [k, i] = b.dataset.tpshow.split(":"), show = JSON.parse(JSON.stringify(RB.tp.cfg().show)); show[k][Number(i)] = !show[k][Number(i)];
        b.classList.toggle("on", show[k][Number(i)]); b.setAttribute("aria-checked", show[k][Number(i)]);
        tpQuick({ show }, "Team Performance: show or hide changed");
      }));
      root.querySelectorAll("[data-tphide]").forEach((c) => (c.onchange = () => { c.closest("label").classList.toggle("on", c.checked); tpQuick({ hidden: [...root.querySelectorAll("[data-tphide]")].filter((x) => x.checked).map((x) => x.dataset.tphide) }, "Team Performance: hidden people changed"); }));
      if (S.tab.settings === "rules") RB.rulesBind(root);
      if (!root.classList.contains("ro")) draftBind(root, S.tab.settings);
      if (!["log", "docs", "updates", "look", "holidays", "perf", "rules"].includes(S.tab.settings)) RB.meet.bind(root);
    },
  };
})();
