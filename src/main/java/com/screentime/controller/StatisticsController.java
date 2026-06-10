/* ============================================================
 *  StatisticsController.java — 使用统计页面控制器
 *  展示被监控应用的使用时长统计：
 *    - 今日使用时长（实时更新，每 15 秒刷新）
 *    - 历史日汇总记录
 *  从 UsageRecordDao 读取数据，数据由后台 ForegroundMonitorService 写入
 * ============================================================ */
package com.screentime.controller;

import com.screentime.dao.UsageRecordDao;
import com.screentime.model.MonitoredApp;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.Duration;

import java.time.LocalDate;
import java.util.Map;

public class StatisticsController {

    @FXML
    private TableView<TodayRow> tblToday;
    @FXML
    private TableColumn<TodayRow, String> colTodayApp;
    @FXML
    private TableColumn<TodayRow, String> colTodayDuration;

    @FXML
    private TableView<HistoryRow> tblHistory;
    @FXML
    private TableColumn<HistoryRow, String> colHistoryApp;
    @FXML
    private TableColumn<HistoryRow, String> colHistoryDate;
    @FXML
    private TableColumn<HistoryRow, String> colHistoryDuration;

    @FXML
    private Label lblTodayHint;
    @FXML
    private Label lblHistoryHint;

    private final UsageRecordDao dao = new UsageRecordDao();
    private final ObservableList<TodayRow> todayRows = FXCollections.observableArrayList();
    private final ObservableList<HistoryRow> historyRows = FXCollections.observableArrayList();

    private Timeline refreshTimer;

    @FXML
    public void initialize() {
        tblToday.setItems(todayRows);
        tblHistory.setItems(historyRows);

        colTodayApp.setCellValueFactory(new PropertyValueFactory<>("appName"));
        colTodayDuration.setCellValueFactory(new PropertyValueFactory<>("duration"));

        colHistoryApp.setCellValueFactory(new PropertyValueFactory<>("appName"));
        colHistoryDate.setCellValueFactory(new PropertyValueFactory<>("date"));
        colHistoryDuration.setCellValueFactory(new PropertyValueFactory<>("duration"));

        // 设置列宽
        colTodayApp.prefWidthProperty().bind(tblToday.widthProperty().multiply(0.55));
        colTodayDuration.prefWidthProperty().bind(tblToday.widthProperty().multiply(0.43));
        colHistoryApp.prefWidthProperty().bind(tblHistory.widthProperty().multiply(0.35));
        colHistoryDate.prefWidthProperty().bind(tblHistory.widthProperty().multiply(0.30));
        colHistoryDuration.prefWidthProperty().bind(tblHistory.widthProperty().multiply(0.33));

        refreshData();

        // 每 15 秒自动刷新
        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(15), e -> refreshData()));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
    }

    private void refreshData() {
        refreshToday();
        refreshHistory();
    }

    private void refreshToday() {
        todayRows.clear();
        Map<Integer, Integer> usage = dao.getTodayUsage();
        if (usage.isEmpty()) {
            lblTodayHint.setText("暂无今日使用记录 — 请先在「监控软件管理」中添加要监控的应用");
            lblTodayHint.setVisible(true);
            return;
        }
        lblTodayHint.setVisible(false);

        for (Map.Entry<Integer, Integer> entry : usage.entrySet()) {
            int seconds = entry.getValue();
            if (seconds > 0) {
                // 通过 DAO 获取 appName — 这里简单查一下
                String appName = getAppName(entry.getKey());
                todayRows.add(new TodayRow(appName, formatDuration(seconds)));
            }
        }

        if (todayRows.isEmpty()) {
            lblTodayHint.setText("今日暂无使用记录");
            lblTodayHint.setVisible(true);
        }
    }

    private void refreshHistory() {
        historyRows.clear();
        Map<String, Map<LocalDate, Integer>> summaries = dao.getAllDailySummaries();
        if (summaries.isEmpty()) {
            lblHistoryHint.setText("暂无历史记录");
            lblHistoryHint.setVisible(true);
            return;
        }
        lblHistoryHint.setVisible(false);

        for (Map.Entry<String, Map<LocalDate, Integer>> appEntry : summaries.entrySet()) {
            String appName = appEntry.getKey();
            for (Map.Entry<LocalDate, Integer> dateEntry : appEntry.getValue().entrySet()) {
                historyRows.add(new HistoryRow(
                        appName,
                        dateEntry.getKey().toString(),
                        formatDuration(dateEntry.getValue())
                ));
            }
        }
    }

    private String getAppName(int appId) {
        try {
            var apps = new com.screentime.dao.MonitoredAppDao().findAll();
            for (MonitoredApp app : apps) {
                if (app.getId() == appId) {
                    return app.getAppName();
                }
            }
        } catch (Exception ignored) {
        }
        return "应用 #" + appId;
    }

    /**
     * 格式化秒数为 "X 小时 Y 分钟" 或 "Y 分钟" 或 "Z 秒"
     */
    static String formatDuration(int totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + " 秒";
        }
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        return minutes + " 分钟";
    }

    // ---- TableView 数据行类 ----

    public static class TodayRow {
        private final String appName;
        private final String duration;

        public TodayRow(String appName, String duration) {
            this.appName = appName;
            this.duration = duration;
        }

        public String getAppName() { return appName; }
        public String getDuration() { return duration; }
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
