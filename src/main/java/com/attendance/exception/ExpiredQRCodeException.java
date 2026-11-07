package com.attendance.exception;

/** The QR code is genuine but older than the rotation window - e.g. a screenshot being reused. */
public class ExpiredQRCodeException extends InvalidQRCodeException {
    public ExpiredQRCodeException(String message) {
        super(message);
    }
}
