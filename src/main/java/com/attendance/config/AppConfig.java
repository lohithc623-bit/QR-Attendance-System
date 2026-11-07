package com.attendance.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Single place for every setting. Lookup order (first hit wins):
 *   1. environment variable   (db.url -> DB_URL, api.port -> API_PORT, ...)
 *   2. JVM system property    (-Ddb.password=...)
 *   3. ./local.properties     (git-ignored file next to where you start the app)
 *   4. db.properties          (bundled defaults, contains only placeholders)
 * so real credentials never have to be committed or shared with the code.
 */
public final class AppConfig {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in != null) {
                PROPS.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load db.properties", e);
        }
        Path local = Paths.get("local.properties");
        if (Files.isRegularFile(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                PROPS.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load local.properties", e);
            }
        }
    }

    private AppConfig() {
    }

    public static String get(String key, String defaultValue) {
        String env = System.getenv(key.toUpperCase().replace('.', '_'));
        if (env != null && !env.isBlank()) {
            return env;
        }
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String value = PROPS.getProperty(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
