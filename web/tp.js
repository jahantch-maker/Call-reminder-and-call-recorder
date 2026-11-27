/* Team Performance (was App view), locked 25 Nov 2026 (pending-list J1-J33, mockups/team-performance v7 + person v3,
 * calendar v2, dossier tab). View only: nothing here changes a task. Two tabs:
 *  · Overview: 4 cards, team score line, one table (Tasks · Dossier · Score), oldest overdue; a click on a row slides the
 *    table into a slim list and opens that person's report (Esc or Close slides it back).
 *  · Person: slim ranked list on the left; his tasks as on his phone (All / My tasks / Follow-ups / Assigned / Dossier |
 *    Calendar), filters All / Overdue / Upcoming / Not scheduled / Done, each row's Timeline opens in place.
 * Numbers come from tpcalc.js (RB.tp). Redraws keep the scroll spot (shell.js); animations use transform and opacity only.
 */
(function () {
  "use strict";
  const RB = window.RB, D = RB.D, S = RB.S, T = RB.tp, esc = RB.esc, first = RB.first, DAY = RB.DAY;
  Object.assign(S, {
    tpPeriod: S.tpPeriod || null, tpSort: S.tpSort || ["score", -1], tpSel: S.tpSel || null, tpRepTab: S.tpRepTab || "over", tpAllOld: false,
    tpPerson: S.tpPerson || null, tpTab: S.tpTab || "all", tpChip: S.tpChip || "all", tpDoneSub: S.tpDoneSub || "", tpDonePer: S.tpDonePer || "week",
    tpOpen: null, tpDosOpen: false, tpDosPer: S.tpDosPer || "week", tpDosDay: null,
    tpCalView: S.tpCalView || "day", tpCalFit: S.tpCalFit !== false, tpCalDay: 0, tpCalWeek: 0,
  });
  const msgs = {};   // task id -> its Timeline talk (loaded when the Timeline opens)
  const players = {};   // message id -> its Audio, kept through redraws so a playing voice note keeps playing
  const b64 = (b) => { if (!b) return ""; if (typeof b === "string") return b; try { if (typeof b.toBase64 === "function") return b.toBase64(); if (b._byteString && b._byteString.toBase64) return b._byteString.toBase64(); } catch (e) {} return ""; };
  const findMsg = (id) => Object.values(msgs).filter(Array.isArray).flat().find((m) => m.id === id);
  /** A Timeline note: text, a photo you can open, or a voice note you can play (same look as Plot files). */
  function msgHtml(m) {
    const who = `<b>${esc(first(m.fromName))}</b>`, d = m.kind === "photo" || m.kind === "voice" ? b64(m.data) : "";
    if (m.kind === "photo") return `${who} added a photo${m.text ? ": " + esc(m.text) : ""}${d ? `<button class="plimg" data-tpimg="${esc(m.id)}" aria-label="Open the photo"><img alt="" src="data:image/jpeg;base64,${d}"><span>🔍 Click to open</span></button>` : ' <i class="tp-hint">(photo cleared)</i>'}`;
    if (m.kind === "voice") { const sec = Math.round((m.durMs || 0) / 1000), a = players[m.id]; return `${who} sent a voice note${d ? `<div class="plvoice" data-tpvoice="${esc(m.id)}"><button class="plplay" aria-label="Play">${a && !a.paused ? "❚❚" : "▶"}</button><span class="plwave" aria-hidden="true"></span><span class="pldur">${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, "0")}</span><button class="plspeed" aria-label="Speed">${a ? a.playbackRate : 1}x</button></div>` : ' <i class="tp-hint">(voice note cleared)</i>'}`; }
    return `${who}${m.text ? ": " + esc(m.text) : ""}`;
  }
  const clock = (t) => new Date(t).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
  const dshort = (t) => new Date(t).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
  const dday = (t) => new Date(t).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
  const letter = (t) => "SMTWTFS"[new Date(t).getDay()];
  const ord = (n) => n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : ["th", "st", "nd", "rd"][n % 10] || "th");
  const rk = (r) => (r ? `<span class="tp-rk ${r <= 3 ? "top" : ""}">${r <= 3 ? "★" : ""}${ord(r)}</span>` : "");
  const pc = (v) => (v == null ? "–" : v + "%");
  const av = (m, big) => { const ph = m && m.photo; return `<span class="tp-av${big ? " big" : ""}">${ph ? `<img src="${esc(ph)}" alt="" referrerpolicy="no-referrer">` : esc((first(m && m.name)[0] || "?").toUpperCase())}</span>`; };
  const daysAgo = (t, now) => Math.max(1, Math.round((T.sod(now) - T.sod(t)) / DAY));
  const matchQ = (m) => !S.q || first(m.name).toLowerCase().includes(S.q.toLowerCase());
  const period = () => S.tpPeriod || T.cfg().period;
  const pName = (k) => (T.PERIODS.find((p) => p[0] === k) || [k, k])[1];

  // ------------------------------------------------------------------ small charts (SVG, scale with their box)
  function line(w, h, data, labels, color, o) {
    o = o || {};
    const vals = data.concat(o.cmp || []).filter((x) => x != null);
    if (!vals.length) return '<div class="tp-hint">No numbers yet.</div>';
    const mn = o.axis ? Math.max(0, Math.floor((Math.min(...vals) - 5) / 10) * 10) : Math.min(...vals) - 2;
    const mx = o.axis ? Math.min(100, Math.ceil((Math.max(...vals) + 5) / 10) * 10) : Math.max(...vals) + 2;
    const L = o.axis ? 34 : 4, R = o.axis ? 22 : 4, B = labels.length ? 18 : 4, Tp = o.axis ? 16 : 4, span = mx - mn || 1;
    const px = (i) => L + (i * (w - L - R)) / Math.max(1, data.length - 1), py = (x) => h - B - ((x - mn) / span) * (h - B - Tp);
    const pts = (d) => d.map((x, i) => (x == null ? null : px(i) + "," + py(x))).filter(Boolean).join(" ");
    let s = `<svg class="tp-svg" viewBox="0 0 ${w} ${h}" role="img">`;
    if (o.axis) for (let g = mn; g <= mx; g += span > 40 ? 20 : 10) s += `<line x1="${L}" x2="${w - R}" y1="${py(g)}" y2="${py(g)}" class="tp-grid"/><text x="0" y="${py(g) + 4}" class="tp-ax">${g}%</text>`;
    if (o.fill) { const d0 = data.map((x, i) => [x, i]).filter((p) => p[0] != null); if (d0.length > 1) s += `<polygon points="${px(d0[0][1])},${h - B} ${pts(data)} ${px(d0[d0.length - 1][1])},${h - B}" fill="${color}" opacity=".08"/>`; }
    if (o.cmp) s += `<polyline points="${pts(o.cmp)}" fill="none" stroke="#B8BCC6" stroke-width="2" stroke-dasharray="5 4"/>`;
    s += `<polyline points="${pts(data)}" fill="none" stroke="${color}" stroke-width="${o.axis ? 3 : 2.2}" stroke-linejoin="round"/>`;
    const lastI = data.map((x, i) => (x == null ? -1 : i)).filter((i) => i >= 0).pop();
    if (o.axis) {
      s += data.map((x, i) => (x == null ? "" : `<circle cx="${px(i)}" cy="${py(x)}" r="3.5" class="tp-dot" stroke="${color}"/>`)).join("");
      if (lastI != null) s += `<text x="${px(lastI)}" y="${py(data[lastI]) - 8}" class="tp-last" fill="${color}">${data[lastI]}%</text>`;
    } else if (lastI != null) s += `<circle cx="${px(lastI)}" cy="${py(data[lastI])}" r="3" fill="${color}"/>`;
    s += labels.map((l, i) => `<text x="${px(i)}" y="${h - 3}" class="tp-ax mid">${l}</text>`).join("");
    return s + "</svg>";
  }
  const wkLabels = (n, now) => Array.from({ length: n }, (_, i) => dshort(T.addDays(T.weekStart(now), -7 * (n - 1 - i))).replace(/ \w+$/, (m) => m));
  const arrow = (d) => { const v = d.filter((x) => x != null); if (v.length < 2) return ""; const c = v[v.length - 1] - v[0]; return c >= 0 ? `<span class="tp-up">▲${c}</span>` : `<span class="tp-down">▼${-c}</span>`; };
  /** A person's dossier days as bars: fill = done ÷ due, red under the weak-day line, done/due on top (month: the missed count). */
  function dosBars(days, o) {
    o = o || {};
    if (!days.length) return '<div class="tp-hint">No dossier days in this period.</div>';
    const c = T.cfg(), n = days.length, many = n > 14, w = o.w || Math.max(120, n * (many ? 13 : 30)), h = o.h || 46, bw = w / n, full = h - 24;
    let s = `<svg class="tp-svg" viewBox="0 0 ${w} ${h}" style="max-width:${w}px" role="img">`;
    days.forEach((d, i) => {
      const X = i * bw + (many ? 1 : 3), W = Math.max(2, bw - (many ? 2 : 6)), pct = d.due ? d.done / d.due : 0;
      const weak = d.due && pct * 100 < c.weakDay, fill = d.open && !d.due ? "#93B0E8" : weak ? "#D64545" : "#1F3A93";
      const top = d.none ? "–" : d.open && !d.due ? d.done + "/" + d.all : many && !o.labels ? (d.due - d.done ? String(d.due - d.done) : "") : d.done + "/" + d.due;
      s += `<g><title>${dday(d.t)}${d.none ? ": no dossier" : `: ${d.done} of ${d.open && !d.due ? d.all : d.due} done`}</title>`;
      s += `<text x="${X + W / 2}" y="9" class="tp-bt${weak ? " red" : ""}" font-size="${many ? 8 : 10}">${top}</text>`;
      s += `<rect x="${X}" y="12" width="${W}" height="${full}" rx="2" class="tp-bg"/>`;
      const hh = d.open && !d.due ? (d.all ? (full * d.done) / d.all : 0) : full * pct;
      if (hh) s += `<rect x="${X}" y="${12 + full - hh}" width="${W}" height="${hh}" rx="2" fill="${fill}"/>`;
      s += `<text x="${X + W / 2}" y="${h - 1}" class="tp-ax mid" font-size="${many ? 8 : 10}">${letter(d.t)}</text></g>`;
    });
    return s + "</svg>";
  }
  const stackBar = (f) => { const t = f.onTime + f.late + f.overdue + f.uns; if (!t) return '<div class="tp-bar empty"></div>'; return `<div class="tp-bar" title="On time ${f.onTime} · Late ${f.late} · Overdue ${f.overdue} · Not scheduled ${f.uns}">${[[f.onTime, "g"], [f.late, "a"], [f.overdue, "r"], [f.uns, "n"]].map(([x, c]) => (x ? `<i class="${c}" style="width:${(x / t) * 100}%"></i>` : "")).join("")}</div>`; };
  const legend = '<span class="tp-leg"><i class="g"></i>On time<i class="a"></i>Late<i class="r"></i>Overdue<i class="n"></i>Not scheduled</span>';
  const periodChips = (cur, attr) => `<div class="seg tp-seg">${T.PERIODS.map(([k, l]) => `<button data-${attr}="${k}" class="${cur === k ? "on" : ""}">${l}</button>`).join("")}</div>`;

  // ------------------------------------------------------------------ Overview
  function sumFacts(rows) { return rows.reduce((t, r) => ({ onTime: t.onTime + r.f.onTime, late: t.late + r.f.late, overdue: t.overdue + r.f.overdue, uns: t.uns + r.f.uns }), { onTime: 0, late: 0, overdue: 0, uns: 0 }); }
  function overview() {
    const now = Date.now(), c = T.cfg(), p = period(), all = T.board(p, now), rows = all.filter((r) => matchQ(r.m));
    rows.forEach((r) => (r.tr = T.trend(r.uid, now, c.weeks)));
    if (S.tpSel && !all.some((r) => r.uid === S.tpSel)) S.tpSel = null;
    const tabsExtra = periodChips(p, "tpper");   // the period chips sit in the page tab row (shell.js tabsExtra)
    if (S.tpSel) return { html: report(all, now, p), tabsExtra };
    // cards: this period, with the change since the period before
    const [pa, pb] = T.prevRange(p, now), prev = sumFacts(T.people().map((m) => ({ f: T.facts(m.uid, pa, pb, pb) }))), cur = sumFacts(all);
    const tot = cur.onTime + cur.late + cur.overdue + cur.uns, ptot = prev.onTime + prev.late + prev.overdue + prev.uns;
    const ch = (a, b, goodUp, isPct) => { const d = a - b; if (!d) return ""; const good = goodUp ? d > 0 : d < 0; return `<em class="${good ? "tp-up" : "tp-down"}">${d > 0 ? "▲" : "▼"}${Math.abs(d)}${isPct ? "%" : ""}</em>`; };
    const onP = tot ? Math.round((cur.onTime / tot) * 100) : 0, onPp = ptot ? Math.round((prev.onTime / ptot) * 100) : 0;
    const kpi = (lbl, n, cls, sub) => `<div class="tp-kpi ${cls}"><i>${lbl}</i><b>${n}</b><i>${sub}</i></div>`;
    const teamTr = Array.from({ length: c.weeks }, (_, i) => T.avg(all.map((r) => (T.trend(r.uid, now, c.weeks)[i]))));
    const cards = `<div class="card tp-cwrap tp-split"><div class="tp-cards two">${kpi("Overdue now", cur.overdue, "r", "past first due date " + ch(cur.overdue, prev.overdue, false))}${kpi("Done on time", cur.onTime, "g", onP + "% of all counted " + ch(onP, onPp, true, true))}${kpi("Done late", cur.late, "a", "after first due date " + ch(cur.late, prev.late, false))}${kpi("Not scheduled", cur.uns, "n", "no date, over " + c.grace + " days")}</div>
      <div class="tp-kpi chart"><i>Team score · last ${c.weeks} weeks</i>${line(460, 128, teamTr, wkLabels(c.weeks, now), "#1F3A93", { axis: 1, fill: 1 })}</div></div>`;
    // the table
    const [k, dir] = S.tpSort;
    const val = { name: (r) => first(r.m.name).toLowerCase(), on: (r) => r.f.on, over: (r) => r.f.overdue, uns: (r) => r.f.uns, moved: (r) => r.f.moved, dos: (r) => r.dos, score: (r) => r.score }[k] || ((r) => r.score);
    rows.sort((x, y) => { const a = val(x), b = val(y); if (a === b) return x.rank - y.rank; if (a == null) return 1; if (b == null) return -1; return (a < b ? -1 : 1) * dir; });
    const showDos = c.show.dosBars[0], showTr = c.show.trend[0];
    const nDays = T.dayList(...T.range(p, now)).length, BW = !showDos ? 0 : nDays > 14 ? 380 : nDays > 7 ? 300 : nDays > 1 ? 200 : 80;
    const cols = `34px minmax(110px,1fr) minmax(150px,1.4fr) 58px 58px 58px 58px ${showDos ? BW + "px " : ""}52px 128px${showTr ? " 104px" : ""}`;
    const th = (t, key) => `<button class="tp-th ${k === key ? "on" : ""}" data-tpsort="${key}">${t} <span>${k === key ? (dir > 0 ? "▲" : "▼") : "▲▼"}</span></button>`;
    const band = `<div class="tp-band" style="grid-template-columns:${cols}"><span></span><span></span><span class="b t" style="grid-column:span 5">TASKS ${legend}</span><span class="b d" style="grid-column:span ${showDos ? 2 : 1}">DOSSIER <em>· daily tasks done</em></span><span class="b s" style="grid-column:span ${showTr ? 2 : 1}">SCORE <em>· ${c.wOn}% tasks + ${c.wDos}% dossier</em></span></div>`;
    const hdr = `<div class="tp-tr tp-thr" style="grid-template-columns:${cols}"><span></span>${th("Person", "name")}<span></span>${th("On time", "on")}${th("Over-<br>due", "over")}${th("Not<br>sched.", "uns")}${th("Moved<br>" + c.movedN + "+", "moved")}${showDos ? '<span class="tp-c tp-small">Each day</span>' : ""}${th("%", "dos")}${th("Score · rank", "score")}${showTr ? `<span class="tp-c tp-small">${c.weeks}-wk score</span>` : ""}</div>`;
    const body = rows.map((r) => {
      const red = r.score != null && r.score < c.redBelow;
      return `<button class="tp-tr tp-row${red ? " flag" : ""}" style="grid-template-columns:${cols}" data-tpsel="${esc(r.uid)}">${av(r.m)}<span class="tp-name">${esc(first(r.m.name))}</span>${stackBar(r.f)}
        <b class="tp-c ${r.f.on != null && r.f.on < c.redBelow ? "low" : ""}">${pc(r.f.on)}</b><b class="tp-c ${r.f.overdue ? "low" : ""}">${r.f.overdue}</b><b class="tp-c ${r.f.uns ? "low" : ""}">${r.f.uns}</b><b class="tp-c ${r.f.moved ? "low" : ""}">${r.f.moved}</b>
        ${showDos ? `<span class="tp-dcell">${T.hasDossier(r.uid) ? dosBars(r.days, { w: BW, h: 44, labels: nDays <= 14 }) : '<span class="tp-hint">No dossier data from the phone yet</span>'}</span>` : ""}<b class="tp-c ${r.dos != null && r.dos < c.weakDay ? "low" : ""}">${pc(r.dos)}</b>
        <span class="tp-c tp-sc"><b class="${red ? "low" : ""}">${pc(r.score)}</b>${rk(r.rank)}</span>${showTr ? `<span class="tp-spark">${line(64, 24, r.tr, [], r.tr[r.tr.length - 1] >= r.tr[0] ? "#1F3A93" : "#D64545")}${arrow(r.tr)}</span>` : ""}</button>`;
    }).join("");
    const table = `<div class="card tp-tcard${S._tpAnim === "close" ? " back" : ""}"><div class="tp-scroll"><div class="tp-table">${band}${hdr}${body || '<div class="muted" style="padding:12px">No people to show.</div>'}</div></div>
      <div class="tp-hint tp-foot">Click a person to open their report · click a heading to sort · Score = Tasks on time % × ${c.wOn}% + Dossier % × ${c.wDos}% · rank always follows Score · number on top = dossier tasks done that day · day letters skip each person's weekly off day (from their timetable)</div></div>`;
    // oldest overdue (+ needs help, off unless switched on in Settings)
    let bottom = "";
    if (c.show.oldOver[0]) {
      const old = [];
      all.forEach((r) => T.items(r.uid).forEach((x) => { if (!x.done && x.promise && now - x.promise > c.older * DAY) old.push({ r, x }); }));
      old.sort((a, b) => a.x.promise - b.x.promise);
      const shown = S.tpAllOld ? old : old.slice(0, c.oldOverN);
      bottom += `<div class="card tp-old"><div class="tp-ct">Oldest overdue tasks <span class="tp-hint">more than ${c.older} days · ${old.length}</span></div>${shown.map(({ r, x }) => `<button class="tp-li" data-tpsel="${esc(r.uid)}"><span><b>${esc(first(r.m.name))}</b> · ${esc(x.title)} <span class="tp-hint">· ${T.KIND[x.kind]}</span></span><span class="low">${daysAgo(x.promise, now)} days</span></button>`).join("") || '<div class="tp-hint">Nothing overdue for that long. 👍</div>'}${old.length > c.oldOverN ? `<button class="tp-more" data-tpallold="1">${S.tpAllOld ? "Show less" : "Show all " + old.length + " ›"}</button>` : ""}</div>`;
    }
    if (c.show.needs[0]) {
      const low = all.filter((r) => r.score != null).sort((a, b) => a.score - b.score).slice(0, c.needsN);
      bottom += `<div class="card tp-needs"><div class="tp-ct">Needs help · ${esc(pName(p).toLowerCase())}</div>${low.map((r) => `<button class="tp-li" data-tpsel="${esc(r.uid)}"><b>${esc(first(r.m.name))}</b><span class="low">${pc(r.f.on)} on time · ${r.f.overdue} overdue · ${r.f.uns} not scheduled</span></button>`).join("")}</div>`;
    }
    return { html: cards + table + (bottom ? `<div class="tp-bottom">${bottom}</div>` : ""), tabsExtra };
  }

  // ------------------------------------------------------------------ the person report (Overview, after a click)
  function report(all, now, p) {
    const c = T.cfg(), r = all.find((x) => x.uid === S.tpSel), m = r.m, anim = S._tpAnim === "open";
    const list = all.slice().sort((a, b) => a.rank - b.rank).map((x) => `<button class="tp-pl ${x.uid === r.uid ? "on" : ""}" data-tpsel="${esc(x.uid)}">${av(x.m)}<span class="tp-name">${esc(first(x.m.name))}</span><b class="${x.score != null && x.score < c.redBelow ? "low" : ""}">${pc(x.score)}</b>${rk(x.rank)}</button>`).join("");
    const avgOver = all.length ? (all.reduce((s, x) => s + x.f.overdue, 0) / all.length).toFixed(1) : "0";
    const kpi = (lbl, n, cls, sub) => `<div class="tp-kpi ${cls}"><i>${lbl}</i><b>${n}</b><i>${sub}</i></div>`;
    const tr = T.trend(r.uid, now, c.weeks), team = Array.from({ length: c.weeks }, (_, i) => T.avg(all.map((x) => T.trend(x.uid, now, c.weeks)[i])));
    const its = T.items(r.uid);
    // most missed dossier tasks of the period
    const miss = {};
    r.days.forEach((d) => (d.items || []).forEach((i) => { if (i.s === "O") return; const k = i.t; miss[k] = miss[k] || { m: 0, n: 0 }; miss[k].n++; if (i.s === "M") miss[k].m++; }));
    const mm = Object.entries(miss).filter((e) => e[1].m).sort((a, b) => b[1].m - a[1].m).slice(0, 2);
    const parts = [];
    if (c.show.trend[1]) parts.push(`<div class="tp-box"><div class="tp-ct">Score · last ${c.weeks} weeks <span class="tp-leg2"><i style="background:#D64545"></i>${esc(first(m.name))}<i style="background:#B8BCC6"></i>Team average</span></div>${line(880, 110, tr, wkLabels(c.weeks, now), "#D64545", { axis: 1, cmp: team })}</div>`);
    if (c.show.dosBars[1]) parts.push(`<div class="tp-box"><div class="tp-ct">Dossier · ${esc(pName(p).toLowerCase())} <span>${r.dosDue ? `<span class="${r.dos < c.weakDay ? "low" : ""}">${r.dosDone} of ${r.dosDue} done · ${r.dos}%</span>` : '<span class="tp-hint">no dossier days yet</span>'}<span class="tp-hint"> · team avg ${pc(T.avg(all.map((x) => x.dos)))}</span></span></div>
      ${T.hasDossier(r.uid) ? dosBars(r.days, { w: Math.max(300, r.days.length * 40), h: 74, labels: true }) : '<div class="tp-hint">His phone hasn\'t sent dossier days yet (needs app 0.59 or later).</div>'}
      ${mm.length ? `<div class="tp-hint">Most missed: ${mm.map(([t, v]) => `<b>${esc(t)}</b> ${v.m} of ${v.n} days`).join(" · ")} · red = day under ${c.weakDay}%</div>` : ""}</div>`);
    const lower = [];
    if (c.show.byType[1]) lower.push(`<div class="tp-box"><div class="tp-ct">By task type</div>${[["own", "My tasks"], ["fu", "Follow-ups"], ["given", "Assigned to him"], ["plot", "Plot files"]].map(([k, l]) => { const f = r.f.kinds[k] || { onTime: 0, late: 0, overdue: 0, uns: 0 }; const n = f.onTime + f.late + f.overdue + f.uns; return `<div class="tp-type"><span>${l}</span>${stackBar(f)}<span class="tp-hint">${n}</span></div>`; }).join("")}</div>`);
    const tabs = [["over", "Overdue", true], ["uns", "Not sched.", c.show.oldUns[1]], ["moved", "Moved " + c.movedN + "+", c.show.moved[1]]].filter((t) => t[2]);
    if (c.show.oldOver[1] || tabs.length > 1) {
      if (!tabs.some((t) => t[0] === S.tpRepTab)) S.tpRepTab = tabs[0][0];
      const pick = { over: (x) => !x.done && x.promise && x.promise < now, uns: (x) => !x.done && !x.promise && x.created && x.created < now - c.grace * DAY, moved: (x) => x.moves >= c.movedN && !x.done };
      const lists = {}; tabs.forEach(([k]) => (lists[k] = its.filter(pick[k])));
      lists.over && lists.over.sort((a, b) => a.promise - b.promise); lists.uns && lists.uns.sort((a, b) => a.created - b.created); lists.moved && lists.moved.sort((a, b) => b.moves - a.moves);
      const cur = lists[S.tpRepTab] || [], txt = (x) => (S.tpRepTab === "over" ? daysAgo(x.promise, now) + " days" : S.tpRepTab === "uns" ? "added " + daysAgo(x.created, now) + " days ago" : "moved " + x.moves + "×");
      lower.push(`<div class="tp-box"><div class="tp-mini">${tabs.map(([k, l]) => `<button data-tpreptab="${k}" class="${S.tpRepTab === k ? "on" : ""}">${l} · ${lists[k].length}</button>`).join("")}</div>
        ${cur.slice(0, S.tpAllOld ? 99 : 3).map((x) => `<div class="tp-li"><span>${esc(x.title)} <span class="tp-hint">· ${T.KIND[x.kind]}</span></span><span class="low">${txt(x)}</span></div>`).join("") || '<div class="tp-hint" style="padding:6px">None. 👍</div>'}
        ${cur.length > 3 ? `<button class="tp-more" data-tpallold="1">${S.tpAllOld ? "Show less" : "Show all " + cur.length + " ›"}</button>` : ""}</div>`);
    }
    return `<div class="tp-rep${anim ? " in" : ""}"><div class="card tp-plist">${list}</div>
      <div class="card tp-panel"><div class="tp-ph">${av(m, true)}<b>${esc(first(m.name))}</b>${rk(r.rank)}<span class="tp-hint">of ${all.length} · ${esc(pName(p).toLowerCase())}</span><div class="sp"></div><button class="btnp" data-tpopen="${esc(r.uid)}">Open tasks ›</button><button class="btnt" data-tpclose="1" title="Esc also closes">✕ Close</button></div>
      <div class="tp-cards four">${kpi("Score", pc(r.score), "b", "on time " + pc(r.f.on) + " · dossier " + pc(r.dos))}${kpi("Overdue now", r.f.overdue, "r", "team avg " + avgOver)}${kpi("Not scheduled", r.f.uns, "n", "older than " + c.grace + " days")}${kpi("Moved " + c.movedN + "+", r.f.moved, "a", "tasks")}</div>
      ${parts.join("")}${lower.length ? `<div class="tp-two">${lower.join("")}</div>` : ""}</div></div>`;
  }

  // ------------------------------------------------------------------ Person tab
  const TABS = [["all", "All"], ["own", "My tasks"], ["fu", "Follow-ups"], ["given", "Assigned to him"], ["dossier", "Dossier"], ["cal", "Calendar"]];
  const inTab = (x, tab) => tab === "all" || (tab === "given" ? x.kind === "given" || x.kind === "plot" : x.kind === tab);
  function personTab() {
    const now = Date.now(), c = T.cfg(), board = T.board("week", now), ppl = board.slice().sort((a, b) => a.rank - b.rank);
    if (!ppl.length) return { html: '<div class="card">No people to show yet.</div>' };
    if (S.person && ppl.some((r) => r.uid === S.person)) { S.tpPerson = S.person; S.person = null; }   // from Meetings
    if (!S.tpPerson || !ppl.some((r) => r.uid === S.tpPerson)) S.tpPerson = ppl[0].uid;
    const r = ppl.find((x) => x.uid === S.tpPerson), uid = r.uid, its = T.items(uid);
    const list = `<div class="card tp-plist tp-side"><div class="tp-hint" style="margin:0 4px 4px">Team · score this week</div>${ppl.filter((x) => matchQ(x.m)).map((x) => `<button class="tp-pl ${x.uid === uid ? "on" : ""}" data-tpperson="${esc(x.uid)}">${av(x.m)}<span class="tp-name">${esc(first(x.m.name))}</span><b class="${x.score != null && x.score < c.redBelow ? "low" : ""}">${pc(x.score)}</b>${rk(x.rank)}</button>`).join("")}</div>`;
    const today = T.dosDays(uid, T.sod(now), T.addDays(T.sod(now), 1), now)[0] || { items: [] };
    const cnt = (tab) => (tab === "dossier" ? (today.items || []).length : tab === "cal" ? "" : its.filter((x) => !x.done && inTab(x, tab)).length);
    const tabs = `<div class="tp-tabs">${TABS.map(([k, l], i) => `${i === 5 ? '<span class="tp-div"></span>' : ""}<button data-tptab="${k}" class="${S.tpTab === k ? "on" : S.tpTab === "all" && i >= 1 && i <= 4 ? "inall" : ""}">${l} ${cnt(k) === "" ? "" : `<b>${cnt(k)}</b>`}</button>`).join("")}${S.tpTab === "all" ? '<span class="tp-allnote">All = the 4 blue tabs together</span>' : ""}</div>`;
    // 27 Nov (S3.3, S4.6) Stuck presses, paused time and dossier reminder skips: counts only, the score doesn't change.
    const mine = D.tasks.filter((t) => t.toUid === uid), stuckN = mine.reduce((n, t) => n + (t.stuckN || 0), 0), pausedMs = mine.reduce((n, t) => n + (t.pausedMs || 0), 0);
    const [wa0] = T.range("week", now), skips = ((RB.member(uid) || {}).dosSkips || []).filter((x) => x >= wa0).length;
    const facts = [stuckN ? `⚠ Stuck ${stuckN}×${pausedMs ? " · paused " + pausedLbl(pausedMs) : ""}` : "", skips ? `📋 Dossier reminder skipped ${skips}× this week` : ""].filter(Boolean).map((t) => `<span class="tp-fact">${t}</span>`).join("");
    const sent = (D.waSent || {})[uid];
    const head = `<div class="tp-ph">${av(r.m, true)}<b>${esc(first(r.m.name))}</b>${rk(r.rank)}<span class="tp-hint">Score this week ${pc(r.score)} · view only</span>${facts}<span class="sp" style="flex:1"></span>
      <div class="tp-wa"><div class="tp-warow"><button class="tp-wabtn" data-tpwa="${esc(uid)}">${WA_LOGO} Send pending list on WhatsApp</button><span class="tp-hov tp-wai" tabindex="0" aria-label="About this button">i<span class="tp-tip left">Sends ${esc(first(r.m.name))} all open tasks from everyone (not dossier). You choose which groups, then press Send in WhatsApp.</span></span></div>
      ${sent ? `<div class="tp-wasent">Last sent: ${esc(dshort(sent.at))}, ${esc(clock(sent.at))} by ${esc(first(sent.by || ""))}</div>` : ""}</div></div>${tabs}`;
    let body;
    if (S.tpTab === "dossier") body = dossierTab(uid, now);
    else if (S.tpTab === "cal") body = calendar(uid, its, now);
    else body = taskList(uid, its, today, now);
    return { html: `<div class="tp-person">${list}<div class="card tp-main">${head}<div class="tp-body${S._tpFresh === "body" ? " in" : ""}">${body}</div></div></div>` };
  }

  const pausedLbl = (ms) => { const m = Math.round(ms / 60000), h = Math.floor(m / 60); return h ? h + "h" + (m % 60 ? " " + (m % 60) + "m" : "") : m + "m"; };
  const WA_LOGO = '<svg class="tp-walogo" viewBox="0 0 32 32" aria-hidden="true"><path fill="#fff" d="M16 3a13 13 0 0 0-11.2 19.6L3 29l6.6-1.7A13 13 0 1 0 16 3z"/><path fill="#25D366" d="M16 5.4a10.6 10.6 0 0 0-9 16.2l.3.4-1 3.8 3.9-1 .4.2A10.6 10.6 0 1 0 16 5.4z"/><path fill="#fff" d="M12.2 9.8c-.3-.6-.6-.6-.9-.6h-.7c-.3 0-.7.1-1 .5-.4.4-1.4 1.3-1.4 3.2s1.4 3.7 1.6 4c.2.2 2.7 4.3 6.7 5.9 3.3 1.3 4 1 4.7 1 .7-.1 2.3-1 2.6-1.9.3-.9.3-1.7.2-1.9-.1-.2-.4-.3-.8-.5l-2.7-1.3c-.4-.1-.6-.2-.9.2l-1.2 1.5c-.2.3-.4.3-.8.1-.4-.2-1.6-.6-3.1-1.9-1.1-1-1.9-2.3-2.1-2.7-.2-.4 0-.6.2-.8l.6-.7c.2-.2.3-.4.4-.6.1-.3.1-.5 0-.7l-1.2-3z"/></svg>';
  /**
   * S5.1/S5.5 The pending list for WhatsApp, the same format as the phone's (TaskListText.kt): bold heading, groups
   * Late / No time yet / Today / Later with counts, numbering runs on, "Due 25 Nov, 5:00 pm · from Ali", a blank line after each task.
   */
  const GROUPS = [["late", "🔴 Late"], ["none", "⏳ No time yet"], ["today", "📅 Today"], ["later", "🗓 Later"]];
  const groupOf = (x, now) => (!x.due ? "none" : x.due < now ? "late" : T.sod(x.due) === T.sod(now) ? "today" : "later");
  const dueTxt = (t) => dshort(t) + ", " + clock(t).toLowerCase().replace(/\s/g, " ");
  const tailOf = (x) => (x.kind === "given" ? "from " + first(x.from || "") : x.kind === "own" ? "my task" : x.kind === "fu" ? "follow-up" : "plot file");
  function pendingText(name, its, now, pick) {
    let n = 0, out = `*${first(name)}, your open tasks (${dshort(now)})*\n`;
    GROUPS.filter(([g]) => pick.has(g)).forEach(([g, l]) => {
      const L = its.filter((x) => groupOf(x, now) === g).sort((a, b) => (a.due || a.created) - (b.due || b.created));
      if (!L.length) return;
      out += `\n*${l} (${L.length})*\n` + L.map((x) => `${++n}. ${x.title}\n   ${x.due ? "Due " + dueTxt(x.due) + " · " : ""}${tailOf(x)}`).join("\n\n") + "\n";
    });
    return n ? out.trimEnd() : null;
  }
  function waPicker(uid) {
    const now = Date.now(), m0 = RB.member(uid) || {}, its = T.items(uid).filter((x) => !x.done), pick = new Set(["late", "none", "today"]), md = document.getElementById("modal");
    const seat = RB.seatOfUid(uid), num = String(m0.wa || (seat && seat.phone) || m0.phone || "").replace(/\D/g, ""), intl = num.length === 11 && num[0] === "0" ? "92" + num.slice(1) : num;
    const cnt = (g) => its.filter((x) => groupOf(x, now) === g).length;
    const draw = () => {
      const txt = pendingText(m0.name || "", its, now, pick);
      md.querySelector("#wagr").innerHTML = GROUPS.map(([g, l]) => `<label class="swrow" style="cursor:pointer;${cnt(g) ? "" : "opacity:.45"}"><input type="checkbox" data-wag="${g}" ${pick.has(g) && cnt(g) ? "checked" : ""} ${cnt(g) ? "" : "disabled"} style="width:18px;height:18px;margin-right:10px"><div class="w"><b>${l}</b></div><span class="sub">${cnt(g)}</span></label>`).join("");
      md.querySelector("#wapv").innerHTML = txt ? esc(txt).replace(/\*([^*\n]+)\*/g, "<b>$1</b>").replace(/\n/g, "<br>") : '<span class="muted">Nothing picked.</span>';
      md.querySelector("#wago").disabled = !txt;
      md.querySelectorAll("[data-wag]").forEach((c) => (c.onchange = () => { c.checked ? pick.add(c.dataset.wag) : pick.delete(c.dataset.wag); draw(); }));
    };
    md.innerHTML = `<div class="back"><div class="dlg wide"><b style="font-size:19px">Send which tasks to ${esc(first(m0.name || ""))}?</b><div class="foot">${intl ? "Opens WhatsApp with the text typed; you press Send." : "No WhatsApp number on this seat: WhatsApp opens and you pick the chat."}</div>
      <div class="tp-wagrid"><div id="wagr"></div><div class="tp-wapv" id="wapv"></div></div>
      <div style="display:flex;justify-content:flex-end;gap:16px;margin-top:14px"><button class="link" id="wac">Cancel</button><button class="tp-wabtn" id="wago">${WA_LOGO} Open in WhatsApp</button></div></div></div>`;
    md.querySelector("#wac").onclick = () => (md.innerHTML = "");
    md.querySelector("#wago").onclick = () => {
      const txt = pendingText(m0.name || "", its, now, pick); if (!txt) return;
      window.open("https://wa.me/" + intl + "?text=" + encodeURIComponent(txt), "_blank", "noopener");
      const me = RB.member(RB.uidOf()) || {}, rec = { [uid]: { at: Date.now(), by: me.name || "" } };
      RB.Store.put("config", "waSent", rec, true).catch((e) => console.warn("waSent", e));
      md.innerHTML = ""; RB.render();
    };
    draw();
  }

  const cardLegend = `<div class="tp-lg"><span><i class="norm"></i>Normal</span><span><i class="urg"></i>🔴 Urgent</span><span><i class="plot"></i>Plot file</span><span><span class="tp-mv">Moved ${T.cfg().movedN}×</span> date moved ${T.cfg().movedN}+ times</span></div>`;
  function kindLabel(x) {
    const cls = { own: "own", fu: "fu", given: "given", plot: "plot" }[x.kind];
    // Who it went to, as the phone shows it: "Basharat → Husnain", plus "→ Ilyas" when passed on.
    if (x.chain && x.kind !== "plot") return `<span class="tp-lbl ch ${cls}">${x.chain.map((n) => `<span>${esc(String(n).trim().split(/\s+/)[0])}</span>`).join(" → ")}</span>`;
    const extra = x.kind === "given" && x.from ? " · by " + first(x.from) : x.kind === "plot" && x.stage ? " · Stage " + x.stage : "";
    return `<span class="tp-lbl ${cls}">${T.KIND[x.kind]}${esc(extra)}</span>`;
  }
  const rowStyle = (x) => (x.urgent && !x.done ? "urg" : x.kind === "plot" ? "plot" : "norm");
  function taskRow(x, when, whenCls, now) {
    const c = T.cfg(), open = S.tpOpen === x.id;
    return `<div class="tp-task ${rowStyle(x)}"><span class="tp-tt">${x.urgent && !x.done ? "🔴 " : ""}${esc(x.title)}${kindLabel(x)}${x.moves >= c.movedN ? `<span class="tp-mv">Moved ${x.moves}×</span>` : ""}</span><span class="tp-when ${whenCls || ""}">${when}</span><button class="tp-tlb ${open ? "on" : ""}" data-tptl="${esc(x.id)}">Timeline ${open ? "▴" : "›"}</button></div>${open ? timeline(x, now) : ""}`;
  }
  function taskList(uid, its, today, now) {
    const c = T.cfg(), mine = its.filter((x) => inTab(x, S.tpTab));
    const over = mine.filter((x) => !x.done && x.promise && x.promise < now).sort((a, b) => a.promise - b.promise);
    const up = mine.filter((x) => !x.done && x.promise >= now).sort((a, b) => a.due - b.due);
    const uns = mine.filter((x) => !x.done && !x.promise).sort((a, b) => a.created - b.created);
    const [da, db] = T.range(S.tpDonePer, now);
    const done = mine.filter((x) => x.done && x.doneAt >= da && x.doneAt < db).sort((a, b) => b.doneAt - a.doneAt);
    const isLate = (x) => x.promise && x.doneAt - x.promise > T.GRACE;
    const onT = done.filter((x) => !isLate(x)), late = done.filter(isLate);
    const chips = [["all", "All", over.length + up.length + uns.length], ["over", "Overdue", over.length], ["up", "Upcoming", up.length], ["uns", "Not scheduled", uns.length], ["done", "Done", done.length]];
    let html = `<div class="tp-chips">${chips.map(([k, l, n]) => `<button class="tp-chip ${S.tpChip === k ? "on" : ""}" data-tpchip="${k}">${l} <b>${n}</b>${k === "done" ? " ▾" : ""}</button>`).join("")}
      ${S.tpChip === "done" ? `<span class="tp-sep"></span><select class="tp-sel" id="tpdoneper" aria-label="Done in">${T.PERIODS.map(([k, l]) => `<option value="${k}" ${S.tpDonePer === k ? "selected" : ""}>${l}</option>`).join("")}</select><button class="tp-chip ok ${S.tpDoneSub === "on" ? "on" : ""}" data-tpdsub="on">✓ On time <b>${onT.length}</b></button><button class="tp-chip late ${S.tpDoneSub === "late" ? "on" : ""}" data-tpdsub="late">Late <b>${late.length}</b></button>` : ""}${cardLegend}</div>`;
    const gh = (t, cls, n) => `<div class="tp-gh ${cls}">${t}<span class="tp-hint"> · ${n}</span></div>`;
    if (S.tpChip === "all" && S.tpTab === "all") html += dossierRow(today, now);
    if (S.tpChip === "done") {
      const list = S.tpDoneSub === "on" ? onT : S.tpDoneSub === "late" ? late : done;
      html += gh("DONE · " + pName(S.tpDonePer).toUpperCase() + " · NEWEST FIRST", "", `${list.length} · On time ${onT.length} · Late ${late.length}`);
      html += list.map((x) => { const lt = isLate(x), r = x.reasons && x.reasons.length ? x.reasons[x.reasons.length - 1] : null; const dl = Math.round((T.sod(x.doneAt) - T.sod(x.promise)) / DAY); return taskRow(x, lt ? `Late · ${dl ? dl + " day" + (dl > 1 ? "s" : "") : "same day"}${r ? " · " + esc(r.r) + (x.outside ? ' <span class="tp-hint">(outside)</span>' : "") : ""}` : "✓ On time", lt ? "late" : "ok", now); }).join("") || '<div class="tp-empty">Nothing done in this period.</div>';
      return html;
    }
    if (S.tpChip === "all" || S.tpChip === "over") html += over.length ? gh("OVERDUE", "red", over.length + " · oldest first") + over.map((x) => taskRow(x, daysAgo(x.promise, now) + " days overdue", "low", now)).join("") : S.tpChip === "over" ? '<div class="tp-empty">Nothing overdue. 👍</div>' : "";
    if (S.tpChip === "all" || S.tpChip === "up") {
      const byDay = {}; up.forEach((x) => { const k = T.sod(x.due); (byDay[k] = byDay[k] || []).push(x); });
      const t0 = T.sod(now);
      html += Object.keys(byDay).map(Number).sort((a, b) => a - b).map((k) => gh("UPCOMING · " + (k === t0 ? "TODAY" : k === T.addDays(t0, 1) ? "TOMORROW · " + dday(k).toUpperCase() : dday(k).toUpperCase()), k === t0 ? "navy" : "", byDay[k].length) + byDay[k].map((x) => taskRow(x, clock(x.due), "", now)).join("")).join("");
      if (!up.length && S.tpChip === "up") html += '<div class="tp-empty">Nothing coming up.</div>';
    }
    if (S.tpChip === "all" || S.tpChip === "uns") html += uns.length ? gh("NOT SCHEDULED", "", uns.length + " · oldest first") + uns.map((x) => taskRow(x, x.created ? "added " + daysAgo(x.created, now) + " days ago" : "no date", x.created && x.created < now - c.grace * DAY ? "low" : "", now)).join("") : S.tpChip === "uns" ? '<div class="tp-empty">Every task has a date. 👍</div>' : "";
    if (S.tpChip === "all" && !over.length && !up.length && !uns.length) html += '<div class="tp-empty">No open tasks.</div>';
    return html;
  }
  function dossierRow(today, now) {
    const its = today.items || [];
    if (!its.length) return "";
    const done = its.filter((i) => i.s === "D").length, open = S.tpDosOpen;
    let h = `<button class="tp-task dos" data-tpdos="1"><span class="tp-tt">📋 Today's dossier <span class="tp-lbl dos">Dossier</span> <span class="${its.some((i) => i.s === "M") ? "low" : done === its.length ? "okc" : ""}" style="margin-left:8px">${done} of ${its.length} done</span>${its.length - done ? `<span class="tp-hint"> · ${its.length - done} not ticked yet</span>` : ""}</span><span></span><span class="tp-tlb grey ${open ? "on" : ""}">${open ? "Close ▴" : "Show " + its.length + " ▾"}</span></button>`;
    if (open) h += `<div class="tp-dlist${S._tpFresh === "dos" ? " in" : ""}">${its.map((i) => dosItem(i)).join("")}</div>`;
    return h;
  }
  const minLbl = (m) => { const h = Math.floor(m / 60) % 24, mm = m % 60; return ((h + 11) % 12) + 1 + ":" + String(mm).padStart(2, "0") + (h >= 12 ? " PM" : " AM"); };
  const dosItem = (i) => (i.s === "D"
    ? `<div class="tp-task done"><span class="tp-tt"><span class="okc">✓</span> ${esc(i.t)} <span class="tp-hint">· by ${minLbl(i.m)}</span>${i.p ? " 📷" : ""}</span><span class="tp-when okc">Done ${i.at ? clock(i.at) : ""}</span><span></span></div>`
    : i.s === "M" ? `<div class="tp-task miss"><span class="tp-tt"><span class="low">✗</span> ${esc(i.t)} <span class="tp-hint">· by ${minLbl(i.m)}</span></span><span class="tp-when lowd">Not done · ${esc(i.r || "No reason given")}</span><span></span></div>`
    : `<div class="tp-task norm"><span class="tp-tt">${esc(i.t)}</span><span class="tp-when">by ${minLbl(i.m)}</span><span></span></div>`);

  // ------------------------------------------------------------------ Timeline (same look as Plot files: day pills, time column)
  function timeline(x, now) {
    const L = [], tag = (t) => (/given by|created|added/i.test(t) ? ["grn", "Created"] : /moved|later|snooz|extend|new time|deadline changed|📅/i.test(t) ? ["yel", "Moved"] : /done|✓|completed|checked/i.test(t) ? ["grn", "Done"] : ["", "Update"]);
    (x.events || []).forEach((e) => { const t = String(e.text || ""); L.push({ at: e.at, tag: tag(t), h: esc(t) + (e.note ? ` <i class="tp-hint">“${esc(e.note)}”</i>` : "") }); });
    if (x.kind === "fu") (x.raw.moves || []).forEach((m) => L.push({ at: m.at, tag: ["yel", "Moved"], h: `Moved ${m.from ? dday(m.from) + " " + clock(m.from) : ""} → ${m.to ? dday(m.to) + " " + clock(m.to) : ""}${m.r ? " · reason: <b>" + esc(m.r) + "</b>" : ""}` }));
    else (x.reasons || []).forEach((r) => L.push({ at: r.at, tag: ["red", r.k === "move" ? "Moved" : "Reason"], h: `Why: <b>${esc(r.r)}</b>${r.o || x.outside ? ' <span class="tp-hint">(outside)</span>' : ""}${r.n ? ` <i class="tp-hint">“${esc(r.n)}”</i>` : ""}` }));
    if (x.taskId) {
      if (!msgs[x.taskId]) { msgs[x.taskId] = "loading"; RB.Store.msgs(x.taskId).then((l) => { msgs[x.taskId] = l || []; RB.soft(); }).catch(() => { msgs[x.taskId] = []; }); }
      if (Array.isArray(msgs[x.taskId])) msgs[x.taskId].forEach((m) => L.push({ at: m.at, tag: ["blu", m.kind === "photo" ? "Photo" : m.kind === "voice" ? "Voice" : "Note"], h: msgHtml(m) }));
    }
    L.sort((a, b) => b.at - a.at);
    const t0 = T.sod(now); let last = null;
    const lines = L.map((l) => { const d = T.sod(l.at), pill = d !== last ? `<div class="tp-day"><span>${d === t0 ? "Today · " : ""}${dday(l.at)}</span></div>` : ""; last = d; return `${pill}<div class="tp-ev"><span class="tp-hint">${clock(l.at)}</span><div><span class="tag ${l.tag[0]}">${l.tag[1]}</span> ${l.h}</div></div>`; }).join("");
    const top = [x.created ? `Created <b>${dshort(x.created)}</b>${x.kind === "given" || x.kind === "plot" ? " by " + esc(first(x.from)) : ""}` : "", x.promise ? `First promise <b>${dshort(x.promise)}</b>` : "No date yet", x.due && x.due !== x.promise ? `Now due <b class="${!x.done && x.promise < now ? "low" : ""}">${dshort(x.due)}</b>` : "", x.moves ? `Moved <b class="amb">${x.moves} time${x.moves > 1 ? "s" : ""}</b>` : ""].filter(Boolean).map((s) => `<span>${s}</span>`).join("");
    return `<div class="tp-tl${S._tpFresh === x.id ? " in" : ""}"><div class="tp-tltop">${top}<span class="sp"></span><span>Latest first</span></div>${lines || '<div class="tp-hint" style="padding:8px 0">Nothing on the Timeline yet.</div>'}${msgs[x.taskId] === "loading" ? '<div class="tp-hint">Loading notes…</div>' : ""}<div class="tp-hint" style="margin-top:6px">View only · call recordings stay on the phone.</div></div>`;
  }

  // ------------------------------------------------------------------ Dossier tab
  function dossierTab(uid, now) {
    const c = T.cfg(), [a, b] = T.range(S.tpDosPer, now), days = T.dosDays(uid, a, b, now), d = T.dosPct(days);
    const team = T.avg(T.people().map((m) => T.dosPct(T.dosDays(m.uid, a, b, now)).pct));
    const edit = RB.isOwner() ? `<button class="btnt" data-tpeditdos="${esc(uid)}">Edit dossier ›</button>` : "";
    if (!T.hasDossier(uid)) return `<div class="tp-empty">No dossier days from this person's phone yet. They come with app version 0.59 or later.${edit ? "<br><br>" + edit : ""}</div>`;
    const pick = days.find((x) => x.t === S.tpDosDay) || days.filter((x) => x.items.length && !x.future).slice(-1)[0] || days[0];
    S.tpDosDay = pick ? pick.t : null;
    const n = days.length, w = Math.max(320, n * 58);
    const bars = days.map((x, i) => {
      const pct = x.due ? x.done / x.due : 0, weak = x.due && pct * 100 < c.weakDay, today = x.t === T.sod(now);
      const fillH = today && !x.due ? (x.all ? x.done / x.all : 0) : pct;
      return `<button class="tp-db ${pick && x.t === pick.t ? "on" : ""}" data-tpdday="${x.t}" title="${dday(x.t)}"><span class="tp-dbn ${weak ? "low" : ""}">${x.none ? "–" : today && !x.due ? x.done + "/" + x.all : x.done + "/" + x.due}</span><span class="tp-dbb"><i class="${today && !x.due ? "today" : weak ? "red" : "grn"}" style="height:${Math.round(fillH * 100)}%"></i></span><span class="tp-dbl">${letter(x.t)}</span><span class="tp-dbd ${today ? "navy" : ""}">${new Date(x.t).getDate()}${today ? " · today" : ""}</span></button>`;
    }).join("");
    void w;
    const its = pick ? pick.items : [];
    const miss = {};
    days.forEach((x) => (x.items || []).forEach((i) => { if (i.s === "O") return; miss[i.t] = miss[i.t] || []; miss[i.t].push(i.s === "M"); }));
    const most = Object.entries(miss).filter((e) => e[1].some(Boolean)).sort((p, q) => q[1].filter(Boolean).length - p[1].filter(Boolean).length).slice(0, 6);
    return `<div class="tp-dhead">${edit}<select class="tp-sel" id="tpdosper" aria-label="Period">${T.PERIODS.map(([k, l]) => `<option value="${k}" ${S.tpDosPer === k ? "selected" : ""}>${l}</option>`).join("")}</select><b>${dshort(a)} – ${dshort(T.addDays(b, -1))}</b>
      <span class="tp-dpct">Dossier <span class="${d.pct != null && d.pct < c.weakDay ? "low" : ""}">${pc(d.pct)}</span></span><span class="tp-hint">· ${d.done} of ${d.due} done (days so far) · team average ${pc(team)}</span><span class="sp"></span><span class="tp-hint">Red day = under ${c.weakDay}% · weekly off day from the timetable</span></div>
      <div class="tp-dgrid"><div><div class="tp-dbars">${bars}</div>
      ${pick ? `<div class="tp-dsel"><b>${dday(pick.t)}</b><span class="tp-hint"> · ${pick.done} of ${pick.open && !pick.due ? pick.all : pick.due} done · click any day bar to change</span><span class="sp"></span><span class="tp-hint">📷 = proof photo sent</span></div>${its.map(dosItem).join("") || '<div class="tp-empty">No dossier that day.</div>'}` : ""}</div>
      <div class="tp-box tp-most"><div class="tp-ct">MOST MISSED · ${esc(pName(S.tpDosPer).toUpperCase())}</div>${most.map(([t, arr]) => `<div class="tp-mm"><b>${esc(t)}</b><div>${arr.map((m) => `<i class="${m ? "r" : "g"}"></i>`).join("")}<span class="low">missed ${arr.filter(Boolean).length} of ${arr.length} days</span></div></div>`).join("") || '<div class="tp-hint">Nothing missed. 👍</div>'}
      <div class="tp-hint" style="margin-top:8px">Red square = missed that day, green = done.</div></div></div>`;
  }

  // ------------------------------------------------------------------ Calendar (Day | Week, Working hours | Best fit, like the phone)
  const CK = { own: "task", given: "task", plot: "plot", dos: "dos", g: "goo" };
  function dayData(uid, its, d0, now) {
    const d1 = T.addDays(d0, 1), blocks = [], fus = [];
    its.forEach((x) => {
      if (x.kind === "fu") { if (x.due >= d0 && x.due < d1) fus.push(x); return; }
      if (x.due >= d0 && x.due < d1) { const s = (x.due - d0) / 60000; blocks.push({ k: CK[x.kind], s, e: s + Math.max(15, x.dur || 30), title: x.title, done: x.done, urgent: x.urgent && !x.done, x }); }
    });
    const day = D.days[uid] || {}, iso = T.isoDay(d0);
    const dd = (day.dosDays || []).find((x) => x.d === iso);
    if (dd) (dd.i || []).forEach((i) => blocks.push({ k: "dos", s: i.m, e: Math.max(i.e > i.m ? i.e : i.m + 30, i.m + 15), title: i.t, done: i.s === "D" }));
    else if (d0 > now) {
      const dow = (new Date(d0).getDay() + 6) % 7;
      ((D.dossier[uid] || {}).blocks || []).filter((b) => b.days & (1 << dow)).forEach((b) => blocks.push({ k: "dos", s: b.minute, e: Math.max(b.endMinute || b.minute + 30, b.minute + 15), title: b.title }));
    }
    const gs = (day.googleWeek || day.google || []).filter((g) => g.start >= d0 && g.start < d1);
    gs.forEach((g) => { const s = (g.start - d0) / 60000; blocks.push({ k: "goo", s, e: Math.max((g.end - d0) / 60000, s + 15), title: g.title || "Meeting" }); });
    // side by side when they overlap; a real overlap is a clash (red edge, ⚠)
    blocks.sort((a, b) => a.s - b.s || b.e - a.e);
    let cl = [], ce = -1;
    const flush = () => { const cols = []; cl.forEach((it) => { let c = cols.findIndex((end) => end <= it.s); if (c < 0) { c = cols.length; cols.push(0); } cols[c] = it.e; it.c = c; }); cl.forEach((it) => { it.n = cols.length; it.clash = cols.length > 1; }); cl = []; };
    blocks.forEach((it) => { if (cl.length && it.s >= ce) { flush(); ce = -1; } cl.push(it); ce = Math.max(ce, it.e); });
    if (cl.length) flush();
    fus.sort((a, b) => a.due - b.due);
    return { blocks, fus };
  }
  const calLegend = '<div class="tp-lg"><span><i class="c-task"></i>My task</span><span><i class="c-plot"></i>Plot file</span><span><i class="c-dos"></i>Dossier</span><span><i class="c-fu"></i>Follow-up</span><span><i class="c-goo"></i>Google</span><span><i class="c-clash"></i>⚠ Clash</span></div>';
  const hl = (h) => (h % 12 || 12) + (h < 12 || h === 24 ? " AM" : " PM");
  function calendar(uid, its, now) {
    const t = T.tt(uid), week = S.tpCalView === "week", fit = S.tpCalFit;
    const nav = week ? `${dshort(T.addDays(T.weekStart(now), 7 * S.tpCalWeek))} – ${dshort(T.addDays(T.weekStart(now), 7 * S.tpCalWeek + 6))}` : dday(T.addDays(T.sod(now), S.tpCalDay));
    const top = `<div class="tp-calnav"><div class="seg tp-seg"><button data-tpcv="day" class="${!week ? "on" : ""}">Day</button><button data-tpcv="week" class="${week ? "on" : ""}">Week</button></div><button class="btnt" data-tpcn="-1" aria-label="Back">‹</button><b>${nav}</b><button class="btnt" data-tpcn="1" aria-label="Next">›</button><button class="btnt" data-tpcn="0">Today</button>
      <div class="seg tp-seg"><button data-tpfit="0" class="${!fit ? "on" : ""}">⊟ Working hours</button><button data-tpfit="1" class="${fit ? "on" : ""}">⇕ Best fit</button></div>${calLegend}</div>`;
    return top + (week ? weekCal(uid, its, now, t, fit) : dayCal(uid, its, now, t, fit));
  }
  function dayCal(uid, its, now, t, fit) {
    const d0 = T.addDays(T.sod(now), S.tpCalDay), { blocks, fus } = dayData(uid, its, d0, now), off = T.isOff(uid, d0);
    const ws = Math.floor(t.start / 60), we = Math.ceil(t.end / 60), H0 = 0, H1 = 24, VIEW = 460;
    const byH = {}; fus.forEach((f) => { const h = new Date(f.due).getHours(); (byH[h] = byH[h] || []).push(f); });
    const base = fit ? 40 : Math.max(26, VIEW / Math.max(1, we - ws));
    const hts = []; for (let h = H0; h < H1; h++) hts.push(fit ? Math.max(base, (byH[h] || []).length * 30 + 8) : base);
    const tops = [0]; hts.forEach((x) => tops.push(tops[tops.length - 1] + x));
    const y = (min) => { const h = Math.min(23, Math.floor(min / 60)); return tops[h] + (hts[h] * (min - h * 60)) / 60; };
    let s = "";
    for (let h = H0; h < H1; h++) { const out = off || h < ws || h >= we, grown = fit && (byH[h] || []).length > 1; s += `<div class="tp-hr ${grown ? "grown" : out ? "out" : ""}" style="top:${tops[h]}px;height:${hts[h]}px"><span>${hl(h)}</span></div>`; }
    const bk = blocks.map((b) => { const top = y(b.s), hh = Math.max(y(Math.min(b.e, 1440)) - top - 2, 22), one = hh < 36; return `<div class="tp-blk c-${b.k}${b.done ? " done" : ""}${b.clash ? " clash" : ""}${one ? " one" : ""}" style="top:${top + 1}px;height:${hh}px;left:calc(${(b.c / b.n) * 100}% + 2px);width:calc(${100 / b.n}% - 4px)" title="${esc(b.title)}">${b.clash ? "⚠ " : ""}${b.done ? "✓ " : ""}${esc(b.title)}<small>${minLbl(Math.round(b.s))} – ${minLbl(Math.round(Math.min(b.e, 1439)))}${b.k === "dos" ? " · Dossier" : b.k === "goo" ? " · Google" : ""}</small></div>`; }).join("");
    let fuH = "";
    for (let h = H0; h < H1; h++) {
      const L = byH[h]; if (!L) continue;
      if (fit) { let prev = tops[h]; L.forEach((f, i) => { const want = y((f.due - d0) / 60000) + 4, latest = tops[h] + hts[h] - 4 - 30 * (L.length - i); const yy = Math.max(prev, Math.min(want, latest)); prev = yy + 30; fuH += `<div class="tp-fu c-fu${f.done ? " done" : ""}" style="top:${yy}px">${f.done ? "✓ " : ""}${clock(f.due)} · ${esc(f.title)}</div>`; }); }
      else fuH += `<div class="tp-fu c-fu tp-hov" style="top:${tops[h] + 4}px" tabindex="0">📞 ${L.length > 1 ? `<b>${L.length}</b> follow-ups · ${esc(L.map((f) => f.raw.name || f.title).join(", "))}` : clock(L[0].due) + " · " + esc(L[0].title)}${L.length > 1 ? `<span class="tp-tip ${h > 14 ? "up" : ""}"><b>${hl(h)} · ${L.length} follow-ups</b>${L.map((f) => `<br>${clock(f.due)} · ${esc(f.title)}`).join("")}</span>` : ""}</div>`;
    }
    const nowL = S.tpCalDay === 0 ? `<div class="tp-now" style="top:${y((now - d0) / 60000)}px"></div>` : "";
    const scrollTo = fit ? tops[Math.max(0, ws - 1)] : tops[ws];
    const key = uid + "|" + S.tpCalDay + "|" + fit;
    if (S._tpCalKey !== key) { S._tpCalKey = key; S._tpCalScroll = scrollTo; }
    return `<div class="tp-lanes"><span>MY WORK</span><span class="navy">FOLLOW-UPS</span><span class="tp-hint">${off ? "Day off" : fit ? "Busy hours grow (light peach)" : `Working hours ${hl(ws)} – ${hl(we)} fill the screen`}</span></div>
      <div class="tp-daybox" id="tpdaybox" style="height:${VIEW}px"><div class="tp-dayin" style="height:${tops[24]}px"><div class="tp-mine">${s}<div class="tp-blks">${bk}</div></div><div class="tp-fus">${fuH}</div>${nowL}</div></div>`;
  }
  function weekCal(uid, its, now, t, fit) {
    const a = T.addDays(T.weekStart(now), 7 * S.tpCalWeek), days = Array.from({ length: 7 }, (_, i) => T.addDays(a, i));
    let ws = Math.floor(t.start / 60), we = Math.ceil(t.end / 60);
    const data = days.map((d0) => (T.isOff(uid, d0) ? null : dayData(uid, its, d0, now)));
    data.forEach((dd) => dd && dd.blocks.forEach((b) => { ws = Math.min(ws, Math.floor(b.s / 60)); we = Math.max(we, Math.min(24, Math.ceil(b.e / 60))); }));
    data.forEach((dd) => dd && dd.fus.forEach((f) => { const h = new Date(f.due).getHours(); ws = Math.min(ws, h); we = Math.max(we, h + 1); }));
    const VIEW = 430, nh = Math.max(1, we - ws);
    const maxAt = (h) => Math.max(0, ...data.map((dd) => (dd ? dd.fus.filter((f) => new Date(f.due).getHours() === h).length : 0)));
    let hts = []; for (let h = ws; h < we; h++) hts.push(fit && maxAt(h) > 1 ? 1 + (maxAt(h) - 1) * 0.35 : 1);
    const sum = hts.reduce((x, y) => x + y, 0); hts = hts.map((x) => (x / sum) * VIEW);
    const tops = [0]; hts.forEach((x) => tops.push(tops[tops.length - 1] + x));
    const y = (min) => { const h = Math.max(ws, Math.min(we - 1, Math.floor(min / 60))); const i = h - ws; return tops[i] + (hts[i] * Math.max(0, Math.min(60, min - h * 60))) / 60; };
    let grid = ""; for (let h = ws; h < we; h++) grid += `<div class="tp-hr ${fit && maxAt(h) > 1 ? "grown" : ""}" style="top:${tops[h - ws]}px;height:${hts[h - ws]}px"><span>${hl(h)}</span></div>`;
    const t0 = T.sod(now);
    const head = days.map((d0, i) => { const dd = data[i]; if (!dd) return `<div class="tp-wh off">${dday(d0).replace(/ \w+$/, "")}<small>OFF</small></div>`; const hrs = dd.blocks.filter((b) => b.k !== "goo").reduce((s, b) => s + (b.e - b.s), 0) / 60; return `<button class="tp-wh ${d0 === t0 ? "today" : ""}" data-tpwday="${i}">${dday(d0).replace(/ \w+$/, "")}${d0 === t0 ? " · Today" : ""}<small>${Math.round(hrs * 2) / 2}h booked · 📞 ${dd.fus.length}</small></button>`; }).join("");
    const cols = days.map((d0, i) => {
      const dd = data[i];
      if (!dd) return '<div class="tp-wc off"></div>';
      const bl = dd.blocks.map((b) => { const top = y(b.s), hh = Math.max(y(Math.min(b.e, we * 60)) - top - 2, 18); const ln = Math.floor((hh - 4) / 13); return `<div class="tp-wb c-${b.k}${b.done ? " done" : ""}${b.clash ? " clash" : ""}${ln < 2 ? " one" : ""}" style="top:${top + 1}px;height:${hh}px;left:calc(${(b.c / b.n) * 100}% + 1px);width:calc(${100 / b.n}% - 2px);-webkit-line-clamp:${Math.max(1, ln)}" title="${esc(b.title)} · ${minLbl(Math.round(b.s))}">${b.clash ? "⚠" : ""}${b.done ? "✓ " : ""}${esc(b.title)}</div>`; }).join("");
      const byH = {}; dd.fus.forEach((f) => { const h = new Date(f.due).getHours(); (byH[h] = byH[h] || []).push(f); });
      const mk = Object.keys(byH).map(Number).map((h) => `<div class="tp-mk c-fu tp-hov" tabindex="0" style="top:${y(h * 60) + 3}px">📞${byH[h].length > 1 ? `<b>${byH[h].length}</b>` : ""}<span class="tp-tip ${i >= 3 ? "left" : ""} ${h - ws > nh / 2 ? "up" : ""}"><b>${dday(d0).replace(/ \w+$/, "")} · ${hl(h)} · ${byH[h].length} follow-up${byH[h].length > 1 ? "s" : ""}</b>${byH[h].map((f) => `<br>${clock(f.due)} · ${esc(f.title)}`).join("")}</span></div>`).join("");
      const nowL = d0 === t0 && S.tpCalWeek === 0 ? `<div class="tp-now" style="top:${y((now - d0) / 60000)}px"></div>` : "";
      return `<div class="tp-wc ${d0 === t0 ? "today" : ""}"><div class="tp-wblk">${bl}</div><div class="tp-wfu">${mk}</div>${nowL}</div>`;
    }).join("");
    return `<div class="tp-week"><div class="tp-whead"><span></span>${head}</div><div class="tp-wbody" style="height:${VIEW}px"><div class="tp-wgrid">${grid}</div><span></span>${cols}</div></div>
      <div class="tp-hint" style="margin-top:4px">📞3 = 3 follow-ups in that hour · hover shows who · click a day name for the Day view · faded ✓ = done</div>`;
  }

  // ------------------------------------------------------------------ section
  RB.sections.app = {
    render(tab) {
      const sub = "View only · from everyone's phone";
      if (tab === "person") return Object.assign(personTab(), { title: "Team Performance", sub });
      return Object.assign(overview(), { title: "Team Performance", sub });
    },
    bind(root) {
      S._tpAnim = null; S._tpFresh = null;
      const db = root.querySelector("#tpdaybox");
      if (db) { db.scrollTop = S._tpCalScroll || 0; db.onscroll = () => (S._tpCalScroll = db.scrollTop); }
      const sel = (id, k) => { const e = root.querySelector(id); if (e) e.onchange = () => { S[k] = e.value; RB.render(); }; };
      sel("#tpdoneper", "tpDonePer"); sel("#tpdosper", "tpDosPer");
      root.querySelectorAll("[data-tpvoice]").forEach((v) => {
        const id = v.dataset.tpvoice, m = findMsg(id); if (!m) return;
        const play = v.querySelector(".plplay"), sp = v.querySelector(".plspeed"), R = [1, 1.5, 2];
        const a = players[id] || (players[id] = new Audio("data:audio/mp4;base64," + b64(m.data)));
        a.onplay = () => { const b = document.querySelector(`[data-tpvoice="${id}"] .plplay`); if (b) b.textContent = "❚❚"; };
        a.onpause = a.onended = () => { const b = document.querySelector(`[data-tpvoice="${id}"] .plplay`); if (b) b.textContent = "▶"; };
        play.onclick = (ev) => { ev.stopPropagation(); Object.entries(players).forEach(([k, o]) => k !== id && o.pause()); a.paused ? a.play() : a.pause(); };
        sp.onclick = (ev) => { ev.stopPropagation(); a.playbackRate = R[(R.indexOf(a.playbackRate) + 1) % R.length]; sp.textContent = a.playbackRate + "x"; };
      });
      root.querySelectorAll("[data-tpimg]").forEach((b) => (b.onclick = (ev) => { ev.stopPropagation(); const m = findMsg(b.dataset.tpimg); if (m && RB.photoView) RB.photoView("data:image/jpeg;base64," + b64(m.data)); }));
      const tx = document.querySelector(".tabx");   // the period chips live in the shell tab row, outside root
      root.onclick = (ev) => {
        const e = ev.target.closest("[data-tpper],[data-tpsort],[data-tpsel],[data-tpclose],[data-tpopen],[data-tpreptab],[data-tpallold],[data-tpperson],[data-tptab],[data-tpchip],[data-tpdsub],[data-tptl],[data-tpdos],[data-tpdday],[data-tpcv],[data-tpcn],[data-tpfit],[data-tpwday],[data-tpeditdos],[data-tpwa]");
        if (!e || !(root.contains(e) || (tx && tx.contains(e)))) return;
        const ds = e.dataset;
        if (ds.tpper) S.tpPeriod = ds.tpper;
        else if (ds.tpsort) S.tpSort = S.tpSort[0] === ds.tpsort ? [ds.tpsort, -S.tpSort[1]] : [ds.tpsort, ds.tpsort === "name" ? 1 : -1];
        else if (ds.tpsel) { if (!S.tpSel) S._tpAnim = "open"; S.tpSel = ds.tpsel; S.tpAllOld = false; }
        else if (ds.tpclose) { S.tpSel = null; S._tpAnim = "close"; }
        else if (ds.tpeditdos) { S.dosSel = ds.tpeditdos; S.dosEdit = null; RB.go("team", "dossier"); return; }
        else if (ds.tpwa) { waPicker(ds.tpwa); return; }
        else if (ds.tpopen) { S.tpPerson = ds.tpopen; S.tpTab = "all"; S.tpChip = "all"; S.tpSel = null; RB.go("app", "person"); return; }
        else if (ds.tpreptab) { S.tpRepTab = ds.tpreptab; S.tpAllOld = false; }
        else if (ds.tpallold) S.tpAllOld = !S.tpAllOld;
        else if (ds.tpperson) { S._tpFresh = "body"; S.tpPerson = ds.tpperson; S.tpOpen = null; S.tpDosDay = null; }
        else if (ds.tptab) { S._tpFresh = "body"; S.tpTab = ds.tptab; S.tpOpen = null; }
        else if (ds.tpchip) { S._tpFresh = "body"; S.tpChip = ds.tpchip; S.tpDoneSub = ""; S.tpOpen = null; }
        else if (ds.tpdsub) S.tpDoneSub = S.tpDoneSub === ds.tpdsub ? "" : ds.tpdsub;
        else if (ds.tptl) { S.tpOpen = S.tpOpen === ds.tptl ? null : ds.tptl; S._tpFresh = S.tpOpen; }
        else if (ds.tpdos) { S.tpDosOpen = !S.tpDosOpen; S._tpFresh = "dos"; }
        else if (ds.tpdday) S.tpDosDay = Number(ds.tpdday);
        else if (ds.tpcv) S.tpCalView = ds.tpcv;
        else if (ds.tpcn) { const v = Number(ds.tpcn), k = S.tpCalView === "week" ? "tpCalWeek" : "tpCalDay"; S[k] = v === 0 ? 0 : S[k] + v; }
        else if (ds.tpfit) S.tpCalFit = ds.tpfit === "1";
        else if (ds.tpwday) { const now = Date.now(); S.tpCalDay = Math.round((T.addDays(T.weekStart(now), 7 * S.tpCalWeek + Number(ds.tpwday)) - T.sod(now)) / DAY); S.tpCalView = "day"; }
        ev.preventDefault();
        RB.render();
      };
      if (tx) tx.onclick = root.onclick;
    },
  };
  // Esc closes the person report (when no window is open on top).
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape" || S.sec !== "app" || S.view !== "people" || !S.tpSel) return;
    const m = document.getElementById("modal"); if (m && m.innerHTML.trim()) return;
    S.tpSel = null; S._tpAnim = "close"; RB.render();
  });
})();
