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
        // DAO 层每次拿连接时都会确保 schema 已存在，降低调用方忘记初始化的风险。
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
        // 显式初始化入口；ThreadLocal 防止初始化过程中再次递归触发 ensureSchema。
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
        // schema.sql 是统一的建表脚本；所有 DAO 共用，避免表结构分散在多个类里。
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
        // 旧版本数据库可能缺少新列，迁移逻辑用“尝试添加，重复则忽略”的方式兼容。
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
        // 测试可通过 screentime.db.path 指定临时数据库；正式运行默认放在用户目录 ScreenTime 下。
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
