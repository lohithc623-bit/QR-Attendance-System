# QR Attendance System (v2)

CSE203 (OOP using Java) mini project. Two front-ends share one set of business rules:

* **Desktop app** (JavaFX) - students, faculty and the administrator, with a **live webcam scanner**.
* **Mobile web app** (installable PWA) served by a small **REST API** - students show a live QR on their phone,
  faculty scan with the phone camera. Only the API server talks to MySQL.

```
 JavaFX desktop ─┐                          ┌─ service/  (all rules)  ─ dao/ ─ JDBC ─ MySQL
                 ├── same service layer ────┤
 Phone browser ──┴── HTTPS ── api/ApiServer ┘
```

## What changed in v2 (every weak spot from the code review)

| Weakness in v1 | Fix in v2 |
|---|---|
| Scan only from an image file | **Live webcam scanning** (desktop `ScanDialog`) and **phone-camera scanning** (web app). Green/red feedback, beep, vibration. Image-file scan kept as a fallback. |
| Client connected straight to MySQL | New **REST API** (`api/ApiServer`, JDK built-in HTTP server, no framework). Phones never touch the DB, so it is never exposed to the network. Bearer-token sessions, role checks, login lockout. |
| Unsalted SHA-256 passwords | **PBKDF2-HMAC-SHA256**, random salt per user, 120 000 iterations (JDK only). Old hashes still log in and are **upgraded automatically** on first login. Min. 8 chars for new accounts. |
| QR never changed (screenshots / proxy attendance) | **Rotating signed QR**: `QRA2:<studentId>:<window>:<HMAC>`, new code every 30 s, accepted for ~1 minute. The permanent token is only an HMAC key and never appears in the QR. Old static codes are rejected. |
| Default `root/root` credentials | No credentials in the code. Settings come from env vars or a git-ignored `local.properties`. The app **refuses to start with root/root or the CHANGE_ME placeholder**. `database/create_app_user.sql` makes a least-privilege user. |
| Several queries per subject | Student summary, class report and subject report are now **one SQL query each** (no loops). Added an index for session lookups. |
| Only PRESENT rows | `ABSENT` status, **manual override** per student, and **"Finish session"** which marks everyone not scanned as absent. A late student can still be scanned afterwards. |
| No live faculty view | Roster of every enrolled student with PRESENT / ABSENT / not marked, auto-refreshing every 3 s (desktop and web). |
| No warnings / charts | **Percentage ring**, per-subject progress bars, and a **below-75% warning** with "attend the next N classes in a row to recover". |
| No export | **CSV export** of class and subject reports (desktop + web). Formula-injection safe, opens correctly in Excel. |
| No dark mode / phone layout | Dark/light toggle (desktop and web); responsive mobile-first web UI. |
| Basic UI / limited screens | **Modern Design System**: Polished glassmorphism cards, interactive rotating QR code, laser camera scanner reticle, fast demo login chips, and print-ready reports. |
| No Google login option | **Google Single Sign-On (SSO)**: One-click Google campus account chooser and Google Identity Services integration with campus ID resolution. |
| Admin restricted to desktop | **Web Admin Dashboard**: Manage classes, faculty, students, and subjects directly in the web browser. |
| Undocumented constraints | **System Limitations Explorer**: In-app audit modal & comprehensive academic defense guide in [LIMITATIONS.md](LIMITATIONS.md). |

Also fixed while in there: a faculty member could previously mark attendance for **any** subject - the service now checks the subject really belongs to that faculty and class; future dates and periods outside 1-8 are rejected; the fat jar now starts correctly (`Launcher` class).

## Setup

You need JDK 17+, Maven and MySQL 8.

**1. Database**

```bash
mysql -u root -p < database/schema.sql
# edit the password inside the file first, then:
mysql -u root -p < database/create_app_user.sql
```

Upgrading an existing v1 database instead? Run `database/migration_v1_to_v2.sql` - your data is kept.

Sample logins (change them!): `admin / admin123`, `FAC1001 / faculty123`, `9923CS0001 / student123`.

**2. Configure the connection** - copy `local.properties.example` to `local.properties` (same folder you run from) and set `db.user` / `db.password`. Or set `DB_USER` / `DB_PASSWORD` environment variables.

**3. Run the desktop app**

```bash
mvn clean javafx:run
```

On Windows, you can create a desktop shortcut by running this once from the project folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\install-desktop-shortcut.ps1
```

Double-click **QR Attendance** on the desktop to start the app. The shortcut runs `mvn javafx:run`
from the project folder, so keep `local.properties` there and make sure Maven, JDK 17+, and MySQL are
available as described above.

**4. Run the mobile web app / API server**

```bash
mvn clean package
java -cp target/qr-attendance-system-1.0.0.jar com.attendance.api.ServerMain
```

Open `http://localhost:8080/` (or the port you set). The web app covers students and faculty; **administration stays in the desktop app**.

