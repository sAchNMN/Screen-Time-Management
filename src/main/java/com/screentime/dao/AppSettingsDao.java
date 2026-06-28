/* ============================================================
 *  AppSettingsDao.java — 通用设置 DAO
 *  对 app_settings 表（键值对存储）的 CRUD 操作：
 *    - initTable(): 创建表
 *    - get(key):    读取设置值
 *    - set(key, value): 写入/更新设置值
 *  当前存储的设置：close_to_tray、window_width、window_height
 * ============================================================ */
package com.screentime.dao;

import com.screentime.util.DatabaseUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AppSettingsDao {

    public void initTable() {
        DatabaseUtil.initializeSchema();
    }

    public String get(String key, String defaultValue) {
        String sql = "SELECT value FROM app_settings WHERE key = ?";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            // 表可能还不存在，返回默认值
        }
        return defaultValue;
    }

    public void set(String key, String value) {
        String sql = "INSERT OR REPLACE INTO app_settings (key, value) VALUES (?, ?)";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to save setting " + key + ": " + e.getMessage());
        }
    }
}
