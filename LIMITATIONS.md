# Technical Constraints, Security Analysis & System Limitations

> **Project:** QR Attendance System (v2.1)  
> **Course / Context:** CSE203 Object-Oriented Programming using Java (Academic Mini-Project)  
> **Architecture:** Hybrid JavaFX (Desktop Client) & REST API (Mobile PWA / Phone Client)  

---

## 1. Executive Summary

This document provides a rigorous, transparent breakdown of all technical limitations, architectural trade-offs, security vectors, and functional boundaries present in the QR Attendance System. In academic evaluations (project defenses, viva examinations, and IEEE-standard documentation), demonstrating a profound, objective understanding of a system's constraints is as critical as showcasing its features.

---

## 2. Threat Analysis & Anti-Proxy Constraints

### 2.1 The Remote Screen-Sharing / Video-Relay Attack
* **The Problem:** The system implements a **dynamically rotating QR code** (`QRA2:<studentId>:<window>:<HMAC>`) generated via `HMAC-SHA256` with a shared secret key (`qr_token`), rotating every 30 seconds and valid for approximately 45–60 seconds. This **completely eliminates static screenshot sharing, WhatsApp image forwarding, and printed QR proxies** (a major flaw in v1).
* **The Remaining Limitation:** While screenshots fail after 30 seconds, an absent student can theoretically establish a real-time live video call (e.g., Discord, WhatsApp Video, Google Meet) with a proxy student sitting in the lecture hall. The proxy can aim their laptop screen at the faculty scanner while the absent student displays the live code.
* **Mitigation in v2:**
  1. The scanning terminal is operated directly by faculty (or placed at the front podium).
  2. The short 30-second window leaves minimal latency buffer for streaming compression artifacts or alignment.
* **Roadmap for v3:**
  - **Dynamic Geofencing:** Client-side GPS coordinates cryptographically bound to the HMAC payload (`QRA3:<id>:<window>:<lat>:<lng>:<HMAC>`).
  - **Campus Wi-Fi BSSID Binding:** The API rejects attendance tokens unless originating from the campus subnet / Access Point MAC.

---

### 2.2 Virtual Camera & Video Stream Injection
* **The Problem:** In desktop/laptop browsers, malicious software or tools like OBS Virtual Camera can inject pre-recorded video feeds into the browser's `getUserMedia` stream.
* **Mitigation in v2:**
  - The mobile web application explicitly requests `{ facingMode: "environment" }` to access physical rear cameras on mobile smartphones, where virtual camera drivers are rare and sandboxed by mobile OSs (iOS / Android).
* **Roadmap for v3:**
  - Hardware attestation via WebAuthn or device integrity APIs (SafetyNet / Play Integrity).

---

### 2.3 Absence of Multi-Factor Biometric Validation
* **The Problem:** The system validates **possession** (possession of the authenticated device and rotating cryptographic token) and **knowledge** (passwords/Google login), but does not incorporate **inherence** (biometrics: face recognition or fingerprint).
* **Architectural Trade-off:** Pure-Java facial recognition would add multi-gigabyte native dependencies (OpenCV/DL4J), high inference latency on commodity classroom PCs, and privacy concerns regarding biometric storage under GDPR/DPDP regulations.

---

## 3. Architecture & Networking Constraints

### 3.1 Hybrid Client Architecture: Desktop Direct JDBC vs Mobile REST API
* **The Problem:**
  - The **mobile web app** communicates strictly via the REST API (`com.attendance.api.ApiServer`), meaning phone browsers never touch the MySQL database.
  - However, the **JavaFX desktop application** currently connects directly to MySQL via JDBC (`DatabaseConnection`).
* **Implication:**
  - Direct JDBC requires opening MySQL's port `3306` to the local desktop machine. If deployed outside a local LAN or lab PC, this exposes database ports unless proxied or tunneled.
* **Mitigation in v2:**
  - Enforced least-privilege database user (`database/create_app_user.sql`).
  - Web admin dashboard added in v2.1 to enable web-only administration without desktop dependency.
* **Roadmap for v3:**
  - Migrate JavaFX desktop view models to consume `ApiServer` HTTP endpoints using JDK 11+ `HttpClient`.

---

### 3.2 Ephemeral In-Memory Session Storage
* **The Problem:**
  - `ApiServer.java` maintains user login sessions in a `ConcurrentHashMap<String, Session>` held in JVM heap memory.
* **Implication:**
  - If the API server process restarts, all active bearer tokens are invalidated, requiring students and faculty to log in again.
  - The server cannot easily scale horizontally across multiple instances behind a load balancer without sticky sessions.
* **Mitigation in v2:**
  - Zero external dependency design (runs on pure JDK without requiring Redis or Hazelcast clusters).
* **Roadmap for v3:**
  - Replace in-memory map with stateless JWTs (signed with an HMAC key) or Redis-backed distributed sessions.

---

### 3.3 Strict HTTPS Enforcement for Mobile Camera Access
* **The Problem:**
  - Modern web browsers (Safari on iOS, Chrome on Android) strictly restrict camera hardware access (`navigator.mediaDevices.getUserMedia`) to **secure origins** (`https://` or `http://localhost`).
  - Accessing the API over local LAN IP (e.g. `http://192.168.1.50:8080/`) will cause the browser to block camera scanning.
* **Mitigation in v2:**
  - Embedded PKCS12 TLS support in `ApiServer` (`api.tls.keystore` and `api.tls.password` in `local.properties`).
  - Supports self-signed SAN certificates (`dns:localhost`, `ip:YOUR.LAN.IP`) or reverse tunnels (Cloudflare Tunnel / ngrok).
  - Web application provides a fallback file-upload image scanner (`scanFile`) for devices without camera hardware.

