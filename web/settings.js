/* Settings: late-reason list, red lines, meeting steps, ranking, reading deadline, office holidays and the change log (owners). */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  /** The five tabs the owner can tick per person (RB.SET_TABS); Change log is for owners, Updates and Appearance for everyone. */
  const TABS = RB.SET_TABS;
  RB.sections.settings = {
    tabs: () => { const ok = RB.settingsTabs(); return TABS.filter((t) => ok.includes(t[0])).concat([["holidays", "Holidays"]]).concat(RB.isOwner() ? [["log", "Change log"]] : []).concat([["updates", "Updates"], ["look", "Appearance"]]); },
    render(tab) {
      let html;
      if (tab === "look") {
        const cur = RB.theme.get(), card = (k, n, bg, cd) => `<button class="thm ${cur === k ? "on" : ""}" data-theme-pick="${k}"><div class="thp" style="background:${bg}"><i style="background:${cd}"></i><i style="background:${cd};width:85%"></i></div><b>${n}${cur === k ? " ✓" : ""}</b></button>`;
        html = `<div class="card" style="max-width:640px"><h3>Appearance</h3><div class="hint">Your own choice. It follows you to any laptop you sign in on. “Same as device” follows your computer's light or dark setting.</div>
          <div class="thms">${card("light", "Light", "#F4F4F2", "#fff")}${card("dark", "Dark", "#17181B", "#2E3035")}${card("auto", "Same as device", "linear-gradient(90deg,#F4F4F2 50%,#17181B 50%)", "#8f9198")}</div></div>`;
      } else if (tab === "updates") {
        const vi = RB.versionInfo ? RB.versionInfo() : {};
        html = `<div class="card" style="max-width:560px"><h3>Website updates</h3><div class="hint">A new version is published after every change. When one is ready, a bar at the bottom of the screen shows <b>Update</b> and stays until you press it.</div>
          <div class="rrow"><span class="n">This page is running</span><b>${esc(vi.version || "—")}</b>${vi.at ? `<span class="sub" style="margin-left:8px">published ${esc(RB.fmtWhen(Date.parse(vi.at)))}</span>` : ""}</div>
          <div style="display:flex;align-items:center;gap:14px;margin-top:12px"><button class="btnp" id="updchk">Check for updates</button><span class="sub" id="updres"></span></div></div>`;
      } else if (tab === "holidays") {
        // Office holidays for the whole company. Every SOP (Plot files and any later one) counts its days from here.
        const H = D.holidays || {}, own = RB.isOwner();
        const list = (H.dates || []).slice().sort((a, b) => (a.d < b.d ? -1 : 1));
        const fmt = (d) => new Date(d + "T12:00:00").toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" });
        html = `<div class="card" style="max-width:640px"><h3>Holidays</h3><div class="hint">Public holidays and other days the office is closed. Due times in every SOP skip these dates. Weekly days off are set per timetable, in Team › Timetables.</div>
          ${list.map((h) => `<div class="rrow"><span class="n">${esc(fmt(h.d))}</span><b style="flex:1;min-width:0">${esc(h.name || "")}</b>${own ? `<button class="btn" data-hdel="${esc(h.d)}">Remove</button>` : ""}</div>`).join("") || '<div class="muted">No holidays added yet.</div>'}
          ${own ? `<div class="rrow" style="flex-wrap:wrap;gap:8px;margin-top:12px"><input type="date" id="hdate"><input id="hname" placeholder="Name, e.g. Eid ul Fitr" style="flex:1;min-width:160px"><button class="btnp" id="hadd">Add</button></div>` : '<div class="foot">Only the owner changes holidays.</div>'}</div>`;
      } else if (tab === "log") {
        const q = (S.q || "").toLowerCase();
        const list = D.changelog.filter((c) => !q || c.text.toLowerCase().includes(q) || (c.byName || "").toLowerCase().includes(q));
        html = `<div class="card" style="max-width:820px"><div class="subhead"><h3>Change log</h3></div><div class="hint">Every access, chart, seat and offboarding change. Only owners see it and nobody can edit or delete a line.</div>
          ${list.map((c) => `<div class="tlr"><span class="t" style="width:120px">${esc(RB.fmtWhen(c.at))}</span><div><b>${esc(c.text)}</b><p>by ${esc(c.byName || "")}</p></div></div>`).join("") || '<div class="muted">Nothing logged yet.</div>'}</div>`;
      } else if (tab === "docs") {
        const days = (D.cfg && D.cfg.readingDays) || 7;
        html = `<div class="card" style="max-width:560px"><h3>Reading list deadline</h3><div class="rrow"><span class="n">New people finish their reading in</span><input class="num" type="number" min="1" max="60" id="rdays" value="${days}"> days</div><button class="btnp edit-only" id="rsave" style="margin-top:12px">Save</button><div class="foot">Counted from the day he starts in the seat. The phone reminds him before it.</div></div>`;
      } else { S.settingsTab = tab; html = RB.meet.html("settings"); }
      return { html, title: "Settings", sub: "Only people with access can change these" };
    },
    bind(root) {
      root.querySelectorAll("[data-theme-pick]").forEach((b) => (b.onclick = () => { RB.theme.set(b.dataset.themePick); RB.render(); }));
      const sv = root.querySelector("#rsave");
      if (sv) sv.onclick = async () => { const n = Math.max(1, Math.min(60, Number(root.querySelector("#rdays").value) || 7)); await RB.Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { readingDays: n })); await RB.log("Reading deadline set to " + n + " days"); RB.alertBox("Saved."); };
      const uc = root.querySelector("#updchk");
      if (uc) uc.onclick = async () => {
        const out = root.querySelector("#updres"); uc.disabled = true; out.textContent = "Checking…";
        const r = RB.checkUpdate ? await RB.checkUpdate() : "offline";
        uc.disabled = false;
        out.textContent = r === "new" ? "A new version is ready. Press Update in the bar at the bottom." : r === "same" ? "You have the latest version." : "Couldn't check. Are you online?";
      };
      const hsave = (data, note) => RB.Store.saveConfig("holidays", Object.assign({ dates: [] }, D.holidays || {}, data)).then(() => RB.log(note)).then(() => RB.render());
      const ha = root.querySelector("#hadd");
      if (ha) ha.onclick = () => {
        const d = root.querySelector("#hdate").value, name = root.querySelector("#hname").value.trim();
        if (!d) return RB.alertBox("Pick a date first.");
        const dates = ((D.holidays && D.holidays.dates) || []).filter((h) => h.d !== d).concat([{ d, name }]);
        hsave({ dates }, "Holiday added: " + d + (name ? " (" + name + ")" : ""));
      };
      root.querySelectorAll("[data-hdel]").forEach((b) => (b.onclick = () => { const d = b.dataset.hdel; hsave({ dates: ((D.holidays && D.holidays.dates) || []).filter((h) => h.d !== d) }, "Holiday removed: " + d); }));
      if (!["log", "docs", "updates", "look", "holidays"].includes(S.tab.settings)) RB.meet.bind(root);
    },
  };
})();