### Using phones: you need HTTPS

Browsers only allow camera access on `https://` pages (or `localhost`). For phones on your Wi-Fi create a certificate once:

```bash
keytool -genkeypair -alias qr -keyalg RSA -keysize 2048 -storetype PKCS12 \
        -keystore keystore.p12 -validity 365 -ext san=ip:YOUR.LAN.IP,dns:localhost
```

then add to `local.properties`: `api.tls.keystore=keystore.p12`, `api.tls.password=...`, `api.port=8443`. Open `https://YOUR.LAN.IP:8443/` on the phone, accept the self-signed certificate warning once, and use *Add to Home Screen*. (A tunnel such as Cloudflare Tunnel gives a trusted certificate without the warning.)

The scanner library (html5-qrcode) loads from unpkg.com on first use. To run fully offline, download `html5-qrcode.min.js` into `src/main/resources/web/` and change the `<script src>` in `index.html` to `/html5-qrcode.min.js`.

## How attendance marking works

1. The student's screen (desktop or phone) shows a **live QR** that changes every 30 s.
2. Faculty pick **class → subject → date → period** and scan with the webcam / phone camera. Checks run in this order:
   0. faculty teaches this subject in this class (`AccessDeniedException`)
   1. QR is well formed and correctly signed (`InvalidQRCodeException`)
   1b. QR is not older than the rotation window (`ExpiredQRCodeException`)
   2. student is in the selected class (`StudentClassMismatchException`)
   3. student is registered for the subject (`StudentNotRegisteredException`)
   4. not already PRESENT for that subject/date/period (`DuplicateAttendanceException`)
3. The row is stored as `PRESENT` (method `QR`). Faculty can flip any student with the **Override** button, and **Finish session** marks the rest `ABSENT`.
4. Percentage = PRESENT rows ÷ distinct sessions held for that subject.

## Project layout

```
src/main/java/com/attendance/
  config/     AppConfig, DBConfig        - env / local.properties / defaults, credential safety check
  db/         DatabaseConnection
  model/      User, Student, Faculty, SchoolClass, Subject, AttendanceRecord, RosterEntry,
              AttendanceStatus, SubjectAttendanceSummary, StudentAttendanceSummary
  dao/        plain JDBC (AttendanceDAO holds the single-query reports)
  service/    AuthService, AttendanceService, AdminService   - business rules
  exception/  ...Exception classes (+ ExpiredQRCodeException)
  util/       PasswordUtil (PBKDF2), QRTokenService (rotating HMAC QR), QRImageWriter / QRCodeUtil (ZXing),
              CsvUtil, Json
  api/        ApiServer, ServerMain          - REST + static web app, optional HTTPS
  ui/         Login/Student/Faculty/Admin views, ScanDialog (live webcam), SceneManager (themes)
  Main.java, Launcher.java
src/main/resources/web/   index.html, app.js, style.css, sw.js, manifest.webmanifest, icon.svg
database/                 schema.sql, migration_v1_to_v2.sql, create_app_user.sql
```

## Viva demo flow

1. Admin: add a class, subject, faculty and student (the student gets a fresh secret QR key automatically).
2. Student on a phone or the desktop: show the live QR and its countdown, the ring, subject bars and any warning.
3. Faculty: choose a period, click **Live camera scan**, scan the student → green banner + beep, roster updates.
4. Scan a **screenshot taken a few minutes ago** → "expired" (the rotating-QR point).
5. Scan the same live code twice → "already marked". Scan a student from another class → class mismatch.
6. **Override** one student, then **Finish session** → the rest become absent. Export the report to CSV or print to PDF.
7. Click **System Limitations**: inspect real-time technical audit and threat mitigations directly in the UI.

## System Limitations (Honest Notes)

For the complete, publication-grade academic analysis and viva defense guide, see [LIMITATIONS.md](LIMITATIONS.md):

* **Hybrid Architecture**: The desktop app still connects to MySQL directly (suitable for a closed lab PC); phones and remote browsers access the system strictly via the HTTPS REST API.
* **In-Memory Session Store**: Web sessions are maintained in server memory (`ConcurrentHashMap`) for zero external dependencies; a server restart clears active sessions.
* **Live Video Relay Constraint**: Rotating QR codes stop static screenshots and printed photos. However, an absent student streaming their live screen via video call to a classmate requires physical faculty supervision or GPS geofencing (planned for v3).
* **Reporting Format**: Direct RFC 4180 CSV export (Excel) and print-optimized PDF view via browser print; server-side vector PDF generation is omitted to keep JAR footprint lightweight.

