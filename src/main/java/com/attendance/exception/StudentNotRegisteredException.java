package com.attendance.exception;

/** Thrown when the scanned student is not registered/enrolled for the selected subject. */
public class StudentNotRegisteredException extends Exception {
    public StudentNotRegisteredException(String message) {
        super(message);
    }
}
