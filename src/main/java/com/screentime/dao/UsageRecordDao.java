/* ============================================================
 *  UsageRecordDao.java — 使用记录 DAO
 *  管理 usage_records 和 daily_summary 两张表的读写：
 *    - initTable():     创建所需数据库表
 *    - startSession():   开始一个新的使用会话（end_time=NULL）
 *    - endSession():     结束最近的未关闭会话
 *    - getTodayUsage():  查询今日各应用的总使用秒数
 *    - getAllDailySummaries(): 查询所有日汇总记录
 *    - getTodayRecordCount(): 获取今日记录条数（调试用）
 *    - getTableList():   获取数据库所有表名（调试用）
 *
 *  所有涉及"今天"的查询使用参数化的 LocalDate（从 Java 传入），
 *  避免依赖 SQLite 的 date() / strftime() 时区处理和格式兼容性。
 * ============================================================ */
package com.screentime.dao;

import com.screentime.util.DatabaseUtil;

import java.sql.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UsageRecordDao {

    public void initTable() {
        String[] sqls = {
            """
            CREATE TABLE IF NOT EXISTS usage_records (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                app_id      INTEGER NOT NULL,
                start_time  TIMESTAMP NOT NULL,
                end_time    TIMESTAMP,
                duration_seconds INTEGER
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS daily_summary (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                app_id      INTEGER NOT NULL,
                date        DATE NOT NULL,
                total_seconds INTEGER DEFAULT 0,
                UNIQUE(app_id, date)
            );
            """
        };
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : sqls) {
                stmt.executeUpdate(sql);
            }
        } catch (SQLException e) {
        }
    }

    /**
     * 开始一个新的使用会话。同一个应用只应有一个未关闭的会话。
     */
    public void startSession(int appId, LocalDateTime startTime) {
        String sql = "INSERT INTO usage_records (app_id, start_time) VALUES (?, ?)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, appId);
            ps.setTimestamp(2, Timestamp.valueOf(startTime));
            ps.executeUpdate();
        } catch (SQLException e) {
        }
    }

    /**
     * 结束该应用最近的未关闭会话。
     */
    public void endSession(int appId, LocalDateTime endTime) {
        String findSql = "SELECT id, start_time FROM usage_records WHERE app_id = ? AND end_time IS NULL ORDER BY id DESC LIMIT 1";
        String updateSql = "UPDATE usage_records SET end_time = ?, duration_seconds = ? WHERE id = ?";

        try (Connection conn = DatabaseUtil.getConnection()) {
            try (PreparedStatement findPs = conn.prepareStatement(findSql)) {
                findPs.setInt(1, appId);
                try (ResultSet rs = findPs.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        LocalDateTime start = rs.getTimestamp("start_time").toLocalDateTime();
                        long seconds = Duration.between(start, endTime).getSeconds();
                        if (seconds < 0) seconds = 0;
                        if (seconds > Integer.MAX_VALUE) seconds = Integer.MAX_VALUE;

                        try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                            updatePs.setTimestamp(1, Timestamp.valueOf(endTime));
                            updatePs.setLong(2, seconds);
                            updatePs.setInt(3, id);
                            updatePs.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
        }
    }

    /**
     * 查询今日所有应用的使用总时长（秒）。
     *
     * 使用 Timestamp 范围比较（start_time >= 今日00:00 AND < 明日00:00）
     * 替代 SQLite 的 date() 函数，彻底避免类型转换和格式兼容问题。
     */
    public Map<Integer, Integer> getTodayUsage() {
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        Map<Integer, Integer> result = new LinkedHashMap<>();

        // 1. 已完成会话 — 从 duration_seconds 列读取
        String completedSql = """
                SELECT a.id, a.app_name, COALESCE(SUM(r.duration_seconds), 0) AS total
                FROM monitored_apps a
                LEFT JOIN usage_records r ON r.app_id = a.id
                    AND r.start_time >= ?
                    AND r.start_time < ?
                    AND r.end_time IS NOT NULL
                GROUP BY a.id
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(completedSql)) {
            ps.setTimestamp(1, Timestamp.valueOf(dayStart));
            ps.setTimestamp(2, Timestamp.valueOf(dayEnd));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getInt("id"), rs.getInt("total"));
                }
            }
        } catch (SQLException e) {
        }

        // 2. 进行中会话 — 在 Java 端用 Duration 计算
        String ongoingSql = """
                SELECT r.app_id, r.start_time
                FROM usage_records r
                WHERE r.end_time IS NULL
                  AND r.start_time >= ?
                  AND r.start_time < ?
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(ongoingSql)) {
            ps.setTimestamp(1, Timestamp.valueOf(dayStart));
            ps.setTimestamp(2, Timestamp.valueOf(dayEnd));
            LocalDateTime now = LocalDateTime.now();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int appId = rs.getInt("app_id");
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts == null) continue;
                    LocalDateTime start = ts.toLocalDateTime();
                    long ongoingSeconds = Duration.between(start, now).getSeconds();
                    if (ongoingSeconds < 0) ongoingSeconds = 0;
                    if (ongoingSeconds > Integer.MAX_VALUE) ongoingSeconds = Integer.MAX_VALUE;
                    result.merge(appId, (int) ongoingSeconds, Integer::sum);
                }
            }
        } catch (SQLException e) {
        }

        return result;
    }

    /**
     * 获取今日 usage_records 的总条数（用于调试）。
     */
    public int getTodayRecordCount() {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = LocalDate.now().plusDays(1).atStartOfDay();
        String sql = "SELECT COUNT(*) FROM usage_records WHERE start_time >= ? AND start_time < ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(dayStart));
            ps.setTimestamp(2, Timestamp.valueOf(dayEnd));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
        }
        return 0;
    }

    /**
     * 查询数据库中的所有表名（用于调试）。
     */
    public List<String> getTableList() {
        List<String> tables = new ArrayList<>();
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }
        } catch (SQLException e) {
        }
        return tables;
    }

    /**
     * 查询最近的所有日汇总记录，带应用名。
     */
    public Map<String, Map<LocalDate, Integer>> getAllDailySummaries() {
        String sql = """
                SELECT a.app_name, s.date, s.total_seconds
                FROM daily_summary s
                JOIN monitored_apps a ON a.id = s.app_id
                ORDER BY s.date DESC, s.total_seconds DESC
                LIMIT 200
                """;
        Map<String, Map<LocalDate, Integer>> result = new LinkedHashMap<>();
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String appName = rs.getString("app_name");
                LocalDate date = rs.getDate("date").toLocalDate();
                int seconds = rs.getInt("total_seconds");
                result.computeIfAbsent(appName, k -> new LinkedHashMap<>()).put(date, seconds);
            }
        } catch (SQLException e) {
        }
        return result;
    }

    /**
     * 将昨天的 usage_records 聚合并写入 daily_summary。
     */
    public void flushYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime dayStart = yesterday.atStartOfDay();
        LocalDateTime dayEnd = yesterday.plusDays(1).atStartOfDay();
        String sql = """
                INSERT OR REPLACE INTO daily_summary (app_id, date, total_seconds)
                SELECT app_id, ?, SUM(duration_seconds) AS total_seconds
                FROM usage_records
                WHERE start_time >= ?
                  AND start_time < ?
                  AND duration_seconds IS NOT NULL
                GROUP BY app_id
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(yesterday));
            ps.setTimestamp(2, Timestamp.valueOf(dayStart));
            ps.setTimestamp(3, Timestamp.valueOf(dayEnd));
            ps.executeUpdate();
        } catch (SQLException e) {
        }
    }
}
