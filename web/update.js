/* One-tap update, like the phone app: every publish writes version.json; when it changes, a bar offers "Update". */
(function () {
  let mine = null;
  async function check() {
    try {
      const r = await fetch("version.json?t=" + Date.now(), { cache: "no-store" });
      if (!r.ok) return;
      const v = (await r.json()).version;
      if (!v) return;
      if (mine === null) { mine = v; return; }
      if (v !== mine) show();
    } catch (e) { /* offline or preview: nothing to do */ }
  }
  function show() {
    if (document.getElementById("updbar")) return;
    const b = document.createElement("div");
    b.id = "updbar";
    b.innerHTML = '<span>New version of the meeting app is ready</span><button type="button">Update</button>';
    b.querySelector("button").onclick = () => location.reload();
    document.body.appendChild(b);
  }
  check();
  setInterval(check, 5 * 60 * 1000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) check(); });
})();
