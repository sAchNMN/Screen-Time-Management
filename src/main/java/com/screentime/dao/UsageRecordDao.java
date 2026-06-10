/* ============================================================
 *  UsageRecordDao.java — 使用记录 DAO
 *  管理 usage_records 和 daily_summary 两张表的读写：
 *    - startSession():   开始一个新的使用会话（end_time=NULL）
 *    - endSession():     结束最近的未关闭会话，写入 end_time 和 duration_seconds
 *    - getTodayUsage():  查询今日各应用的总使用秒数
 *    - getAllDailySummaries(): 查询所有日汇总记录
 *    - upsertDailySummary():   插入或更新某天的汇总数据
 *    - flushYesterday(): 将昨天的 usage_records 聚合写入 daily_summary
 * ============================================================ */
package com.screentime.dao;

import com.screentime.util.DatabaseUtil;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class UsageRecordDao {

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
            System.err.println("[UsageRecordDao] startSession 失败: " + e.getMessage());
        }
    }

    /**
     * 结束该应用最近的未关闭会话：设置 end_time 和 duration_seconds。
     */
    public void endSession(int appId, LocalDateTime endTime) {
        // 找到该 app 最新一条 end_time IS NULL 的记录
        String findSql = "SELECT id, start_time FROM usage_records WHERE app_id = ? AND end_time IS NULL ORDER BY id DESC LIMIT 1";
        String updateSql = "UPDATE usage_records SET end_time = ?, duration_seconds = ? WHERE id = ?";

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement findPs = conn.prepareStatement(findSql)) {
                findPs.setInt(1, appId);
                try (ResultSet rs = findPs.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        LocalDateTime start = rs.getTimestamp("start_time").toLocalDateTime();
                        long seconds = java.time.Duration.between(start, endTime).getSeconds();
                        if (seconds < 0) seconds = 0;

                        try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                            updatePs.setTimestamp(1, Timestamp.valueOf(endTime));
                            updatePs.setLong(2, seconds);
                            updatePs.setInt(3, id);
                            updatePs.executeUpdate();
                        }
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            System.err.println("[UsageRecordDao] endSession 失败: " + e.getMessage());
        }
    }

    /**
     * 查询今日所有应用的使用总时长（秒）。
     * 同时包含已关闭会话（有 end_time）和正在进行中的会话。
     *
     * @return Map<appId, totalSeconds>
     */
    public Map<Integer, Integer> getTodayUsage() {
        String sql = """
                SELECT a.id, a.app_name,
                       COALESCE(SUM(
                           CASE WHEN r.end_time IS NOT NULL THEN r.duration_seconds
                                ELSE strftime('%s','now','localtime') - strftime('%s', r.start_time, 'localtime')
                           END
                       ), 0) AS total_seconds
                FROM monitored_apps a
                LEFT JOIN usage_records r ON r.app_id = a.id
                    AND date(r.start_time) = date('now','localtime')
                GROUP BY a.id, a.app_name
                ORDER BY total_seconds DESC
                """;
        Map<Integer, Integer> result = new LinkedHashMap<>();
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getInt("id"), rs.getInt("total_seconds"));
            }
        } catch (SQLException e) {
            System.err.println("[UsageRecordDao] getTodayUsage 失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取某应用的今日总使用秒数（包括进行中的会话）。
     */
    public int getTodayUsageForApp(int appId) {
        String sql = """
                SELECT COALESCE(SUM(
                    CASE WHEN end_time IS NOT NULL THEN duration_seconds
                         ELSE strftime('%s','now','localtime') - strftime('%s', start_time, 'localtime')
                    END
                ), 0) AS total
                FROM usage_records
                WHERE app_id = ? AND date(start_time) = date('now','localtime')
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, appId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            }
        } catch (SQLException e) {
            System.err.println("[UsageRecordDao] getTodayUsageForApp 失败: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 查询所有日汇总记录，带应用名。
     */
    public Map<String, Map<LocalDate, Integer>> getAllDailySummaries() {
        String sql = """
                SELECT a.app_name, s.date, s.total_seconds
                FROM daily_summary s
                JOIN monitored_apps a ON a.id = s.app_id
                ORDER BY s.date DESC, s.total_seconds DESC
                """;
        // appName → (date → seconds)
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
            System.err.println("[UsageRecordDao] getAllDailySummaries 失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 插入或替换某应用某天的汇总数据。
     */
    public void upsertDailySummary(int appId, LocalDate date, int totalSeconds) {
        String sql = "INSERT OR REPLACE INTO daily_summary (app_id, date, total_seconds) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, appId);
            ps.setDate(2, Date.valueOf(date));
            ps.setInt(3, totalSeconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[UsageRecordDao] upsertDailySummary 失败: " + e.getMessage());
        }
    }

    /**
     * 将昨天的 usage_records 聚合并写入 daily_summary。
     * 在每天凌晨 00:01 调用，确保昨天数据已完整。
     */
    public void flushYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String sql = """
                INSERT OR REPLACE INTO daily_summary (app_id, date, total_seconds)
                SELECT app_id, date(start_time) AS date, SUM(duration_seconds) AS total_seconds
                FROM usage_records
                WHERE date(start_time) = ? AND duration_seconds IS NOT NULL
                GROUP BY app_id
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(yesterday));
            int rows = ps.executeUpdate();
            if (rows > 0) {
                System.out.println("[UsageRecordDao] 已刷新 " + yesterday + " 的日汇总，影响 " + rows + " 条记录");
            }
        } catch (SQLException e) {
            System.err.println("[UsageRecordDao] flushYesterday 失败: " + e.getMessage());
        }
    }
}
