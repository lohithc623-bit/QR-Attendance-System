package com.attendance.api;

import com.attendance.dao.StudentDAO;
import com.attendance.exception.AccessDeniedException;
import com.attendance.exception.AuthenticationException;
import com.attendance.exception.DataAccessException;
import com.attendance.exception.DuplicateAttendanceException;
import com.attendance.exception.ExpiredQRCodeException;
import com.attendance.exception.InvalidQRCodeException;
import com.attendance.exception.StudentClassMismatchException;
import com.attendance.exception.StudentNotRegisteredException;
import com.attendance.model.AttendanceRecord;
import com.attendance.model.Faculty;
import com.attendance.model.RosterEntry;
import com.attendance.model.Role;
import com.attendance.model.SchoolClass;
import com.attendance.model.Student;
import com.attendance.model.StudentAttendanceSummary;
import com.attendance.model.Subject;
import com.attendance.model.SubjectAttendanceSummary;
import com.attendance.service.AdminService;
import com.attendance.service.AttendanceService;
import com.attendance.service.AuthService;
import com.attendance.util.CsvUtil;
import com.attendance.util.Json;
import com.attendance.util.QRImageWriter;
import com.attendance.util.QRTokenService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST API + static mobile web app, on the JDK's built-in HTTP server (no framework needed).
 * Reuses the existing service layer unchanged, so every business rule applies identically
 * to the desktop app and to phones. Only THIS process talks to MySQL.
 */
public class ApiServer {

    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    private static final long SESSION_TTL_MS = 8L * 60 * 60 * 1000;
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_FAILED_LOGINS = 5;
    private static final long LOCKOUT_MS = 60_000;

    /** A logged-in API session, looked up by an unguessable bearer token. */
    private record Session(int userId, Role role, String name, Integer studentId, Integer facultyId, long expiresAt) { }

    private record Failures(int count, long lockedUntil) { }

