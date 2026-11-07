package com.attendance.config;

/**
 * Database connection settings, read through {@link AppConfig}.
 * Refuses to run with the well-known default credentials (root/root) or the
 * CHANGE_ME placeholder, so nobody demos or deploys with them by accident.
 */
public final class DBConfig {

    private DBConfig() {
    }

    public static String url() {
        return AppConfig.get("db.url", "jdbc:mysql://localhost:3306/qr_attendance?useSSL=false&serverTimezone=UTC");
    }

    public static String user() {
        return AppConfig.get("db.user", "qr_app");
    }

    public static String password() {
        return AppConfig.get("db.password", "");
    }

    /** @return a human-readable problem with the configuration, or null if it looks fine. */
    public static String validate() {
        String pw = password();
        if (pw.isBlank() || pw.equals("CHANGE_ME")) {
            return "Database password is not set. Put it in local.properties (db.password=...) "
                    + "or the DB_PASSWORD environment variable - see README, section 'Configure the connection'.";
        }
        if (user().equalsIgnoreCase("root") && pw.equals("root")) {
            return "Refusing to start with the default root/root credentials. Create a dedicated MySQL user "
                    + "(database/create_app_user.sql) and set db.user / db.password.";
        }
        return null;
    }
}
