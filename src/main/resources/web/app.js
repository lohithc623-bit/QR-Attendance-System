"use strict";
/* QR Attendance - mobile web app. Plain JS, no build step. Talks only to /api/* on the same server. */

const $app = document.getElementById("app");
const $top = document.getElementById("topbar");
const $who = document.getElementById("who");
const $flash = document.getElementById("flash");

const state = { token: sessionStorage.getItem("token"), role: sessionStorage.getItem("role"), name: sessionStorage.getItem("name") };
let timers = [];       // intervals/timeouts owned by the current screen
let scanner = null;    // Html5Qrcode instance
let notice = "";       // message shown on the login screen (e.g. session expired)

if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});

// ---------- tiny helpers ----------
function el(tag, props = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") n.className = v;
    else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) n.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat()) n.append(k instanceof Node ? k : document.createTextNode(k ?? ""));
  return n;   // text always goes through createTextNode / textContent, so server data cannot inject HTML
}
function every(ms, fn) { const t = setInterval(fn, ms); timers.push(t); return t; }
async function cleanup() {
  timers.forEach(clearInterval); timers = [];
  if (scanner) { try { await scanner.stop(); } catch (e) {} try { scanner.clear(); } catch (e) {} scanner = null; }
}
function today() { const d = new Date(); return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); }

