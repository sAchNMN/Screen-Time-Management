package com.screentime.dao;

import com.screentime.model.MonitoredApp;
import com.screentime.util.DatabaseUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MonitoredAppDao {

    public void initTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS monitored_apps (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    app_name    TEXT NOT NULL,
                    process_name TEXT NOT NULL,
                    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
                
                CREATE TABLE IF NOT EXISTS usage_records (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    app_id      INTEGER NOT NULL,
                    start_time  TIMESTAMP NOT NULL,
                    end_time    TIMESTAMP,
                    duration_seconds INTEGER,
                    FOREIGN KEY (app_id) REFERENCES monitored_apps(id)
                );
                
                CREATE TABLE IF NOT EXISTS daily_summary (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    app_id      INTEGER NOT NULL,
                    date        DATE NOT NULL,
                    total_seconds INTEGER DEFAULT 0,
                    FOREIGN KEY (app_id) REFERENCES monitored_apps(id),
                    UNIQUE(app_id, date)
                );
                """;
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database tables", e);
        }
    }

    public List<MonitoredApp> findAll() {
        String sql = "SELECT id, app_name, process_name, created_at FROM monitored_apps ORDER BY id";
        List<MonitoredApp> list = new ArrayList<>();
        try (Connection conn = DatabaseUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new MonitoredApp(
                        rs.getInt("id"),
                        rs.getString("app_name"),
                        rs.getString("process_name"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query monitored apps", e);
        }
        return list;
    }

    public MonitoredApp insert(MonitoredApp app) {
        String sql = "INSERT INTO monitored_apps (app_name, process_name) VALUES (?, ?)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, app.getAppName());
            ps.setString(2, app.getProcessName());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    app.setId(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert monitored app", e);
        }
        return app;
    }

    public void delete(int id) {
        String sqlDeleteRecords = "DELETE FROM usage_records WHERE app_id = ?";
        String sqlDeleteSummary = "DELETE FROM daily_summary WHERE app_id = ?";
        String sqlDeleteApp = "DELETE FROM monitored_apps WHERE id = ?";
        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps1 = conn.prepareStatement(sqlDeleteRecords);
                 PreparedStatement ps2 = conn.prepareStatement(sqlDeleteSummary);
                 PreparedStatement ps3 = conn.prepareStatement(sqlDeleteApp)) {
                ps1.setInt(1, id); ps1.executeUpdate();
                ps2.setInt(1, id); ps2.executeUpdate();
                ps3.setInt(1, id); ps3.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete monitored app", e);
        }
    }

    public boolean existsByProcessName(String processName) {
        String sql = "SELECT COUNT(*) FROM monitored_apps WHERE process_name = ?";
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
}
