package com.attendance.dao;

import com.attendance.db.DatabaseConnection;
import com.attendance.exception.DataAccessException;
import com.attendance.model.SchoolClass;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ClassDAO {

    public List<SchoolClass> findAll() {
        String sql = "SELECT id, class_name, department, semester, section FROM classes ORDER BY department, semester, section";
        List<SchoolClass> classes = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                classes.add(mapRow(rs));
            }
            return classes;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load classes", e);
        }
    }

    /** Only the classes in which this faculty member teaches at least one subject. */
    public List<SchoolClass> findByFaculty(int facultyId) {
        String sql = "SELECT DISTINCT c.id, c.class_name, c.department, c.semester, c.section " +
                     "FROM classes c JOIN subjects s ON s.class_id = c.id WHERE s.faculty_id = ? " +
                     "ORDER BY c.department, c.semester, c.section";
        List<SchoolClass> classes = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, facultyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    classes.add(mapRow(rs));
                }
            }
            return classes;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load classes for faculty " + facultyId, e);
        }
    }

    public Optional<SchoolClass> findById(int id) {
        String sql = "SELECT id, class_name, department, semester, section FROM classes WHERE id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load class " + id, e);
        }
    }

    public SchoolClass insert(String className, String department, int semester, String section) {
        String sql = "INSERT INTO classes (class_name, department, semester, section) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, className);
            ps.setString(2, department);
            ps.setInt(3, semester);
            ps.setString(4, section);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new SchoolClass(keys.getInt(1), className, department, semester, section);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create class '" + className + "'", e);
        }
    }

    public void delete(int id) {
        String sql = "DELETE FROM classes WHERE id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to delete class " + id, e);
        }
    }

    private SchoolClass mapRow(ResultSet rs) throws SQLException {
        return new SchoolClass(
                rs.getInt("id"),
                rs.getString("class_name"),
                rs.getString("department"),
                rs.getInt("semester"),
                rs.getString("section")
        );
    }
}
