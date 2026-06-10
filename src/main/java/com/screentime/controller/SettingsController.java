/* ============================================================
 *  SettingsController.java — 设置页面控制器
 *  管理应用级偏好设置：
 *    - 读取/写入"关闭时最小化到托盘"选项
 *    - 后台检测频率（1-60 秒，默认 15 秒）
 *    - 统计页面刷新频率（1-99 秒，默认 15 秒）
 *    - 设置值持久化存储于 app_settings 表
 * ============================================================ */
package com.screentime.controller;

import com.screentime.App;
import com.screentime.dao.AppSettingsDao;
import com.screentime.service.ForegroundMonitorService;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;

public class SettingsController {

    @FXML
    private CheckBox cbMinimizeToTray;
    @FXML
    private Label lblSaveHint;
    @FXML
    private Spinner<Integer> spPollInterval;
    @FXML
    private Spinner<Integer> spStatRefreshInterval;

    private final AppSettingsDao settingsDao = new AppSettingsDao();

    @FXML
    public void initialize() {
        settingsDao.initTable();

        // ---- 关闭到托盘 ----
        boolean saved = "true".equals(settingsDao.get("close_to_tray", "true"));
        cbMinimizeToTray.setSelected(saved);
        App.setCloseToTray(saved);

        cbMinimizeToTray.setOnAction(e -> {
            boolean enabled = cbMinimizeToTray.isSelected();
            App.setCloseToTray(enabled);
            settingsDao.set("close_to_tray", String.valueOf(enabled));
        });

        // ---- 后台检测频率 ----
        int pollInterval = getIntSetting("poll_interval_seconds", 15);
        pollInterval = clamp(pollInterval, 1, 60);
        SpinnerValueFactory<Integer> pollFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, pollInterval);
        spPollInterval.setValueFactory(pollFactory);
        spPollInterval.setEditable(true);
        spPollInterval.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            int val = clamp(newVal, 1, 60);
            if (val != newVal) {
                spPollInterval.getValueFactory().setValue(val);
                return;
            }
            ForegroundMonitorService.getInstance().setPollIntervalSeconds(val);
        });

        // ---- 统计页面刷新频率 ----
        int refreshInterval = getIntSetting("stat_refresh_interval_seconds", 15);
        refreshInterval = clamp(refreshInterval, 1, 99);
        SpinnerValueFactory<Integer> refreshFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 99, refreshInterval);
        spStatRefreshInterval.setValueFactory(refreshFactory);
        spStatRefreshInterval.setEditable(true);
        spStatRefreshInterval.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            int val = clamp(newVal, 1, 99);
            if (val != newVal) {
                spStatRefreshInterval.getValueFactory().setValue(val);
                return;
            }
            settingsDao.set("stat_refresh_interval_seconds", String.valueOf(val));
        });
    }

    private int getIntSetting(String key, int defaultValue) {
        try {
            return Integer.parseInt(settingsDao.get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
