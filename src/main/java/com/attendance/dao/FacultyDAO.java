package com.attendance.dao;

import com.attendance.db.DatabaseConnection;
import com.attendance.exception.DataAccessException;
import com.attendance.model.Faculty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FacultyDAO {

    private static final String BASE_SELECT =
            "SELECT f.id, f.user_id, u.full_name, f.faculty_code, f.department " +
            "FROM faculty f JOIN users u ON u.id = f.user_id ";

    public Optional<Faculty> findByUserId(int userId) {
        String sql = BASE_SELECT + "WHERE f.user_id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load faculty for user " + userId, e);
        }
    }

    public List<Faculty> findAll() {
        String sql = BASE_SELECT + "ORDER BY f.faculty_code";
        List<Faculty> list = new ArrayList<>();
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load faculty list", e);
        }
    }

    public Faculty insert(int userId, String facultyCode, String department) {
        String sql = "INSERT INTO faculty (user_id, faculty_code, department) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setString(2, facultyCode);
            ps.setString(3, department);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Faculty(keys.getInt(1), userId, null, facultyCode, department);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create faculty '" + facultyCode + "'", e);
        }
    }

    public void delete(int facultyId) {
        String sql = "DELETE u FROM users u JOIN faculty f ON f.user_id = u.id WHERE f.id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, facultyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to delete faculty " + facultyId, e);
        }
    }

    private Faculty mapRow(ResultSet rs) throws SQLException {
        return new Faculty(
                rs.getInt("id"),
                rs.getInt("user_id"),
                rs.getString("full_name"),
                rs.getString("faculty_code"),
                rs.getString("department")
        );
    }
}
