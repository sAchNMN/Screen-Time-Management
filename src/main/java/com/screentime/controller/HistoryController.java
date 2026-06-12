/* ============================================================
 *  HistoryController.java — 统计历史记录页面控制器
 *  展示历史使用记录的表格视图
 * ============================================================ */
package com.screentime.controller;

import com.screentime.dao.UsageRecordDao;
import com.screentime.util.TimeUtil;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.time.LocalDate;
import java.util.Map;

public class HistoryController {

    @FXML
    private Button btnRefresh;
    @FXML
    private Label lblHistoryHint;
    @FXML
    private TableView<HistoryRow> tblHistory;
    @FXML
    private TableColumn<HistoryRow, String> colHistoryApp;
    @FXML
    private TableColumn<HistoryRow, String> colHistoryDate;
    @FXML
    private TableColumn<HistoryRow, String> colHistoryDuration;

    private final UsageRecordDao usageRecordDao = new UsageRecordDao();
    private final ObservableList<HistoryRow> historyData = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        colHistoryApp.setCellValueFactory(new PropertyValueFactory<>("appName"));
        colHistoryDate.setCellValueFactory(new PropertyValueFactory<>("date"));
        colHistoryDuration.setCellValueFactory(new PropertyValueFactory<>("duration"));
        tblHistory.setItems(historyData);
        tblHistory.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        btnRefresh.setOnAction(e -> refreshData());
        refreshData();
    }

    private void refreshData() {
        historyData.clear();

        // 使用 getRecentUsageHistory() 替代 getAllDailySummaries()，
        // 因为前者包含 daily_summary + usage_records（已完成 + 进行中会话），
        // 能正确展示今天的数据，而 daily_summary 要次日凌晨才汇总。
        Map<String, Map<LocalDate, Integer>> data = usageRecordDao.getRecentUsageHistory();
        if (data.isEmpty()) {
            lblHistoryHint.setVisible(true);
            return;
        }
        lblHistoryHint.setVisible(false);

        for (var appEntry : data.entrySet()) {
            String appName = appEntry.getKey();
            for (var dayEntry : appEntry.getValue().entrySet()) {
                LocalDate date = dayEntry.getKey();
                int totalSec = dayEntry.getValue();
                historyData.add(new HistoryRow(
                        appName,
                        date.toString(),
                        formatDuration(totalSec)
                ));
            }
        }
    }

    private static String formatDuration(int totalSeconds) {
        return TimeUtil.formatDuration(totalSeconds);
    }

    public static class HistoryRow {
        private final String appName;
        private final String date;
        private final String duration;

        public HistoryRow(String appName, String date, String duration) {
            this.appName = appName;
            this.date = date;
            this.duration = duration;
        }

        public String getAppName() { return appName; }
        public String getDate() { return date; }
        public String getDuration() { return duration; }
    }
}
