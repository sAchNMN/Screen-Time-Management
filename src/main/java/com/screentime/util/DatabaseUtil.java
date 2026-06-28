/* ============================================================
 *  DatabaseUtil.java — SQLite 数据库连接管理
 *  管理数据库连接：
 *    - 自动在 %userprofile%/ScreenTime/ 目录下创建数据库文件
 *    - 加载 SQLite JDBC 驱动
 *    - 提供静态 getConnection() 方法供 DAO 层使用
 * ============================================================ */
package com.screentime.util;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseUtil {
    private static final ThreadLocal<Boolean> INITIALIZING =
            ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean DATABASE_RECREATED = new AtomicBoolean(false);

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
    }

    private DatabaseUtil() {
    }

    public static Connection getConnection() throws SQLException {
        String dbUrl = getDbUrl();
        Connection conn = DriverManager.getConnection(dbUrl);
        if (!INITIALIZING.get()) {
            try {
                ensureSchema(conn);
            } catch (Exception e) {
                try {
                    conn.close();
                } catch (SQLException closeError) {
                    e.addSuppressed(closeError);
                }
                throw new SQLException("Failed to ensure database schema", e);
            }
        }
        return conn;
    }

    public static void initializeSchema() {
        INITIALIZING.set(true);
        try (Connection conn = DriverManager.getConnection(getDbUrl())) {
            ensureSchema(conn);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        } finally {
            INITIALIZING.set(false);
        }
    }

    public static boolean consumeDatabaseRecreatedFlag() {
        return DATABASE_RECREATED.getAndSet(false);
    }

    private static void ensureSchema(Connection conn) {
        try (var in = DatabaseUtil.class.getResourceAsStream("/database/schema.sql")) {
            if (in == null) {
                throw new RuntimeException("Database schema resource not found");
            }
            String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : schema.split(";")) {
                    String trimmed = sql.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.executeUpdate(trimmed);
                    }
                }
                applyMigrations(stmt);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    private static void applyMigrations(Statement stmt) throws SQLException {
        tryAddColumn(stmt, "usage_records", "last_seen_time", "TIMESTAMP");
    }

    private static void tryAddColumn(Statement stmt, String table, String column, String definition)
            throws SQLException {
        try {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            String message = e.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column name")) {
                throw e;
            }
        }
    }

    private static String getDbUrl() {
        String override = System.getProperty("screentime.db.path");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override).toAbsolutePath();
            if (!Files.exists(path)) {
                DATABASE_RECREATED.set(true);
            }
            return "jdbc:sqlite:" + path;
        }

        try {
            Path dbDir = Path.of(System.getProperty("user.home"), "ScreenTime");
            Files.createDirectories(dbDir);
            Path dbPath = dbDir.resolve("screentime.db").toAbsolutePath();
            if (!Files.exists(dbPath)) {
                DATABASE_RECREATED.set(true);
            }
            return "jdbc:sqlite:" + dbPath;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create database directory", e);
        }
    }
}
