package com.attendance.model;

/** One row of a class-wise or subject-wise faculty report: a student's present/total/percentage. */
public class StudentAttendanceSummary {
    private final String registerNumber;
    private final String studentName;
    private final int presentCount;
    private final int totalClasses;

    public StudentAttendanceSummary(String registerNumber, String studentName, int presentCount, int totalClasses) {
        this.registerNumber = registerNumber;
        this.studentName = studentName;
        this.presentCount = presentCount;
        this.totalClasses = totalClasses;
    }

    public String getRegisterNumber() { return registerNumber; }
    public String getStudentName() { return studentName; }
    public int getPresentCount() { return presentCount; }
    public int getTotalClasses() { return totalClasses; }

    public double getPercentage() {
        return totalClasses == 0 ? 0.0 : (presentCount * 100.0) / totalClasses;
    }
}
