package com.attendance.exception;

/** Thrown when the same student already has an attendance row for this subject/date/period. */
public class DuplicateAttendanceException extends Exception {
    public DuplicateAttendanceException(String message) {
        super(message);
    }
}
