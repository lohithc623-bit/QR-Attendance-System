package com.attendance.dao;

import com.attendance.db.DatabaseConnection;
import com.attendance.exception.DataAccessException;
import com.attendance.model.Subject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SubjectDAO {

    private static final String BASE_SELECT =
            "SELECT id, subject_code, subject_name, class_id, faculty_id FROM subjects ";

    public List<Subject> findAll() {
        String sql = BASE_SELECT + "ORDER BY subject_code";
        return query(sql, ps -> {});
    }

    public List<Subject> findByClassId(int classId) {
        String sql = BASE_SELECT + "WHERE class_id = ? ORDER BY subject_code";
        return query(sql, ps -> ps.setInt(1, classId));
    }

    /** Subjects a given faculty member teaches for a given class - drives the faculty dashboard's subject dropdown. */
    public List<Subject> findByClassAndFaculty(int classId, int facultyId) {
        String sql = BASE_SELECT + "WHERE class_id = ? AND faculty_id = ? ORDER BY subject_code";
        return query(sql, ps -> {
            ps.setInt(1, classId);
            ps.setInt(2, facultyId);
        });
    }

    public Optional<Subject> findById(int id) {
        String sql = BASE_SELECT + "WHERE id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load subject " + id, e);
        }
    }

    public Subject insert(String subjectCode, String subjectName, int classId, int facultyId) {
        String sql = "INSERT INTO subjects (subject_code, subject_name, class_id, faculty_id) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, subjectCode);
            ps.setString(2, subjectName);
            ps.setInt(3, classId);
            ps.setInt(4, facultyId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Subject(keys.getInt(1), subjectCode, subjectName, classId, facultyId);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create subject '" + subjectCode + "'", e);
        }
    }

    public void delete(int subjectId) {
        String sql = "DELETE FROM subjects WHERE id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, subjectId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to delete subject " + subjectId, e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Subject> query(String sql, Binder binder) {
        List<Subject> list = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load subjects", e);
        }
    }

    private Subject mapRow(ResultSet rs) throws SQLException {
        return new Subject(
                rs.getInt("id"),
                rs.getString("subject_code"),
                rs.getString("subject_name"),
                rs.getInt("class_id"),
                rs.getInt("faculty_id")
        );
    }
}
