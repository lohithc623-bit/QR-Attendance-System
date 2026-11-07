package com.attendance.dao;

import com.attendance.db.DatabaseConnection;
import com.attendance.exception.DataAccessException;
import com.attendance.model.Student;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class StudentDAO {

    private static final String BASE_SELECT =
            "SELECT s.id, s.user_id, u.full_name, s.register_number, s.department, " +
            "       s.semester, s.section, s.class_id, s.qr_token " +
            "FROM students s JOIN users u ON u.id = s.user_id ";

    public Optional<Student> findByUserId(int userId) {
        String sql = BASE_SELECT + "WHERE s.user_id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load student for user " + userId, e);
        }
    }

    public Optional<Student> findById(int studentId) {
        String sql = BASE_SELECT + "WHERE s.id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load student " + studentId, e);
        }
    }

    /** Central to the whole scan flow: the QR code encodes this token, nothing else. */
    public Optional<Student> findByQrToken(String qrToken) {
        String sql = BASE_SELECT + "WHERE s.qr_token = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qrToken);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to look up student by QR token", e);
        }
    }

    public List<Student> findByClassId(int classId) {
        String sql = BASE_SELECT + "WHERE s.class_id = ? ORDER BY s.register_number";
        List<Student> students = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    students.add(mapRow(rs));
                }
            }
            return students;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load students for class " + classId, e);
        }
    }

    public List<Student> findAll() {
        String sql = BASE_SELECT + "ORDER BY s.register_number";
        List<Student> students = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                students.add(mapRow(rs));
            }
            return students;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load students", e);
        }
    }

    public Student insert(int userId, String registerNumber, String department, int semester,
                           String section, int classId, String qrToken) {
        String sql = "INSERT INTO students (user_id, register_number, department, semester, section, class_id, qr_token) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setString(2, registerNumber);
            ps.setString(3, department);
            ps.setInt(4, semester);
            ps.setString(5, section);
            ps.setInt(6, classId);
            ps.setString(7, qrToken);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Student(keys.getInt(1), userId, null, registerNumber, department, semester, section, classId, qrToken);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create student '" + registerNumber + "'", e);
        }
    }

    public void delete(int studentId) {
        // Deleting the user row cascades to students, subject_enrollments, attendance.
        String sql = "DELETE u FROM users u JOIN students s ON s.user_id = u.id WHERE s.id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to delete student " + studentId, e);
        }
    }

    private Student mapRow(ResultSet rs) throws SQLException {
        return new Student(
                rs.getInt("id"),
                rs.getInt("user_id"),
                rs.getString("full_name"),
                rs.getString("register_number"),
                rs.getString("department"),
                rs.getInt("semester"),
                rs.getString("section"),
                rs.getInt("class_id"),
                rs.getString("qr_token")
        );
    }
}
