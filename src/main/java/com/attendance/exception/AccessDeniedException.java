package com.attendance.exception;

/** Thrown when a logged-in user's role does not permit the action they attempted. */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
