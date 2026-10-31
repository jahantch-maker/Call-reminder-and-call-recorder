/* App view (Overview · Person · Calendar · Late reasons) and Meetings pages, drawn in the pastel look. */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, C = RB.calc, esc = RB.esc, first = RB.first;
  const { fmtDay, fmtWhen, fmtShort } = RB;
  const DAY = 86400000;
  Object.assign(S, { selUid: null, psub: "tasks", calWeek: 0, calPerson: "all", sort: S.sort || "az", period: S.period || "week" });

  const pastel = (i) => RB.colorOf(i);
  const seatTag = (m) => { const s = RB.seatOfUid(m.uid); const d = s ? s.designation : (m.designation || ""); return d ? `<span class="ty" style="background:${RB.desigColor(d)}">${esc(d)}</span>` : '<span class="ty" style="background:#EFEFEC">Member</span>'; };
  const headName = (m) => { const s = RB.seatOfUid(m.uid), h = RB.headOf(s); return h && h.status === "filled" ? h.name : s && s.role === "owner" ? "" : h ? "" : ""; };
  const periodSeg = () => `<div class="seg"><button data-period="week" class="${S.period === "week" ? "on" : ""}">Week</button><button data-period="month" class="${S.period === "month" ? "on" : ""}">Month</button></div>`;
  const nextDeadline = (all, uid, now) => { const open = all.filter((x) => x.uid === uid && !x.done && x.due >= now - 3600000).sort((a, b) => a.due - b.due)[0]; return open ? open.due : 0; };
  const matches = (m) => !S.q || (m.name + " " + ((RB.seatOfUid(m.uid) || {}).designation || "")).toLowerCase().includes(S.q.toLowerCase());

  function statusOf(f, R) {
    if (f.overdue) return ["red", f.overdue + " overdue"];
    if (f.moved >= R.moved) return ["org", "Moved a lot"];
    if (f.noDate) return ["org", f.noDate + " no date"];
    if (f.late) return ["org", f.late + " late"];
    return ["grn", "On track"];
  }

  // ------------------------------------------------------------------ overview
  function overview() {
    const now = Date.now(), all = C.items(), [a, b] = C.range(S.period, now), R = C.red();
    const ppl = C.people().filter(matches);
    const f = C.facts(all, null, a, b, now);
    const wait = RB.waiting().length, canApprove = RB.canEdit("team");
    const rows = ppl.map((p) => ({ p, f: C.facts(all, p.uid, a, b, now) }));
    const key = { overdue: "overdue", late: "late", moved: "moved", nodate: "noDate" }[S.sort];
    if (key) rows.sort((x, y) => y.f[key] - x.f[key] || first(x.p.name).localeCompare(first(y.p.name)));
    const sel = S.selUid && ppl.find((p) => p.uid === S.selUid) ? S.selUid : (ppl[0] && ppl[0].uid);
    S.selUid = sel;
    const chips = [["az", "A–Z"], ["overdue", "Overdue"], ["late", "Late"], ["moved", "Moved"], ["nodate", "No date"]].map(([k, l]) => `<button class="sc ${S.sort === k ? "on" : ""}" data-sort="${k}">${l}</button>`).join("");
    const html = `<div class="kp">
      <div class="k" style="background:var(--lil)"><div class="l">Team on time</div><div class="n">${f.pct == null ? "–" : f.pct + "%"}</div><button class="ck" data-kpi="reasons">Check</button></div>
      <div class="k" style="background:var(--blu)"><div class="l">Tasks due this ${S.period}</div><div class="n">${f.due}</div><button class="ck" data-kpi="due">Check</button></div>
      <div class="k" style="background:var(--yel)"><div class="l">Still overdue</div><div class="n">${f.overdue}</div><button class="ck" data-kpi="overdue">Check</button></div>
      ${canApprove ? `<div class="k" style="background:var(--peach)"><div class="l">Waiting to join</div><div class="n">${wait}</div><button class="ck" data-kpi="waiting">Approve</button></div>` : `<div class="k" style="background:var(--peach)"><div class="l">No date</div><div class="n">${f.noDate}</div><button class="ck" data-kpi="nodate">Check</button></div>`}</div>
      <div class="card"><div class="subhead" style="margin-bottom:6px"><h3>Team this ${S.period}</h3><div class="sp"></div>${chips}${periodSeg()}</div>
      <div class="tablewrap"><table id="ptable"><tr><th>Person</th><th>Seat</th><th class="c">Due</th><th class="c">On time</th><th class="c">Moved</th><th>Status</th><th>Next deadline</th></tr>
      ${rows.map(({ p, f }) => { const st = statusOf(f, R), nd = nextDeadline(all, p.uid, now); return `<tr class="click ${p.uid === sel ? "sel" : ""}" data-pick="${p.uid}"><td><b>${esc(first(p.name))}</b>${p.uid === (D.team && D.team.ownerUid) ? ' <span class="sub">· you</span>' : ""}</td><td>${seatTag(p)}</td><td class="c">${f.due}</td><td class="c">${f.onTime}</td><td class="c">${f.moved}×</td><td class="st" style="color:var(--${st[0]})"><i class="dot" style="background:var(--${st[0]})"></i>${st[1]}</td><td>${nd ? fmtWhen(nd).replace(/^(\w+) (\d+ \w+), /, "$1 $2, ") : "–"}</td></tr>`; }).join("")}</table></div>
      ${rows.length ? "" : '<div class="muted" style="padding:12px">No people to show.</div>'}</div>`;
    return { html, side: sidePanel(sel, all, now) };
  }

  function sidePanel(uid, all, now) {
    const p = uid && RB.member(uid); if (!p) return "";
    const s = RB.seatOfUid(uid), h = RB.headOf(s);
    const sod = C.startOfDay(now);
    const today = all.filter((x) => x.uid === uid && ((x.due >= sod && x.due < sod + DAY) || (x.done && x.doneAt >= sod && x.doneAt < sod + DAY))).sort((x, y) => x.due - y.due).slice(0, 5);
    const day = D.days[uid] || {}, blocks = (day.dossier || []).slice(0, 3);
    const tm = (t) => new Date(t).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
    const lines = today.map((x) => `<div class="tlr"><span class="t">${x.due ? tm(x.due) : ""}</span><div><b>${esc(x.title)}</b><p style="${isRed(x, now) ? "color:var(--er)" : ""}">${x.done ? "✓ done " + tm(x.doneAt) : isRed(x, now) ? "Overdue " + Math.max(1, Math.round((sod - C.startOfDay(x.due)) / DAY)) + " days" + (C.lastReason(x) ? " · reason: " + esc(C.lastReason(x).r) : "") : x.src === "team" ? "From " + esc(first(x.from)) : "Promised by " + esc(first(p.name))}</p></div></div>`).join("");
    const bl = blocks.map((d) => `<div class="tlr"><span class="t">${esc((d.time || "").split("–")[0] || "")}</span><div><b>${esc(d.title)}</b><p>Dossier${d.doneAt ? " · ✓ done " + tm(d.doneAt) : ""}</p></div></div>`).join("");
    const wk = [];
    for (let i = 6; i >= 0; i--) { const d0 = sod - i * DAY; const its = all.filter((x) => x.uid === uid && x.promise >= d0 && x.promise < d0 + DAY && x.promise <= now); let c = "#DEDEDA"; if (its.length) { c = its.some((x) => C.isLate(x) || C.isOverdue(x, now)) ? "#F2A9A6" : its.some((x) => x.moves) ? "var(--yel)" : "#A7D9B5"; } wk.push(`<div><i style="background:${c}"></i>${new Date(d0).toLocaleDateString("en-US", { weekday: "narrow" })}</div>`); }
    const wa = (p.phone || "").replace(/\D/g, "");
    return `<div class="ph"><div class="pav on">${esc(first(p.name)[0])}</div><h2>${esc(first(p.name))}</h2><div class="s">${esc(s && s.designation || "")}${s && s.dept ? " · " + esc(s.dept) : ""}${h && h.status === "filled" ? " · reports to " + esc(h.name) : ""}</div>
      <div class="pb"><button style="background:var(--yel)" data-openp="${uid}" title="Open person">★ Open</button>${p.phone ? `<a style="background:var(--lil)" href="tel:${esc(p.phone)}" title="Call">✆</a>` : ""}${wa ? `<a style="background:var(--blu)" target="_blank" rel="noopener" href="https://wa.me/${wa}" title="WhatsApp">💬</a>` : ""}</div></div>
      <div class="sec"><h4>Today<span data-openp="${uid}" data-psub="calendar">Calendar ›</span></h4>${lines || bl ? lines + bl : '<div class="sub">Nothing today.</div>'}</div>
      <div class="sec"><h4>Last 7 days<span data-openp="${uid}" data-psub="timeline">Timeline ›</span></h4><div class="wk">${wk.join("")}</div></div>`;
  }
  const isRed = (x, now) => !x.done && x.promise && x.promise < now;

  // ------------------------------------------------------------------ person
  function person() {
    const ps = C.people();
    if (!S.person || !RB.member(S.person)) S.person = ps[0] && ps[0].uid;
    if (!S.person) return { html: '<div class="card">No people to show yet.</div>' };
    const p = RB.member(S.person), now = Date.now(), all = C.items(), mine = all.filter((x) => x.uid === S.person);
    const subs = [["day", "Day"], ["tasks", "Tasks"], ["calendar", "Calendar"], ["dossier", "Dossier"], ["reasons", "Late reasons"], ["timeline", "Timeline"]];
    const head = `<div class="subhead"><select id="pperson">${ps.map((x) => `<option value="${x.uid}" ${x.uid === S.person ? "selected" : ""}>${esc(first(x.name))}</option>`).join("")}</select><div class="seg" id="psub">${subs.map(([k, l]) => `<button data-psub2="${k}" class="${S.psub === k ? "on" : ""}">${l}</button>`).join("")}</div></div>`;
    let body = "";
    const sod = C.startOfDay(now);
    if (S.psub === "tasks") body = RB.meet.html("person");
    else if (S.psub === "day") {
      const day = D.days[S.person] || {};
      const t = mine.filter((x) => (x.due >= sod && x.due < sod + DAY) || (!x.done && x.promise && x.promise < sod + DAY) || (x.done && x.doneAt >= sod)).sort((a, b) => a.done - b.done || a.due - b.due);
      body = `<div class="cols"><div class="card"><h3>Tasks today</h3>${t.map((x) => `<div class="arow"><span>${x.done ? "🟢" : isRed(x, now) ? "🔴" : "⚪"}</span><span class="w">${esc(x.title)}<span>${x.src === "team" ? "From " + esc(first(x.from)) : "Own task"}</span></span><span class="d">${x.due ? fmtWhen(x.due) : "No date"}</span></div>`).join("") || '<div class="muted">Nothing today.</div>'}</div>
        <div class="card"><h3>Follow-ups and Dossier</h3>${(day.followUps || []).map((f) => `<div class="arow"><span>${f.done ? "🟢" : "⚪"}</span><span class="w">${esc(f.title)}<span>${esc(f.name || "")}</span></span></div>`).join("")}${(day.dossier || []).map((d) => `<div class="arow"><span>${d.doneAt ? "🟢" : "⚪"}</span><span class="w">${esc(d.title)}<span>Dossier · ${esc(d.time || "")}${d.reason ? " · " + esc(d.reason) : ""}</span></span></div>`).join("") || (!(day.followUps || []).length ? '<div class="muted">Nothing shared for today yet.</div>' : "")}<div class="foot">Private tasks are never shown.${day.updatedAt ? " Updated " + fmtWhen(day.updatedAt) : ""}</div></div></div>`;
    } else if (S.psub === "calendar") body = calendarGrid(mine, true);
    else if (S.psub === "dossier") {
      const bl = (D.dossier[S.person] || {}).blocks || [];
      const hm = (m) => { const h = Math.floor(m / 60), mm = m % 60; return ((h + 11) % 12 + 1) + ":" + String(mm).padStart(2, "0") + (h >= 12 ? " PM" : " AM"); };
      const dayNames = (mask) => mask >= 63 && mask !== 127 ? "Every day except Sunday" : mask === 127 ? "Every day" : ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].filter((_, i) => mask & (1 << i)).join(" ");
      body = `<div class="card"><h3>Dossier · fixed daily time blocks</h3>${bl.map((x) => `<div class="docrow"><div class="w"><b>${esc(x.title)}</b><span>${hm(x.minute)} – ${hm(x.endMinute)} · ${esc(dayNames(x.days))}${x.proof ? " · 📷 photo proof" : ""}${x.byName ? " · set by " + esc(first(x.byName)) : ""}</span></div></div>`).join("") || '<div class="muted">No Dossier blocks set.</div>'}<div class="foot">Dossier is set in the phone app by the owner or by his head.</div></div>`;
    } else if (S.psub === "reasons") {
      const rs = C.reasonCounts(all, S.person, now - 56 * DAY, now + DAY);
      body = `<div class="card"><h3>His late reasons · last 8 weeks</h3>${RB.meet.reasonBars(rs, 10)}<div class="foot">Red = own reasons · grey = outside reasons.</div></div>`;
    } else {
      const ev = []; mine.forEach((x) => { (x.events || []).forEach((e) => ev.push({ at: e.at, text: e.text, t: x.title })); (x.reasons || []).forEach((r) => ev.push({ at: r.at, text: "Why: " + r.r, t: x.title })); });
      ev.sort((a, b) => b.at - a.at);
      body = `<div class="card"><h3>Timeline · latest first</h3>${ev.slice(0, 60).map((e) => `<div class="tlr"><span class="t" style="width:90px">${fmtShort(e.at)}</span><div><b>${esc(e.t)}</b><p>${esc(e.text)}</p></div></div>`).join("") || '<div class="muted">Nothing yet.</div>'}<div class="foot">Call recordings stay on the phone.</div></div>`;
    }
    return { html: head + body };
  }

  // ------------------------------------------------------------------ calendar
  function calendarGrid(items, personMode) {
    const now = Date.now(), a = C.weekStart(now) + S.calWeek * 7 * DAY, sod = C.startOfDay(now);
    const days = Array.from({ length: 7 }, (_, i) => a + i * DAY);
    const cell = (d0) => items.filter((x) => x.due >= d0 && x.due < d0 + DAY).sort((x, y) => x.due - y.due).map((x) => `<div class="e ${x.done ? "done" : isRed(x, now) ? "late" : ""}" title="${esc(x.title)}">${new Date(x.due).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })} · ${esc(x.title)}${personMode ? "" : " · " + esc(first((RB.member(x.uid) || {}).name))}</div>`).join("");
    return `<div class="subhead"><button class="btnt" data-cw="-1">‹ Previous</button><b>${fmtShort(a)} – ${fmtShort(a + 6 * DAY)}</b><button class="btnt" data-cw="1">Next ›</button><button class="btnt" data-cw="0">Today</button></div>
      <div class="cal">${days.map((d0) => `<div class="d ${d0 === sod ? "today" : ""}"><h5>${new Date(d0).toLocaleDateString("en-GB", { weekday: "short", day: "numeric" })}</h5>${cell(d0) || '<div class="sub">–</div>'}</div>`).join("")}</div>`;
  }
  function calendar() {
    const ps = C.people(), all = C.items();
    const items = S.calPerson === "all" ? all.filter((x) => ps.some((p) => p.uid === x.uid)) : all.filter((x) => x.uid === S.calPerson);
    return { html: `<div class="subhead"><select id="calp"><option value="all">Everyone</option>${ps.map((p) => `<option value="${p.uid}" ${S.calPerson === p.uid ? "selected" : ""}>${esc(first(p.name))}</option>`).join("")}</select></div>` + calendarGrid(items, S.calPerson !== "all") };
  }

  // ------------------------------------------------------------------ section objects
  RB.sections.app = {
    render(tab) {
      if (tab === "people") return overview();
      if (tab === "person") return person();
      if (tab === "calendar") return calendar();
      return { html: RB.meet.html("reasons") };
    },
    bind(root, side) {
      const on = (r, sel, fn) => r && r.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e, ev); }));
      on(root, "[data-pick]", (e) => { S.selUid = e.dataset.pick; RB.render(); });
      on(root, "[data-kpi]", (e) => { const k = e.dataset.kpi; if (k === "reasons") RB.go("app", "reasons"); else if (k === "waiting") { S.tab.team = "waiting"; RB.go("team"); } else { S.sort = k === "due" ? "az" : k; RB.render(); } });
      on(root, "[data-cw]", (e) => { const v = Number(e.dataset.cw); S.calWeek = v === 0 ? 0 : S.calWeek + v; RB.render(); });
      on(root, "[data-psub2]", (e) => { S.psub = e.dataset.psub2; RB.render(); });
      on(side, "[data-openp]", (e) => { S.person = e.dataset.openp; S.psub = e.dataset.psub || "tasks"; RB.go("app", "person"); });
      const pp = root.querySelector("#pperson"); if (pp) pp.onchange = () => { S.person = pp.value; S.sel = null; RB.render(); };
      const cp = root.querySelector("#calp"); if (cp) cp.onchange = () => { S.calPerson = cp.value; RB.render(); };
    },
  };

  RB.sections.meetings = {
    render(view) {
      const owner = RB.isOwner(), view_ = RB.level("meetings") !== "edit" && !owner;
      let html;
      if (view_) {
        // Access "View": only the meetings he was in and the actions on him, no task details.
        const sd = D.meetings, st = D.tasks, me = RB.uidOf();
        D.meetings = sd.filter((m) => m.closedAt && m.attendance && m.attendance[me]);
        D.tasks = st.filter((t) => t.meeting && t.toUid === me);
        try { html = RB.meet.html(view === "minutes" ? "minutes" : "actions"); } finally { D.meetings = sd; D.tasks = st; }
      } else html = RB.meet.html(view);
      return { html, title: "Meetings", sub: "Daily and weekly meetings, actions and minutes" };
    },
  };
})();
