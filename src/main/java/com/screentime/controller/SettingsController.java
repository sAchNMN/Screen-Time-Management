/* ============================================================
 *  SettingsController.java - settings page controller
 *  Manages application preferences and CSV import/export.
 * ============================================================ */
package com.screentime.controller;

import com.screentime.App;
import com.screentime.dao.AppSettingsDao;
import com.screentime.dao.UsageRecordDao;
import com.screentime.service.ForegroundMonitorService;
import com.screentime.util.StartupUtil;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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

    private final AppSettingsDao settingsDao = new AppSettingsDao();
    private final UsageRecordDao usageRecordDao = new UsageRecordDao();

    @FXML
    public void initialize() {
        settingsDao.initTable();

        // ---- Minimize to tray ----
        boolean saved = "true".equals(settingsDao.get("close_to_tray", "true"));
        cbMinimizeToTray.setSelected(saved);
        App.setCloseToTray(saved);

        cbMinimizeToTray.setOnAction(e -> {
            boolean enabled = cbMinimizeToTray.isSelected();
            App.setCloseToTray(enabled);
            settingsDao.set("close_to_tray", String.valueOf(enabled));
        });

        // ---- Start with Windows ----
        cbStartup.setSelected(StartupUtil.isStartupEnabled());
        cbStartup.setOnAction(e -> {
            boolean enabled = cbStartup.isSelected();
            StartupUtil.setStartupEnabled(enabled);
            updateStartMinimizedState();
        });

        // ---- Start minimized ----
        cbStartMinimized.setSelected("true".equals(settingsDao.get("start_minimized", "false")));
        cbStartMinimized.setOnAction(e -> {
            settingsDao.set("start_minimized", String.valueOf(cbStartMinimized.isSelected()));
        });
        updateStartMinimizedState();

        // ---- Minimum session setting ----
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

        // ---- Data export ----
        btnExport.setOnAction(e -> exportData());

        // ---- Data import ----
        btnImport.setOnAction(e -> importData());
    }

    private void showHint(String message, boolean isError) {
        lblSaveHint.setText(message);
        lblSaveHint.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (isError ? "#e74c3c" : "#27ae60") + ";");
        lblSaveHint.setVisible(true);
    }

    private int getIntSetting(String key, int defaultValue) {
        try {
            return Integer.parseInt(settingsDao.get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Disables start-minimized when startup is disabled.
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
        chooser.setTitle("Export usage records");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files (*.csv)", "*.csv"));
        chooser.setInitialFileName("screen-time-export.csv");

        File file = chooser.showSaveDialog(btnExport.getScene().getWindow());
        if (file == null) return;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try (PrintWriter pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
                    List<String[]> rows = usageRecordDao.getExportRows();
                    for (String[] row : rows) {
                        pw.println(toCsvLine(row));
                    }
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setDataButtonsDisabled(false);
            showHint("Export succeeded: " + file.getName(), false);
        });
        task.setOnFailed(e -> {
            setDataButtonsDisabled(false);
            showHint("Export failed: " + task.getException().getMessage(), true);
        });
        setDataButtonsDisabled(true);
        startBackgroundTask(task, "settings-export");
    }

    private void importData() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import usage records");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files (*.csv)", "*.csv"));

        File file = chooser.showOpenDialog(btnImport.getScene().getWindow());
        if (file == null) return;

        Task<UsageRecordDao.ImportResult> task = new Task<>() {
            @Override
            protected UsageRecordDao.ImportResult call() throws Exception {
                List<String[]> rows = parseCsv(file);
                if (rows.size() <= 1) {
                    throw new IllegalArgumentException("CSV file is empty or only contains a header row");
                }
                return usageRecordDao.importRowsDetailed(rows);
            }
        };
        task.setOnSucceeded(e -> {
            setDataButtonsDisabled(false);
            UsageRecordDao.ImportResult result = task.getValue();
            if (result.success()) {
                String message = "Import finished: " + result.imported() + " imported";
                if (result.skipped() > 0) {
                    message += ", " + result.skipped() + " skipped";
                }
                showHint(message, result.skipped() > 0);
            } else {
                showHint("Import failed; skipped " + result.skipped() + " rows", true);
            }
        });
        task.setOnFailed(e -> {
            setDataButtonsDisabled(false);
            showHint("Import failed: " + task.getException().getMessage(), true);
        });
        setDataButtonsDisabled(true);
        startBackgroundTask(task, "settings-import");
    }

    private static void startBackgroundTask(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void setDataButtonsDisabled(boolean disabled) {
        btnExport.setDisable(disabled);
        btnImport.setDisable(disabled);
    }

    private static String toCsvLine(String[] row) {
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
        return sb.toString();
    }

    /**
     * Parses a CSV file with quoted fields.
     */
    private List<String[]> parseCsv(File file) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (var stream = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            stream.filter(line -> !line.isBlank())
                    .map(SettingsController::parseCsvLine)
                    .forEach(rows::add);
        }
        return rows;
    }

    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("CSV row has an unclosed quote");
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }
}