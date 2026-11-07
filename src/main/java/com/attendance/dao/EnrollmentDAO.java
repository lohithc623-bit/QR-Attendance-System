package com.attendance.dao;

import com.attendance.db.DatabaseConnection;
import com.attendance.exception.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class EnrollmentDAO {

    public boolean isEnrolled(int studentId, int subjectId) {
        String sql = "SELECT 1 FROM subject_enrollments WHERE student_id = ? AND subject_id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to check enrollment", e);
        }
    }

    public void enroll(int studentId, int subjectId) {
        String sql = "INSERT IGNORE INTO subject_enrollments (student_id, subject_id) VALUES (?, ?)";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, subjectId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to enroll student " + studentId + " in subject " + subjectId, e);
        }
    }

    /** Enroll every current student of a class into a newly created subject for that class. */
    public void enrollAllStudentsOfClass(int classId, int subjectId) {
        String sql = "INSERT IGNORE INTO subject_enrollments (student_id, subject_id) " +
                     "SELECT id, ? FROM students WHERE class_id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, subjectId);
            ps.setInt(2, classId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to bulk-enroll class " + classId + " into subject " + subjectId, e);
        }
    }

    /** Enroll a newly added student into every existing subject of their class. */
    public void enrollStudentInAllSubjectsOfClass(int studentId, int classId) {
        String sql = "INSERT IGNORE INTO subject_enrollments (student_id, subject_id) " +
                     "SELECT ?, id FROM subjects WHERE class_id = ?";
        try (Connection conn = DatabaseConnection.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, studentId);
            ps.setInt(2, classId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to enroll student " + studentId + " into class subjects", e);
        }
    }
}