async function api(path, { method = "GET", body, raw } = {}) {
  const res = await fetch(path, {
    method,
    headers: { ...(body ? { "Content-Type": "application/json" } : {}), ...(state.token ? { Authorization: "Bearer " + state.token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401 && path !== "/api/login") { logout(true); throw new Error("Session expired. Please log in again."); }
  if (raw) { if (!res.ok) throw new Error("Request failed"); return res; }
  let data = {};
  try { data = await res.json(); } catch (e) {}
  if (!res.ok) { const err = new Error(data.error || "Request failed"); err.code = data.code; throw err; }
  return data;
}

// ---------- feedback: colour flash + beep + vibration ----------
let audioCtx;
function beep(good) {
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    const o = audioCtx.createOscillator(), g = audioCtx.createGain();
    o.frequency.value = good ? 880 : 220; o.type = good ? "sine" : "square";
    g.gain.value = 0.15; o.connect(g); g.connect(audioCtx.destination);
    o.start(); o.stop(audioCtx.currentTime + (good ? 0.12 : 0.35));
  } catch (e) {}
  if (navigator.vibrate) navigator.vibrate(good ? 80 : [120, 60, 120]);
}
function flash(good, text) {
  $flash.className = "show " + (good ? "good" : "bad");
  $flash.textContent = text;
  beep(good);
  setTimeout(() => { $flash.className = ""; }, 1100);
}

// ---------- theme ----------
function applyTheme(t) { if (t) document.documentElement.dataset.theme = t; else delete document.documentElement.dataset.theme; }
try { applyTheme(localStorage.getItem("theme")); } catch (e) {}
document.getElementById("themeBtn").onclick = () => {
  const dark = document.documentElement.dataset.theme === "dark" ||
    (!document.documentElement.dataset.theme && matchMedia("(prefers-color-scheme: dark)").matches);
  const next = dark ? "light" : "dark";
  applyTheme(next); try { localStorage.setItem("theme", next); } catch (e) {}
};
document.getElementById("logoutBtn").onclick = () => { api("/api/logout", { method: "POST" }).catch(() => {}); logout(false); };

function logout(expired) {
  state.token = state.role = state.name = null; sessionStorage.clear();
  notice = expired ? "Session expired. Please log in again." : "";
  route();
}

// ---------- routing ----------
async function route() {
  await cleanup();
  $app.replaceChildren();
  if (!state.token) { $top.hidden = true; return loginView(); }
  $top.hidden = false; $who.textContent = state.name;
  return state.role === "FACULTY" ? facultyView() : studentView();
}

// ---------- login ----------
function loginView() {
  const err = el("div", { class: "err", role: "alert" }, notice);
  notice = "";
  const role = el("select", {}, el("option", { value: "STUDENT" }, "Student"), el("option", { value: "FACULTY" }, "Faculty"));
  const user = el("input", { autocomplete: "username", placeholder: "Register number", autocapitalize: "characters" });
  const pass = el("input", { type: "password", autocomplete: "current-password", placeholder: "Password" });
  role.onchange = () => { user.placeholder = role.value === "STUDENT" ? "Register number" : "Faculty ID"; };
  const btn = el("button", { style: "width:100%;margin-top:1rem" }, "Login");
  const submit = async () => {
    btn.disabled = true; err.textContent = "";
    try {
      const d = await api("/api/login", { method: "POST", body: { username: user.value.trim(), password: pass.value, role: role.value } });
      state.token = d.token; state.role = d.role; state.name = d.name;
      sessionStorage.setItem("token", d.token); sessionStorage.setItem("role", d.role); sessionStorage.setItem("name", d.name);
      route();
    } catch (e) { err.textContent = e.message; btn.disabled = false; }
  };
  btn.onclick = submit; pass.onkeydown = (e) => { if (e.key === "Enter") submit(); };
  $app.append(el("div", { class: "card" },
    el("h2", {}, "QR Attendance"), el("p", { class: "muted" }, "Sign in as a student or faculty member."),
    el("label", {}, "I am a"), role, el("label", {}, "ID"), user, el("label", {}, "Password"), pass, btn, err));
}

// ---------- student ----------
async function studentView() {
  const me = await api("/api/student/me");
  const low = me.overall < 75 && me.subjects.some((s) => s.total > 0);
  const ring = el("div", { class: "ring", style: `--p:${me.overall};--c:${low ? "var(--bad)" : "var(--ok)"}` }, el("span", {}, me.overall + "%"));

  const qrImg = el("img", { class: "qr", alt: "Your attendance QR code" });
  const bar = el("div"); const countdown = el("div", { class: "countdown" }, bar);
  const qrNote = el("p", { class: "muted center" }, "Loading your QR…");
  let loading = false;
  async function refreshQr() {
    if (loading) return; loading = true;
    try {
      const info = await api("/api/student/qr");
      const res = await api("/api/student/qr.png", { raw: true });
      const url = URL.createObjectURL(await res.blob());
      const old = qrImg.src; qrImg.src = url; if (old.startsWith("blob:")) URL.revokeObjectURL(old);
      qrNote.textContent = "This code changes every " + info.stepSeconds + " seconds. Screenshots stop working.";
      bar.style.width = "100%"; qrState.left = info.secondsLeft; qrState.step = info.stepSeconds;
    } catch (e) { qrNote.textContent = e.message; } finally { loading = false; }
  }
  const qrState = { left: 0, step: 30 };
  every(1000, () => {
    qrState.left -= 1;
    bar.style.width = Math.max(0, (qrState.left / qrState.step) * 100) + "%";
    if (qrState.left <= 0) refreshQr();
  });
  refreshQr();

  const subjects = me.subjects.length ? me.subjects.map((s) => el("div", { class: "card" },
    el("div", { class: "row", style: "justify-content:space-between" }, el("strong", {}, s.code + " · " + s.name), el("span", {}, s.percentage + "%")),
    el("div", { class: "bar" + (s.belowThreshold ? " low" : "") }, el("div", { style: `width:${Math.min(100, s.percentage)}%` })),
    el("div", { class: "muted" }, s.present + " / " + s.total + " classes"),
    s.belowThreshold ? el("div", { class: "warn" }, "⚠ Below 75% — attend the next " + s.classesNeeded + " class(es) in a row to recover.") : "",
  )) : [el("p", { class: "muted" }, "No subjects yet.")];

  const hist = el("div", { class: "tablewrap" });
  api("/api/student/history").then((d) => {
    hist.replaceChildren(d.history.length ? el("table", {},
      el("tr", {}, ...["Date", "Period", "Subject", "Status"].map((h) => el("th", {}, h))),
      ...d.history.map((r) => el("tr", {}, el("td", {}, r.date), el("td", {}, String(r.period)), el("td", {}, r.subject), el("td", {}, el("span", { class: "pill " + r.status }, r.status)))))
      : el("p", { class: "muted" }, "Nothing marked yet."));
  }).catch(() => {});

  const tabs = tabBar(["My QR", "Subjects", "History"], [
    el("div", { class: "card center" }, el("h3", {}, me.name), el("p", { class: "muted" }, me.registerNumber + " · " + me.department + " · Sem " + me.semester + " " + me.section), qrImg, countdown, qrNote),
    el("div", {}, el("div", { class: "card center" }, ring, el("div", { class: "muted" }, "Overall attendance"),
      low ? el("div", { class: "warn" }, "⚠ Below the 75% requirement") : ""), ...subjects),
    el("div", { class: "card" }, hist),
  ]);
  $app.append(tabs);
}

function tabBar(names, panels, onSwitch) {
  const wrap = el("div"); const bar = el("div", { class: "tabs" }); const body = el("div");
  names.forEach((n, i) => bar.append(el("button", { onclick: () => select(i) }, n)));
  function select(i) {
    [...bar.children].forEach((b, j) => b.classList.toggle("active", i === j));
    body.replaceChildren(panels[i]);
    if (onSwitch) onSwitch(i);
  }
  wrap.append(bar, body); select(0); wrap.select = select; return wrap;
}

// ---------- faculty ----------
async function facultyView() {
  const sel = { classId: "", subjectId: "", date: today(), period: "1" };
  const classSel = el("select"), subjectSel = el("select"), dateIn = el("input", { type: "date", value: sel.date, max: today() });
  const periodSel = el("select", {}, ...[1, 2, 3, 4, 5, 6, 7, 8].map((p) => el("option", { value: p }, "Period " + p)));
  const status = el("div", { class: "muted" }, "Pick class, subject, date and period, then start scanning.");
  const rosterBox = el("div", { class: "tablewrap" }); const stats = el("div", { class: "stats" });
  let scanning = false, lastPayload = "", lastAt = 0, busy = false;

  const ready = () => sel.classId && sel.subjectId && sel.date && sel.period;
  const qs = () => `classId=${sel.classId}&subjectId=${sel.subjectId}&date=${sel.date}&period=${sel.period}`;
  const body = () => ({ classId: +sel.classId, subjectId: +sel.subjectId, date: sel.date, period: +sel.period });

  const classes = (await api("/api/faculty/classes")).classes;
  classSel.append(el("option", { value: "" }, "Select class"), ...classes.map((c) => el("option", { value: c.id }, c.name)));
  subjectSel.append(el("option", { value: "" }, "Select subject"));

  classSel.onchange = async () => {
    sel.classId = classSel.value; sel.subjectId = ""; subjectSel.replaceChildren(el("option", { value: "" }, "Select subject"));
    if (!sel.classId) return;
    const d = await api("/api/faculty/subjects?classId=" + sel.classId);
    subjectSel.append(...d.subjects.map((s) => el("option", { value: s.id }, s.code + " – " + s.name)));
    loadRoster();
  };
  subjectSel.onchange = () => { sel.subjectId = subjectSel.value; loadRoster(); };
  dateIn.onchange = () => { sel.date = dateIn.value; loadRoster(); };
  periodSel.onchange = () => { sel.period = periodSel.value; loadRoster(); };

  async function loadRoster() {
    if (!ready()) return;
    try {
      const d = await api("/api/faculty/session?" + qs());
      stats.replaceChildren(...[["Present", d.present, "var(--ok)"], ["Absent", d.absent, "var(--bad)"], ["Not marked", d.pending, "var(--muted)"]]
        .map(([l, n, c]) => el("div", {}, el("b", { style: "color:" + c }, String(n)), l)));
      rosterBox.replaceChildren(el("table", {},
        el("tr", {}, ...["Reg. no.", "Name", "Status", ""].map((h) => el("th", {}, h))),
        ...d.roster.map((r) => el("tr", {},
          el("td", {}, r.registerNumber), el("td", {}, r.name),
          el("td", {}, el("span", { class: "pill " + (r.status || "") }, r.status ? r.status + (r.method === "MANUAL" ? " (manual)" : "") : "—")),
          el("td", {}, el("button", { class: "small " + (r.status === "PRESENT" ? "danger" : "ok"), onclick: () => override(r.studentId, r.status === "PRESENT" ? "ABSENT" : "PRESENT") },
            r.status === "PRESENT" ? "Mark absent" : "Mark present"))))));
    } catch (e) { status.textContent = e.message; }
  }
  async function override(studentId, newStatus) {
    try { await api("/api/faculty/mark", { method: "POST", body: { ...body(), studentId, status: newStatus } }); loadRoster(); }
    catch (e) { flash(false, e.message); }
  }
  async function finalize() {
    if (!ready()) return flash(false, "Select class, subject, date and period first.");
    if (!confirm("Mark every student who has not been scanned as ABSENT?")) return;
    try { const d = await api("/api/faculty/finalize", { method: "POST", body: body() }); status.textContent = d.markedAbsent + " student(s) marked absent."; loadRoster(); }
    catch (e) { flash(false, e.message); }
  }

  async function onScan(text) {
    const now = Date.now();
    if (busy || (text === lastPayload && now - lastAt < 4000)) return;   // ignore the same frame repeating
    busy = true; lastPayload = text; lastAt = now;
    try {
      const d = await api("/api/faculty/scan", { method: "POST", body: { ...body(), payload: text } });
      flash(true, "✓ " + d.name + "\n" + d.registerNumber); status.textContent = "Marked present: " + d.name;
      loadRoster();
    } catch (e) { flash(false, e.message); status.textContent = e.message; }
    finally { busy = false; }
  }
  const scanBtn = el("button", { style: "width:100%" }, "📷 Start camera scan");
  const readerBox = el("div", { id: "reader" });
  scanBtn.onclick = async () => {
    if (scanning) { await stopScan(); return; }
    if (!ready()) return flash(false, "Select class, subject, date and period first.");
    if (typeof Html5Qrcode === "undefined") { status.textContent = "Scanner library failed to load (needs internet access to unpkg.com)."; return; }
    try {
      scanner = new Html5Qrcode("reader");
      await scanner.start({ facingMode: "environment" }, { fps: 10, qrbox: { width: 240, height: 240 } }, onScan, () => {});
      scanning = true; scanBtn.textContent = "⏹ Stop scanning";
    } catch (e) {
      status.textContent = location.protocol === "http:" && location.hostname !== "localhost"
        ? "Camera blocked: browsers only allow the camera on HTTPS or localhost. See README (HTTPS section)."
        : "Could not start the camera: " + (e.message || e);
    }
  };
  async function stopScan() { scanning = false; scanBtn.textContent = "📷 Start camera scan"; if (scanner) { try { await scanner.stop(); } catch (e) {} } }

  every(3000, loadRoster);   // live list: updates as other devices scan too

  const scanPanel = el("div", {},
    el("div", { class: "card" }, el("div", { class: "row" }, el("div", {}, el("label", {}, "Class"), classSel), el("div", {}, el("label", {}, "Subject"), subjectSel)),
      el("div", { class: "row" }, el("div", {}, el("label", {}, "Date"), dateIn), el("div", {}, el("label", {}, "Period"), periodSel))),
    el("div", { class: "card" }, scanBtn, el("div", { style: "margin-top:.8rem" }, readerBox), status),
    el("div", { class: "card" }, el("div", { class: "row", style: "justify-content:space-between;align-items:center" }, el("h3", {}, "Live session"),
      el("button", { class: "ghost small", onclick: finalize }, "Finish & mark rest absent")), stats, rosterBox));

  // reports
  const rClass = el("select"), rSub = el("select"), rType = el("select", {}, el("option", { value: "class" }, "Class-wise"), el("option", { value: "subject" }, "Subject-wise"));
  const rOut = el("div", { class: "tablewrap" }); const rErr = el("div", { class: "err" });
  rClass.append(el("option", { value: "" }, "Select class"), ...classes.map((c) => el("option", { value: c.id }, c.name)));
  rSub.append(el("option", { value: "" }, "Select subject"));
  rClass.onchange = async () => {
    rSub.replaceChildren(el("option", { value: "" }, "Select subject"));
    if (rClass.value) rSub.append(...(await api("/api/faculty/subjects?classId=" + rClass.value)).subjects.map((s) => el("option", { value: s.id }, s.code)));
  };
  const rq = (extra = "") => `/api/faculty/report?type=${rType.value}&classId=${rClass.value}` + (rType.value === "subject" ? `&subjectId=${rSub.value}` : "") + extra;
  const rValid = () => rClass.value && (rType.value === "class" || rSub.value);
  const gen = el("button", {}, "Generate");
  gen.onclick = async () => {
    rErr.textContent = ""; if (!rValid()) { rErr.textContent = "Choose a class (and a subject for subject-wise)."; return; }
    try {
      const d = await api(rq());
      rOut.replaceChildren(el("table", {}, el("tr", {}, ...["Reg. no.", "Name", "Present", "Total", "%"].map((h) => el("th", {}, h))),
        ...d.report.map((r) => el("tr", {}, el("td", {}, r.registerNumber), el("td", {}, r.name), el("td", {}, String(r.present)), el("td", {}, String(r.total)),
          el("td", { class: r.total > 0 && r.percentage < 75 ? "warn" : "" }, r.percentage + "%")))));
    } catch (e) { rErr.textContent = e.message; }
  };
  const csvBtn = el("button", { class: "ghost" }, "⬇ Download CSV");
  csvBtn.onclick = async () => {
    rErr.textContent = ""; if (!rValid()) { rErr.textContent = "Choose a class (and a subject for subject-wise)."; return; }
    try {
      const blob = await (await api(rq("&format=csv"), { raw: true })).blob();
      const a = el("a", { href: URL.createObjectURL(blob), download: `attendance-${rType.value}-report.csv` }); document.body.append(a); a.click(); a.remove();
    } catch (e) { rErr.textContent = e.message; }
  };
  const reportPanel = el("div", { class: "card" }, el("div", { class: "row" }, rType, rClass, rSub), el("div", { class: "row", style: "margin-top:.6rem" }, gen, csvBtn), rErr, rOut);

  $app.append(tabBar(["Mark attendance", "Reports"], [scanPanel, reportPanel], (i) => { if (i !== 0 && scanning) stopScan(); }));
}

route();
