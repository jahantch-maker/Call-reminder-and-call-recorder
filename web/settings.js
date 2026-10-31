/* Settings: late-reason list, red lines, meeting steps, ranking, reading deadline and the change log (owners). */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, esc = RB.esc;
  const TABS = [["reasons", "Late reasons"], ["red", "Red lines"], ["steps", "Meeting steps"], ["ranking", "Ranking"], ["docs", "Documents"]];
  RB.sections.settings = {
    tabs: () => TABS.concat(RB.isOwner() ? [["log", "Change log"]] : []),
    render(tab) {
      let html;
      if (tab === "log") {
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
      const sv = root.querySelector("#rsave");
      if (sv) sv.onclick = async () => { const n = Math.max(1, Math.min(60, Number(root.querySelector("#rdays").value) || 7)); await RB.Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { readingDays: n })); await RB.log("Reading deadline set to " + n + " days"); RB.alertBox("Saved."); };
      if (S.tab.settings !== "log" && S.tab.settings !== "docs") RB.meet.bind(root);
    },
  };
})();
