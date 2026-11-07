package com.attendance.model;

import java.time.LocalDateTime;

/** One enrolled student in a live session: status is null until they are scanned or marked. */
public class RosterEntry {
    private final int studentId;
    private final String registerNumber;
    private final String studentName;
    private final String status;        // PRESENT, ABSENT, or null = not marked yet
    private final LocalDateTime markedAt;
    private final String method;        // QR or MANUAL (null if not marked)

    public RosterEntry(int studentId, String registerNumber, String studentName,
                       String status, LocalDateTime markedAt, String method) {
        this.studentId = studentId;
        this.registerNumber = registerNumber;
        this.studentName = studentName;
        this.status = status;
        this.markedAt = markedAt;
        this.method = method;
    }

    public int getStudentId() { return studentId; }
    public String getRegisterNumber() { return registerNumber; }
    public String getStudentName() { return studentName; }
    public String getStatus() { return status; }
    public LocalDateTime getMarkedAt() { return markedAt; }
    public String getMethod() { return method; }

    /** Text for tables: "Not marked" when no row exists yet. */
    public String getStatusLabel() { return status == null ? "NOT MARKED" : status; }
}
