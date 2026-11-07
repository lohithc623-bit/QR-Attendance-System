package com.attendance.util;

import com.attendance.config.AppConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Rotating QR payloads, so a screenshot of a student's QR is useless after a minute or so.
 *
 * Payload:   QRA2:&lt;studentId&gt;:&lt;timeWindow&gt;:&lt;signature&gt;
 *   timeWindow = epochSeconds / qr.rotation.seconds   (default 30 s)
 *   signature  = first 12 bytes (hex) of HMAC-SHA256(key = student's secret qr_token,
 *                                                     message = studentId:timeWindow)
 *
 * The permanent qr_token never leaves the server/DB and is never inside the QR; it is only the
 * HMAC key. The checker accepts the current window and one either side (clock skew / slow scan),
 * i.e. a code lives for at most about 60-90 seconds.
 */
public final class QRTokenService {

    public static final String PREFIX = "QRA2";
    private static final int ACCEPTED_WINDOW_DRIFT = 1;

    public enum Status { VALID, EXPIRED, INVALID }

    public static final class Parsed {
        public final int studentId;
        public final long window;
        public final String signature;

        Parsed(int studentId, long window, String signature) {
            this.studentId = studentId;
            this.window = window;
            this.signature = signature;
        }
    }

    private QRTokenService() {
    }

    public static int stepSeconds() {
        return Math.max(5, AppConfig.getInt("qr.rotation.seconds", 30));
    }

    public static String generate(int studentId, String qrToken, Instant now) {
        long window = now.getEpochSecond() / stepSeconds();
        return PREFIX + ":" + studentId + ":" + window + ":" + sign(qrToken, studentId, window);
    }

    /** Seconds until the payload produced right now is replaced by a fresh one. */
    public static int secondsUntilRotation(Instant now) {
        int step = stepSeconds();
        return step - (int) (now.getEpochSecond() % step);
    }

    /** @return the parsed fields, or null if the text is not a well-formed payload. */
    public static Parsed parse(String payload) {
        if (payload == null) {
            return null;
        }
        String[] parts = payload.trim().split(":");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return null;
        }
        try {
            return new Parsed(Integer.parseInt(parts[1]), Long.parseLong(parts[2]), parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Status verify(Parsed parsed, String qrToken, Instant now) {
        String expected = sign(qrToken, parsed.studentId, parsed.window);
        boolean signatureOk = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), parsed.signature.getBytes(StandardCharsets.UTF_8));
        if (!signatureOk) {
            return Status.INVALID;
        }
        long current = now.getEpochSecond() / stepSeconds();
        return Math.abs(current - parsed.window) <= ACCEPTED_WINDOW_DRIFT ? Status.VALID : Status.EXPIRED;
    }

    private static String sign(String qrToken, int studentId, long window) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(qrToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] full = mac.doFinal((studentId + ":" + window).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", full[i]));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
