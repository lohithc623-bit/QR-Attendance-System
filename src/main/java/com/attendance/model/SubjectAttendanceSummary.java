package com.attendance.model;

/** Aggregated present/total/percentage for one student in one subject - drives the student dashboard's subject-wise table. */
public class SubjectAttendanceSummary {
    private final String subjectCode;
    private final String subjectName;
    private final int presentCount;
    private final int totalClasses;

    public SubjectAttendanceSummary(String subjectCode, String subjectName, int presentCount, int totalClasses) {
        this.subjectCode = subjectCode;
        this.subjectName = subjectName;
        this.presentCount = presentCount;
        this.totalClasses = totalClasses;
    }

    public String getSubjectCode() { return subjectCode; }
    public String getSubjectName() { return subjectName; }
    public int getPresentCount() { return presentCount; }
    public int getTotalClasses() { return totalClasses; }

    public double getPercentage() {
        return totalClasses == 0 ? 0.0 : (presentCount * 100.0) / totalClasses;
    }

    /** True once classes have been held and the student is under the warning threshold. */
    public boolean isBelowThreshold() {
        return totalClasses > 0 && getPercentage() < AttendanceStatus.WARNING_THRESHOLD;
    }

    /**
     * How many classes in a row the student must attend to get back to the threshold:
     * smallest x with (present + x) / (total + x) >= 0.75, i.e. x >= 3*total - 4*present.
     */
    public int classesNeededToRecover() {
        if (!isBelowThreshold()) {
            return 0;
        }
        return Math.max(0, 3 * totalClasses - 4 * presentCount);
    }
}
