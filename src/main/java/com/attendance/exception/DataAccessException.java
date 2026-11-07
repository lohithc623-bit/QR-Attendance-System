package com.attendance.exception;

/**
 * Unchecked wrapper around SQLException / other low-level database failures,
 * so DAOs and services don't force every caller to declare "throws SQLException".
 * The original SQLException is preserved as the cause for debugging.
 */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
