/* Meetings module: the daily / weekly meeting, actions, minutes, the late-reason report and the meeting settings.
 * Views return HTML; the shell (shell.js) draws them and calls RB.meet.bind(root). Data lives in core.js / calc.js.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, Store = RB.Store, S = RB.S, C = RB.calc, DEMO = RB.DEMO;
  const { esc, first, fmtDay, fmtWhen, fmtShort, member } = RB;
  const { OWN_DEFAULT, OUT_DEFAULT, RED_DEFAULT, STEP_NAMES, STEPS_DEFAULT, reasonsCfg, outsideSet, red, stepsFor, people, team, items, isLate, isOverdue, lastReason, isOutside, daysLate, startOfDay, weekStart, range, facts, weeklyPct, reasonCounts } = C;
  const DAY = 86400000, GRACE = 60000;
  const $modal = document.getElementById("modal");
  let $app = document.getElementById("content");
  Object.assign(S, { view: "home", period: "week", sort: "az", person: null, pchip: "overdue", sel: null, settingsTab: "reasons", minutesId: null, reasonSel: null, tick: 0 }, S);
  let RUN = null; // running meeting: {id, type, steps, i, stepStart}
  const rangeLabel = (a, b) => (S.period === "month" ? "Last 30 days · " + fmtShort(a) + " – " + fmtShort(b - DAY) : "Last 7 work days · " + fmtShort(a) + " – " + fmtShort(b - DAY));
  const render = () => RB.render();

  // ------------------------------------------------------------------ rendering helpers
  function tabs() { return ""; }
  const periodSeg = () => `<div class="seg"><button data-period="week" class="${S.period === "week" ? "on" : ""}">7 days</button><button data-period="month" class="${S.period === "month" ? "on" : ""}">30 days</button></div>`;
  function header(title, sub, right) {
    return `<div class="subhead"><div><h3>${title}</h3><div class="s">${sub || ""}</div></div><div class="sp"></div>${right || ""}${periodSeg()}</div>`;
  }
  function kpis(all, a, b, now) {
    const f = facts(all, null, a, b, now);
    const tr = weeklyPct(all, null, now, 5); const prev = tr.slice(0, 4).filter((x) => x != null);
    const avg = prev.length ? Math.round(prev.reduce((p, q) => p + q, 0) / prev.length) : null;
    const diff = f.pct != null && avg != null ? f.pct - avg : null;
    return `<div class="kpis">
      <div class="kpi"><div class="n">${f.pct == null ? "–" : f.pct + "%"}</div><div class="l">Team on time</div><div class="d ${diff == null ? "grey" : diff >= 0 ? "green" : "red"}">${diff == null ? "no history yet" : (diff >= 0 ? "↑ " : "↓ ") + Math.abs(diff) + "% vs last 4 weeks"}</div></div>
      <div class="kpi"><div class="n">${f.due}</div><div class="l">Tasks due</div><div class="d grey">${f.done} done</div></div>
      <div class="kpi"><div class="n">${f.overdue}</div><div class="l">Still overdue</div><div class="d red">past date, not done</div></div>
      <div class="kpi"><div class="n">${f.noDate}</div><div class="l">No date</div><div class="d ${f.noDate ? "red" : "grey"}">tasks without a time</div></div></div>`;
  }
  function reasonBars(list, max) {
    if (!list.length) return `<div class="muted" style="font-size:13px">No late reasons yet.</div>`;
    const top = list[0][1].n || 1;
    return list.slice(0, max || 6).map(([k, v]) => `<div class="rs" data-reason="${esc(k)}" style="cursor:pointer"><span class="t">${esc(k)}</span><span class="b ${v.out ? "o" : ""}" style="width:${Math.max(8, Math.round((v.n / top) * 110))}px"></span><span class="v">${v.n}${v.out ? " · outside" : ""}</span></div>`).join("");
  }
  function openActions() {
    return D.tasks.filter((t) => t.meeting && t.status !== "cancelled").filter((t) => !(t.status === "done" || t.status === "checked")).sort((p, q) => (p.due || 0) - (q.due || 0));
  }
  function actionStatus(t, now) {
    const done = t.status === "done" || t.status === "checked";
    if (done) return { dot: "🟢", tag: `<span class="tg p">Done${t.doneAt && t.due && t.doneAt - t.due > GRACE ? " · late" : " · on time"}</span>` };
    if (t.due && t.due < now) return { dot: "🔴", tag: `<span class="tg a">${Math.max(1, Math.round((startOfDay(now) - startOfDay(t.due)) / DAY))} days late</span>` };
    return { dot: "⚪", tag: `<span class="tg">Due ${fmtDay(t.due)}</span>` };
  }
  function sideCards(all, a, b) {
    const acts = openActions().slice(0, 4), now = Date.now();
    return `<div class="side"><div class="card"><h3>Top late reasons</h3>${reasonBars(reasonCounts(all, null, a, b), 5)}<div class="foot">Tap a reason to see its tasks.</div></div>
      <div class="card"><h3>Open actions from meetings</h3>${acts.length ? acts.map((t) => `<div class="rs">${actionStatus(t, now).dot} ${esc(t.title)} · ${esc(first(t.toName))} · ${fmtDay(t.due)}</div>`).join("") : '<div class="muted" style="font-size:13px">None open.</div>'}</div></div>`;
  }

  // ------------------------------------------------------------------ views
  function vPeople(embedded) {
    const now = Date.now(), all = items(), [a, b] = range(S.period, now), R = red();
    const rows = people().map((p) => ({ p, f: facts(all, p.uid, a, b, now) }));
    const key = { overdue: "overdue", late: "late", moved: "moved", nodate: "noDate" }[S.sort];
    if (key) rows.sort((x, y) => y.f[key] - x.f[key] || first(x.p.name).localeCompare(first(y.p.name)));
    const cell = (v, lim, suffix) => `<td class="c ${!v ? "z" : lim != null && v >= lim ? "bad" : ""}">${v}${v && suffix ? suffix : ""}</td>`;
    const sortChips = [["az", "A–Z"], ["overdue", "Overdue"], ["late", "Late"], ["moved", "Moved"], ["nodate", "No date"]].map(([k, l]) => `<button class="sc ${S.sort === k ? "on" : ""}" data-sort="${k}">${l}</button>`).join("");
    const html = `${kpis(all, a, b, now)}<div class="card"><div class="chiprow"><span class="lb">SORT</span>${sortChips}</div><div class="tablewrap"><table>
      <tr><th>Name</th><th class="c">Due</th><th class="c">On time</th><th class="c">Late</th><th class="c">Late (outside)</th><th class="c">Still overdue</th><th class="c">Moved / extended</th><th class="c">No date</th><th class="c">Avg days late</th></tr>
      ${rows.map(({ p, f }) => `<tr class="click" data-person="${p.uid}"><td><span class="av">${esc(first(p.name)[0] || "?")}</span>${esc(first(p.name))}${p.uid === (D.team && D.team.ownerUid) ? ' <span class="muted">· you</span>' : ""}</td><td class="c">${f.due}</td><td class="c">${f.onTime}</td>${cell(f.late, R.late)}<td class="c ${f.lateOut ? "grey" : "z"}">${f.lateOut}</td>${cell(f.overdue, R.overdue)}${cell(f.moved, R.moved, "×")}${cell(f.noDate, R.noDate)}<td class="c ${!f.avgLate ? "z" : f.avgLate >= R.avgLate ? "bad" : ""}">${f.avgLate || "–"}</td></tr>`).join("")}
      </table></div>${rows.length ? "" : '<div class="muted" style="padding:12px">No active team members yet.</div>'}
      <div class="foot">Red = at or above the line set in Settings · Red lines. Tap a name to open that person. Ranking and score: off until you switch them on.</div></div>`;
    if (embedded) return html;
    return `${header("People", rangeLabel(a, b))}<div class="body"><div class="main">${html}</div>${sideCards(all, a, b)}</div>`;
  }

  function trendSvg(vals, w, h, team) {
    const pts = vals.map((v, i) => [Math.round(6 + (i * (w - 12)) / Math.max(1, vals.length - 1)), v == null ? null : Math.round(h - 14 - (v / 100) * (h - 24))]);
    const line = pts.filter((p) => p[1] != null).map((p) => p.join(",")).join(" ");
    const last = pts.filter((p) => p[1] != null).pop();
    const lv = vals.filter((v) => v != null).pop();
    const ty = team != null ? Math.round(h - 14 - (team / 100) * (h - 24)) : null;
    return `<svg width="100%" viewBox="0 0 ${w} ${h}">${ty != null ? `<line x1="0" y1="${ty}" x2="${w}" y2="${ty}" stroke="#3a3f4f" stroke-dasharray="3 3"/><text x="${w}" y="${ty - 4}" fill="#6f7588" font-size="9" text-anchor="end">team ${team}%</text>` : ""}
      ${line ? `<polyline fill="none" stroke="#8BA6FF" stroke-width="2.5" points="${line}"/>` : ""}${last ? `<circle cx="${last[0]}" cy="${last[1]}" r="4" fill="#8BA6FF"/><text x="${Math.min(w - 4, last[0])}" y="${Math.min(h - 2, last[1] + 16)}" fill="#DCE4FF" font-size="11" text-anchor="end">${lv}%</text>` : `<text x="${w / 2}" y="${h / 2}" fill="#6f7588" font-size="11" text-anchor="middle">No history yet</text>`}
      <text x="6" y="${h - 1}" fill="#6f7588" font-size="9">${vals.length} wk ago</text></svg>`;
  }

  function personItems(uidP) { return items().filter((x) => x.uid === uidP); }
  function itemChips(x, now) {
    const c = [];
    if (x.done) {
      if (!x.promise) c.push(`<span class="ch m">No date</span>`);
      else if (isLate(x)) c.push(`<span class="ch r">⏱ ${daysLate(x)} day${daysLate(x) > 1 ? "s" : ""} late${x.moves ? " · " + (x.src === "team" ? "extended " : "moved ") + x.moves + "×" : ""}</span>`);
      else c.push(`<span class="ch g">✅ On time${x.moves ? " · " + (x.src === "team" ? "extended " : "moved ") + x.moves + "×" : ""}</span>`);
    } else if (!x.promise) c.push(`<span class="ch m">No date</span>`);
    else if (x.promise < now) c.push(`<span class="ch r">⏱ ${Math.max(1, Math.round((startOfDay(now) - startOfDay(x.promise)) / DAY))} day(s) overdue${x.moves ? " · " + (x.src === "team" ? "extended " : "moved ") + x.moves + "×" : ""}</span>`);
    else c.push(`<span class="ch o">📅 ${fmtWhen(x.due || x.promise)}${x.moves ? " · moved " + x.moves + "×" : ""}</span>`);
    const r = lastReason(x); if (r) c.push(`<span class="ch m">Why: ${esc(r.r)}${r.o ? " (outside)" : ""}</span>`);
    c.push(x.src === "team" ? `<span class="ch b">From ${esc(first(x.from))}</span>` : `<span class="ch m">Own task</span>`);
    if (x.meeting) c.push(`<span class="ch b">🤝 Meeting</span>`);
    return c.join("");
  }
  function timelineOf(x) {
    const L = [];
    L.push(`<div>Created${x.src === "own" ? " by " + esc(first((member(x.uid) || {}).name)) : " · given by " + esc(first(x.from))}<span>${x.createdAt ? fmtWhen(x.createdAt) : ""}</span></div>`);
    if (x.promise) L.push(`<div>First date: ${fmtWhen(x.promise)}<span>this is the promise</span></div>`);
    const evs = (x.events || []).slice().sort((p, q) => p.at - q.at);
    evs.forEach((e) => { const t = e.text || ""; if (/moved|Deadline|extend|Time set|asks to extend|approved/i.test(t) && !/^Created|^Given/.test(t)) L.push(`<div class="mv">${esc(t)}<span>${fmtWhen(e.at)}</span></div>`); });
    (x.reasons || []).forEach((r) => L.push(`<div class="mv">Why: ${esc(r.r)}${r.n ? " · “" + esc(r.n) + "”" : ""}${r.o ? " (outside)" : ""}<span>${fmtWhen(r.at)}</span></div>`));
    const now = Date.now();
    if (x.done) L.push(`<div class="${isLate(x) ? "dn" : "ok"}">${isLate(x) ? "Done · " + daysLate(x) + " day(s) after the first date" : "Done on time"}<span>${fmtWhen(x.doneAt)}</span></div>`);
    else if (isOverdue(x, now)) L.push(`<div class="dn">Still not done · ${Math.max(1, Math.round((startOfDay(now) - startOfDay(x.promise)) / DAY))} day(s) past first date<span>today ${fmtDay(now)}</span></div>`);
    return `<div class="tl">${L.join("")}</div>`;
  }

  function vPerson() {
    const ps = people();
    if (!S.person || !member(S.person)) S.person = ps[0] && ps[0].uid;
    const now = Date.now(), all = items(), [a, b] = range(S.period, now);
    if (!S.person) return `${header("Person", "")}<div class="body"><div class="card">No active team members yet.</div></div>`;
    const p = member(S.person), f = facts(all, S.person, a, b, now), R = red();
    const idx = ps.findIndex((x) => x.uid === S.person);
    const prev = ps[(idx - 1 + ps.length) % ps.length], next = ps[(idx + 1) % ps.length];
    const mine = personItems(S.person);
    const groups = {
      overdue: mine.filter((x) => isOverdue(x, now)).sort((x, y) => x.promise - y.promise),
      late: mine.filter((x) => isLate(x)).sort((x, y) => y.doneAt - x.doneAt),
      moved: mine.filter((x) => x.moves > 0 && !x.done).sort((x, y) => y.moves - x.moves),
      nodate: mine.filter((x) => !x.done && !x.promise),
      done: mine.filter((x) => x.done && x.doneAt >= a).sort((x, y) => y.doneAt - x.doneAt),
      open: mine.filter((x) => !x.done && x.promise >= now).sort((x, y) => x.promise - y.promise),
    };
    const list = groups[S.pchip] || [];
    const sel = list.find((x) => x.src + x.id === S.sel) || list[0];
    const bad = (v, lim) => (v && lim != null && v >= lim ? "bad" : "");
    const trend = weeklyPct(all, S.person, now, 8), team = facts(all, null, a, b, now).pct;
    const reasons = reasonCounts(all, S.person, now - 56 * DAY, now + DAY);
    const acts = openActions().filter((t) => t.toUid === S.person);
    const chips = [["overdue", "Overdue"], ["late", "Late"], ["moved", "Moved"], ["nodate", "No date"], ["open", "Coming up"], ["done", "Done"]].map(([k, l]) => `<button class="sc ${S.pchip === k ? "on" : ""}" data-pchip="${k}">${l} ${groups[k].length}</button>`).join("");
    const who = `<div class="who"><div class="big">${esc(first(p.name)[0])}</div><div><h2>${esc(first(p.name))}</h2><div class="s">${rangeLabel(a, b)} · ${RUN ? '<a class="link" data-go="people" href="#">All people</a> · ' : ""}<a class="link" data-person="${prev.uid}" href="#">‹ ${esc(first(prev.name))}</a> · <a class="link" data-person="${next.uid}" href="#">${esc(first(next.name))} ›</a></div></div></div>`;
    return `<div class="subhead">${who}<div class="sp"></div>${periodSeg()}</div>
      <div class="body"><div style="width:290px;display:flex;flex-direction:column;gap:12px">
        <div class="card"><h3>${S.period === "month" ? "Last 30 days" : "Last 7 work days"}</h3><div class="mini"><div>Due<b>${f.due}</b></div><div>On time<b>${f.onTime}</b></div><div class="${bad(f.late, R.late)}">Late<b>${f.late}</b></div><div>Late (outside)<b style="color:#8a90a3">${f.lateOut}</b></div><div class="${bad(f.overdue, R.overdue)}">Still overdue<b>${f.overdue}</b></div><div class="${bad(f.moved, R.moved)}">Moved<b>${f.moved}×</b></div><div class="${bad(f.noDate, R.noDate)}">No date<b>${f.noDate}</b></div><div class="${bad(f.avgLate, R.avgLate)}">Avg days late<b>${f.avgLate || "–"}</b></div></div></div>
        <div class="card"><h3>On time · last 8 weeks</h3>${trendSvg(trend, 260, 96, team)}</div>
        <div class="card"><h3>His late reasons · 8 weeks</h3>${reasonBars(reasons, 6)}</div>
        <div class="card"><h3>His open actions</h3>${acts.length ? acts.map((t) => `<div class="rs">${actionStatus(t, now).dot} ${esc(t.title)} · ${fmtDay(t.due)}</div>`).join("") : '<div class="muted" style="font-size:13px">None.</div>'}
          <button class="btnt" style="margin-top:8px" data-action-person="${p.uid}">+ Action for ${esc(first(p.name))}</button></div>
      </div>
      <div class="card" style="flex:1;min-width:300px"><div class="chiprow">${chips}</div>
        ${list.length ? list.map((x) => `<div class="tcard ${sel && sel.src + sel.id === x.src + x.id ? "sel" : ""}" data-sel="${x.src + x.id}"><div class="tkc ${x.done ? "ok" : ""}"></div><div><div class="ttl">${esc(x.title)}</div><div class="chips">${itemChips(x, now)}</div></div></div>`).join("") : '<div class="muted">Nothing here.</div>'}
        <div class="foot">Read-only: tasks are changed only in the phone app.</div></div>
      <div class="card" style="width:300px">${sel ? `<h3>${esc(sel.title)}</h3><div class="foot" style="margin:0 0 12px">${sel.src === "own" ? "His own task · first date = his promise" : "Given by " + esc(first(sel.from)) + " · first deadline = the promise"}</div>${timelineOf(sel)}<button class="btnt" data-full="${sel.src + sel.id}">Full timeline</button>` : '<div class="muted">Tap a task to see its timeline.</div>'}</div>
      </div>`;
  }

  function vReasons(embedded) {
    const now = Date.now(), all = items(), [a, b] = range(S.period, now);
    const list = reasonCounts(all, null, a, b);
    const total = list.reduce((s, [, v]) => s + v.n, 0), other = (list.find(([k]) => k === "Other") || [0, { n: 0 }])[1].n;
    const selR = S.reasonSel && list.find(([k]) => k === S.reasonSel);
    const top = list[0] ? list[0][1].n : 1;
    const bars = list.length ? list.map(([k, v]) => `<div class="rs" data-reason="${esc(k)}" style="cursor:pointer;font-size:15px;margin-bottom:10px"><span class="t" style="width:200px">${esc(k)}</span><span class="b ${v.out ? "o" : ""}" style="height:16px;width:${Math.max(10, Math.round((v.n / top) * 380))}px"></span><span class="v">${v.n}${v.out ? " · outside" : ""} · ${Math.round((v.n / total) * 100)}%</span></div>`).join("") : '<div class="muted">No late reasons in this period yet. They arrive when people move a date, ask for more time or finish late.</div>';
    const detail = selR ? `<div class="card"><h3>${esc(selR[0])} · ${selR[1].n} times</h3>${selR[1].items.slice(0, 12).map((x) => `<div class="arow"><span class="w">${esc(x.title)}<span>${esc(first((member(x.uid) || {}).name))}</span></span></div>`).join("")}
      <button class="btnt" style="margin-top:10px" data-action-reason="${esc(selR[0])}">+ Action on this reason</button></div>` : `<div class="card"><h3>What to do here</h3><div class="muted" style="font-size:13px;line-height:1.5">Pick the top one or two reasons. Agree one fix for each, with one owner and one date, and press + Action. Outside reasons (grey) are still worth fixing: a late material order is often a process problem.</div></div>`;
    const html = `<div class="card">${bars}${other >= 3 ? `<div class="note" style="margin-top:8px">"Other" was picked ${other} times: consider adding a proper reason in Settings.</div>` : ""}<div class="foot">Red = their own reasons · grey = outside reasons (not counted against the person). Tap a reason.</div></div>`;
    if (embedded) return `<div class="body"><div class="main">${html}</div><div class="side">${detail}</div></div>`;
    return `${header("Late reasons", rangeLabel(a, b))}<div class="body"><div class="main">${html}</div><div class="side">${detail}</div></div>`;
  }

  function lastMeeting(type) { return D.meetings.find((m) => m.closedAt && (!type || m.type === type)); }
  function vActions(embedded) {
    const now = Date.now();
    const last = lastMeeting();
    const ids = new Set(last ? last.actions || [] : []);
    const fromLast = D.tasks.filter((t) => ids.has(t.id));
    const older = openActions().filter((t) => !ids.has(t.id));
    const row = (t) => { const st = actionStatus(t, now); return `<div class="arow"><span>${st.dot}</span><span class="w">${esc(t.title)}<span>${esc(t.meetingFrom || "From a meeting")}</span></span><span class="o">${esc(first(t.toName))}</span><span class="d">${st.tag}</span></div>`; };
    const html = `<div class="card"><h3>${last ? "From the meeting on " + fmtDay(last.startedAt) + " · " + fromLast.length + " actions" : "No closed meeting yet"}</h3>${fromLast.map(row).join("") || '<div class="muted">No actions from the last meeting.</div>'}
      <h3 style="margin-top:16px">Older actions still open</h3>${older.map(row).join("") || '<div class="muted">None.</div>'}
      <div class="foot">Late one? Ask why, then + Action or agree a new date in the phone app.</div></div>`;
    if (embedded) return html;
    return `${header("Actions", "Every action becomes a task in that person's phone, with the 🤝 Meeting label", '<button class="btnp" data-newaction="1">+ Action</button>')}<div class="body"><div class="main">${html}</div></div>`;
  }

  function minutesText(m) {
    const att = m.attendance || {}, names = (k) => Object.keys(att).filter((u) => att[u] === k).map((u) => first((member(u) || {}).name));
    const acts = (m.actions || []).map((id) => D.tasks.find((t) => t.id === id)).filter(Boolean);
    const L = ["Meeting " + fmtShort(m.startedAt)];
    const pres = names("present").length + names("late").length;
    L.push("Present " + pres + (names("absent").length ? " · Absent: " + names("absent").join(", ") : "") + (names("leave").length ? " · Leave: " + names("leave").join(", ") : ""));
    if ((m.decisions || []).length) { L.push("Decided:"); m.decisions.forEach((d) => L.push("- " + d.text)); }
    if (acts.length) { L.push("Actions:"); acts.forEach((t) => L.push("- " + first(t.toName) + ": " + t.title + ", by " + fmtDay(t.due))); }
    (m.notes || []).forEach((n) => L.push("Note: " + n.text));
    return L.join("\n");
  }
  function vMinutes(embedded) {
    const list = D.meetings;
    const m = (RUN && embedded && list.find((x) => x.id === RUN.id)) || list.find((x) => x.id === S.minutesId) || list[0];
    const q = (S.mq || "").toLowerCase();
    const shown = q ? list.filter((x) => minutesText(x).toLowerCase().includes(q)) : list;
    const left = embedded ? "" : `<div class="card mlist" style="width:230px"><input id="mq" placeholder="🔍 Search minutes" value="${esc(S.mq || "")}" style="width:100%;margin-bottom:8px">${shown.map((x) => `<button data-minutes="${x.id}" class="${m && x.id === m.id ? "on" : ""}">${fmtDay(x.startedAt)} · ${x.type}</button>`).join("") || '<div class="muted">No meetings yet.</div>'}</div>`;
    if (!m) return embedded ? '<div class="card">No meeting.</div>' : `${header("Minutes", "")}<div class="body">${left}</div>`;
    const locked = !!m.closedAt && !embedded;
    const acts = (m.actions || []).map((id) => D.tasks.find((t) => t.id === id)).filter(Boolean);
    const line = (d) => `<div class="ml">${esc(d.text)}${d.later ? ' <span class="grey" style="font-size:11px">(edited later)</span>' : ""}</div>`;
    const att = m.attendance || {}, cnt = (k) => Object.values(att).filter((v) => v === k).length;
    const mid = `<div class="card" style="flex:1;min-width:300px">
      <div class="mh" style="margin-top:0">ATTENDANCE</div><div style="font-size:13px">Present ${cnt("present") + cnt("late")}${cnt("late") ? " · late " + cnt("late") : ""}${cnt("absent") ? " · absent " + cnt("absent") : ""}${cnt("leave") ? " · on leave " + cnt("leave") : ""}</div>
      <div class="mh">DECISIONS</div>${(m.decisions || []).map(line).join("")}<div class="addline"><input id="addDec" placeholder="+ Add a decision"><button class="btnt" data-add="decisions">Add</button></div>
      <div class="mh">NOTES</div>${(m.notes || []).map(line).join("")}<div class="addline"><input id="addNote" placeholder="+ Add a note"><button class="btnt" data-add="notes">Add</button></div>
      <div class="mh">ACTIONS <span style="color:var(--onv);font-weight:500;letter-spacing:0">· filled in by themselves</span></div>${acts.map((t) => `<div class="ml">${esc(t.title)} · ${esc(first(t.toName))} · ${fmtDay(t.due)}<span class="lab">🤝 Meeting</span></div>`).join("") || '<div class="muted" style="font-size:13px">None.</div>'}
      ${locked ? '<div class="foot">Closed and locked · anything added now shows "edited later".</div>' : ""}</div>`;
    const wa = `<div class="wa"><div style="font-size:12px;color:#9fb6ae;margin-bottom:8px">Preview · WhatsApp</div><div class="wab">${esc(minutesText(m))}</div><button class="btnw" style="margin-top:10px;width:100%" data-share="${m.id}">Share on WhatsApp</button>${m.sharedAt ? `<div class="foot">Shared ${fmtWhen(m.sharedAt)}</div>` : ""}</div>`;
    S.minutesId = m.id;
    if (embedded) return `<div class="body">${mid}${wa}</div>`;
    return `${header("Minutes", fmtDay(m.startedAt) + " · " + m.type + " meeting")}<div class="body">${left}${mid}${wa}</div>`;
  }

  // ------------------------------------------------------------------ meeting run
  function vHome() {
    const now = Date.now(), last = lastMeeting();
    const tot = (t) => stepsFor(t).filter((s) => s[2]).reduce((p, s) => p + Number(s[1]), 0);
    const open = openActions().length;
    return `<div class="center" style="min-height:60vh"><div class="muted">${fmtWhen(now)}</div>
      <button class="bigbtn btnp" data-start="daily">▶ Start daily meeting · ${tot("daily")} min</button>
      <button class="bigbtn" style="background:var(--sv);color:var(--onprc)" data-start="weekly">▶ Start weekly meeting · ${tot("weekly")} min</button>
      <div class="muted" style="font-size:12.5px">${last ? "Last meeting: " + fmtDay(last.startedAt) + " · " + last.type + " · " : ""}${open} action${open === 1 ? "" : "s"} open</div>
      ${DEMO ? '<div class="note">Sample data: this is a preview, nothing is saved.</div>' : ""}</div>`;
  }
  function curMeeting() { return RUN && D.meetings.find((m) => m.id === RUN.id); }
  async function startMeeting(type) {
    const steps = stepsFor(type).filter((s) => s[2]);
    const att = {}; team().forEach((p) => (att[p.uid] = "present"));
    const m = { type, startedAt: Date.now(), closedAt: 0, plannedMin: steps.reduce((p, s) => p + Number(s[1]), 0), attendance: att, decisions: [], notes: [], actions: [], agreed: {}, sharedAt: 0 };
    const id = await Store.saveMeeting(m);
    if (DEMO) m.id = id;
    if (!D.meetings.find((x) => x.id === id)) D.meetings.unshift(Object.assign({ id }, m));
    RUN = { id, type, steps: steps.map((s) => ({ key: s[0], min: Number(s[1]) })), i: 0, stepStart: Date.now() };
    render();
  }
  async function saveCur(fn) { const m = curMeeting(); if (!m) return; fn(m); await Store.saveMeeting(m); render(); }
  const mmss = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };
  function stepBar() {
    const n = RUN.steps.length + 1, i = RUN.i; // + Close
    const st = RUN.steps[i];
    const el = Date.now() - RUN.stepStart, lim = st ? st.min * 60000 : (curMeeting() ? curMeeting().plannedMin * 60000 : 0);
    const total = Date.now() - (curMeeting() ? curMeeting().startedAt : Date.now());
    const shown = st ? el : total;
    return `<div class="stepbar"><b style="color:var(--on)">${RUN.type === "daily" ? "Daily" : "Weekly"} · step ${i + 1} of ${n} · ${st ? STEP_NAMES[st.key] : "Close"}</b><div class="dots">${Array.from({ length: n }, (_, k) => `<i class="${k < i ? "d" : k === i ? "c" : ""}"></i>`).join("")}</div><span class="clk ${shown > lim ? "over" : ""}" id="clk">⏱ ${mmss(shown)} / ${mmss(lim)}</span></div>`;
  }
  function pinBar() {
    return `<div class="pin"><button class="btnt" data-newaction="1">+ Action</button><button class="btnt" data-quick="decisions">+ Decision</button><button class="btnt" data-quick="notes">+ Note</button><div class="sp"></div>
      ${RUN.i > 0 ? '<button class="pill" data-step="-1">‹ Back</button>' : ""}${RUN.i < RUN.steps.length ? '<button class="btnp" data-step="1">Next ›</button>' : ""}</div>`;
  }
  function vRun() {
    const m = curMeeting(); if (!m) { RUN = null; return vHome(); }
    const st = RUN.steps[RUN.i], now = Date.now(), all = items(), [a, b] = range(RUN.type === "daily" ? "week" : S.period, now);
    let inner = "";
    const head = (t, s) => `<div class="subhead"><div><h3>${t}</h3><div class="s">${s || ""}</div></div><div class="sp"></div>${periodSeg()}</div>`;
    if (!st) {
      const used = Math.round((now - m.startedAt) / 60000), att = m.attendance || {};
      const pres = Object.values(att).filter((v) => v === "present" || v === "late").length;
      inner = `${head("Close meeting?")}<div class="center" style="min-height:50vh"><div class="kpis" style="max-width:520px;width:100%"><div class="kpi"><div class="n">${used} min</div><div class="l">of ${m.plannedMin} planned</div></div><div class="kpi"><div class="n">${pres} / ${Object.keys(att).length}</div><div class="l">present</div></div><div class="kpi"><div class="n">${(m.actions || []).length}</div><div class="l">new actions</div></div></div>
        ${m.sharedAt ? "" : '<div class="note">⚠ Minutes not shared on WhatsApp yet</div> <button class="btnw" data-share="' + m.id + '">Share on WhatsApp</button>'}
        <button class="bigbtn btnp" data-close="1">Close and lock minutes</button></div>`;
    } else if (st.key === "attendance") {
      const att = m.attendance || {}, lab = { present: ["p", "✓ Present"], late: ["l", "⏰ Late"], absent: ["a", "✕ Absent"], leave: ["", "🌴 On leave"] };
      inner = `${head("Who is here?", "Everyone starts Present · tap a name to change")}<div class="body"><div class="card" style="flex:1;max-width:640px">${team().map((p) => { const v = att[p.uid] || "present"; return `<div class="pr" data-att="${p.uid}" style="cursor:pointer"><span class="n">${esc(first(p.name))}</span><span class="tg ${lab[v][0]}">${lab[v][1]}</span></div>`; }).join("")}</div></div>`;
    } else if (st.key === "lastActions") inner = `${head("Last meeting's actions", "🟢 done · 🔴 late · ⚪ not due yet")}<div class="body"><div class="main">${vActions(true)}</div></div>`;
    else if (st.key === "trend") {
      const tr = weeklyPct(all, null, now, 8);
      inner = `${head("Team trend", "Better or worse than before?")}<div class="body"><div class="main">${kpis(all, a, b, now)}<div class="card"><h3>Team on time · last 8 weeks</h3>${trendSvg(tr, 700, 200, null)}</div></div></div>`;
    } else if (st.key === "reasons") inner = `${head("Top late reasons", "Pick the top one or two, agree one fix each: + Action")}${vReasons(true)}`;
    else if (st.key === "people") inner = S.view === "person" ? vPerson() : `${head("People", "Tap Overdue to see reds first · tap a name to open")}<div class="body"><div class="main">${vPeople(true)}</div></div>`;
    else if (st.key === "readback") {
      const acts = (m.actions || []).map((id) => D.tasks.find((t) => t.id === id)).filter(Boolean);
      inner = `${head("Today's actions · read-back", 'Each owner says "yes, mine" · tap when they do')}<div class="body"><div class="card" style="flex:1;max-width:820px">${acts.map((t) => `<div class="pr" data-agree="${t.id}" style="cursor:pointer"><span class="n">${esc(t.title)} · ${esc(first(t.toName))} · ${fmtDay(t.due)}</span><span class="tg ${m.agreed && m.agreed[t.id] ? "p" : ""}">${m.agreed && m.agreed[t.id] ? "✓ Agreed" : "Tap when agreed"}</span></div>`).join("") || '<div class="muted">No actions yet in this meeting. Use + Action below.</div>'}</div></div>`;
    } else if (st.key === "minutes") inner = `${head("Minutes · " + fmtDay(m.startedAt), RUN.type)}${vMinutes(true)}`;
    return `<div class="run">${stepBar()}${inner}<div style="flex:1"></div>${pinBar()}</div>`;
  }

  // ------------------------------------------------------------------ settings
  function vSettings(bare) {
    const tabsS = [["reasons", "Late reasons"], ["red", "Red lines"], ["steps", "Meeting steps"], ["ranking", "Ranking"]].map(([k, l]) => `<button data-stab="${k}" class="${S.settingsTab === k ? "on" : ""}">${l}</button>`).join("");
    let body = "";
    if (S.settingsTab === "reasons") {
      const rc = reasonsCfg(), used = {}; const now = Date.now();
      items().forEach((x) => (x.reasons || []).forEach((r) => { if (r.at > now - 28 * DAY) used[r.r] = (used[r.r] || 0) + 1; }));
      // Settings redesign (27 Nov): tinted rows, small grey buttons; on a phone the buttons show only their icon.
      const btn = (a, ic, l, n) => `<button class="st-b edit-only" data-rr="${a}" aria-label="${l} ${esc(n)}">${RB.lineIcon(ic)}<span>${l}</span></button>`;
      const col = (k, title) => `<div class="card st-card"><h4 class="st-h">${title}</h4>${rc[k].map((r, i) => `<div class="st-row ${k === "outside" ? "grey" : ""} ${r.retired ? "ret" : ""}"><span class="t">${esc(r.name)}</span><span class="u">${r.retired ? "Retired" : "Used " + (used[r.name] || 0)}</span>${r.retired ? btn(k + ":" + i + ":back", "undo", "Bring back", r.name) : btn(k + ":" + i + ":rename", "pen", "Rename", r.name) + btn(k + ":" + i + ":retire", "bin", "Retire", r.name)}</div>`).join("")}
        <div class="st-add edit-only"><input id="add_${k}" placeholder="New reason" aria-label="New reason"><button class="btnp" data-radd="${k}">+ Add</button></div></div>`;
      body = `<div class="st-2">${col("own", "Their own · <span>counted in the score</span>")}${col("outside", "Outside · <span>shown grey, not counted</span>")}</div><div class="st-note">Retired reasons leave the phone list but stay on old tasks, so past charts never change. "Other" is always last on the phone and asks for a short note.</div>`;
    } else if (S.settingsTab === "red") {
      const R = red(), row = (k, l) => RB.stField(l, "", `<input class="num" type="number" min="1" value="${R[k]}" data-red="${k}" data-st="red_${k}" aria-label="${l}">`);
      body = `<div class="card st-card"><h4 class="st-h">A number turns red at or above</h4>${row("overdue", "Still overdue")}${row("late", "Late")}${row("moved", "Moved / extended")}${row("noDate", "No date")}${row("avgLate", "Avg days late")}<button class="edit-only st-hidden" data-savered="1" data-st-save="1">Save</button></div>`;
    } else if (S.settingsTab === "steps") {
      const col = (type) => { const st = stepsFor(type); return `<div class="card st-card"><h4 class="st-h">${type === "daily" ? "Daily" : "Weekly"} · <span>${st.filter((s) => s[2]).reduce((p, s) => p + Number(s[1]), 0)} min</span></h4>${st.map((s, i) => `<div class="st-row ${s[2] ? "" : "grey"}"><span class="mv edit-only"><button class="st-b" data-mv="${type}:${i}:-1" aria-label="Move ${STEP_NAMES[s[0]]} up">↑</button><button class="st-b" data-mv="${type}:${i}:1" aria-label="Move ${STEP_NAMES[s[0]]} down">↓</button></span><span class="t">${STEP_NAMES[s[0]]}</span><input class="num" type="number" min="1" value="${s[1]}" data-min="${type}:${i}" data-st="min_${type}_${s[0]}" aria-label="Minutes for ${STEP_NAMES[s[0]]}"><span class="u">min</span><button class="sw ${s[2] ? "on" : ""}" role="switch" aria-checked="${!!s[2]}" aria-label="${STEP_NAMES[s[0]]} on" data-on="${type}:${i}"></button></div>`).join("")}</div>`; };
      body = `<div class="st-2">${col("daily")}${col("weekly")}</div><div class="st-note">↑ ↓ change the order · minutes per step · the switch turns a step on or off. Start and Close are always first and last.</div><button class="edit-only st-hidden" data-savesteps="1" data-st-save="1">Save</button>`;
    } else {
      body = `<div class="card st-card"><h4 class="st-h">Off for now</h4><div class="muted" style="line-height:1.5">Switched off until the team is used to the meetings. Everything is already being saved, so when it comes on it will have weeks of history. The four agreed rules: one fair score for everyone (moves and No date cost points), a minimum of 5 tasks to be ranked, outside reasons not counted, and a trend arrow against the last 4 weeks.</div></div>`;
    }
    return bare ? `<div class="body" style="flex-direction:column">${body}</div>` : `<div class="subhead"><div class="seg">${tabsS}</div></div><div class="body" style="flex-direction:column">${body}</div>`;
  }

  // ------------------------------------------------------------------ dialogs
  function closeModal() { $modal.innerHTML = ""; }
  function alertBox(t) { $modal.innerHTML = `<div class="back"><div class="dlg"><div>${t}</div><div style="text-align:right;margin-top:12px"><button class="btnp" id="ok">OK</button></div></div></div>`; document.getElementById("ok").onclick = closeModal; }
  function nextWorkDay(from, plus) { let d = startOfDay(from) + plus * DAY; return d; }
  function newAction(opts) {
    opts = opts || {};
    const ps = team(); let who = opts.person || null, when = null, title = "";
    const now = Date.now(), at6 = (d) => d + 18 * 3600000;
    const fri = (() => { let d = startOfDay(now); for (let i = 1; i < 8; i++) { const x = d + i * DAY; if (new Date(x).getDay() === 4) return x; } return d + DAY; })();
    const nextMeeting = nextWorkDay(now, 1) + 11 * 3600000;
    const choices = [["Tmrw", at6(startOfDay(now) + DAY)], ["Thu", at6(fri)], ["Next meeting", nextMeeting]];
    const draw = () => {
      $modal.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">New action</b><div class="foot" style="margin-top:2px">${esc(opts.from || "Agreed in the meeting")}</div>
        <div class="lbl">WHAT</div><input id="aw" style="width:100%" placeholder="What will be done" value="${esc(title)}">
        <div class="lbl">WHO</div><div>${ps.map((p) => `<span class="pk ${who === p.uid ? "on" : ""}" data-who="${p.uid}">${esc(first(p.name))}</span>`).join("")}</div>
        <div class="lbl">BY WHEN</div><div>${choices.map(([l, t]) => `<span class="pk ${when === t ? "on" : ""}" data-when="${t}">${l}</span>`).join("")}<input type="date" id="ad" style="margin-top:4px" value="${when && !choices.some((c) => c[1] === when) ? new Date(when).toISOString().slice(0, 10) : ""}"></div>
        <div class="foot" style="margin-top:12px">${who ? "Goes to " + esc(first(member(who).name)) + "'s phone as a task from you · 🤝 Meeting" : "Pick who"}</div>
        <div style="display:flex;justify-content:flex-end;gap:16px;margin-top:12px"><button class="link" id="ac">Cancel</button><button class="btnp" id="ag" ${who && when && title.trim() ? "" : "disabled"}>Give task</button></div></div></div>`;
      const aw = document.getElementById("aw"); aw.oninput = () => { title = aw.value; document.getElementById("ag").disabled = !(who && when && title.trim()); }; aw.focus(); aw.setSelectionRange(title.length, title.length);
      $modal.querySelectorAll("[data-who]").forEach((e) => (e.onclick = () => { who = e.dataset.who; draw(); }));
      $modal.querySelectorAll("[data-when]").forEach((e) => (e.onclick = () => { when = Number(e.dataset.when); draw(); }));
      document.getElementById("ad").onchange = (e) => { if (e.target.value) { const [y, mo, d] = e.target.value.split("-").map(Number); when = at6(new Date(y, mo - 1, d).getTime()); draw(); } };
      document.getElementById("ac").onclick = closeModal;
      document.getElementById("ag").onclick = async () => {
        document.getElementById("ag").disabled = true;
        try {
          const id = await Store.addAction(who, title, when, opts.from);
          const m = curMeeting() || (RUN ? null : null);
          if (m) { m.actions = (m.actions || []).concat(id); await Store.saveMeeting(m); }
          closeModal(); render();
        } catch (e) { alertBox("Couldn't save: " + esc(e.message || e)); }
      };
    };
    draw();
  }
  function quickLine(kind) {
    $modal.innerHTML = `<div class="back"><div class="dlg"><b style="font-size:19px">${kind === "decisions" ? "New decision" : "New note"}</b><div class="lbl">${kind === "decisions" ? "WHAT WAS DECIDED" : "NOTE"}</div><textarea id="ql" rows="3" style="width:100%"></textarea>
      <div style="display:flex;justify-content:flex-end;gap:16px;margin-top:12px"><button class="link" id="qc">Cancel</button><button class="btnp" id="qs">Add</button></div></div></div>`;
    const t = document.getElementById("ql"); t.focus();
    document.getElementById("qc").onclick = closeModal;
    document.getElementById("qs").onclick = async () => { const v = t.value.trim(); if (!v) return; closeModal(); await saveCur((m) => (m[kind] = (m[kind] || []).concat({ at: Date.now(), text: v }))); };
  }
  async function fullTimeline(key) {
    const x = items().find((y) => y.src + y.id === key); if (!x) return;
    $modal.innerHTML = `<div class="back"><div class="dlg wide"><b style="font-size:19px">${esc(x.title)}</b><div class="foot">Full timeline · loading…</div></div></div>`;
    let msgs = [];
    if (x.src === "team") { try { msgs = await Store.msgs(x.id); } catch (e) { msgs = []; } }
    const lines = (x.events || []).map((e) => ({ at: e.at, line: e.text }));
    msgs.forEach((m) => lines.push({ at: m.at, msg: m }));
    lines.sort((p, q) => p.at - q.at);
    const bub = (m) => {
      let body = "";
      if (m.text === "\u0001deleted") body = '<i class="grey">🚫 This message was deleted</i>';
      else if (RB.fileMsg(m)) body = RB.fileMsg(m);
      else if (m.kind === "photo" && m.data) body = `<img src="data:image/jpeg;base64,${blobB64(m.data)}">` + (m.text ? "<div>" + esc(m.text) + "</div>" : "");
      else if (m.kind === "voice" && m.data) body = `<audio controls src="data:audio/mp4;base64,${blobB64(m.data)}"></audio>`;
      else if (m.kind === "photo" || m.kind === "voice") body = `<i class="grey">${m.kind === "photo" ? "📷 Photo" : "🎤 Voice note"} (cleared)</i>`;
      else body = esc(m.text);
      const tag = { ask: "❓ Ask", stuck: "⚠ Stuck", proof: "📷 Proof", brief: "Brief" }[m.tag] || "";
      return `<div class="bub"><div class="l">${esc(first(m.fromName))}${tag ? " · " + tag : ""}</div>${body}<div class="tm">${fmtWhen(m.at)}</div></div>`;
    };
    $modal.innerHTML = `<div class="back"><div class="dlg wide"><div style="display:flex;align-items:center"><b style="font-size:19px;flex:1">${esc(x.title)}</b><button class="pill" id="fc">Close</button></div>
      <div class="foot" style="margin-bottom:8px">The same timeline as in the app. Call recordings stay on the phone.</div>
      ${lines.map((l) => (l.msg ? bub(l.msg) : `<div class="line">${esc(l.line)} · ${fmtWhen(l.at)}</div>`)).join("") || '<div class="muted">Nothing yet.</div>'}</div></div>`;
    document.getElementById("fc").onclick = closeModal;
  }
  function blobB64(b) {
    try { if (typeof b.toBase64 === "function") return b.toBase64(); if (b._byteString && b._byteString.toBase64) return b._byteString.toBase64(); } catch (e) {}
    return "";
  }
  function share(id) {
    const m = D.meetings.find((x) => x.id === id); if (!m) return;
    window.open("https://wa.me/?text=" + encodeURIComponent(minutesText(m)), "_blank");
    m.sharedAt = Date.now(); Store.saveMeeting(m).then(render);
  }

  function bind() {
    const on = (sel, fn) => $app.querySelectorAll(sel).forEach((e) => (e.onclick = (ev) => { ev.preventDefault(); fn(e, ev); }));
    on("[data-go]", (e) => { S.view = e.dataset.go; render(); });
    on("[data-period]", (e) => { S.period = e.dataset.period; render(); });
    on("[data-sort]", (e) => { S.sort = e.dataset.sort; render(); });
    on("[data-person]", (e) => { S.person = e.dataset.person; S.sel = null; S.pchip = "overdue"; S.view = "person"; if (!RUN) RB.go("app", "person"); else render(); });
    on("[data-pchip]", (e) => { S.pchip = e.dataset.pchip; S.sel = null; render(); });
    on("[data-sel]", (e) => { S.sel = e.dataset.sel; render(); });
    on("[data-full]", (e) => fullTimeline(e.dataset.full));
    on("[data-reason]", (e) => { S.reasonSel = e.dataset.reason; if (!RUN) RB.go("app", "reasons"); else render(); });
    on("[data-newaction]", () => newAction());
    on("[data-action-person]", (e) => newAction({ person: e.dataset.actionPerson, from: "From person: " + first(member(e.dataset.actionPerson).name) }));
    on("[data-action-reason]", (e) => newAction({ from: "From reason: " + e.dataset.actionReason }));
    on("[data-quick]", (e) => quickLine(e.dataset.quick));
    on("[data-start]", (e) => startMeeting(e.dataset.start));
    on("[data-step]", (e) => { RUN.i = Math.max(0, Math.min(RUN.steps.length, RUN.i + Number(e.dataset.step))); RUN.stepStart = Date.now(); S.view = "people"; window.scrollTo(0, 0); render(); });
    on("[data-att]", (e) => saveCur((m) => { const cyc = ["present", "late", "absent", "leave"]; const v = (m.attendance || {})[e.dataset.att] || "present"; m.attendance[e.dataset.att] = cyc[(cyc.indexOf(v) + 1) % cyc.length]; }));
    on("[data-agree]", (e) => saveCur((m) => { m.agreed = m.agreed || {}; m.agreed[e.dataset.agree] = !m.agreed[e.dataset.agree]; }));
    on("[data-share]", (e) => share(e.dataset.share));
    on("[data-close]", async () => { await saveCur((m) => (m.closedAt = Date.now())); S.minutesId = RUN.id; RUN = null; RB.go("meetings", "minutes"); });
    on("[data-minutes]", (e) => { S.minutesId = e.dataset.minutes; render(); });
    on("[data-add]", async (e) => {
      const kind = e.dataset.add, inp = document.getElementById(kind === "decisions" ? "addDec" : "addNote"), v = inp.value.trim(); if (!v) return;
      const m = D.meetings.find((x) => x.id === S.minutesId); if (!m) return;
      m[kind] = (m[kind] || []).concat({ at: Date.now(), text: v, later: !!m.closedAt }); await Store.saveMeeting(m); render();
    });
    const mq = document.getElementById("mq"); if (mq) mq.oninput = () => { S.mq = mq.value; render(); const n = document.getElementById("mq"); n.focus(); n.setSelectionRange(n.value.length, n.value.length); };
    // settings
    on("[data-stab]", (e) => { S.settingsTab = e.dataset.stab; render(); });
    const saveReasons = (rc) => Store.saveConfig("reasons", { own: rc.own, outside: rc.outside, updatedAt: Date.now() }).then(render);
    on("[data-rr]", (e) => {
      const [k, i, act] = e.dataset.rr.split(":"), rc = reasonsCfg(), r = rc[k][Number(i)];
      if (act === "retire") r.retired = true; else if (act === "back") delete r.retired;
      else if (act === "rename") { const n = prompt("New name", r.name); if (!n || !n.trim()) return; rc[k].push({ name: n.trim() }); r.retired = true; }
      saveReasons(rc);
    });
    on("[data-radd]", (e) => { const k = e.dataset.radd, v = document.getElementById("add_" + k).value.trim(); if (!v) return; const rc = reasonsCfg(); rc[k].push({ name: v }); saveReasons(rc); });
    on("[data-savered]", () => { const r = {}; $app.querySelectorAll("[data-red]").forEach((i) => (r[i.dataset.red] = Math.max(1, Number(i.value) || 1))); Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { red: r })).then(() => RB.stDone()); });
    let stepsDraft = null;
    const draft = () => (stepsDraft = stepsDraft || { daily: stepsFor("daily"), weekly: stepsFor("weekly") });
    on("[data-mv]", (e) => { const [t, i, d] = e.dataset.mv.split(":"); const s = draft()[t], a = Number(i), b = a + Number(d); if (b < 0 || b >= s.length) return; [s[a], s[b]] = [s[b], s[a]]; Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { steps: draft() })).then(render); });
    on("[data-savesteps]", () => {
      const s = draft();
      $app.querySelectorAll("[data-min]").forEach((i) => { const [t, k] = i.dataset.min.split(":"); s[t][Number(k)][1] = Math.max(1, Number(i.value) || 1); });
      Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { steps: s })).then(() => RB.stDone());
    });
    // A step's switch saves at once (Settings redesign J3); typed minutes still wait for the Save bar.
    on("[data-on]", (e) => { const [t, k] = e.dataset.on.split(":"), s = draft(); s[t][Number(k)][2] = !s[t][Number(k)][2]; Store.saveConfig("meeting", Object.assign({}, D.cfg || {}, { steps: s })).then(() => { render(); RB.stSaved(); }); });
  }
  // The meeting clock ticks every second without redrawing the page.
  setInterval(() => {
    const c = document.getElementById("clk"); if (!c || !RUN) return;
    const st = RUN.steps[RUN.i], m = curMeeting(); if (!m) return;
    const shown = st ? Date.now() - RUN.stepStart : Date.now() - m.startedAt, lim = st ? st.min * 60000 : m.plannedMin * 60000;
    c.textContent = "⏱ " + mmss(shown) + " / " + mmss(lim); c.className = "clk" + (shown > lim ? " over" : "");
  }, 1000);

  RB.meet = {
    running: () => !!RUN,
    html(view) {
      if (RUN && view !== "settings") return vRun();
      const v = { people: vPeople, person: vPerson, reasons: vReasons, actions: vActions, minutes: vMinutes, settings: () => vSettings(true), home: vHome }[view] || vHome;
      return v();
    },
    bind(root) { $app = root; bind(); },
    newAction, openActions, actionStatus, minutesText, fullTimeline, rangeLabel, trendSvg, reasonBars, itemChips, timelineOf, personItems,
  };

  // Meetings page (moved here from the old App view file).
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
