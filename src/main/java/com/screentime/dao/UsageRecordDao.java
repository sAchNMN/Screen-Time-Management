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
import java.util.LinkedHashMap;
import java.util.Map;

public class UsageRecordDao {
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
        // 程序异常退出时可能留下 end_time=NULL 的会话。
        // 下次启动时用 last_seen_time 作为结束时间恢复，避免把停机时间算进使用时长。
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
        // 心跳只更新时间，不新增记录；它表示“这个应用仍然保持前台状态”。
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
        // 一次使用可能跨过 0 点。为了按天统计准确，这里把跨天会话拆成多段。
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
     * 查询最近的所有使用数据。
     * 历史记录页展示的是“已经产生过的数据”，不按 is_active 过滤。
     * 这样应用从监控列表移除后，旧历史仍能证明数据保存在本地。
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
     * 不按 is_active 过滤，避免历史数据因为应用被移除而不可见。
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
            throw new RuntimeException("Failed to query daily summaries", e);
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
            throw new RuntimeException("Failed to flush yesterday usage", e);
        }
    }

}