---

## 4. Academic, Domain & Functional Limits

| # | Feature / Area | v2 Current Boundary | Practical Consequence | Proposed v3 Resolution |
|---|---|---|---|---|
| 1 | **Period Timetable** | Fixed 1 to 8 periods per day | Cannot model non-standard time slots (e.g. 10:15 - 11:45) or 3-hour practical lab sessions as a single block | Configurable `session_slots` table with start/end time ranges |
| 2 | **Faculty Assignment** | 1 faculty per subject offering | Co-teaching, lab teaching assistants, and substitute teachers cannot co-mark | Many-to-many `faculty_subject_assignments` junction table |
| 3 | **Attendance Threshold** | Hardcoded 75% requirement | Cannot configure per-course thresholds (e.g. 80% for practicals, 65% for sports quota) | Column `min_attendance_pct` on `classes` or `subjects` table |
| 4 | **Manual Override Audit** | Overrides are recorded in-place as `MANUAL` | Does not store the timestamp, faculty reason, or prior state before override | Dedicated `attendance_audit_log` table storing before/after diffs |
| 5 | **Condonation / Medical Leave** | Binary status (`PRESENT` or `ABSENT`) | Medical exemptions, sports OD (On Duty), and condonation leaves cannot be tagged | Add `ON_DUTY` and `MEDICAL_LEAVE` ENUM states |

---

## 5. Hardware, Device & Environmental Factors

1. **Screen Glare and Reflectivity:**
   - Scanning smartphone screens using a webcam can cause glare and reflection from classroom fluorescent lighting, temporarily degrading QR decoding contrast.
2. **Low-End Camera Autofocus:**
   - Cheap external USB webcams lacking hardware autofocus may struggle to focus on small phone screens held closer than 15 cm.
   - *Mitigation:* The mobile web app displays a high-contrast 280px QR code with pixelated image-rendering and white padding.
3. **Student Smartphone Dependency:**
   - If a student runs out of phone battery, forgets their device, or has no connectivity:
   - *Mitigation:* Faculty can immediately mark them present using the **1-click manual override** button on the live session roster.

---

## 6. Data Export & Interoperability Bounds

* **CSV vs Vector PDF:**
  - Reports export cleanly to RFC 4180 CSV format (opens natively in Microsoft Excel, Google Sheets, and LibreOffice).
  - Native server-side vector PDF generation (e.g. Apache PDFBox or iText) was deliberately omitted to prevent JAR bloat.
  - *Mitigation in v2.1:* Web application includes custom `@media print` CSS so faculty can press **"🖨️ Print / Save as PDF"** to produce print-ready reports directly from Chrome / Safari.
* **Lack of LMS / ERP Sync:**
  - The project does not currently integrate with university ERP systems (SAP, Canvas, Moodle, Blackboard) via LTI 1.3 or SCIM.

---

## 7. Comparative Assessment Matrix

| Dimension | Legacy System (v1) | Current System (v2.1) | Production Enterprise (v3 Target) |
|---|---|---|---|
| **QR Code Security** | Static token (screenshots work) | Rotating HMAC-SHA256 (30s window) | Geofenced + Biometric Multi-Factor |
| **Database Exposure** | Direct JDBC everywhere | Mobile clients isolated behind REST API | 100% microservice API, Zero Direct DB |
| **Password Storage** | Plain / Unsalted SHA-256 | Salted PBKDF2 (120,000 iters) | PBKDF2 + Argon2id / Enterprise SSO |
| **Authentication Options**| Username/Password only | Credentials + **Google SSO** | OAuth2 / OpenID Connect + SAML 2.0 |
| **Administration** | Desktop only | Desktop + **Web Admin Dashboard** | Role-Based Access Control (RBAC) Portal |
| **Session Tracking** | None | Real-time 3s auto-refresh roster | WebSocket / Server-Sent Events (SSE) |
| **Reporting** | None | Single-query SQL + CSV + Print PDF | Automated PDF generation + Email alerts |

---

## 8. Viva Voce / Project Defense Q&A

**Q1: "Can a student take a photo of their friend's phone in the morning and scan it in class?"**  
*Answer:* **No.** The system generates dynamic codes using `QRA2:<studentId>:<window>:<HMAC>`. The window changes every 30 seconds. A photo taken earlier in the morning has an expired time-window and is rejected with an `ExpiredQRCodeException`.

**Q2: "What if a student livestreams their screen on WhatsApp to a classmate in the lecture hall?"**  
*Answer:* This is the primary theoretical anti-proxy limitation documented in §2.1. While streaming overcomes the 30-second window, it requires an accomplice physically present. Faculty supervise the podium scan station. In v3, binding device GPS coordinates or campus Wi-Fi BSSID to the HMAC key neutralizes remote relays.

**Q3: "Why doesn't the desktop app use the REST API like the mobile app?"**  
*Answer:* The desktop app was built as a standalone workstation application using JavaFX and direct JDBC for high throughput in a closed campus LAN environment. In v2.1, we introduced a full Web Admin Suite on the REST API so administrators can manage the entire institution via browser without touching JDBC directly.

**Q4: "Why use in-memory sessions instead of database sessions?"**  
*Answer:* In-memory concurrent hash maps provide $O(1)$ zero-latency lookups and require zero external infrastructure (e.g. Redis), fitting the project's goal of a lightweight, self-contained educational application. For horizontal production scaling, transitioning to signed JWTs or Redis is straightforward.
