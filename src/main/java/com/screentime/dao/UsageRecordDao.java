/* ============================================================
 *  UsageRecordDao.java — 使用记录 DAO
 *  管理 usage_records 和 daily_summary 两张表的读写：
 *    - initTable():     创建所需数据库表
 *    - startSession():   开始一个新的使用会话（end_time=NULL）
 *    - endSession():     结束最近的未关闭会话
 *    - getTodayUsage():  查询今日各应用的总使用秒数
 *    - getAllDailySummaries(): 查询所有日汇总记录
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
    public record ImportResult(int imported, int skipped, List<String> warnings) {
        public boolean success() {
            return imported >= 0;
        }
    }

    public void initTable() {
        DatabaseUtil.initializeSchema();
    }

    /**
     * 开始一个新的使用会话。同一个应用只应有一个未关闭的会话。
     */
    public void startSession(int appId, LocalDateTime startTime) {
        String closeOpenSql = "DELETE FROM usage_records WHERE end_time IS NULL";
        String insertSql = "INSERT INTO usage_records (app_id, start_time, last_seen_time) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement();
                 PreparedStatement ps = conn.prepareStatement(insertSql)) {
                stmt.executeUpdate(closeOpenSql);
                ps.setInt(1, appId);
                ps.setTimestamp(2, Timestamp.valueOf(startTime));
                ps.setTimestamp(3, Timestamp.valueOf(startTime));
                ps.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to start usage session", e);
        }
    }

    /**
     * 丢弃所有未关闭会话。
     *
     * 未关闭会话只代表“本进程内当前前台应用”。应用重启后，数据库中的旧 open session
     * 已经失去前台状态依据，继续按 now - start_time 统计会虚增数小时。
     */
    public int discardOpenSessions() {
        String sql = "DELETE FROM usage_records WHERE end_time IS NULL";
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to discard open usage sessions", e);
        }
    }

    public int recoverOpenSessions(int minSessionSeconds) {
        String findSql = """
                SELECT id, app_id, start_time, last_seen_time
                FROM usage_records
                WHERE end_time IS NULL
                ORDER BY id
                """;
        String deleteSql = "DELETE FROM usage_records WHERE id = ?";
        String updateSql = """
                UPDATE usage_records
                SET end_time = ?, last_seen_time = ?, duration_seconds = ?
                WHERE id = ?
                """;
        int recovered = 0;
        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement findPs = conn.prepareStatement(findSql);
                 ResultSet rs = findPs.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int appId = rs.getInt("app_id");
                    LocalDateTime start = rs.getTimestamp("start_time").toLocalDateTime();
                    Timestamp lastSeenTs = rs.getTimestamp("last_seen_time");
                    LocalDateTime end = lastSeenTs == null ? start : lastSeenTs.toLocalDateTime();
                    int duration = toDurationSeconds(start, end);

                    if (duration < Math.max(0, minSessionSeconds)) {
                        try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
                            deletePs.setInt(1, id);
                            deletePs.executeUpdate();
                        }
                        continue;
                    }

                    if (start.toLocalDate().equals(end.toLocalDate())) {
                        try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                            updatePs.setTimestamp(1, Timestamp.valueOf(end));
                            updatePs.setTimestamp(2, Timestamp.valueOf(end));
                            updatePs.setInt(3, duration);
                            updatePs.setInt(4, id);
                            updatePs.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
                            deletePs.setInt(1, id);
                            deletePs.executeUpdate();
                        }
                        insertCompletedSessionSegments(conn, appId, start, end);
                    }
                    recovered++;
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to recover open usage sessions", e);
        }
        return recovered;
    }

    public void heartbeatSession(int appId, LocalDateTime seenTime) {
        String sql = """
                UPDATE usage_records
                SET last_seen_time = ?
                WHERE id = (
                    SELECT id FROM usage_records
                    WHERE app_id = ? AND end_time IS NULL
                    ORDER BY id DESC LIMIT 1
                )
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(seenTime));
            ps.setInt(2, appId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update usage session heartbeat", e);
        }
    }

    public int countOpenSessions() {
        String sql = "SELECT COUNT(*) FROM usage_records WHERE end_time IS NULL";
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count open usage sessions", e);
        }
    }

    public int countOpenSessionsForApp(int appId) {
        String sql = "SELECT COUNT(*) FROM usage_records WHERE app_id = ? AND end_time IS NULL";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, appId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count open usage sessions for app", e);
        }
    }

    /**
     * 删除指定 app 最新一条未关闭的会话（用于短时过滤丢弃）。
     */
    public void deleteOpenSession(int appId) {
        String findSql = "SELECT id FROM usage_records WHERE app_id = ? AND end_time IS NULL ORDER BY id DESC LIMIT 1";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement findPs = conn.prepareStatement(findSql)) {
            findPs.setInt(1, appId);
            try (ResultSet rs = findPs.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("id");
                    String deleteSql = "DELETE FROM usage_records WHERE id = ?";
                    try (PreparedStatement delPs = conn.prepareStatement(deleteSql)) {
                        delPs.setInt(1, id);
                        delPs.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete open usage session", e);
        }
    }

    /**
     * 结束该应用最近的未关闭会话。
     */
    public void endSession(int appId, LocalDateTime endTime) {
        String findSql = "SELECT id, start_time FROM usage_records WHERE app_id = ? AND end_time IS NULL ORDER BY id DESC LIMIT 1";
        String updateSql = "UPDATE usage_records SET end_time = ?, last_seen_time = ?, duration_seconds = ? WHERE id = ?";

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement findPs = conn.prepareStatement(findSql)) {
                findPs.setInt(1, appId);
                try (ResultSet rs = findPs.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        LocalDateTime start = rs.getTimestamp("start_time").toLocalDateTime();
                        if (!endTime.isAfter(start)) {
                            try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                                updatePs.setTimestamp(1, Timestamp.valueOf(endTime));
                                updatePs.setTimestamp(2, Timestamp.valueOf(endTime));
                                updatePs.setInt(3, 0);
                                updatePs.setInt(4, id);
                                updatePs.executeUpdate();
                            }
                        } else if (start.toLocalDate().equals(endTime.toLocalDate())) {
                            try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                                updatePs.setTimestamp(1, Timestamp.valueOf(endTime));
                                updatePs.setTimestamp(2, Timestamp.valueOf(endTime));
                                updatePs.setInt(3, toDurationSeconds(start, endTime));
                                updatePs.setInt(4, id);
                                updatePs.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement deletePs = conn.prepareStatement("DELETE FROM usage_records WHERE id = ?")) {
                                deletePs.setInt(1, id);
                                deletePs.executeUpdate();
                            }
                            insertCompletedSessionSegments(conn, appId, start, endTime);
                        }
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to end usage session", e);
        }
    }

    private void insertCompletedSessionSegments(Connection conn, int appId, LocalDateTime start, LocalDateTime end)
            throws SQLException {
        String insertSql = "INSERT INTO usage_records (app_id, start_time, last_seen_time, end_time, duration_seconds) VALUES (?, ?, ?, ?, ?)";
        LocalDateTime segmentStart = start;
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            while (segmentStart.isBefore(end)) {
                LocalDateTime segmentEnd = segmentStart.toLocalDate().plusDays(1).atStartOfDay();
                if (segmentEnd.isAfter(end)) {
                    segmentEnd = end;
                }

                ps.setInt(1, appId);
                ps.setTimestamp(2, Timestamp.valueOf(segmentStart));
                ps.setTimestamp(3, Timestamp.valueOf(segmentEnd));
                ps.setTimestamp(4, Timestamp.valueOf(segmentEnd));
                ps.setInt(5, toDurationSeconds(segmentStart, segmentEnd));
                ps.addBatch();

                segmentStart = segmentEnd;
            }
            ps.executeBatch();
        }
    }

    private static int toDurationSeconds(LocalDateTime start, LocalDateTime end) {
        long seconds = Duration.between(start, end).getSeconds();
        if (seconds < 0) return 0;
        if (seconds > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) seconds;
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
                WHERE a.is_active = 1
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
            throw new RuntimeException("Failed to query today's completed usage", e);
        }

        // 2. 进行中会话 — 在 Java 端用 Duration 计算（仅激活的应用）
        String ongoingSql = """
                SELECT r.app_id, r.start_time
                FROM usage_records r
                JOIN monitored_apps a ON a.id = r.app_id AND a.is_active = 1
                WHERE r.end_time IS NULL
                  AND r.start_time < ?
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(ongoingSql)) {
            ps.setTimestamp(1, Timestamp.valueOf(dayEnd));
            LocalDateTime now = LocalDateTime.now();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int appId = rs.getInt("app_id");
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts == null) continue;
                    LocalDateTime start = ts.toLocalDateTime();
                    if (start.isBefore(dayStart)) start = dayStart;
                    long ongoingSeconds = Duration.between(start, now).getSeconds();
                    if (ongoingSeconds < 0) ongoingSeconds = 0;
                    if (ongoingSeconds > Integer.MAX_VALUE) ongoingSeconds = Integer.MAX_VALUE;
                    result.merge(appId, (int) ongoingSeconds, Integer::sum);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query today's active usage", e);
        }

        return result;
    }

    /**
     * 获取今日所有应用的总使用秒数（含进行中会话）。
     * 用于每日使用时间超时判断。
     */
    public int getTodayTotalSeconds() {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = LocalDate.now().plusDays(1).atStartOfDay();
        int total = 0;

        // 已完成会话
        String completed = "SELECT COALESCE(SUM(duration_seconds), 0) FROM usage_records WHERE start_time >= ? AND start_time < ? AND end_time IS NOT NULL";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(completed)) {
            ps.setTimestamp(1, Timestamp.valueOf(dayStart));
            ps.setTimestamp(2, Timestamp.valueOf(dayEnd));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) total = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query today's total completed usage", e);
        }

        // 进行中会话
        String ongoing = "SELECT start_time FROM usage_records WHERE start_time < ? AND end_time IS NULL LIMIT 1";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(ongoing)) {
            ps.setTimestamp(1, Timestamp.valueOf(dayEnd));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts != null) {
                        LocalDateTime start = ts.toLocalDateTime();
                        if (start.isBefore(dayStart)) start = dayStart;
                        long secs = Duration.between(start, LocalDateTime.now()).getSeconds();
                        if (secs > 0) total += (int) secs;
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query today's total active usage", e);
        }

        return total;
    }

    /**
     * 查询最近的所有使用数据（含日汇总 + 未汇总的 usage_records）。
     * 解决应用未运行到午夜时 flushYesterday 未执行导致历史数据丢失的问题。
     */
    public Map<String, Map<LocalDate, Integer>> getRecentUsageHistory() {
        Map<String, Map<LocalDate, Integer>> result = new LinkedHashMap<>();

        // 1. 从 usage_records 读取已完成会话（含今天 — 不使用 SQLite date() 函数，
        //    直接取 start_time 在 Java 端按日期分组，避免纳秒精度 timestamp 导致 date() 返回 NULL）
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(31);
        LocalDateTime rangeStart = startDate.atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(1).atStartOfDay();
        String rawSql = """
                SELECT a.app_name, r.start_time, r.duration_seconds
                FROM usage_records r
                JOIN monitored_apps a ON a.id = r.app_id
                WHERE r.duration_seconds IS NOT NULL
                  AND a.is_active = 1
                  AND r.start_time >= ?
                  AND r.start_time < ?
                ORDER BY a.app_name
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(rawSql)) {
            ps.setTimestamp(1, Timestamp.valueOf(rangeStart));
            ps.setTimestamp(2, Timestamp.valueOf(rangeEnd));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String appName = rs.getString("app_name");
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts == null) continue;
                    LocalDate date = ts.toLocalDateTime().toLocalDate();
                    int seconds = rs.getInt("duration_seconds");
                    Map<LocalDate, Integer> appMap = result.computeIfAbsent(appName, k -> new LinkedHashMap<>());
                    appMap.merge(date, seconds, Integer::sum);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query recent completed usage history", e);
        }

        // 2. 包含今天的进行中会话（仅激活的应用）
        String ongoingSql = """
                SELECT a.app_name, r.start_time
                FROM usage_records r
                JOIN monitored_apps a ON a.id = r.app_id
                WHERE r.end_time IS NULL
                  AND a.is_active = 1
                  AND r.start_time < ?
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(ongoingSql)) {
            ps.setTimestamp(1, Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
            LocalDateTime now = LocalDateTime.now();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String appName = rs.getString("app_name");
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts == null) continue;
                    LocalDateTime start = ts.toLocalDateTime();
                    if (start.isBefore(today.atStartOfDay())) start = today.atStartOfDay();
                    long seconds = Duration.between(start, now).getSeconds();
                    if (seconds < 0) seconds = 0;
                    if (seconds > Integer.MAX_VALUE) seconds = Integer.MAX_VALUE;
                    result.computeIfAbsent(appName, k -> new LinkedHashMap<>()).merge(today, (int) seconds, Integer::sum);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query recent active usage history", e);
        }

        return result;
    }

    /**
     * 查询最近的所有日汇总记录，带应用名。
     */
    public Map<String, Map<LocalDate, Integer>> getAllDailySummaries() {
        String sql = """
                SELECT a.app_name, s.date, s.total_seconds
                FROM daily_summary s
                JOIN monitored_apps a ON a.id = s.app_id
                WHERE a.is_active = 1
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
            throw new RuntimeException("Failed to query daily summaries", e);
        }
        return result;
    }

    /**
     * 获取过去 N 天的使用数据（用于图表展示）。
     * 返回 appName → (date → totalSeconds)，按日期升序。
     * 同时包含已完成会话和进行中会话。
     */
    public Map<String, Map<LocalDate, Integer>> getDaysUsage(int days) {
        Map<String, Map<LocalDate, Integer>> result = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1);
        LocalDateTime rangeStart = startDate.atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(1).atStartOfDay();

        // 已完成会话
        String sql = """
                SELECT a.app_name, r.start_time, r.duration_seconds
                FROM usage_records r
                JOIN monitored_apps a ON a.id = r.app_id
                WHERE r.duration_seconds IS NOT NULL
                  AND a.is_active = 1
                  AND r.start_time >= ?
                  AND r.start_time < ?
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(rangeStart));
            ps.setTimestamp(2, Timestamp.valueOf(rangeEnd));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String appName = rs.getString("app_name");
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts == null) continue;
                    LocalDate date = ts.toLocalDateTime().toLocalDate();
                    int seconds = rs.getInt("duration_seconds");
                    result.computeIfAbsent(appName, k -> new LinkedHashMap<>()).merge(date, seconds, Integer::sum);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query completed usage for days", e);
        }

        // 进行中会话（今天，仅激活的应用）
        String ongoingSql = """
                SELECT a.app_name, r.start_time
                FROM usage_records r
                JOIN monitored_apps a ON a.id = r.app_id
                WHERE r.end_time IS NULL
                  AND a.is_active = 1
                  AND r.start_time < ?
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(ongoingSql)) {
            ps.setTimestamp(1, Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
            LocalDateTime now = LocalDateTime.now();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String appName = rs.getString("app_name");
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts == null) continue;
                    LocalDateTime start = ts.toLocalDateTime();
                    if (start.isBefore(today.atStartOfDay())) start = today.atStartOfDay();
                    long seconds = Duration.between(start, now).getSeconds();
                    if (seconds < 0) seconds = 0;
                    if (seconds > Integer.MAX_VALUE) seconds = Integer.MAX_VALUE;
                    result.computeIfAbsent(appName, k -> new LinkedHashMap<>()).merge(today, (int) seconds, Integer::sum);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query active usage for days", e);
        }

        return result;
    }

    /**
     * 删除指定时间范围内的 usage_records 和 daily_summary（用于调试清除今日数据）。
     */
    public void deleteUsageRecordsInRange(LocalDateTime from, LocalDateTime to) {
        String delRecords = "DELETE FROM usage_records WHERE start_time >= ? AND start_time < ?";
        String delSummary = "DELETE FROM daily_summary WHERE date = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps1 = conn.prepareStatement(delRecords);
             PreparedStatement ps2 = conn.prepareStatement(delSummary)) {
            ps1.setTimestamp(1, Timestamp.valueOf(from));
            ps1.setTimestamp(2, Timestamp.valueOf(to));
            ps1.executeUpdate();
            ps2.setDate(1, Date.valueOf(from.toLocalDate()));
            ps2.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete usage records in range", e);
        }
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
            throw new RuntimeException("Failed to flush yesterday usage", e);
        }
    }

    /**
     * 获取所有已完成的使用记录（用于 CSV 导出）。
     * 每条记录包含：应用名、日期、开始时间、结束时间、持续秒数。
     */
    public List<String[]> getExportRows() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"应用名", "日期", "开始时间", "结束时间", "持续秒数", "持续时长"});
        LocalDate today = LocalDate.now();
        LocalDateTime rangeStart = today.minusDays(30).atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(1).atStartOfDay();
        String sql = """
                SELECT a.app_name, r.start_time, r.end_time, r.duration_seconds
                FROM usage_records r
                JOIN monitored_apps a ON a.id = r.app_id
                WHERE r.duration_seconds IS NOT NULL
                  AND a.is_active = 1
                  AND r.start_time >= ?
                  AND r.start_time < ?
                ORDER BY r.start_time DESC
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(rangeStart));
            ps.setTimestamp(2, Timestamp.valueOf(rangeEnd));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                String appName = rs.getString("app_name");
                Timestamp startTs = rs.getTimestamp("start_time");
                Timestamp endTs = rs.getTimestamp("end_time");
                int seconds = rs.getInt("duration_seconds");
                String date = startTs != null ? startTs.toLocalDateTime().toLocalDate().toString() : "";
                String start = startTs != null ? startTs.toLocalDateTime().toString() : "";
                String end = endTs != null ? endTs.toLocalDateTime().toString() : "";
                String duration = formatDurationCSV(seconds);
                rows.add(new String[]{appName, date, start, end, String.valueOf(seconds), duration});
            }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to export usage rows", e);
        }
        return rows;
    }

    private static String formatDurationCSV(int totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + " 秒";
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) return hours + " 小时 " + minutes + " 分钟";
        return minutes + " 分钟";
    }

    /**
     * 从 CSV 数据导入使用记录（追加到本地数据之上）。
     * 每行格式：应用名,日期,开始时间,结束时间,持续秒数,持续时长
     * 第一行是标题行跳过。新应用自动创建到 monitored_apps。
     */
    public int importRows(List<String[]> rows) {
        ImportResult result = importRowsDetailed(rows);
        return result.success() ? result.imported() : -1;
    }

    public ImportResult importRowsDetailed(List<String[]> rows) {
        int imported = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> appCache = new LinkedHashMap<>();
        java.util.Set<LocalDate> affectedDates = new java.util.HashSet<>();

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String findSql = "SELECT id, process_name FROM monitored_apps WHERE app_name = ?";
                String insertAppSql = "INSERT INTO monitored_apps (app_name, process_name) VALUES (?, ?)";

                for (int i = 1; i < rows.size(); i++) { // 跳过标题行
                    String[] row = rows.get(i);
                    if (row.length < 5) {
                        skipped++;
                        warnings.add("line " + (i + 1) + ": fewer than 5 columns");
                        continue;
                    }
                    String appName = row[0].trim();
                    String startStr = row[2].trim();
                    String endStr = row[3].trim();
                    String secondsStr = row[4].trim();

                    if (appName.isEmpty() || startStr.isEmpty()) {
                        skipped++;
                        warnings.add("line " + (i + 1) + ": missing app name or start time");
                        continue;
                    }

                    // 解析时间并检查是否在近 30 天内（超出则跳过）
                    String normalizedStart = startStr.contains("T") ? startStr : startStr.replace(" ", "T");
                    LocalDateTime importStartTime;
                    try {
                        importStartTime = LocalDateTime.parse(normalizedStart);
                    } catch (Exception e) {
                        skipped++;
                        warnings.add("line " + (i + 1) + ": invalid start time");
                        continue;
                    }
                    LocalDate importDate = importStartTime.toLocalDate();
                    if (importDate.isBefore(LocalDate.now().minusDays(30))) {
                        skipped++;
                        warnings.add("line " + (i + 1) + ": older than 30 days");
                        continue; // 跳过超过 30 天的数据
                    }

                    LocalDateTime endTime;
                    if (endStr.isEmpty()) {
                        skipped++;
                        warnings.add("line " + (i + 1) + ": missing end time");
                        continue;
                    }
                    String normalizedEnd = endStr.contains("T") ? endStr : endStr.replace(" ", "T");
                    try {
                        endTime = LocalDateTime.parse(normalizedEnd);
                    } catch (Exception e) {
                        skipped++;
                        warnings.add("line " + (i + 1) + ": invalid end time");
                        continue;
                    }
                    if (!endTime.isAfter(importStartTime)) {
                        skipped++;
                        warnings.add("line " + (i + 1) + ": end time is not after start time");
                        continue;
                    }
                    if (!secondsStr.isEmpty()) {
                        try {
                            if (Integer.parseInt(secondsStr) < 0) {
                                skipped++;
                                warnings.add("line " + (i + 1) + ": negative duration");
                                continue;
                            }
                        } catch (NumberFormatException e) {
                            skipped++;
                            warnings.add("line " + (i + 1) + ": invalid duration");
                            continue;
                        }
                    }

                    // 应用名 → appId
                    int appId;
                    if (appCache.containsKey(appName)) {
                        appId = appCache.get(appName);
                    } else {
                        // 查数据库
                        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                            ps.setString(1, appName);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    appId = rs.getInt("id");
                                } else {
                                    // 创建新应用，process_name 取 appName 的小写+" .exe"（若无后缀）
                                    String processName = appName.toLowerCase();
                                    if (!processName.endsWith(".exe")) {
                                        processName = processName + ".exe";
                                    }
                                    try (PreparedStatement insPs = conn.prepareStatement(insertAppSql, Statement.RETURN_GENERATED_KEYS)) {
                                        insPs.setString(1, appName);
                                        insPs.setString(2, processName);
                                        insPs.executeUpdate();
                                        try (ResultSet kg = insPs.getGeneratedKeys()) {
                                            appId = kg.next() ? kg.getInt(1) : -1;
                                            if (appId < 0) {
                                                skipped++;
                                                warnings.add("line " + (i + 1) + ": failed to create app");
                                                continue;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        appCache.put(appName, appId);
                    }

                    insertCompletedSessionSegments(conn, appId, importStartTime, endTime);
                    LocalDate affected = importStartTime.toLocalDate();
                    while (!affected.isAfter(endTime.toLocalDate())) {
                        affectedDates.add(affected);
                        affected = affected.plusDays(1);
                    }
                    imported++;
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            System.err.println("[UsageRecordDao] importRows 失败: " + e.getMessage());
            warnings.add("fatal: " + e.getMessage());
            return new ImportResult(-1, skipped, warnings);
        }

        // 刷新受影响的 daily_summary
        for (LocalDate date : affectedDates) {
            rebuildDailySummaryForDate(date);
        }

        return new ImportResult(imported, skipped, warnings);
    }

    /**
     * 为指定日期重建 daily_summary。
     */
    private void rebuildDailySummaryForDate(LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
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
            ps.setDate(1, Date.valueOf(date));
            ps.setTimestamp(2, Timestamp.valueOf(dayStart));
            ps.setTimestamp(3, Timestamp.valueOf(dayEnd));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to rebuild daily summary", e);
        }
    }
}
