"use strict";
/* ==========================================================================
   QR Attendance System - Mobile & Desktop Web App v2.1
   Features: Rotating QR, Camera Scanning, Google SSO, Admin Suite, Limitations
   ========================================================================== */

const $app = document.getElementById("app");
const $top = document.getElementById("topbar");
const $who = document.getElementById("who");
const $userAvatar = document.getElementById("userAvatar");
const $roleBadge = document.getElementById("roleBadge");
const $flash = document.getElementById("flash");
const $modalOverlay = document.getElementById("modalOverlay");
const $modalContainer = document.getElementById("modalContainer");

const state = {
  token: sessionStorage.getItem("token"),
  role: sessionStorage.getItem("role"),
  name: sessionStorage.getItem("name"),
  email: sessionStorage.getItem("email"),
};

let timers = [];       // intervals/timeouts owned by the active screen
let scanner = null;    // Html5Qrcode instance
let notice = "";       // message shown on login screen
let scanFacingMode = "environment"; // default to rear camera

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

// ------------------------------------------------------------------ DOM Helpers
function el(tag, props = {}, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") n.className = v;
    else if (k === "html") n.innerHTML = v;
    else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) n.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat()) {
    if (k == null) continue;
    n.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
  return n;
}

function every(ms, fn) {
  const t = setInterval(fn, ms);
  timers.push(t);
  return t;
}

async function cleanup() {
  timers.forEach(clearInterval);
  timers = [];
  if (scanner) {
    try { await scanner.stop(); } catch (e) {}
    try { scanner.clear(); } catch (e) {}
    scanner = null;
  }
}

