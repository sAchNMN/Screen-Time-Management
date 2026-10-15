/* ============================================================
 *  SettingsController.java - settings page controller
 *  Keeps only the close-to-tray preference used by the current
 *  presentation version.
 * ============================================================ */
package com.screentime.controller;

import com.screentime.App;
import com.screentime.dao.AppSettingsDao;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;

public class SettingsController {

    @FXML
    private CheckBox cbMinimizeToTray;

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