    private final AuthService authService = new AuthService();
    private final AttendanceService attendance = new AttendanceService();
    private final AdminService adminService = new AdminService();
    private final StudentDAO studentDAO = new StudentDAO();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    /** Thrown to produce a specific HTTP status from anywhere in a handler. */
    private static final class HttpError extends RuntimeException {
        final int status;
        final String code;
        HttpError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    public void attachTo(HttpServer server) {
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(16));
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(ex, path);
            } else {
                handleStatic(ex, path);
            }
        } catch (HttpError e) {
            sendJson(ex, e.status, error(e.code, e.getMessage()));
        } catch (AuthenticationException e) {
            sendJson(ex, 401, error("AUTH", e.getMessage()));
        } catch (AccessDeniedException e) {
            sendJson(ex, 403, error("FORBIDDEN", e.getMessage()));
        } catch (ExpiredQRCodeException e) {
            sendJson(ex, 422, error("EXPIRED", e.getMessage()));
        } catch (InvalidQRCodeException e) {
            sendJson(ex, 422, error("INVALID_QR", e.getMessage()));
        } catch (StudentClassMismatchException e) {
            sendJson(ex, 422, error("WRONG_CLASS", e.getMessage()));
        } catch (StudentNotRegisteredException e) {
            sendJson(ex, 422, error("NOT_REGISTERED", e.getMessage()));
        } catch (DuplicateAttendanceException e) {
            sendJson(ex, 409, error("DUPLICATE", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error("BAD_REQUEST", e.getMessage()));
        } catch (DataAccessException e) {
            LOG.log(Level.SEVERE, "Database error", e);
            sendJson(ex, 500, error("SERVER", "Server error. Please try again."));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unexpected error", e);
            sendJson(ex, 500, error("SERVER", "Server error. Please try again."));
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();

        if (path.equals("/api/login") && method.equals("POST")) {
            login(ex);
            return;
        }
        if (path.equals("/api/auth/google") && method.equals("POST")) {
            googleLogin(ex);
            return;
        }
        if (path.equals("/api/config/auth") && method.equals("GET")) {
            sendJson(ex, 200, Map.of(
                    "googleClientId", com.attendance.config.AppConfig.get("google.client.id", "")
            ));
            return;
        }
        if (path.equals("/api/system/limitations") && method.equals("GET")) {
            systemLimitations(ex);
            return;
        }

        Session session = requireSession(ex);

        switch (path) {
            case "/api/logout" -> {
                sessions.remove(bearer(ex));
                sendJson(ex, 200, Map.of("ok", true));
            }
            case "/api/student/me" -> studentMe(ex, requireRole(session, Role.STUDENT));
            case "/api/student/qr" -> studentQr(ex, requireRole(session, Role.STUDENT));
            case "/api/student/qr.png" -> studentQrPng(ex, requireRole(session, Role.STUDENT));
            case "/api/student/history" -> studentHistory(ex, requireRole(session, Role.STUDENT));
            case "/api/faculty/classes" -> facultyClasses(ex, requireRole(session, Role.FACULTY));
            case "/api/faculty/subjects" -> facultySubjects(ex, requireRole(session, Role.FACULTY));
            case "/api/faculty/scan" -> facultyScan(ex, requireRole(session, Role.FACULTY));
            case "/api/faculty/session" -> facultySession(ex, requireRole(session, Role.FACULTY));
            case "/api/faculty/mark" -> facultyMark(ex, requireRole(session, Role.FACULTY));
            case "/api/faculty/finalize" -> facultyFinalize(ex, requireRole(session, Role.FACULTY));
            case "/api/faculty/report" -> facultyReport(ex, requireRole(session, Role.FACULTY));
            case "/api/admin/overview" -> adminOverview(ex, requireRole(session, Role.ADMIN));
            case "/api/admin/classes" -> adminClasses(ex, requireRole(session, Role.ADMIN));
            case "/api/admin/faculty" -> adminFaculty(ex, requireRole(session, Role.ADMIN));
            case "/api/admin/students" -> adminStudents(ex, requireRole(session, Role.ADMIN));
            case "/api/admin/subjects" -> adminSubjects(ex, requireRole(session, Role.ADMIN));
            default -> throw new HttpError(404, "NOT_FOUND", "No such endpoint.");
        }
    }

    // ------------------------------------------------------------------ auth

    private void login(HttpExchange ex) throws Exception {
        Map<String, Object> body = readBody(ex);
        String username = str(body, "username");
        String password = str(body, "password");
        Role role;
        try {
            role = Role.valueOf(str(body, "role").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "BAD_REQUEST", "Unknown role.");
        }

        String key = username.toLowerCase();
        Failures f = failures.get(key);
        if (f != null && f.lockedUntil() > System.currentTimeMillis()) {
            throw new HttpError(429, "LOCKED", "Too many failed attempts. Try again in a minute.");
        }

        AuthService.LoginResult result;
        try {
            result = authService.login(username, password, role);
        } catch (AuthenticationException e) {
            int count = (f == null ? 0 : f.count()) + 1;
            failures.put(key, count >= MAX_FAILED_LOGINS
                    ? new Failures(0, System.currentTimeMillis() + LOCKOUT_MS) : new Failures(count, 0));
            throw e;
        }
        failures.remove(key);

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = java.util.HexFormat.of().formatHex(raw);
        Session s = new Session(result.user.getId(), role, result.user.getFullName(),
                result.studentProfile == null ? null : result.studentProfile.getId(),
                result.facultyProfile == null ? null : result.facultyProfile.getId(),
                System.currentTimeMillis() + SESSION_TTL_MS);
        sessions.put(token, s);
        purgeExpired();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("role", role.name());
        out.put("name", s.name());
        sendJson(ex, 200, out);
    }

    private void googleLogin(HttpExchange ex) throws Exception {
        requirePost(ex);
        Map<String, Object> body = readBody(ex);
        String email = str(body, "email");
        String roleStr = (String) body.get("role");
        Role role = (roleStr != null && !roleStr.isBlank()) ? Role.valueOf(roleStr.toUpperCase()) : null;
        String campusId = (String) body.get("campusId");

        AuthService.LoginResult result;
        try {
            result = authService.loginWithGoogle(email, role, campusId);
        } catch (AuthenticationException e) {
            throw new HttpError(401, "AUTH", e.getMessage());
        }

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = java.util.HexFormat.of().formatHex(raw);
        Session s = new Session(result.user.getId(), result.user.getRole(), result.user.getFullName(),
                result.studentProfile == null ? null : result.studentProfile.getId(),
                result.facultyProfile == null ? null : result.facultyProfile.getId(),
                System.currentTimeMillis() + SESSION_TTL_MS);
        sessions.put(token, s);
        purgeExpired();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("role", result.user.getRole().name());
        out.put("name", s.name());
        out.put("email", email);
        sendJson(ex, 200, out);
    }


    private String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : "";
    }

    private Session requireSession(HttpExchange ex) {
        Session s = sessions.get(bearer(ex));
        if (s == null || s.expiresAt() < System.currentTimeMillis()) {
            throw new HttpError(401, "AUTH", "Please log in again.");
        }
        return s;
    }

    private Session requireRole(Session s, Role role) {
        if (s.role() != role) {
            throw new HttpError(403, "FORBIDDEN", "Not allowed for your role.");
        }
        return s;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> s.expiresAt() < now);
    }

    // ------------------------------------------------------------------ student

    private void studentMe(HttpExchange ex, Session s) throws IOException {
        Student st = studentDAO.findById(s.studentId()).orElseThrow(() -> new HttpError(404, "NOT_FOUND", "Student not found."));
        List<SubjectAttendanceSummary> subjects = attendance.getSubjectWiseSummary(st.getId());
        List<Object> rows = new ArrayList<>();
        for (SubjectAttendanceSummary x : subjects) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", x.getSubjectCode());
            m.put("name", x.getSubjectName());
            m.put("present", x.getPresentCount());
            m.put("total", x.getTotalClasses());
            m.put("percentage", round1(x.getPercentage()));
            m.put("belowThreshold", x.isBelowThreshold());
            m.put("classesNeeded", x.classesNeededToRecover());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", st.getFullName());
        out.put("registerNumber", st.getRegisterNumber());
        out.put("department", st.getDepartment());
        out.put("semester", st.getSemester());
        out.put("section", st.getSection());
        out.put("overall", round1(AttendanceService.overallPercentage(subjects)));
        out.put("subjects", rows);
        sendJson(ex, 200, out);
    }

    private String currentPayload(Session s) {
        Student st = studentDAO.findById(s.studentId()).orElseThrow(() -> new HttpError(404, "NOT_FOUND", "Student not found."));
        return QRTokenService.generate(st.getId(), st.getQrToken(), Instant.now());
    }

    private void studentQr(HttpExchange ex, Session s) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payload", currentPayload(s));
        out.put("secondsLeft", QRTokenService.secondsUntilRotation(Instant.now()));
        out.put("stepSeconds", QRTokenService.stepSeconds());
        sendJson(ex, 200, out);
    }

    private void studentQrPng(HttpExchange ex, Session s) throws IOException {
        byte[] png = QRImageWriter.toPng(currentPayload(s), 320);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "image/png");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, png.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(png);
        }
    }

    private void studentHistory(HttpExchange ex, Session s) throws IOException {
        List<Object> rows = new ArrayList<>();
        for (AttendanceRecord r : attendance.getAttendanceHistory(s.studentId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", r.getAttendanceDate().toString());
            m.put("period", r.getPeriod());
            m.put("subject", r.getSubjectName());
            m.put("status", r.getStatus());
            m.put("time", r.getMarkedAt().toLocalTime().withNano(0).toString());
            rows.add(m);
        }
        sendJson(ex, 200, Map.of("history", rows));
    }

    // ------------------------------------------------------------------ faculty

    private void facultyClasses(HttpExchange ex, Session s) throws IOException {
        List<Object> rows = new ArrayList<>();
        for (SchoolClass c : attendance.getClassesForFaculty(s.facultyId())) {
            rows.add(Map.of("id", c.getId(), "name", c.getClassName()));
        }
        sendJson(ex, 200, Map.of("classes", rows));
    }

    private void facultySubjects(HttpExchange ex, Session s) throws IOException {
        int classId = intParam(query(ex), "classId");
        List<Object> rows = new ArrayList<>();
        for (Subject sub : attendance.getSubjectsForFaculty(s.facultyId(), classId)) {
            rows.add(Map.of("id", sub.getId(), "code", sub.getSubjectCode(), "name", sub.getSubjectName()));
        }
        sendJson(ex, 200, Map.of("subjects", rows));
    }

    private record SessionKey(int classId, int subjectId, LocalDate date, int period) { }

    private SessionKey sessionKey(Map<String, ?> p) {
        return new SessionKey(intParam(p, "classId"), intParam(p, "subjectId"), dateParam(p, "date"), intParam(p, "period"));
    }

    private void facultyScan(HttpExchange ex, Session s) throws Exception {
        requirePost(ex);
        Map<String, Object> body = readBody(ex);
        SessionKey k = sessionKey(body);
        Student st = attendance.markAttendance(str(body, "payload"), s.facultyId(),
                k.classId(), k.subjectId(), k.date(), k.period());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("name", st.getFullName());
        out.put("registerNumber", st.getRegisterNumber());
        sendJson(ex, 200, out);
    }

    private void facultySession(HttpExchange ex, Session s) throws IOException {
        SessionKey k = sessionKey(query(ex));
        List<RosterEntry> roster = attendance.getRoster(s.facultyId(), k.classId(), k.subjectId(), k.date(), k.period());
        int present = 0, absent = 0, pending = 0;
        List<Object> rows = new ArrayList<>();
        for (RosterEntry r : roster) {
            if ("PRESENT".equals(r.getStatus())) present++;
            else if ("ABSENT".equals(r.getStatus())) absent++;
            else pending++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", r.getStudentId());
            m.put("registerNumber", r.getRegisterNumber());
            m.put("name", r.getStudentName());
            m.put("status", r.getStatus());
            m.put("method", r.getMethod());
            m.put("time", r.getMarkedAt() == null ? null : r.getMarkedAt().toLocalTime().withNano(0).toString());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", present);
        out.put("absent", absent);
        out.put("pending", pending);
        out.put("roster", rows);
        sendJson(ex, 200, out);
    }

    private void facultyMark(HttpExchange ex, Session s) throws Exception {
        requirePost(ex);
        Map<String, Object> body = readBody(ex);
        SessionKey k = sessionKey(body);
        attendance.setStatus(s.facultyId(), intParam(body, "studentId"), k.classId(), k.subjectId(),
                k.date(), k.period(), str(body, "status").toUpperCase());
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void facultyFinalize(HttpExchange ex, Session s) throws Exception {
        requirePost(ex);
        SessionKey k = sessionKey(readBody(ex));
        int n = attendance.finalizeSession(s.facultyId(), k.classId(), k.subjectId(), k.date(), k.period());
        sendJson(ex, 200, Map.of("ok", true, "markedAbsent", n));
    }

    private void facultyReport(HttpExchange ex, Session s) throws IOException {
        Map<String, String> q = query(ex);
        String type = q.getOrDefault("type", "class");
        int classId = intParam(q, "classId");
        List<StudentAttendanceSummary> data = type.equals("subject")
                ? attendance.getSubjectWiseReport(s.facultyId(), intParam(q, "subjectId"), classId)
                : attendance.getClassWiseReport(s.facultyId(), classId);

        if ("csv".equals(q.get("format"))) {
            List<List<String>> rows = new ArrayList<>();
            for (StudentAttendanceSummary r : data) {
                rows.add(List.of(r.getRegisterNumber(), r.getStudentName(), String.valueOf(r.getPresentCount()),
                        String.valueOf(r.getTotalClasses()), String.format("%.1f%%", r.getPercentage())));
            }
            byte[] csv = ("\uFEFF" + CsvUtil.toCsv(
                    List.of("Register No.", "Name", "Present", "Total Classes", "Percentage"), rows))
                    .getBytes(StandardCharsets.UTF_8);
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", "text/csv; charset=utf-8");
            h.set("Content-Disposition", "attachment; filename=\"attendance-" + type + "-report.csv\"");
            ex.sendResponseHeaders(200, csv.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(csv);
            }
            return;
        }
        List<Object> rows = new ArrayList<>();
        for (StudentAttendanceSummary r : data) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("registerNumber", r.getRegisterNumber());
            m.put("name", r.getStudentName());
            m.put("present", r.getPresentCount());
            m.put("total", r.getTotalClasses());
            m.put("percentage", round1(r.getPercentage()));
            rows.add(m);
        }
        sendJson(ex, 200, Map.of("report", rows));
    }

    // ------------------------------------------------------------------ admin

    private void adminOverview(HttpExchange ex, Session s) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classesCount", adminService.listClasses().size());
        out.put("subjectsCount", adminService.listSubjects().size());
        out.put("facultyCount", adminService.listFaculty().size());
        out.put("studentsCount", adminService.listStudents().size());
        sendJson(ex, 200, out);
    }

    private void adminClasses(HttpExchange ex, Session s) throws Exception {
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            List<SchoolClass> list = adminService.listClasses();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (SchoolClass c : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("className", c.getClassName());
                m.put("department", c.getDepartment());
                m.put("semester", c.getSemester());
                m.put("section", c.getSection());
                rows.add(m);
            }
            sendJson(ex, 200, Map.of("classes", rows));
        } else if (method.equals("POST")) {
            Map<String, Object> b = readBody(ex);
            String className = str(b, "className");
            String department = str(b, "department");
            int semester = intParam(b, "semester");
            String section = str(b, "section");
            SchoolClass c = adminService.createClass(className, department, semester, section);
            sendJson(ex, 200, Map.of("ok", true, "class", Map.of("id", c.getId(), "name", c.getClassName())));
        } else if (method.equals("DELETE")) {
            Map<String, String> q = query(ex);
            int id = Integer.parseInt(q.getOrDefault("id", "0"));
            if (id <= 0) throw new HttpError(400, "BAD_REQUEST", "Missing id");
            adminService.deleteClass(id);
            sendJson(ex, 200, Map.of("ok", true));
        } else {
            throw new HttpError(405, "METHOD", "Method not allowed");
        }
    }

    private void adminFaculty(HttpExchange ex, Session s) throws Exception {
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            List<Faculty> list = adminService.listFaculty();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Faculty f : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("code", f.getFacultyCode());
                m.put("name", f.getFullName());
                m.put("department", f.getDepartment());
                rows.add(m);
            }
            sendJson(ex, 200, Map.of("faculty", rows));
        } else if (method.equals("POST")) {
            Map<String, Object> b = readBody(ex);
            String code = str(b, "code");
            String name = str(b, "name");
            String department = str(b, "department");
            String password = str(b, "password");
            Faculty f = adminService.createFaculty(code, name, department, password);
            sendJson(ex, 200, Map.of("ok", true, "faculty", Map.of("id", f.getId(), "code", f.getFacultyCode())));
        } else if (method.equals("DELETE")) {
            Map<String, String> q = query(ex);
            int id = Integer.parseInt(q.getOrDefault("id", "0"));
            if (id <= 0) throw new HttpError(400, "BAD_REQUEST", "Missing id");
            adminService.deleteFaculty(id);
            sendJson(ex, 200, Map.of("ok", true));
        } else {
            throw new HttpError(405, "METHOD", "Method not allowed");
        }
    }

    private void adminStudents(HttpExchange ex, Session s) throws Exception {
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            List<Student> list = adminService.listStudents();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Student st : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", st.getId());
                m.put("registerNumber", st.getRegisterNumber());
                m.put("name", st.getFullName());
                m.put("department", st.getDepartment());
                m.put("semester", st.getSemester());
                m.put("section", st.getSection());
                m.put("classId", st.getClassId());
                rows.add(m);
            }
            sendJson(ex, 200, Map.of("students", rows));
        } else if (method.equals("POST")) {
            Map<String, Object> b = readBody(ex);
            String regNo = str(b, "registerNumber");
            String name = str(b, "name");
            String department = str(b, "department");
            int semester = intParam(b, "semester");
            String section = str(b, "section");
            int classId = intParam(b, "classId");
            String password = str(b, "password");
            Student st = adminService.createStudent(regNo, name, department, semester, section, classId, password);
            sendJson(ex, 200, Map.of("ok", true, "student", Map.of("id", st.getId(), "registerNumber", st.getRegisterNumber())));
        } else if (method.equals("DELETE")) {
            Map<String, String> q = query(ex);
            int id = Integer.parseInt(q.getOrDefault("id", "0"));
            if (id <= 0) throw new HttpError(400, "BAD_REQUEST", "Missing id");
            adminService.deleteStudent(id);
            sendJson(ex, 200, Map.of("ok", true));
        } else {
            throw new HttpError(405, "METHOD", "Method not allowed");
        }
    }

    private void adminSubjects(HttpExchange ex, Session s) throws Exception {
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            List<Subject> list = adminService.listSubjects();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Subject sub : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", sub.getId());
                m.put("code", sub.getSubjectCode());
                m.put("name", sub.getSubjectName());
                m.put("classId", sub.getClassId());
                m.put("facultyId", sub.getFacultyId());
                rows.add(m);
            }
            sendJson(ex, 200, Map.of("subjects", rows));
        } else if (method.equals("POST")) {
            Map<String, Object> b = readBody(ex);
            String code = str(b, "code");
            String name = str(b, "name");
            int classId = intParam(b, "classId");
            int facultyId = intParam(b, "facultyId");
            Subject sub = adminService.createSubject(code, name, classId, facultyId);
            sendJson(ex, 200, Map.of("ok", true, "subject", Map.of("id", sub.getId(), "code", sub.getSubjectCode())));
        } else if (method.equals("DELETE")) {
            Map<String, String> q = query(ex);
            int id = Integer.parseInt(q.getOrDefault("id", "0"));
            if (id <= 0) throw new HttpError(400, "BAD_REQUEST", "Missing id");
            adminService.deleteSubject(id);
            sendJson(ex, 200, Map.of("ok", true));
        } else {
            throw new HttpError(405, "METHOD", "Method not allowed");
        }
    }

    private void systemLimitations(HttpExchange ex) throws IOException {
        List<Map<String, Object>> list = List.of(
                Map.of(
                        "id", "LIM-01",
                        "category", "Security & Anti-Proxy",
                        "severity", "MODERATE",
                        "title", "Live Screen-Share / Remote Video Relay",
                        "description", "Rotating QR codes (HMAC-SHA256 valid for 30s) eliminate static screenshot and printed proxy attendance. However, a student can theoretically stream their phone screen to a classmate via live video call in real time.",
                        "mitigation", "Faculty supervise the scanning station in class. Future roadmap includes GPS geofencing and campus Wi-Fi BSSID verification."
                ),
                Map.of(
                        "id", "LIM-02",
                        "category", "Security & Anti-Proxy",
                        "severity", "LOW",
                        "title", "Camera Stream Spoofing",
                        "description", "On laptop browsers, a malicious user could potentially hook a virtual webcam driver (e.g. OBS Virtual Camera) to feed pre-recorded frames.",
                        "mitigation", "Mobile browser scanning enforces physical rear-facing cameras ('environment'). Hardware attestation can be added in production apps."
                ),
                Map.of(
                        "id", "LIM-03",
                        "category", "Architecture & Networking",
                        "severity", "MODERATE",
                        "title", "Hybrid Architecture (Desktop Direct JDBC vs Web REST API)",
                        "description", "The JavaFX desktop application connects directly to MySQL via JDBC, while the mobile web application communicates via the REST API.",
                        "mitigation", "Desktop is intended for local college workstation/server use. Desktop client can be migrated to REST API endpoints."
                ),
                Map.of(
                        "id", "LIM-04",
                        "category", "Architecture & Networking",
                        "severity", "LOW",
                        "title", "In-Memory Session Storage",
                        "description", "Active bearer token sessions are maintained in ConcurrentHashMap server memory and reset when the server restarts.",
                        "mitigation", "Lightweight and zero external dependencies for evaluation. Production deployments can use Redis or JWT signed tokens."
                ),
                Map.of(
                        "id", "LIM-05",
                        "category", "Hardware & Network",
                        "severity", "MODERATE",
                        "title", "HTTPS Requirement for Mobile Camera Access",
                        "description", "Modern mobile browsers strictly enforce getUserMedia camera permissions on HTTPS (or localhost) only.",
                        "mitigation", "System bundles PKCS12 keystore TLS support (api.tls.keystore) and LAN IP SAN certificates, with Cloudflare/ngrok reverse proxy support."
                ),
                Map.of(
                        "id", "LIM-06",
                        "category", "Academic & Functional",
                        "severity", "LOW",
                        "title", "Fixed 8-Period Timetable Model",
                        "description", "Periods are constrained from 1 to 8, which fits typical university schedules but does not model flexible time durations or multi-period lab blocks.",
                        "mitigation", "Input validation ensures data integrity. Variable duration slots can be introduced in schema v3."
                ),
                Map.of(
                        "id", "LIM-07",
                        "category", "Academic & Functional",
                        "severity", "LOW",
                        "title", "Single Faculty per Subject Offering",
                        "description", "Each subject is currently assigned to one faculty member. Co-teaching or visiting faculty require administrator reassignment.",
                        "mitigation", "Administrators can quickly reassign or edit faculty via Admin Dashboard."
                ),
                Map.of(
                        "id", "LIM-08",
                        "category", "Reporting & Data",
                        "severity", "LOW",
                        "title", "CSV Export vs Vector PDF Generation",
                        "description", "Reports are exported in standard RFC 4180 CSV format (optimized for Microsoft Excel). Server-side PDF generation is omitted to keep fat JAR lightweight.",
                        "mitigation", "Web interface includes print-optimized CSS layout for direct 'Save as PDF' via browser print."
                )
        );
        sendJson(ex, 200, Map.of("limitations", list, "version", "2.1.0"));
    }

    // ------------------------------------------------------------------ static files (the mobile web app)

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "svg", "image/svg+xml",
            "png", "image/png", "ico", "image/x-icon",
            "webmanifest", "application/manifest+json");

    private void handleStatic(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            throw new HttpError(405, "METHOD", "Method not allowed.");
        }
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!name.matches("[A-Za-z0-9_.-]+\\.(html|js|css|svg|webmanifest|png|ico)")) {
            throw new HttpError(404, "NOT_FOUND", "Not found.");
        }
        try (InputStream in = ApiServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                throw new HttpError(404, "NOT_FOUND", "Not found.");
            }
            byte[] bytes = in.readAllBytes();
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", TYPES.get(name.substring(name.lastIndexOf('.') + 1)));
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Content-Security-Policy", "default-src 'self'; "
                    + "script-src 'self' 'unsafe-inline' https://unpkg.com https://accounts.google.com; "
                    + "frame-src 'self' https://accounts.google.com; "
                    + "style-src 'self' 'unsafe-inline' https://accounts.google.com https://fonts.googleapis.com; "
                    + "font-src 'self' https://fonts.gstatic.com; "
                    + "img-src 'self' data: blob: https://*.googleusercontent.com https://accounts.google.com; "
                    + "media-src 'self' blob:; "
                    + "worker-src 'self' blob:; "
                    + "connect-src 'self' https://accounts.google.com");
            if (name.equals("sw.js")) {
                h.set("Cache-Control", "no-cache");
            }
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }


    // ------------------------------------------------------------------ helpers

    private void requirePost(HttpExchange ex) {
        if (!ex.getRequestMethod().equals("POST")) {
            throw new HttpError(405, "METHOD", "Use POST.");
        }
    }

    private Map<String, Object> readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new HttpError(413, "TOO_LARGE", "Request too large.");
        }
        return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private Map<String, String> query(HttpExchange ex) {
        Map<String, String> map = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private String str(Map<String, ?> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return v.toString();
    }

    private int intParam(Map<String, ?> m, String key) {
        try {
            Object v = m.get(key);
            if (v instanceof Number n) return n.intValue();
            return Integer.parseInt(str(m, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field must be a number: " + key);
        }
    }

    private LocalDate dateParam(Map<String, ?> m, String key) {
        try {
            return LocalDate.parse(str(m, key), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date must look like 2026-10-29.");
        }
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("code", code);
        m.put("error", message);
        return m;
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
