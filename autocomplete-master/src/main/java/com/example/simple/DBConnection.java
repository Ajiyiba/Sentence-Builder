package com.example.simple;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Centralized DB connection factory shared by UI and importer.
 * <p>
 * Credentials are read from environment variables so nothing secret is committed to the repo.
 * Set {@code SB_DB_URL}, {@code SB_DB_USER}, and {@code SB_DB_PASSWORD} in your run configuration
 * or shell before starting the app (defaults match a local MySQL {@code sentence_builder} database).
 */
public final class DBConnection {
    private static final String URL = System.getenv().getOrDefault(
            "SB_DB_URL", "jdbc:mysql://127.0.0.1:3306/sentence_builder");
    private static final String USER = System.getenv().getOrDefault("SB_DB_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("SB_DB_PASSWORD", "");

    private DBConnection() {
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
