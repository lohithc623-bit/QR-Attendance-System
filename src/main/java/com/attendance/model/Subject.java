package com.attendance.model;

/** A subject offered to one class, taught by one faculty member. */
public class Subject {
    private final int id;
    private final String subjectCode;
    private final String subjectName;
    private final int classId;
    private final int facultyId;

    public Subject(int id, String subjectCode, String subjectName, int classId, int facultyId) {
        this.id = id;
        this.subjectCode = subjectCode;
        this.subjectName = subjectName;
        this.classId = classId;
        this.facultyId = facultyId;
    }

    public int getId() { return id; }
    public String getSubjectCode() { return subjectCode; }
    public String getSubjectName() { return subjectName; }
    public int getClassId() { return classId; }
    public int getFacultyId() { return facultyId; }

    @Override
    public String toString() {
        return subjectCode + " - " + subjectName;
    }
}
