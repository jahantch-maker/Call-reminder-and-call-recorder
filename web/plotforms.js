/* Plot files forms printed by the app, in the locked layouts (sop/forms-revised).
 * RG-03 Documents Checklist is built from the imported SOP's Form items (no item list in this code) and this file's ticks.
 * RG-05 (from the plot's approved cheque lines) and RG-14 (its rows not yet Verified) print from the plot file too.
 * Docs > SOPs > Forms shows the locked samples of RG-05, RG-07, RG-13, RG-14 and RG-15. */
(function () {
  "use strict";
  const RB = window.RB, esc = RB.esc;
  const F = (RB.plotForms = {});

  /** Locked sample previews (plot 245, sample data), one static file per form. */
  F.SAMPLES = { "RG-05": "forms/rg-05-sample.html", "RG-07": "forms/rg-07-sample.html", "RG-13": "forms/rg-13-sample.html", "RG-14": "forms/rg-14-sample.html", "RG-15": "forms/rg-15-sample.html" };
  /** Page heights for the preview frame: A4 forms are taller than the half-A4 slips. */
  F.HEIGHT = { "RG-03": 1250, "RG-05": 1250, "RG-07": 1000, "RG-13": 560, "RG-14": 560, "RG-15": 560 };

  /** RG-03 v3 (# | Document | In file | Date). f = the plot file or null for a blank form; items = [{stage, no, name, status, at}]. */
  F.rg03 = (f, items, printedAt) => {
    const gs = Array.from(new Set(items.map((i) => i.stage || i.group || "")));
    const ok = (i) => i.status === "verified" || i.status === "received";
    let n = 0;
    const body = gs.map((g) => `<tr class="g"><td colspan="4">${esc(String(g).toUpperCase())}</td></tr>` + items.filter((i) => (i.stage || i.group || "") === g).map((i) => { n++; return `<tr><td class="c">${esc(String(i.no || n))}</td><td>${esc(i.name)}</td><td class="c">${ok(i) ? "☑" : "☐"}</td><td>${ok(i) && i.at ? esc(RB.fmtShort(i.at)) : ""}</td></tr>`; }).join("")).join("");
    const fld = (v) => (v ? `<b style="flex:1;border-bottom:1px solid #444;padding-left:4px">${esc(v)}</b>` : "<i></i>");
    return `<!doctype html><html><head><meta charset="utf-8"><title>Documents Checklist</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap" rel="stylesheet">
<style>@page{size:A4;margin:10mm}body{font-family:Inter,Arial,sans-serif;font-size:10.5px;color:#111;margin:0}
.hd{display:flex;justify-content:space-between;align-items:flex-end;border-bottom:2px solid #111;padding-bottom:5px}.org{font-weight:700;font-size:12px}.sub{font-size:10px;color:#555}
h1{text-align:center;font-size:17px;margin:8px 0 6px;letter-spacing:1px}
.top{display:flex;gap:20px;margin-bottom:8px;font-size:12px}.top span{flex:1;display:flex;gap:6px}.top i{flex:1;border-bottom:1px solid #444}
table{width:100%;border-collapse:collapse}th,td{border:1px solid #888;padding:4px 6px;vertical-align:top}th{background:#e9e9e9;font-size:9.5px}
.c{text-align:center}.rm{font-size:9.5px;color:#333}tr.g td{background:#f3f3f3;font-weight:700;font-size:9.5px;letter-spacing:.4px}
.note{font-size:9px;color:#444;margin-top:6px}@media screen{body{padding:14px 16px;background:#fff}}</style></head><body>
<div class="hd"><div><div class="org">RAFIQ BUILDERS &amp; DEVELOPERS · ROYAL GARDEN, GOJRA</div><div class="sub">Plot files · glue inside the front cover</div></div><div class="sub">Form RG-03 · v3${printedAt ? " · printed " + esc(RB.fmtShort(printedAt)) : ""}</div></div>
<h1>DOCUMENTS CHECKLIST</h1>
<div class="top"><span>Plot No.${fld(f && f.plot)}</span><span style="flex:2">Owner name${fld(f && f.owner)}</span></div>
<table><thead><tr><th style="width:24px">#</th><th>Document</th><th style="width:60px">In file</th><th style="width:110px">Date</th></tr></thead><tbody>${body}</tbody></table>
<div class="note">Filled only by the Document Officer: tick and date each paper the day it goes into the file. Shows at a glance which papers are in the file; full details (who, receipt numbers, approvals) are in the app.</div>
</body></html>`;
  };

  const amt = (n) => Number(n || 0).toLocaleString("en-US");
  const dd = (d) => { if (!d) return ""; const x = new Date(String(d).length === 10 ? d + "T12:00:00" : d); return x.getDate() + "-" + x.toLocaleDateString("en-GB", { month: "short" }) + "-" + x.getFullYear(); };
  const when = (t) => dd(t) + ", " + new Date(t).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });

  /** RG-05 v2 in the locked printed-sample layout. d = {plot, owner, title, items:[{name} | {name, cols:[{name,kind}], total, rows:[{col: value}]}], prepared, approved, phone}.
   *  Normal items print as tick lines; repeating items (cheque lines) print as their own table with a Total row. */
  F.rg05 = (d) => {
    const tbl = (it) => {
      const cols = it.cols || [], am = cols.find((c) => c.kind === "amount"), rest = cols.filter((c) => c !== am);
      let tot = 0;
      const rows = (it.rows || []).map((r, i) => { tot += Number((am && r[am.name]) || 0); return `<tr><td><span class="b"></span></td><td>${i + 1}</td>${rest.map((c) => `<td>${esc(c.kind === "date" ? dd(r[c.name]) : r[c.name] || "")}</td>`).join("")}${rest.length < 2 ? "<td></td>" : ""}${am ? `<td class="n">${amt(r[am.name])}</td>` : ""}</tr>`; }).join("");
      return `<div class="h" style="margin-top:14px">${esc(it.name)}</div><table><tr><th style="width:24px"></th><th style="width:30px">#</th>${rest.map((c, i) => `<th${i === 0 && rest.length > 1 ? ' style="width:130px"' : i === 0 ? ' style="width:130px"' : ""}>${esc(c.name)}</th>`).join("")}${rest.length < 2 ? "<th></th>" : ""}${am ? `<th class="n" style="width:120px">${esc(am.name)} (Rs)</th>` : ""}</tr>${rows}${it.total && am ? `<tr class="t"><td></td><td></td><td>Total</td>${rest.length < 2 ? "<td></td>" : rest.slice(1).map(() => "<td></td>").join("")}<td class="n">${amt(tot)}</td></tr>` : ""}</table>`;
    };
    return `<!doctype html><html><head><meta charset="utf-8"><title>Required Attachments</title>
<link href="https://fonts.googleapis.com/css2?family=Tinos:wght@700&family=Arimo:wght@400;700&display=swap" rel="stylesheet">
<style>@page{size:A4;margin:16mm 18mm}body{font-family:Arimo,Arial,sans-serif;font-size:13.5px;color:#111;margin:0;position:relative}
h1{font-family:Tinos,'Times New Roman',serif;text-align:center;font-size:19px;text-decoration:underline;margin:10px 90px 16px;line-height:1.35}
.code{position:absolute;top:0;right:0;font-size:10px;color:#666}
.r{display:flex;gap:24px;margin:0 0 16px}.f{flex:1;display:flex;align-items:flex-end;white-space:nowrap}.f i{flex:1;border-bottom:1px solid #222;margin-left:4px;height:14px}
p{line-height:1.6;margin:10px 0 10px}.c{margin:0 0 9px;display:flex;align-items:flex-end}
.b{display:inline-block;width:10px;height:10px;border:1px solid #111;margin-right:6px;vertical-align:-1px}.h{margin:12px 0 6px;font-weight:700}
.note{font-size:13px;color:#333;margin-top:30px;line-height:1.6}
table{width:100%;table-layout:fixed;border-collapse:collapse;margin:6px 0 4px;font-size:14px}th,td{border-bottom:1px solid #999;padding:4px 6px;text-align:left}th{font-weight:700;border-bottom:1.5px solid #222;font-size:13px}td.n,th.n{text-align:right}tr.t td{border-bottom:none;font-weight:700}
.v{font-weight:700;border-bottom:1px solid #222;padding:0 6px 1px;flex:1}@media screen{body{padding:18px 22px;background:#fff}}</style></head><body>
<div class="code">RG-05 · v2</div>
<h1>${esc(String(d.title || "").toUpperCase())}</h1>
<div class="r"><span class="f">Plot No&nbsp;<span class="v">${esc(d.plot || "")}</span></span><span class="f" style="flex:2">Owner Name&nbsp;<span class="v">${esc(d.owner || "")}</span></span></div>
<p>This checklist serves as a reminder of all documents and payments that must be submitted along with the floor plan. Providing all items listed below will help ensure smooth and timely floor plan approval, after which the plot possession process for house construction will continue.</p>
${(d.items || []).map((it) => (it.cols ? tbl(it) : `<div class="c"><span class="b"></span>${esc(it.name)}</div>`)).join("")}
<div style="margin-top:20px;font-size:13px;line-height:1.8">${d.prepared ? `Prepared by: <b>${esc((d.prepared.desig ? d.prepared.desig + ", " : "") + (d.prepared.name || ""))}</b> · ${esc(dd(d.prepared.at))}` : ""}${d.approved ? `<br>Approved in app by: <b>${esc(d.approved.name || "")}</b> · ${esc(when(d.approved.at))}` : ""}</div>
<div class="r" style="margin-top:28px"><span class="f" style="flex:0 0 55%">Accounts Head signature<i></i></span><span class="f">Date<i></i></span></div>
<div class="note" style="margin-top:14px">For any question about this list, contact the Accounts Officer: ${esc(d.phone || "______________________")}</div>
</body></html>`;
  };

  /** RG-14 v1 Still pending slip, half A4, in the locked layout. Wording (message, closing) comes from the SOP's Slips tab. */
  F.rg14 = (d) => `<!doctype html><html><head><meta charset="utf-8"><title>Still Pending</title>
<link href="https://fonts.googleapis.com/css2?family=Tinos:wght@400;700&display=swap" rel="stylesheet">
<style>@page{size:A4;margin:16mm 20mm}body{font-family:Tinos,'Times New Roman',serif;font-size:14px;color:#111;margin:0}
.slip{height:128mm;position:relative;box-sizing:border-box;padding-top:4mm}.code{position:absolute;top:4mm;right:0;font-size:10px;color:#666}
h1{text-align:center;font-size:19px;text-decoration:underline;margin:0 0 4px}.sub{text-align:center;font-style:italic;margin-bottom:18px}
.r{display:flex;gap:24px;margin:0 0 12px}.f{flex:1;display:flex;align-items:flex-end;white-space:nowrap}.f i{flex:1;border-bottom:1px solid #222;margin-left:6px;height:16px;font-style:normal;padding-left:4px}
.h{font-weight:700;margin:12px 0 6px}ol{margin:0 0 10px;padding-left:24px}ol li{margin:0 0 4px}.note{font-size:12.5px;line-height:1.5;margin-top:10px}
.fill{font-family:Arial,sans-serif;font-size:13px;color:#1a3a8a}@media screen{body{padding:18px 22px;background:#fff}}</style></head><body>
<div class="slip"><div class="code">RG-14 · v1</div><h1>STILL PENDING</h1><div class="sub">${esc(d.stage || "")}</div>
<div class="r"><span class="f">Plot No<i class="fill">${esc(d.plot || "")}</i></span><span class="f" style="flex:2">Owner Name<i class="fill">${esc(d.owner || "")}</i></span></div>
<div class="r"><span class="f">Received on<i class="fill">${d.receivedOn ? esc(new Date(d.receivedOn).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })) : ""}</i></span><span class="f" style="flex:2">Front Desk<i class="fill">${esc(d.frontDesk || "")}</i></span></div>
<div class="r"><span class="f">Submitted by<i class="fill">${esc(d.submittedBy || "")}</i></span></div>
<div class="h">${esc(d.message || "")}</div>
<ol class="fill">${(d.items || []).map((x) => `<li>${esc(x)}</li>`).join("")}</ol>
<div class="note">${esc(d.closing || "")}</div></div></body></html>`;

  /** Prints a whole HTML page through a hidden frame (A4, the browser's print window also saves PDF). */
  F.print = (html) => {
    const fr = document.createElement("iframe"); fr.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0"; document.body.appendChild(fr);
    fr.onload = () => { setTimeout(() => { fr.contentWindow.focus(); fr.contentWindow.print(); setTimeout(() => fr.remove(), 4000); }, 300); };
    fr.srcdoc = html;
  };
})();
