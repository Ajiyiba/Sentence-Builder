package com.example.simple;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class ConnectionCheck {
    public static void main(String[] args) {
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DATABASE() AS db_name, USER() AS db_user")) {

            if (rs.next()) {
                System.out.println("DB connection OK");
                System.out.println("database=" + rs.getString("db_name"));
                System.out.println("user=" + rs.getString("db_user"));
            }
        } catch (Exception e) {
            System.out.println("DB connection FAILED");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
