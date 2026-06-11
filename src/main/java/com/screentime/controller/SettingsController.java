/* ============================================================
 *  SettingsController.java — 设置页面控制器
 *  管理应用级偏好设置：
 *    - 读取/写入"关闭时最小化到系统托盘"选项
 * ============================================================ */
package com.screentime.controller;

import com.screentime.App;
import com.screentime.dao.AppSettingsDao;
import com.screentime.dao.UsageRecordDao;
import com.screentime.service.ForegroundMonitorService;
import com.screentime.util.StartupUtil;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SettingsController {

    @FXML
    private CheckBox cbMinimizeToTray;
    @FXML
    private CheckBox cbStartup;
    @FXML
    private CheckBox cbStartMinimized;
    @FXML
    private Label lblSaveHint;
    @FXML
    private Spinner<Integer> spMinSession;
    @FXML
    private Button btnExport;
    @FXML
    private Button btnImport;
    @FXML
    private Spinner<Integer> spDailyLimit;
    @FXML
    private Spinner<Integer> spRestDuration;
    @FXML
    private Label lblRestStatus;
    @FXML
    private CheckBox cbLimitEnabled;

    private final AppSettingsDao settingsDao = new AppSettingsDao();
    private final UsageRecordDao usageRecordDao = new UsageRecordDao();

    private int importedCount = 0;

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

        // ---- 开机自启 ----
        cbStartup.setSelected(StartupUtil.isStartupEnabled());
        cbStartup.setOnAction(e -> {
            boolean enabled = cbStartup.isSelected();
            StartupUtil.setStartupEnabled(enabled);
            updateStartMinimizedState();
        });

        // ---- 开机静默启动 ----
        cbStartMinimized.setSelected("true".equals(settingsDao.get("start_minimized", "false")));
        cbStartMinimized.setOnAction(e -> {
            settingsDao.set("start_minimized", String.valueOf(cbStartMinimized.isSelected()));
        });
        updateStartMinimizedState();

        // ---- 短时运行过滤 ----
        int minSession = getIntSetting("min_session_seconds", 30);
        minSession = Math.max(1, minSession);
        SpinnerValueFactory<Integer> factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 3600, minSession);
        spMinSession.setValueFactory(factory);
        spMinSession.setEditable(true);
        spMinSession.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            int val = Math.max(1, newVal);
            if (val != newVal) {
                spMinSession.getValueFactory().setValue(val);
                return;
            }
            ForegroundMonitorService.getInstance().setMinSessionSeconds(val);
        });

        // ---- 数据导出 ----
        btnExport.setOnAction(e -> exportData());

        // ---- 数据导入 ----
        btnImport.setOnAction(e -> importData());

        // ---- 每日使用上限 ----
        var monitorSvc = ForegroundMonitorService.getInstance();

        cbLimitEnabled.setSelected(monitorSvc.isLimitEnabled());
        cbLimitEnabled.setOnAction(e -> {
            monitorSvc.setLimitEnabled(cbLimitEnabled.isSelected());
        });

        int dailyLimit = getIntSetting("daily_limit_minutes", 0);
        SpinnerValueFactory<Integer> limitFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 1440, dailyLimit);
        spDailyLimit.setValueFactory(limitFactory);
        spDailyLimit.setEditable(true);
        spDailyLimit.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            monitorSvc.setDailyLimitMinutes(newVal);
        });

        int restDur = getIntSetting("rest_duration_minutes", 5);
        SpinnerValueFactory<Integer> restFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 120, restDur);
        spRestDuration.setValueFactory(restFactory);
        spRestDuration.setEditable(true);
        spRestDuration.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            monitorSvc.setRestDurationMinutes(newVal);
        });

        // 每 5 秒刷新休息状态
        javafx.animation.Timeline restTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5), e -> refreshRestStatus()));
        restTimer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        restTimer.play();
    }

    private void refreshRestStatus() {
        var svc = ForegroundMonitorService.getInstance();
        if (svc.isResting()) {
            var until = svc.getRestUntil();
            if (until != null) {
                long mins = java.time.Duration.between(java.time.LocalDateTime.now(), until).toMinutes();
                long secs = java.time.Duration.between(java.time.LocalDateTime.now(), until).toSeconds() % 60;
                lblRestStatus.setText("休息中，剩余 " + mins + " 分 " + secs + " 秒 - 到达上限后自动休息");
                lblRestStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: #e74c3c;");
            }
            lblRestStatus.setVisible(true);
        } else {
            lblRestStatus.setVisible(false);
        }
    }

    private int getIntSetting(String key, int defaultValue) {
        try {
            return Integer.parseInt(settingsDao.get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 当开机自启关闭时，锁定开机静默启动也为关闭状态。
     */
    private void updateStartMinimizedState() {
        if (!cbStartup.isSelected()) {
            cbStartMinimized.setSelected(false);
            cbStartMinimized.setDisable(true);
            settingsDao.set("start_minimized", "false");
        } else {
            cbStartMinimized.setDisable(false);
        }
    }

    private void exportData() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出使用记录");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV 文件 (*.csv)", "*.csv"));
        chooser.setInitialFileName("screen-time-export.csv");

        File file = chooser.showSaveDialog(btnExport.getScene().getWindow());
        if (file == null) return;

        try (PrintWriter pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
            List<String[]> rows = usageRecordDao.getExportRows();
            for (String[] row : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append(",");
                    String val = row[i];
                    if (val != null && (val.contains(",") || val.contains("\"") || val.contains(" "))) {
                        sb.append("\"").append(val.replace("\"", "\"\"")).append("\"");
                    } else if (val != null) {
                        sb.append(val);
                    }
                }
                pw.println(sb);
            }
            lblSaveHint.setText("导出成功：" + file.getName());
            lblSaveHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #27ae60;");
        } catch (Exception ex) {
            lblSaveHint.setText("导出失败：" + ex.getMessage());
            lblSaveHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #e74c3c;");
        }
        lblSaveHint.setVisible(true);
    }

    private void importData() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入使用记录");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV 文件 (*.csv)", "*.csv"));

        File file = chooser.showOpenDialog(btnImport.getScene().getWindow());
        if (file == null) return;

        try {
            List<String[]> rows = parseCsv(file);
            if (rows.size() <= 1) {
                lblSaveHint.setText("导入失败：CSV 文件为空或只有标题行");
                lblSaveHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #e74c3c;");
                lblSaveHint.setVisible(true);
                return;
            }

            int count = usageRecordDao.importRows(rows);
            if (count >= 0) {
                lblSaveHint.setText("导入成功，已追加 " + count + " 条记录");
                lblSaveHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #27ae60;");
            } else {
                lblSaveHint.setText("导入失败，请检查 CSV 格式");
                lblSaveHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #e74c3c;");
            }
        } catch (Exception ex) {
            lblSaveHint.setText("导入失败：" + ex.getMessage());
            lblSaveHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #e74c3c;");
        }
        lblSaveHint.setVisible(true);
    }

    /**
     * 解析 CSV 文件，处理带引号的字段。
     */
    private List<String[]> parseCsv(File file) throws Exception {
        List<String[]> rows = new java.util.ArrayList<>();
        List<String> lines = java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) continue;
            // 简单的 CSV 行解析（处理引号包裹的字段）
            List<String> fields = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                } else if (c == ',' && !inQuotes) {
                    fields.add(current.toString().trim());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            fields.add(current.toString().trim());
            rows.add(fields.toArray(new String[0]));
        }
        return rows;
    }
}
