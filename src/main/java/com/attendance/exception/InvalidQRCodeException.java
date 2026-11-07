package com.attendance.exception;

/** Thrown when a scanned image is unreadable, or decodes to a token that matches no student. */
public class InvalidQRCodeException extends Exception {
    public InvalidQRCodeException(String message) {
        super(message);
    }
}
