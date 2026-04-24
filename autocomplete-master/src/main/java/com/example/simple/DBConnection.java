package com.example.simple;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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
