package com.attendance.model;

/** A row in `students`, joined with the owning user's name for convenience. */
public class Student {
    private final int id;
    private final int userId;
    private final String fullName;
    private final String registerNumber;
    private final String department;
    private final int semester;
    private final String section;
    private final int classId;
    private final String qrToken;

    public Student(int id, int userId, String fullName, String registerNumber, String department,
                    int semester, String section, int classId, String qrToken) {
        this.id = id;
        this.userId = userId;
        this.fullName = fullName;
        this.registerNumber = registerNumber;
        this.department = department;
        this.semester = semester;
        this.section = section;
        this.classId = classId;
        this.qrToken = qrToken;
    }

    public int getId() { return id; }
    public int getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getRegisterNumber() { return registerNumber; }
    public String getDepartment() { return department; }
    public int getSemester() { return semester; }
    public String getSection() { return section; }
    public int getClassId() { return classId; }
    public String getQrToken() { return qrToken; }
}
