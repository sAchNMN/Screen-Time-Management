/* ============================================================
 *  MonitoredAppDao.java — 被监控应用 DAO
 *  管理三张核心业务表：
 *    - monitored_apps: 用户添加的被监控应用清单
 *    - usage_records:  应用单次使用时间段记录
 *    - daily_summary:  按天统计的使用时长汇总
 *  提供 find/insert/delete/exists 等操作方法
 *
 *  删除采用软删除（is_active 列），保留历史数据。
 *  重新添加同一应用时自动恢复历史数据（复用原 app_id）。
 *
 *  永久显示（is_permanent）最多 3 个，取消后 30 分钟清除 31 天前数据。
 * ============================================================ */
package com.screentime.dao;

import com.screentime.model.MonitoredApp;
import com.screentime.util.DatabaseUtil;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MonitoredAppDao {

    public void initTable() {
        DatabaseUtil.initializeSchema();
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            // 兼容旧数据库
            try { stmt.executeUpdate("ALTER TABLE monitored_apps ADD COLUMN is_active INTEGER DEFAULT 1"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE monitored_apps ADD COLUMN is_permanent INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE monitored_apps ADD COLUMN permanent_cancelled_at TIMESTAMP"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database tables", e);
        }
    }

    public List<MonitoredApp> findAll() {
        String sql = "SELECT id, app_name, process_name, created_at, is_permanent, permanent_cancelled_at FROM monitored_apps WHERE is_active = 1 ORDER BY id";
        List<MonitoredApp> list = new ArrayList<>();
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Timestamp cancelTs = rs.getTimestamp("permanent_cancelled_at");
                list.add(new MonitoredApp(
                        rs.getInt("id"),
                        rs.getString("app_name"),
                        rs.getString("process_name"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getInt("is_permanent") == 1,
                        cancelTs != null ? cancelTs.toLocalDateTime() : null
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query monitored apps", e);
        }
        return list;
    }

    public MonitoredApp insert(MonitoredApp app) {
        String findInactiveSql = "SELECT id FROM monitored_apps WHERE process_name = ? AND is_active = 0 LIMIT 1";
        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(findInactiveSql)) {
                    ps.setString(1, app.getProcessName());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int oldId = rs.getInt("id");
                            String reactivateSql = "UPDATE monitored_apps SET is_active = 1, app_name = ? WHERE id = ?";
                            try (PreparedStatement updPs = conn.prepareStatement(reactivateSql)) {
                                updPs.setString(1, app.getAppName());
                                updPs.setInt(2, oldId);
                                updPs.executeUpdate();
                            }
                            app.setId(oldId);
                            conn.commit();
                            return app;
                        }
                    }
                }
                String insertSql = "INSERT INTO monitored_apps (app_name, process_name) VALUES (?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, app.getAppName());
                    ps.setString(2, app.getProcessName());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) app.setId(rs.getInt(1));
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert monitored app", e);
        }
        return app;
    }

    public void delete(int id) {
        String sql = "UPDATE monitored_apps SET is_active = 0 WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete monitored app", e);
        }
    }

    public boolean existsByProcessName(String processName) {
        String sql = "SELECT COUNT(*) FROM monitored_apps WHERE process_name = ? AND is_active = 1";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, processName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check monitored app existence", e);
        }
    }

    // ---- 永久显示 ----

    public void setPermanent(int appId, boolean permanent) {
        String sql = permanent
                ? "UPDATE monitored_apps SET is_permanent = 1, permanent_cancelled_at = NULL WHERE id = ?"
                : "UPDATE monitored_apps SET is_permanent = 0, permanent_cancelled_at = ? WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (permanent) {
                ps.setInt(1, appId);
            } else {
                ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                ps.setInt(2, appId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set permanent", e);
        }
    }

    public int countPermanent() {
        String sql = "SELECT COUNT(*) FROM monitored_apps WHERE is_active = 1 AND is_permanent = 1";
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count permanent apps", e);
        }
    }

    /**
     * 清理非永久显示应用中超过 31 天的历史数据。
     * 条件：is_permanent = 0 AND permanent_cancelled_at IS NOT NULL
     *       AND (now - permanent_cancelled_at) > 30 分钟
     */
    public void cleanupExpiredNonPermanent() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        LocalDate dateCutoff = LocalDate.now().minusDays(31);

        String a = "DELETE FROM usage_records WHERE app_id IN (SELECT id FROM monitored_apps WHERE is_permanent = 0 AND permanent_cancelled_at IS NOT NULL AND permanent_cancelled_at <= ?) AND date(start_time, 'localtime') < ?";
        String b = "DELETE FROM daily_summary WHERE app_id IN (SELECT id FROM monitored_apps WHERE is_permanent = 0 AND permanent_cancelled_at IS NOT NULL AND permanent_cancelled_at <= ?) AND date < ?";

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(a)) {
                    ps.setTimestamp(1, Timestamp.valueOf(cutoff));
                    ps.setString(2, dateCutoff.toString());
                    int ra = ps.executeUpdate();
                    if (ra > 0) System.out.println("[MonitoredAppDao] 清理 " + ra + " 条过期 usage_records");
                }
                try (PreparedStatement ps = conn.prepareStatement(b)) {
                    ps.setTimestamp(1, Timestamp.valueOf(cutoff));
                    ps.setString(2, dateCutoff.toString());
                    int rb = ps.executeUpdate();
                    if (rb > 0) System.out.println("[MonitoredAppDao] 清理 " + rb + " 条过期 daily_summary");
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to cleanup expired non-permanent usage data", e);
        }
    }
}
