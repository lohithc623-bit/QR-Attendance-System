package com.attendance.db;

import com.attendance.config.DBConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Central place that hands out JDBC connections to MySQL.
 *
 * A fresh Connection is returned per call (simple and safe for modest
 * concurrency); every DAO uses try-with-resources so connections are always
 * closed. In the web deployment ONLY the API server process uses this class,
 * so the database never has to be reachable from phones or other clients.
 */
public final class DatabaseConnection {

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MySQL JDBC driver not found on classpath", e);
        }
    }

    private DatabaseConnection() {
    }

    public static Connection get() throws SQLException {
        String problem = DBConfig.validate();
        if (problem != null) {
            throw new SQLException(problem);
        }
        return DriverManager.getConnection(DBConfig.url(), DBConfig.user(), DBConfig.password());
    }

    /** @return null if a connection can be opened, otherwise a message explaining why not. */
    public static String describeProblem() {
        String problem = DBConfig.validate();
        if (problem != null) {
            return problem;
        }
        try (Connection c = get()) {
            return c.isValid(2) ? null : "The database did not respond.";
        } catch (SQLException e) {
            return "Cannot reach the database: " + e.getMessage();
        }
    }

    public static boolean testConnection() {
        return describeProblem() == null;
    }
}
