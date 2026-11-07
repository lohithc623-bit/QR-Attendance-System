package com.attendance.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A row in `attendance`, enriched with display-only fields (student name,
 * subject name, etc.) that individual queries fill in as needed - fields
 * a given query doesn't populate are simply left null.
 */
public class AttendanceRecord {
    private final int id;
    private final int studentId;
    private final int facultyId;
    private final int subjectId;
    private final int classId;
    private final LocalDate attendanceDate;
    private final int period;
    private final String status;
    private final LocalDateTime markedAt;

    // display-only, populated by joined queries where relevant
    private final String studentName;
    private final String registerNumber;
    private final String subjectName;
    private final String facultyName;

    public AttendanceRecord(int id, int studentId, int facultyId, int subjectId, int classId,
                             LocalDate attendanceDate, int period, String status, LocalDateTime markedAt,
                             String studentName, String registerNumber, String subjectName, String facultyName) {
        this.id = id;
        this.studentId = studentId;
        this.facultyId = facultyId;
        this.subjectId = subjectId;
        this.classId = classId;
        this.attendanceDate = attendanceDate;
        this.period = period;
        this.status = status;
        this.markedAt = markedAt;
        this.studentName = studentName;
        this.registerNumber = registerNumber;
        this.subjectName = subjectName;
        this.facultyName = facultyName;
    }

    public int getId() { return id; }
    public int getStudentId() { return studentId; }
    public int getFacultyId() { return facultyId; }
    public int getSubjectId() { return subjectId; }
    public int getClassId() { return classId; }
    public LocalDate getAttendanceDate() { return attendanceDate; }
    public int getPeriod() { return period; }
    public String getStatus() { return status; }
    public LocalDateTime getMarkedAt() { return markedAt; }
    public String getStudentName() { return studentName; }
    public String getRegisterNumber() { return registerNumber; }
    public String getSubjectName() { return subjectName; }
    public String getFacultyName() { return facultyName; }
}
