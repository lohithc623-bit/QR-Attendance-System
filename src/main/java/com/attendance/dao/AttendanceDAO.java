package com.attendance.dao;

import com.attendance.db.DatabaseConnection;
import com.attendance.exception.DataAccessException;
import com.attendance.model.AttendanceRecord;
import com.attendance.model.RosterEntry;
import com.attendance.model.StudentAttendanceSummary;
import com.attendance.model.SubjectAttendanceSummary;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All attendance SQL. Reports use ONE aggregate query each (no per-subject / per-student
 * query loops). "Sessions held" for a subject = distinct (date, period) rows in `attendance`;
 * because faculty can finalize a session (marking everyone not scanned as ABSENT), a session
 * is counted for every enrolled student, not just for the ones who happened to be scanned.
 */
public class AttendanceDAO {

    /** (subject_id, total sessions) derived table, reused by several reports. */
    private static final String SESSIONS_PER_SUBJECT =
            "(SELECT subject_id, COUNT(*) AS total FROM " +
            "   (SELECT DISTINCT subject_id, attendance_date, period FROM attendance) d " +
            " GROUP BY subject_id)";

    public Optional<String> findStatus(int studentId, int subjectId, LocalDate date, int period) {
        String sql = "SELECT status FROM attendance WHERE student_id = ? AND subject_id = ? AND attendance_date = ? AND period = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, subjectId);
            ps.setDate(3, Date.valueOf(date));
            ps.setInt(4, period);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to check existing attendance", e);
        }
    }

    /** Insert a row, or overwrite the status of the existing one (manual override / late scan after finalize). */
    public void upsert(int studentId, int facultyId, int subjectId, int classId, LocalDate date, int period,
                       String status, String method, LocalDateTime markedAt) {
        String sql = "INSERT INTO attendance (student_id, faculty_id, subject_id, class_id, attendance_date, period, status, method, marked_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE status = VALUES(status), method = VALUES(method), " +
                     "faculty_id = VALUES(faculty_id), marked_at = VALUES(marked_at)";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, facultyId);
            ps.setInt(3, subjectId);
            ps.setInt(4, classId);
            ps.setDate(5, Date.valueOf(date));
            ps.setInt(6, period);
            ps.setString(7, status);
            ps.setString(8, method);
            ps.setTimestamp(9, Timestamp.valueOf(markedAt));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to save attendance record", e);
        }
    }

    /** Marks every enrolled student who has no row yet for this session as ABSENT. @return rows created. */
    public int markRemainingAbsent(int facultyId, int subjectId, int classId, LocalDate date, int period, LocalDateTime now) {
        String sql = "INSERT IGNORE INTO attendance (student_id, faculty_id, subject_id, class_id, attendance_date, period, status, method, marked_at) " +
                     "SELECT e.student_id, ?, e.subject_id, ?, ?, ?, 'ABSENT', 'MANUAL', ? " +
                     "FROM subject_enrollments e JOIN students s ON s.id = e.student_id AND s.class_id = ? " +
                     "WHERE e.subject_id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, facultyId);
            ps.setInt(2, classId);
            ps.setDate(3, Date.valueOf(date));
            ps.setInt(4, period);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            ps.setInt(6, classId);
            ps.setInt(7, subjectId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to finalize session", e);
        }
    }

    /** Everyone enrolled in the subject (in this class) with their status for this session, or null if not marked yet. */
    public List<RosterEntry> findRoster(int classId, int subjectId, LocalDate date, int period) {
        String sql = "SELECT s.id, s.register_number, u.full_name, a.status, a.marked_at, a.method " +
                     "FROM subject_enrollments e " +
                     "JOIN students s ON s.id = e.student_id AND s.class_id = ? " +
                     "JOIN users u ON u.id = s.user_id " +
                     "LEFT JOIN attendance a ON a.student_id = s.id AND a.subject_id = e.subject_id " +
                     "     AND a.attendance_date = ? AND a.period = ? " +
                     "WHERE e.subject_id = ? ORDER BY s.register_number";
        List<RosterEntry> roster = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setDate(2, Date.valueOf(date));
            ps.setInt(3, period);
            ps.setInt(4, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("marked_at");
                    roster.add(new RosterEntry(
                            rs.getInt("id"), rs.getString("register_number"), rs.getString("full_name"),
                            rs.getString("status"), ts == null ? null : ts.toLocalDateTime(), rs.getString("method")));
                }
            }
            return roster;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load session roster", e);
        }
    }

    /** Rows for one exact class/subject/date/period (used by older screens and tests). */
    public List<AttendanceRecord> findForSession(int classId, int subjectId, LocalDate date, int period) {
        String sql = "SELECT a.id, a.student_id, a.faculty_id, a.subject_id, a.class_id, a.attendance_date, " +
                     "       a.period, a.status, a.marked_at, u.full_name, s.register_number " +
                     "FROM attendance a " +
                     "JOIN students s ON s.id = a.student_id " +
                     "JOIN users u ON u.id = s.user_id " +
                     "WHERE a.class_id = ? AND a.subject_id = ? AND a.attendance_date = ? AND a.period = ? " +
                     "ORDER BY a.marked_at";
        List<AttendanceRecord> records = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setInt(2, subjectId);
            ps.setDate(3, Date.valueOf(date));
            ps.setInt(4, period);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRow(rs, null));
                }
            }
            return records;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load session attendance", e);
        }
    }

    /** A student's full attendance history, newest first - the student dashboard's history table. */
    public List<AttendanceRecord> findByStudentId(int studentId) {
        String sql = "SELECT a.id, a.student_id, a.faculty_id, a.subject_id, a.class_id, a.attendance_date, " +
                     "       a.period, a.status, a.marked_at, sub.subject_name " +
                     "FROM attendance a JOIN subjects sub ON sub.id = a.subject_id " +
                     "WHERE a.student_id = ? ORDER BY a.attendance_date DESC, a.period DESC";
        List<AttendanceRecord> records = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRow(rs, rs.getString("subject_name")));
                }
            }
            return records;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load attendance history for student " + studentId, e);
        }
    }

    /** Same, but for one calendar day only (filtered in SQL instead of in Java). */
    public List<AttendanceRecord> findByStudentIdAndDate(int studentId, LocalDate date) {
        String sql = "SELECT a.id, a.student_id, a.faculty_id, a.subject_id, a.class_id, a.attendance_date, " +
                     "       a.period, a.status, a.marked_at, sub.subject_name " +
                     "FROM attendance a JOIN subjects sub ON sub.id = a.subject_id " +
                     "WHERE a.student_id = ? AND a.attendance_date = ? ORDER BY a.period";
        List<AttendanceRecord> records = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setDate(2, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRow(rs, rs.getString("subject_name")));
                }
            }
            return records;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load daily attendance for student " + studentId, e);
        }
    }

    /** One query: present/total for every subject the student is enrolled in. */
    public List<SubjectAttendanceSummary> summariseStudentSubjects(int studentId) {
        String sql = "SELECT sub.subject_code, sub.subject_name, " +
                     "       COALESCE(p.present, 0) AS present, COALESCE(t.total, 0) AS total " +
                     "FROM subject_enrollments e " +
                     "JOIN subjects sub ON sub.id = e.subject_id " +
                     "LEFT JOIN " + SESSIONS_PER_SUBJECT + " t ON t.subject_id = sub.id " +
                     "LEFT JOIN (SELECT subject_id, COUNT(*) AS present FROM attendance " +
                     "           WHERE student_id = ? AND status = 'PRESENT' GROUP BY subject_id) p ON p.subject_id = sub.id " +
                     "WHERE e.student_id = ? ORDER BY sub.subject_code";
        List<SubjectAttendanceSummary> result = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new SubjectAttendanceSummary(rs.getString("subject_code"),
                            rs.getString("subject_name"), rs.getInt("present"), rs.getInt("total")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to summarise attendance for student " + studentId, e);
        }
    }

    /** One query: every student of the class with present/total combined over all subjects they are enrolled in. */
    public List<StudentAttendanceSummary> summariseClass(int classId) {
        String sql = "SELECT s.register_number, u.full_name, " +
                     "       COALESCE(p.present, 0) AS present, COALESCE(t.total, 0) AS total " +
                     "FROM students s JOIN users u ON u.id = s.user_id " +
                     "LEFT JOIN (SELECT student_id, COUNT(*) AS present FROM attendance " +
                     "           WHERE status = 'PRESENT' GROUP BY student_id) p ON p.student_id = s.id " +
                     "LEFT JOIN (SELECT e.student_id, SUM(ss.total) AS total " +
                     "           FROM subject_enrollments e JOIN " + SESSIONS_PER_SUBJECT + " ss ON ss.subject_id = e.subject_id " +
                     "           GROUP BY e.student_id) t ON t.student_id = s.id " +
                     "WHERE s.class_id = ? ORDER BY s.register_number";
        List<StudentAttendanceSummary> result = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new StudentAttendanceSummary(rs.getString("register_number"),
                            rs.getString("full_name"), rs.getInt("present"), rs.getInt("total")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to summarise class " + classId, e);
        }
    }

    /** One query: every student enrolled in the subject with present/total for that subject. */
    public List<StudentAttendanceSummary> summariseSubject(int subjectId, int classId) {
        String sql = "SELECT s.register_number, u.full_name, " +
                     "       COALESCE(p.present, 0) AS present, COALESCE(t.total, 0) AS total " +
                     "FROM subject_enrollments e " +
                     "JOIN students s ON s.id = e.student_id AND s.class_id = ? " +
                     "JOIN users u ON u.id = s.user_id " +
                     "LEFT JOIN (SELECT student_id, COUNT(*) AS present FROM attendance " +
                     "           WHERE subject_id = ? AND status = 'PRESENT' GROUP BY student_id) p ON p.student_id = s.id " +
                     "LEFT JOIN " + SESSIONS_PER_SUBJECT + " t ON t.subject_id = e.subject_id " +
                     "WHERE e.subject_id = ? ORDER BY s.register_number";
        List<StudentAttendanceSummary> result = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setInt(2, subjectId);
            ps.setInt(3, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new StudentAttendanceSummary(rs.getString("register_number"),
                            rs.getString("full_name"), rs.getInt("present"), rs.getInt("total")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to summarise subject " + subjectId, e);
        }
    }

    private AttendanceRecord mapRow(ResultSet rs, String subjectName) throws SQLException {
        return new AttendanceRecord(
                rs.getInt("id"),
                rs.getInt("student_id"),
                rs.getInt("faculty_id"),
                rs.getInt("subject_id"),
                rs.getInt("class_id"),
                rs.getDate("attendance_date").toLocalDate(),
                rs.getInt("period"),
                rs.getString("status"),
                rs.getTimestamp("marked_at").toLocalDateTime(),
                safeGet(rs, "full_name"),
                safeGet(rs, "register_number"),
                subjectName,
                null
        );
    }

    private String safeGet(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }
}
