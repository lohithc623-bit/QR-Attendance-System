package com.attendance.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Salted, slow password hashing using PBKDF2-HMAC-SHA256 from the JDK (no extra library).
 * Stored format:  pbkdf2$<iterations>$<base64 salt>$<base64 hash>
 *
 * Old unsalted SHA-256 hex digests (from version 1 databases) are still
 * accepted by {@link #matches}; {@link #needsUpgrade} tells the login code to
 * re-hash them in the new format right after a successful login.
 */
public final class PasswordUtil {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    public static String hash(String plainText) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(plainText, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derived);
    }

    public static boolean matches(String plainText, String stored) {
        if (plainText == null || stored == null) {
            return false;
        }
        if (!stored.startsWith(PREFIX + "$")) {
            return MessageDigest.isEqual(
                    legacySha256(plainText).getBytes(StandardCharsets.UTF_8),
                    stored.toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(plainText, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True for legacy SHA-256 hashes and for PBKDF2 hashes made with fewer iterations than today's setting. */
    public static boolean needsUpgrade(String stored) {
        if (stored == null || !stored.startsWith(PREFIX + "$")) {
            return true;
        }
        String[] parts = stored.split("\\$");
        try {
            return parts.length != 4 || Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static byte[] derive(String plainText, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(plainText.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    private static String legacySha256(String plainText) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(plainText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
