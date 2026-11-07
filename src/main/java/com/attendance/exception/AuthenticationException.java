package com.attendance.exception;

/** Thrown on any login failure: unknown username, wrong password, or wrong role selected. */
public class AuthenticationException extends Exception {
    public AuthenticationException(String message) {
        super(message);
    }
}
