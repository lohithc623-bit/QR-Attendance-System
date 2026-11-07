package com.attendance.exception;

/** Thrown when the scanned student does not belong to the class selected by the faculty. */
public class StudentClassMismatchException extends Exception {
    public StudentClassMismatchException(String message) {
        super(message);
    }
}
