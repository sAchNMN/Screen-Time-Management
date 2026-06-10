/* ============================================================
 *  DatabaseUtil.java — SQLite 数据库连接管理
 *  管理数据库连接：
 *    - 自动在 %userprofile%/ScreenTime/ 目录下创建数据库文件
 *    - 加载 SQLite JDBC 驱动
 *    - 提供静态 getConnection() 方法供 DAO 层使用
 * ============================================================ */
package com.screentime.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseUtil {

    private static final String DB_URL;

    static {
        try {
            java.nio.file.Path dbDir = java.nio.file.Paths.get(
                System.getProperty("user.home"), "ScreenTime");
            java.nio.file.Files.createDirectories(dbDir);
            DB_URL = "jdbc:sqlite:" + dbDir.resolve("screentime.db").toAbsolutePath();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create database directory", e);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
    }

    private DatabaseUtil() {
    }

    public static String getDbUrl() {
        return DB_URL;
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }
}
