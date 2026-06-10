/* ============================================================
 *  SettingsController.java — 设置页面控制器
 *  管理应用级偏好设置：
 *    - 读取/写入"关闭时最小化到系统托盘"选项
 * ============================================================ */
package com.screentime.controller;

import com.screentime.App;
import com.screentime.dao.AppSettingsDao;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

public class SettingsController {

    @FXML
    private CheckBox cbMinimizeToTray;
    @FXML
    private Label lblSaveHint;

    private final AppSettingsDao settingsDao = new AppSettingsDao();

    @FXML
    public void initialize() {
        settingsDao.initTable();

        boolean saved = "true".equals(settingsDao.get("close_to_tray", "true"));
        cbMinimizeToTray.setSelected(saved);
        App.setCloseToTray(saved);

        cbMinimizeToTray.setOnAction(e -> {
            boolean enabled = cbMinimizeToTray.isSelected();
            App.setCloseToTray(enabled);
            settingsDao.set("close_to_tray", String.valueOf(enabled));
        });
    }
}