function today() {
  const d = new Date();
  return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

// ------------------------------------------------------------------ API Client
async function api(path, { method = "GET", body, raw } = {}) {
  const res = await fetch(path, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(state.token ? { Authorization: "Bearer " + state.token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (res.status === 401 && path !== "/api/login" && path !== "/api/auth/google") {
    logout(true);
    throw new Error("Session expired. Please log in again.");
  }
  if (raw) {
    if (!res.ok) throw new Error("Request failed with status " + res.status);
    return res;
  }
  let data = {};
  try { data = await res.json(); } catch (e) {}
  if (!res.ok) {
    const err = new Error(data.error || "Request failed");
    err.code = data.code;
    throw err;
  }
  return data;
}

// ------------------------------------------------------------------ Modal Dialog
function showModal(title, bodyContent, footerActions = []) {
  $modalContainer.replaceChildren();

  const closeBtn = el("button", {
    class: "icon-btn small",
    title: "Close modal",
    onclick: closeModal
  }, el("span", { html: "&times;" }));

  const head = el("div", { class: "modal-header" },
    el("h3", {}, title),
    closeBtn
  );

  const body = el("div", { class: "modal-body" }, bodyContent);
  const footer = el("div", { class: "modal-footer" }, ...footerActions);

  $modalContainer.append(head, body, footer);
  $modalOverlay.hidden = false;
}

function closeModal() {
  $modalOverlay.hidden = true;
  $modalContainer.replaceChildren();
}

$modalOverlay.onclick = (e) => {
  if (e.target === $modalOverlay) closeModal();
};

// ------------------------------------------------------------------ Audio/Visual Feedback
let audioCtx;
function beep(good) {
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    const o = audioCtx.createOscillator(), g = audioCtx.createGain();
    o.frequency.value = good ? 880 : 220;
    o.type = good ? "sine" : "square";
    g.gain.value = 0.15;
    o.connect(g);
    g.connect(audioCtx.destination);
    o.start();
    o.stop(audioCtx.currentTime + (good ? 0.12 : 0.35));
  } catch (e) {}
  if (navigator.vibrate) navigator.vibrate(good ? 80 : [120, 60, 120]);
}

function flash(good, text) {
  $flash.className = "show " + (good ? "good" : "bad");
  $flash.textContent = text;
  beep(good);
  setTimeout(() => { $flash.className = ""; }, 1200);
}

// ------------------------------------------------------------------ Theme & Auth Bar
function applyTheme(t) {
  if (t) document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
}
try { applyTheme(localStorage.getItem("theme")); } catch (e) {}

document.getElementById("themeBtn").onclick = () => {
  const isDark = document.documentElement.dataset.theme === "dark" ||
    (!document.documentElement.dataset.theme && matchMedia("(prefers-color-scheme: dark)").matches);
  const next = isDark ? "light" : "dark";
  applyTheme(next);
  try { localStorage.setItem("theme", next); } catch (e) {}
};

document.getElementById("logoutBtn").onclick = () => {
  api("/api/logout", { method: "POST" }).catch(() => {});
  logout(false);
};

document.getElementById("navLimitsBtn").onclick = () => {
  showLimitationsModal();
};

function logout(expired) {
  state.token = state.role = state.name = state.email = null;
  sessionStorage.clear();
  notice = expired ? "Session expired. Please log in again." : "";
  route();
}

// ------------------------------------------------------------------ Router
async function route() {
  await cleanup();
  closeModal();
  $app.replaceChildren();

  if (!state.token) {
    $top.hidden = true;
    return loginView();
  }

  $top.hidden = false;
  $who.textContent = state.name || "User";
  $roleBadge.textContent = state.role || "USER";
  $userAvatar.textContent = (state.name || "U").slice(0, 1).toUpperCase();

  switch (state.role) {
    case "STUDENT": return studentView();
    case "FACULTY": return facultyView();
    case "ADMIN":   return adminView();
    default:        logout(false);
  }
}

// ==========================================================================
// LOGIN VIEW (Credentials & Google Sign-In)
// ==========================================================================
function loginView() {
  const err = el("div", { class: "err", role: "alert" }, notice);
  notice = "";

  const roleTabState = { current: "STUDENT" };
  const user = el("input", {
    autocomplete: "username",
    placeholder: "Register Number (e.g. 9923CS0001)",
    autocapitalize: "characters"
  });
  const pass = el("input", {
    type: "password",
    autocomplete: "current-password",
    placeholder: "Password"
  });

  const roleButtons = [
    { role: "STUDENT", label: "Student", icon: "🎓" },
    { role: "FACULTY", label: "Faculty", icon: "👨‍🏫" },
    { role: "ADMIN",   label: "Admin",   icon: "⚙️" },
  ].map((item) => {
    const btn = el("button", {
      class: item.role === roleTabState.current ? "active" : "",
      onclick: () => setRole(item.role)
    }, item.icon + " " + item.label);
    return btn;
  });

  function setRole(r) {
    roleTabState.current = r;
    roleButtons.forEach((b, i) => {
      b.classList.toggle("active", ["STUDENT", "FACULTY", "ADMIN"][i] === r);
    });
    user.placeholder = r === "STUDENT" ? "Register Number (e.g. 9923CS0001)"
      : r === "FACULTY" ? "Faculty ID (e.g. FAC1001)" : "Admin Username (admin)";
  }

  const roleTabs = el("div", { class: "tabs" }, ...roleButtons);

  // Google Sign-In Button
  const googleBtn = el("button", {
    class: "btn-google",
    type: "button",
    onclick: () => openGoogleLoginModal(roleTabState.current)
  },
    el("span", { html: `<svg viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/><path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/></svg>` }),
    el("span", {}, "Continue with Google")
  );

  const loginBtn = el("button", { style: "width:100%;margin-top:0.75rem;" }, "Sign In");

  const submit = async () => {
    loginBtn.disabled = true;
    err.textContent = "";
    try {
      const d = await api("/api/login", {
        method: "POST",
        body: { username: user.value.trim(), password: pass.value, role: roleTabState.current }
      });
      state.token = d.token;
      state.role = d.role;
      state.name = d.name;
      sessionStorage.setItem("token", d.token);
      sessionStorage.setItem("role", d.role);
      sessionStorage.setItem("name", d.name);
      route();
    } catch (e) {
      err.textContent = e.message;
      loginBtn.disabled = false;
    }
  };

  loginBtn.onclick = submit;
  pass.onkeydown = (e) => { if (e.key === "Enter") submit(); };

  // Fast-Fill Demo Chips for Viva/Evaluations
  const demoChips = el("div", { class: "demo-chips" },
    el("span", { class: "chips-label" }, "⚡ Quick Demo Logins:"),
    el("div", { class: "chip-group" },
      el("button", { class: "demo-chip", type: "button", onclick: () => { setRole("STUDENT"); user.value = "99250040129"; pass.value = "student123"; } }, "Student (Lohith)"),
      el("button", { class: "demo-chip", type: "button", onclick: () => { setRole("FACULTY"); user.value = "FAC1001"; pass.value = "faculty123"; } }, "Faculty (Dr. Kumar)"),
      el("button", { class: "demo-chip", type: "button", onclick: () => { setRole("ADMIN"); user.value = "admin"; pass.value = "admin123"; } }, "Admin (System)")
    )
  );

  const limitationsLink = el("p", { class: "muted center", style: "margin-top:1.5rem;" },
    "System security analysis & bounds: ",
    el("a", { href: "#limitations", style: "color:var(--brand);font-weight:600;", onclick: (e) => { e.preventDefault(); showLimitationsModal(); } }, "View System Limitations")
  );

  $app.append(
    el("div", { class: "card login-card" },
      el("div", { class: "login-hero" },
        el("div", { class: "hero-icon" },
          el("span", { html: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>` })
        ),
        el("h2", {}, "QR Attendance"),
        el("p", { class: "muted" }, "Secure rotating QR codes & instant campus attendance.")
      ),
      roleTabs,
      googleBtn,
      el("div", { class: "divider" }, el("span", {}, "or sign in with credentials")),
      el("label", {}, "User ID"),
      user,
      el("label", {}, "Password"),
      pass,
      loginBtn,
      err,
      demoChips,
      limitationsLink
    )
  );
}

// ------------------------------------------------------------------ Google Login Modal
function openGoogleLoginModal(selectedRole) {
  const accounts = [
    { name: "C.S. Lohith Kumar Reddy", email: "lohith.kumar@campus.edu", id: "99250040129", role: "STUDENT", label: "Student Profile (99250040129)" },
    { name: "Dr. S. Kumar", email: "dr.kumar@campus.edu", id: "FAC1001", role: "FACULTY", label: "Associate Professor (FAC1001)" },
    { name: "System Administrator", email: "admin@campus.edu", id: "admin", role: "ADMIN", label: "Campus IT Administrator" },
  ];

  const modalErr = el("div", { class: "err" });

  const accountList = el("div", { class: "account-list" },
    ...accounts.map(acc => {
      const item = el("div", {
        class: "account-item",
        onclick: () => executeGoogleLogin(acc.email, acc.role, acc.id)
      },
        el("div", { class: "account-item-avatar" }, acc.name.slice(0, 1)),
        el("div", { class: "account-item-info" },
          el("div", { class: "account-item-name" }, acc.name),
          el("div", { class: "account-item-email" }, acc.email + " · " + acc.label)
        ),
        el("span", { class: "pill " + acc.role }, acc.role)
      );
      return item;
    })
  );

  // Custom Google Account Section
  const customEmail = el("input", { type: "email", placeholder: "e.g. name@campus.edu or name@gmail.com" });
  const customCampusId = el("input", { placeholder: "Campus ID (Register No / Faculty Code)" });
  const customRole = el("select", {},
    el("option", { value: "STUDENT" }, "Student"),
    el("option", { value: "FACULTY" }, "Faculty"),
    el("option", { value: "ADMIN" }, "Administrator")
  );
  customRole.value = selectedRole || "STUDENT";

  const customSubmitBtn = el("button", {
    class: "small",
    onclick: () => {
      const em = customEmail.value.trim();
      if (!em) { modalErr.textContent = "Please enter an email address."; return; }
      executeGoogleLogin(em, customRole.value, customCampusId.value.trim());
    }
  }, "Sign In with Custom Google Account");

  const customBox = el("div", { style: "margin-top:1.25rem;padding-top:1rem;border-top:1px solid var(--line);" },
    el("h4", {}, "Use Another Google Account"),
    el("p", { class: "muted", style: "font-size:0.825rem;margin-bottom:0.75rem;" }, "Enter your Google email and link your Campus ID:"),
    el("label", {}, "Google Email"), customEmail,
    el("div", { class: "row" },
      el("div", {}, el("label", {}, "Role"), customRole),
      el("div", {}, el("label", {}, "Campus ID"), customCampusId)
    ),
    el("div", { style: "margin-top:0.75rem;" }, customSubmitBtn)
  );

  async function executeGoogleLogin(email, role, campusId) {
    modalErr.textContent = "";
    try {
      const d = await api("/api/auth/google", {
        method: "POST",
        body: { email, role, campusId }
      });
      state.token = d.token;
      state.role = d.role;
      state.name = d.name;
      state.email = d.email;
      sessionStorage.setItem("token", d.token);
      sessionStorage.setItem("role", d.role);
      sessionStorage.setItem("name", d.name);
      sessionStorage.setItem("email", d.email || "");
      closeModal();
      route();
    } catch (e) {
      modalErr.textContent = e.message;
    }
  }

  const content = el("div", {},
    el("p", { class: "muted" }, "Select an authenticated Google identity or sign in with your campus account:"),
    accountList,
    customBox,
    modalErr
  );

  showModal("Sign In with Google SSO", content, [
    el("button", { class: "ghost", onclick: closeModal }, "Cancel")
  ]);
}

// ==========================================================================
// STUDENT VIEW
// ==========================================================================
async function studentView() {
  const me = await api("/api/student/me");
  const low = me.overall < 75 && me.subjects.some((s) => s.total > 0);

  // Student Header Banner
  const profileBanner = el("div", { class: "card", style: "display:flex;align-items:center;gap:1.25rem;flex-wrap:wrap;" },
    el("div", { class: "user-avatar", style: "width:60px;height:60px;font-size:1.5rem;" }, me.name.slice(0, 1)),
    el("div", { style: "flex:1;" },
      el("h3", { style: "margin:0;" }, me.name),
      el("p", { class: "muted", style: "margin-top:0.2rem;" },
        `${me.registerNumber} · ${me.department} · Semester ${me.semester} - Section ${me.section}`)
    ),
    el("div", { style: "text-align:right;" },
      el("span", { class: "pill " + (low ? "ABSENT" : "PRESENT") },
        low ? "⚠ Below 75% Requirement" : "✓ Attendance Compliant")
    )
  );

  // --- Tab 1: Live Rotating QR ---
  const qrImg = el("img", { class: "qr", alt: "Your live attendance QR code" });
  const bar = el("div", { class: "countdown-bar" });
  const countdown = el("div", { class: "countdown-bar-wrap" }, bar);
  const qrTimerText = el("p", { class: "center muted", style: "font-family:var(--font-mono);font-size:0.875rem;" }, "Refreshing code...");
  const qrNote = el("p", { class: "qr-footer-note" }, "Loading dynamic QR…");

  let loading = false;
  async function refreshQr() {
    if (loading) return;
    loading = true;
    try {
      const info = await api("/api/student/qr");
      const res = await api("/api/student/qr.png", { raw: true });
      const url = URL.createObjectURL(await res.blob());
      const old = qrImg.src;
      qrImg.src = url;
      if (old.startsWith("blob:")) URL.revokeObjectURL(old);

      qrNote.textContent = `New code generated every ${info.stepSeconds}s. Screenshots or static photos fail immediately.`;
      bar.style.width = "100%";
      qrState.left = info.secondsLeft;
      qrState.step = info.stepSeconds;
    } catch (e) {
      qrNote.textContent = e.message;
    } finally {
      loading = false;
    }
  }

  const qrState = { left: 0, step: 30 };
  every(1000, () => {
    qrState.left -= 1;
    const pct = Math.max(0, (qrState.left / qrState.step) * 100);
    bar.style.width = pct + "%";
    bar.className = "countdown-bar" + (qrState.left <= 5 ? " danger" : qrState.left <= 10 ? " warning" : "");
    qrTimerText.textContent = `Expires in ${Math.max(0, qrState.left)}s`;
    if (qrState.left <= 0) refreshQr();
  });
  refreshQr();

  const qrPanel = el("div", { class: "card center" },
    el("div", { class: "live-badge" },
      el("span", { class: "pulse-dot" }),
      "LIVE DYNAMIC QR CODE"
    ),
    el("div", { class: "qr-container" },
      qrImg,
      countdown,
      qrTimerText,
      qrNote,
      el("button", { class: "ghost small", style: "margin-top:0.75rem;", onclick: refreshQr }, "🔄 Refresh QR Now")
    )
  );

  // --- Tab 2: Subjects & Analytics ---
  const ring = el("div", {
    class: "ring",
    style: `--p:${me.overall};--c:${low ? "var(--bad)" : "var(--ok)}"`,
  }, el("span", {}, me.overall + "%", el("small", {}, "OVERALL")));

  const ringCard = el("div", { class: "card center" },
    el("h4", {}, "Academic Standing"),
    el("div", { class: "ring-wrap" }, ring),
    low ? el("div", { class: "warn-box center" }, "⚠ Overall attendance is below the university 75% threshold! Immediate recovery required.") : ""
  );

  const subjects = me.subjects.length ? me.subjects.map((s) => el("div", { class: "card" },
    el("div", { class: "row", style: "justify-content:space-between;align-items:center;" },
      el("strong", { style: "font-size:1.05rem;" }, s.code + " · " + s.name),
      el("span", { class: "pill " + (s.belowThreshold ? "ABSENT" : "PRESENT"), style: "font-size:0.95rem;font-weight:800;" }, s.percentage + "%")
    ),
    el("div", { class: "bar" + (s.belowThreshold ? " low" : "") },
      el("div", { style: `width:${Math.min(100, s.percentage)}%` })
    ),
    el("div", { class: "row", style: "justify-content:space-between;font-size:0.875rem;" },
      el("span", { class: "muted" }, `Attended ${s.present} of ${s.total} sessions held`),
      el("span", { class: "muted" }, s.total === 0 ? "No classes yet" : `${s.total - s.present} absent`)
    ),
    s.belowThreshold
      ? el("div", { class: "warn-box" }, `⚠ Below 75% — attend the next ${s.classesNeeded} consecutive class(es) to recover.`)
      : ""
  )) : [el("div", { class: "card muted center" }, "No subjects enrolled yet.")];

  const subjectsPanel = el("div", {}, ringCard, ...subjects);

  // --- Tab 3: Attendance History ---
  const histFilter = el("input", { placeholder: "Filter history by subject or date..." });
  const histTable = el("div", { class: "tablewrap", style: "margin-top:0.75rem;" });

  let allHistory = [];
  function renderHistory() {
    const q = histFilter.value.trim().toLowerCase();
    const filtered = allHistory.filter(r =>
      r.subject.toLowerCase().includes(q) || r.date.toLowerCase().includes(q) || String(r.period).includes(q)
    );

    histTable.replaceChildren(filtered.length ? el("table", {},
      el("thead", {},
        el("tr", {}, ...["Date", "Period", "Subject", "Method", "Status"].map(h => el("th", {}, h)))
      ),
      el("tbody", {},
        ...filtered.map(r => el("tr", {},
          el("td", { style: "font-family:var(--font-mono);font-size:0.875rem;" }, r.date),
          el("td", {}, "Period " + r.period),
          el("td", { style: "font-weight:600;" }, r.subject),
          el("td", {}, el("span", { class: "pill MANUAL" }, r.method || "QR")),
          el("td", {}, el("span", { class: "pill " + r.status }, r.status))
        ))
      )
    ) : el("p", { class: "muted center", style: "padding:1.5rem;" }, "No attendance records found."));
  }

  histFilter.oninput = renderHistory;

  api("/api/student/history").then((d) => {
    allHistory = d.history || [];
    renderHistory();
  }).catch(() => {});

  const historyPanel = el("div", { class: "card" },
    el("h4", {}, "Verified Attendance Logs"),
    el("p", { class: "muted", style: "margin-bottom:0.75rem;" }, "Chronological ledger of classes attended and verified:"),
    histFilter,
    histTable
  );

  const tabs = tabBar(["Live Attendance QR", "Subjects & Analytics", "Attendance History"], [
    qrPanel,
    subjectsPanel,
    historyPanel
  ]);

  $app.append(profileBanner, tabs);
}

// ------------------------------------------------------------------ Tab Bar Helper
function tabBar(names, panels, onSwitch) {
  const wrap = el("div");
  const bar = el("div", { class: "tabs" });
  const body = el("div");

  names.forEach((n, i) => {
    bar.append(el("button", {
      onclick: () => select(i)
    }, n));
  });

  function select(i) {
    [...bar.children].forEach((b, j) => b.classList.toggle("active", i === j));
    body.replaceChildren(panels[i]);
    if (onSwitch) onSwitch(i);
  }

  wrap.append(bar, body);
  select(0);
  wrap.select = select;
  return wrap;
}

// ==========================================================================
// FACULTY VIEW
// ==========================================================================
async function facultyView() {
  const sel = { classId: "", subjectId: "", date: today(), period: "1" };
  const classSel = el("select");
  const subjectSel = el("select");
  const dateIn = el("input", { type: "date", value: sel.date, max: today() });
  const periodSel = el("select", {}, ...[1, 2, 3, 4, 5, 6, 7, 8].map(p => el("option", { value: p }, "Period " + p)));

  const status = el("div", { class: "muted center", style: "margin-top:0.75rem;" }, "Select class, subject, date & period to activate scanner.");
  const rosterBox = el("div", { class: "tablewrap" });
  const rosterSearch = el("input", { placeholder: "Search student by name or register number...", style: "margin-bottom:0.75rem;" });
  const stats = el("div", { class: "stats" });

  let scanning = false, lastPayload = "", lastAt = 0, busy = false;
  let cachedRoster = [];

  const ready = () => sel.classId && sel.subjectId && sel.date && sel.period;
  const qs = () => `classId=${sel.classId}&subjectId=${sel.subjectId}&date=${sel.date}&period=${sel.period}`;
  const body = () => ({ classId: +sel.classId, subjectId: +sel.subjectId, date: sel.date, period: +sel.period });

  const classes = (await api("/api/faculty/classes")).classes;
  classSel.append(el("option", { value: "" }, "Select Class"), ...classes.map(c => el("option", { value: c.id }, c.name)));
  subjectSel.append(el("option", { value: "" }, "Select Subject"));

  classSel.onchange = async () => {
    sel.classId = classSel.value;
    sel.subjectId = "";
    subjectSel.replaceChildren(el("option", { value: "" }, "Select Subject"));
    if (!sel.classId) return;
    const d = await api("/api/faculty/subjects?classId=" + sel.classId);
    subjectSel.append(...d.subjects.map(s => el("option", { value: s.id }, s.code + " – " + s.name)));
    loadRoster();
  };

  subjectSel.onchange = () => { sel.subjectId = subjectSel.value; loadRoster(); };
  dateIn.onchange = () => { sel.date = dateIn.value; loadRoster(); };
  periodSel.onchange = () => { sel.period = periodSel.value; loadRoster(); };

  async function loadRoster() {
    if (!ready()) return;
    try {
      const d = await api("/api/faculty/session?" + qs());
      cachedRoster = d.roster || [];

      stats.replaceChildren(
        el("div", { class: "stat-tile" }, el("b", { style: "color:var(--brand);" }, String(d.totalEnrolled || cachedRoster.length)), el("span", {}, "Enrolled")),
        el("div", { class: "stat-tile" }, el("b", { style: "color:var(--ok);" }, String(d.present)), el("span", {}, "Present")),
        el("div", { class: "stat-tile" }, el("b", { style: "color:var(--bad);" }, String(d.absent)), el("span", {}, "Absent")),
        el("div", { class: "stat-tile" }, el("b", { style: "color:var(--warn);" }, String(d.pending)), el("span", {}, "Pending"))
      );

      renderRosterRows();
    } catch (e) {
      status.textContent = e.message;
    }
  }

  function renderRosterRows() {
    const q = rosterSearch.value.trim().toLowerCase();
    const rows = cachedRoster.filter(r =>
      r.name.toLowerCase().includes(q) || r.registerNumber.toLowerCase().includes(q)
    );

    rosterBox.replaceChildren(el("table", {},
      el("thead", {},
        el("tr", {}, ...["Reg. No.", "Student Name", "Status", "Quick Override"].map(h => el("th", {}, h)))
      ),
      el("tbody", {},
        ...rows.map(r => el("tr", {},
          el("td", { style: "font-family:var(--font-mono);font-size:0.875rem;" }, r.registerNumber),
          el("td", { style: "font-weight:600;" }, r.name),
          el("td", {},
            el("span", { class: "pill " + (r.status || "PENDING") },
              r.status ? `${r.status} ${r.method === "MANUAL" ? "(manual)" : ""}` : "Not Marked"
            )
          ),
          el("td", {},
            el("button", {
              class: "small " + (r.status === "PRESENT" ? "danger" : "ok"),
              onclick: () => override(r.studentId, r.status === "PRESENT" ? "ABSENT" : "PRESENT")
            }, r.status === "PRESENT" ? "Mark Absent" : "Mark Present")
          )
        ))
      )
    ));
  }

  rosterSearch.oninput = renderRosterRows;

  async function override(studentId, newStatus) {
    try {
      await api("/api/faculty/mark", { method: "POST", body: { ...body(), studentId, status: newStatus } });
      loadRoster();
    } catch (e) {
      flash(false, e.message);
    }
  }

  async function finalize() {
    if (!ready()) return flash(false, "Select class, subject, date and period first.");
    if (!confirm("Confirm finalizing this session? All unscanned students will be marked ABSENT.")) return;
    try {
      const d = await api("/api/faculty/finalize", { method: "POST", body: body() });
      status.textContent = `${d.markedAbsent} student(s) finalized as absent.`;
      flash(true, `Session Finalized!\n${d.markedAbsent} absent`);
      loadRoster();
    } catch (e) {
      flash(false, e.message);
    }
  }

  async function onScan(text) {
    const now = Date.now();
    if (busy || (text === lastPayload && now - lastAt < 4000)) return;
    busy = true;
    lastPayload = text;
    lastAt = now;
    try {
      const d = await api("/api/faculty/scan", { method: "POST", body: { ...body(), payload: text } });
      flash(true, `✓ Verified!\n${d.name}\n${d.registerNumber}`);
      status.textContent = "Marked present: " + d.name;
      loadRoster();
    } catch (e) {
      flash(false, e.message);
      status.textContent = e.message;
    } finally {
      busy = false;
    }
  }

  const scanBtn = el("button", { style: "flex:1;" }, "📷 Start Camera Scanner");
  const flipCamBtn = el("button", { class: "ghost", title: "Switch front/rear camera" }, "🔄 Flip Cam");
  const scanFileInput = el("input", { type: "file", accept: "image/*", style: "display:none;" });
  const fileScanBtn = el("button", { class: "ghost", title: "Scan an image file fallback" }, "📁 Image File");

  fileScanBtn.onclick = () => scanFileInput.click();
  scanFileInput.onchange = async () => {
    if (!scanFileInput.files.length) return;
    if (!ready()) return flash(false, "Select class, subject, date and period first.");
    try {
      const file = scanFileInput.files[0];
      const tempScanner = new Html5Qrcode("reader");
      const decoded = await tempScanner.scanFile(file, true);
      onScan(decoded);
    } catch (err) {
      flash(false, "Could not decode QR from image.");
    } finally {
      scanFileInput.value = "";
    }
  };

  const laser = el("div", { class: "scan-laser" });
  const readerBox = el("div", { class: "scanner-viewport-wrap" },
    laser,
    el("div", { class: "reticle-corner reticle-tl" }),
    el("div", { class: "reticle-corner reticle-tr" }),
    el("div", { class: "reticle-corner reticle-bl" }),
    el("div", { class: "reticle-corner reticle-br" }),
    el("div", { id: "reader" })
  );

  scanBtn.onclick = async () => {
    if (scanning) { await stopScan(); return; }
    if (!ready()) return flash(false, "Select class, subject, date and period first.");
    if (typeof Html5Qrcode === "undefined") {
      status.textContent = "Scanner library failed to load (check network).";
      return;
    }
    try {
      scanner = new Html5Qrcode("reader");
      await scanner.start(
        { facingMode: scanFacingMode },
        { fps: 12, qrbox: { width: 250, height: 250 } },
        onScan,
        () => {}
      );
      scanning = true;
      scanBtn.textContent = "⏹ Stop Scanner";
      laser.style.display = "block";
    } catch (e) {
      status.textContent = location.protocol === "http:" && location.hostname !== "localhost"
        ? "Camera blocked: Mobile browsers require HTTPS. See README HTTPS setup."
        : "Camera access error: " + (e.message || e);
    }
  };

  flipCamBtn.onclick = async () => {
    scanFacingMode = scanFacingMode === "environment" ? "user" : "environment";
    if (scanning) {
      await stopScan();
      scanBtn.click();
    }
  };

  async function stopScan() {
    scanning = false;
    scanBtn.textContent = "📷 Start Camera Scanner";
    laser.style.display = "none";
    if (scanner) {
      try { await scanner.stop(); } catch (e) {}
    }
  }

  every(3000, loadRoster); // live sync

  const scanPanel = el("div", {},
    el("div", { class: "card" },
      el("h4", {}, "Session Parameters"),
      el("div", { class: "row" },
        el("div", {}, el("label", {}, "Class"), classSel),
        el("div", {}, el("label", {}, "Subject"), subjectSel)
      ),
      el("div", { class: "row" },
        el("div", {}, el("label", {}, "Date"), dateIn),
        el("div", {}, el("label", {}, "Period"), periodSel)
      )
    ),
    el("div", { class: "card center" },
      el("div", { class: "row", style: "margin-bottom:0.75rem;" }, scanBtn, flipCamBtn, fileScanBtn, scanFileInput),
      readerBox,
      status
    ),
    el("div", { class: "card" },
      el("div", { class: "card-title-row" },
        el("h3", {}, "Active Session Roster"),
        el("button", { class: "danger small", onclick: finalize }, "Finish Session (Mark Rest Absent)")
      ),
      stats,
      rosterSearch,
      rosterBox
    )
  );

  // --- Reports Tab ---
  const rClass = el("select");
  const rSub = el("select");
  const rType = el("select", {}, el("option", { value: "class" }, "Class-wise Report"), el("option", { value: "subject" }, "Subject-wise Report"));
  const rOut = el("div", { class: "tablewrap", style: "margin-top:1rem;" });
  const rErr = el("div", { class: "err" });

  rClass.append(el("option", { value: "" }, "Select Class"), ...classes.map(c => el("option", { value: c.id }, c.name)));
  rSub.append(el("option", { value: "" }, "Select Subject"));

  rClass.onchange = async () => {
    rSub.replaceChildren(el("option", { value: "" }, "Select Subject"));
    if (rClass.value) {
      const d = await api("/api/faculty/subjects?classId=" + rClass.value);
      rSub.append(...d.subjects.map(s => el("option", { value: s.id }, s.code + " – " + s.name)));
    }
  };

  const rq = (extra = "") => `/api/faculty/report?type=${rType.value}&classId=${rClass.value}` + (rType.value === "subject" ? `&subjectId=${rSub.value}` : "") + extra;
  const rValid = () => rClass.value && (rType.value === "class" || rSub.value);

  const genBtn = el("button", {}, "Generate Report");
  genBtn.onclick = async () => {
    rErr.textContent = "";
    if (!rValid()) { rErr.textContent = "Select a class (and subject for subject-wise report)."; return; }
    try {
      const d = await api(rq());
      rOut.replaceChildren(el("table", {},
        el("thead", {},
          el("tr", {}, ...["Reg. No.", "Student Name", "Present", "Total", "Percentage", "Status"].map(h => el("th", {}, h)))
        ),
        el("tbody", {},
          ...d.report.map(r => el("tr", {},
            el("td", { style: "font-family:var(--font-mono);font-size:0.875rem;" }, r.registerNumber),
            el("td", { style: "font-weight:600;" }, r.name),
            el("td", {}, String(r.present)),
            el("td", {}, String(r.total)),
            el("td", { style: "font-weight:700;" }, r.percentage + "%"),
            el("td", {}, el("span", { class: "pill " + (r.percentage < 75 ? "ABSENT" : "PRESENT") }, r.percentage < 75 ? "Below 75%" : "Good"))
          ))
        )
      ));
    } catch (e) {
      rErr.textContent = e.message;
    }
  };

  const csvBtn = el("button", { class: "ghost" }, "⬇ Download CSV");
  csvBtn.onclick = async () => {
    rErr.textContent = "";
    if (!rValid()) { rErr.textContent = "Select a class (and subject for subject-wise report)."; return; }
    try {
      const res = await api(rq("&format=csv"), { raw: true });
      const blob = await res.blob();
      const a = el("a", { href: URL.createObjectURL(blob), download: `attendance-${rType.value}-report.csv` });
      document.body.append(a); a.click(); a.remove();
    } catch (e) {
      rErr.textContent = e.message;
    }
  };

  const printBtn = el("button", { class: "ghost", onclick: () => window.print() }, "🖨️ Print / Save as PDF");

  const reportPanel = el("div", { class: "card" },
    el("h4", {}, "Academic Attendance Reports"),
    el("div", { class: "row", style: "margin-top:0.75rem;" }, rType, rClass, rSub),
    el("div", { class: "row", style: "margin-top:0.75rem;" }, genBtn, csvBtn, printBtn),
    rErr,
    rOut
  );

  $app.append(
    tabBar(["Live Attendance Scanner", "Reports & Export"], [scanPanel, reportPanel], (i) => {
      if (i !== 0 && scanning) stopScan();
    })
  );
}

// ==========================================================================
// ADMIN DASHBOARD VIEW (Web Administration Suite)
// ==========================================================================
async function adminView() {
  const overview = await api("/api/admin/overview");

  const statCards = el("div", { class: "stats" },
    el("div", { class: "stat-tile" }, el("b", { style: "color:var(--brand);" }, String(overview.classesCount)), el("span", {}, "Classes")),
    el("div", { class: "stat-tile" }, el("b", { style: "color:var(--info);" }, String(overview.subjectsCount)), el("span", {}, "Subjects")),
    el("div", { class: "stat-tile" }, el("b", { style: "color:var(--ok);" }, String(overview.facultyCount)), el("span", {}, "Faculty")),
    el("div", { class: "stat-tile" }, el("b", { style: "color:var(--warn);" }, String(overview.studentsCount)), el("span", {}, "Students"))
  );

  // --- Classes Tab ---
  const classWrap = el("div", { class: "tablewrap" });
  async function loadClasses() {
    const d = await api("/api/admin/classes");
    classWrap.replaceChildren(el("table", {},
      el("thead", {}, el("tr", {}, ...["ID", "Class Name", "Department", "Sem", "Sec", "Action"].map(h => el("th", {}, h)))),
      el("tbody", {},
        ...d.classes.map(c => el("tr", {},
          el("td", {}, String(c.id)),
          el("td", { style: "font-weight:600;" }, c.className),
          el("td", {}, c.department),
          el("td", {}, String(c.semester)),
          el("td", {}, c.section),
          el("td", {}, el("button", { class: "danger small", onclick: () => deleteClass(c.id) }, "Delete"))
        ))
      )
    ));
  }
  async function deleteClass(id) {
    if (!confirm("Delete class and all associated data?")) return;
    await api("/api/admin/classes?id=" + id, { method: "DELETE" });
    loadClasses();
  }
  const addClassBtn = el("button", { class: "small", style: "margin-bottom:0.75rem;" }, "+ Add Class");
  addClassBtn.onclick = () => {
    const cName = el("input", { placeholder: "Class Name (e.g. II CSE AI&ML A)" });
    const cDept = el("input", { placeholder: "Department (e.g. CSE)" });
    const cSem = el("input", { type: "number", min: "1", max: "8", value: "3" });
    const cSec = el("input", { placeholder: "Section (e.g. A)", maxlength: "2" });
    const mErr = el("div", { class: "err" });

    showModal("Create New Class", el("div", {},
      el("label", {}, "Class Display Name"), cName,
      el("label", {}, "Department"), cDept,
      el("div", { class: "row" },
        el("div", {}, el("label", {}, "Semester"), cSem),
        el("div", {}, el("label", {}, "Section"), cSec)
      ),
      mErr
    ), [
      el("button", { class: "ghost", onclick: closeModal }, "Cancel"),
      el("button", {
        onclick: async () => {
          try {
            await api("/api/admin/classes", {
              method: "POST",
              body: { className: cName.value.trim(), department: cDept.value.trim(), semester: +cSem.value, section: cSec.value.trim() }
            });
            closeModal();
            loadClasses();
          } catch (e) { mErr.textContent = e.message; }
        }
      }, "Save Class")
    ]);
  };
  loadClasses();
  const classesPanel = el("div", { class: "card" }, addClassBtn, classWrap);

  // --- Faculty Tab ---
  const facultyWrap = el("div", { class: "tablewrap" });
  async function loadFaculty() {
    const d = await api("/api/admin/faculty");
    facultyWrap.replaceChildren(el("table", {},
      el("thead", {}, el("tr", {}, ...["ID", "Faculty Code", "Full Name", "Department", "Action"].map(h => el("th", {}, h)))),
      el("tbody", {},
        ...d.faculty.map(f => el("tr", {},
          el("td", {}, String(f.id)),
          el("td", { style: "font-family:var(--font-mono);" }, f.code),
          el("td", { style: "font-weight:600;" }, f.name),
          el("td", {}, f.department),
          el("td", {}, el("button", { class: "danger small", onclick: () => deleteFaculty(f.id) }, "Delete"))
        ))
      )
    ));
  }
  async function deleteFaculty(id) {
    if (!confirm("Delete faculty member?")) return;
    await api("/api/admin/faculty?id=" + id, { method: "DELETE" });
    loadFaculty();
  }
  const addFacultyBtn = el("button", { class: "small", style: "margin-bottom:0.75rem;" }, "+ Add Faculty");
  addFacultyBtn.onclick = () => {
    const fCode = el("input", { placeholder: "Faculty Code (e.g. FAC1002)" });
    const fName = el("input", { placeholder: "Full Name (e.g. Dr. Priya M)" });
    const fDept = el("input", { placeholder: "Department (e.g. CSE)" });
    const fPass = el("input", { type: "password", placeholder: "Password (min 8 chars)" });
    const mErr = el("div", { class: "err" });

    showModal("Register Faculty Member", el("div", {},
      el("label", {}, "Faculty Code / Username"), fCode,
      el("label", {}, "Full Name"), fName,
      el("label", {}, "Department"), fDept,
      el("label", {}, "Initial Password"), fPass,
      mErr
    ), [
      el("button", { class: "ghost", onclick: closeModal }, "Cancel"),
      el("button", {
        onclick: async () => {
          try {
            await api("/api/admin/faculty", {
              method: "POST",
              body: { code: fCode.value.trim(), name: fName.value.trim(), department: fDept.value.trim(), password: fPass.value }
            });
            closeModal();
            loadFaculty();
          } catch (e) { mErr.textContent = e.message; }
        }
      }, "Save Faculty")
    ]);
  };
  loadFaculty();
  const facultyPanel = el("div", { class: "card" }, addFacultyBtn, facultyWrap);

  // --- Students Tab ---
  const studentWrap = el("div", { class: "tablewrap" });
  async function loadStudents() {
    const d = await api("/api/admin/students");
    studentWrap.replaceChildren(el("table", {},
      el("thead", {}, el("tr", {}, ...["ID", "Register No.", "Full Name", "Dept", "Sem", "Sec", "Action"].map(h => el("th", {}, h)))),
      el("tbody", {},
        ...d.students.map(st => el("tr", {},
          el("td", {}, String(st.id)),
          el("td", { style: "font-family:var(--font-mono);" }, st.registerNumber),
          el("td", { style: "font-weight:600;" }, st.name),
          el("td", {}, st.department),
          el("td", {}, String(st.semester)),
          el("td", {}, st.section),
          el("td", {}, el("button", { class: "danger small", onclick: () => deleteStudent(st.id) }, "Delete"))
        ))
      )
    ));
  }
  async function deleteStudent(id) {
    if (!confirm("Delete student?")) return;
    await api("/api/admin/students?id=" + id, { method: "DELETE" });
    loadStudents();
  }
  const addStudentBtn = el("button", { class: "small", style: "margin-bottom:0.75rem;" }, "+ Add Student");
  addStudentBtn.onclick = async () => {
    const sReg = el("input", { placeholder: "Register Number (e.g. 9923CS0003)" });
    const sName = el("input", { placeholder: "Full Name" });
    const sDept = el("input", { placeholder: "Department" });
    const sSem = el("input", { type: "number", min: "1", max: "8", value: "3" });
    const sSec = el("input", { placeholder: "Section (A/B)" });
    const sClass = el("select");
    const sPass = el("input", { type: "password", placeholder: "Password (min 8 chars)" });
    const mErr = el("div", { class: "err" });

    const classesList = (await api("/api/admin/classes")).classes;
    sClass.append(...classesList.map(c => el("option", { value: c.id }, c.className)));

    showModal("Enroll Student (Generates Secret QR Key)", el("div", {},
      el("label", {}, "Register Number"), sReg,
      el("label", {}, "Full Name"), sName,
      el("div", { class: "row" },
        el("div", {}, el("label", {}, "Department"), sDept),
        el("div", {}, el("label", {}, "Class"), sClass)
      ),
      el("div", { class: "row" },
        el("div", {}, el("label", {}, "Semester"), sSem),
        el("div", {}, el("label", {}, "Section"), sSec)
      ),
      el("label", {}, "Password"), sPass,
      mErr
    ), [
      el("button", { class: "ghost", onclick: closeModal }, "Cancel"),
      el("button", {
        onclick: async () => {
          try {
            await api("/api/admin/students", {
              method: "POST",
              body: {
                registerNumber: sReg.value.trim(),
                name: sName.value.trim(),
                department: sDept.value.trim(),
                semester: +sSem.value,
                section: sSec.value.trim(),
                classId: +sClass.value,
                password: sPass.value
              }
            });
            closeModal();
            loadStudents();
          } catch (e) { mErr.textContent = e.message; }
        }
      }, "Enroll Student")
    ]);
  };
  loadStudents();
  const studentsPanel = el("div", { class: "card" }, addStudentBtn, studentWrap);

  // --- Subjects Tab ---
  const subjectWrap = el("div", { class: "tablewrap" });
  async function loadSubjects() {
    const d = await api("/api/admin/subjects");
    subjectWrap.replaceChildren(el("table", {},
      el("thead", {}, el("tr", {}, ...["ID", "Code", "Subject Name", "Class ID", "Faculty ID", "Action"].map(h => el("th", {}, h)))),
      el("tbody", {},
        ...d.subjects.map(s => el("tr", {},
          el("td", {}, String(s.id)),
          el("td", { style: "font-family:var(--font-mono);font-weight:700;" }, s.code),
          el("td", {}, s.name),
          el("td", {}, String(s.classId)),
          el("td", {}, String(s.facultyId)),
          el("td", {}, el("button", { class: "danger small", onclick: () => deleteSubject(s.id) }, "Delete"))
        ))
      )
    ));
  }
  async function deleteSubject(id) {
    if (!confirm("Delete subject?")) return;
    await api("/api/admin/subjects?id=" + id, { method: "DELETE" });
    loadSubjects();
  }
  const addSubjectBtn = el("button", { class: "small", style: "margin-bottom:0.75rem;" }, "+ Add Subject");
  addSubjectBtn.onclick = async () => {
    const subCode = el("input", { placeholder: "Subject Code (e.g. CSE203)" });
    const subName = el("input", { placeholder: "Subject Name (e.g. OOP using Java)" });
    const subClass = el("select");
    const subFaculty = el("select");
    const mErr = el("div", { class: "err" });

    const [cData, fData] = await Promise.all([api("/api/admin/classes"), api("/api/admin/faculty")]);
    subClass.append(...cData.classes.map(c => el("option", { value: c.id }, c.className)));
    subFaculty.append(...fData.faculty.map(f => el("option", { value: f.id }, f.code + " – " + f.name)));

    showModal("Create Subject (Auto-Enrolls Class Students)", el("div", {},
      el("label", {}, "Subject Code"), subCode,
      el("label", {}, "Subject Title"), subName,
      el("label", {}, "Assigned Class"), subClass,
      el("label", {}, "Assigned Faculty Member"), subFaculty,
      mErr
    ), [
      el("button", { class: "ghost", onclick: closeModal }, "Cancel"),
      el("button", {
        onclick: async () => {
          try {
            await api("/api/admin/subjects", {
              method: "POST",
              body: { code: subCode.value.trim(), name: subName.value.trim(), classId: +subClass.value, facultyId: +subFaculty.value }
            });
            closeModal();
            loadSubjects();
          } catch (e) { mErr.textContent = e.message; }
        }
      }, "Save Subject")
    ]);
  };
  loadSubjects();
  const subjectsPanel = el("div", { class: "card" }, addSubjectBtn, subjectWrap);

  const tabs = tabBar(["Classes", "Faculty", "Students", "Subjects"], [
    classesPanel,
    facultyPanel,
    studentsPanel,
    subjectsPanel
  ]);

  $app.append(
    el("div", { class: "card" },
      el("h3", {}, "Campus Administrator Console"),
      el("p", { class: "muted" }, "Manage classes, academic subjects, faculty assignments and student enrollment."),
      statCards
    ),
    tabs
  );
}

// ==========================================================================
// SYSTEM LIMITATIONS & ARCHITECTURE EXPLORER
// ==========================================================================
async function showLimitationsModal() {
  const loadingText = el("p", { class: "muted center" }, "Loading technical constraints & threat vectors…");
  showModal("System Limitations & Security Audit", loadingText, [
    el("button", { class: "ghost", onclick: closeModal }, "Close")
  ]);

  try {
    const data = await api("/api/system/limitations");
    const filterPills = el("div", { class: "tabs", style: "margin-bottom:1rem;" });
    const cardsContainer = el("div", {});

    const categories = ["ALL", ...new Set(data.limitations.map(l => l.category))];
    let activeCat = "ALL";

    function renderCards() {
      const filtered = activeCat === "ALL"
        ? data.limitations
        : data.limitations.filter(l => l.category === activeCat);

      cardsContainer.replaceChildren(
        ...filtered.map(l => el("div", { class: "limitation-item" },
          el("div", { class: "limitation-header" },
            el("strong", { style: "font-size:0.95rem;" }, l.id + ": " + l.title),
            el("span", { class: "limits-badge " + l.severity }, l.severity)
          ),
          el("div", { class: "limitation-desc" }, l.description),
          el("div", { class: "limitation-mitigation" },
            el("b", {}, "Mitigation / v2 Fix: "), l.mitigation
          )
        ))
      );
    }

    categories.forEach(cat => {
      const btn = el("button", {
        class: cat === activeCat ? "active small" : "small",
        onclick: () => {
          activeCat = cat;
          [...filterPills.children].forEach(b => b.classList.toggle("active", b.textContent === cat));
          renderCards();
        }
      }, cat);
      filterPills.append(btn);
    });

    renderCards();

    const body = el("div", {},
      el("p", { class: "muted", style: "margin-bottom:1rem;" },
        "Architectural assessment, hardware bounds, and security threat mitigations in QR Attendance v2.1:"
      ),
      filterPills,
      cardsContainer
    );

    showModal("System Limitations & Security Audit", body, [
      el("button", { class: "ghost", onclick: closeModal }, "Close")
    ]);
  } catch (e) {
    showModal("System Limitations", el("div", { class: "err" }, e.message), [
      el("button", { class: "ghost", onclick: closeModal }, "Close")
    ]);
  }
}

// ------------------------------------------------------------------ Start Application
route();
