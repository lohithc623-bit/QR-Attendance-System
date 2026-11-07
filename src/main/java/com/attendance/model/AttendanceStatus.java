package com.attendance.model;

/** Values stored in attendance.status. */
public final class AttendanceStatus {
    public static final String PRESENT = "PRESENT";
    public static final String ABSENT = "ABSENT";

    /** Attendance below this percentage triggers a warning on the student's screens. */
    public static final double WARNING_THRESHOLD = 75.0;

    private AttendanceStatus() {
    }

    public static boolean isValid(String status) {
        return PRESENT.equals(status) || ABSENT.equals(status);
    }
}
