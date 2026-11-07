package com.attendance.model;

/** A row in `faculty`, joined with the owning user's name for convenience. */
public class Faculty {
    private final int id;
    private final int userId;
    private final String fullName;
    private final String facultyCode;
    private final String department;

    public Faculty(int id, int userId, String fullName, String facultyCode, String department) {
        this.id = id;
        this.userId = userId;
        this.fullName = fullName;
        this.facultyCode = facultyCode;
        this.department = department;
    }

    public int getId() { return id; }
    public int getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getFacultyCode() { return facultyCode; }
    public String getDepartment() { return department; }
}
