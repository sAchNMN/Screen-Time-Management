/* ============================================================
 *  StatisticsController.java — 使用统计页面控制器
 *  展示被监控应用的使用时长统计：
 *    - 今日使用时长（应用图标 + 名称 + 时长，定时刷新）
 *    - 历史日汇总记录
 *    - 刷新频率由用户在设置页面设定（1-99 秒，默认 15 秒）
 *  数据由后台 ForegroundMonitorService 写入
 * ============================================================ */
package com.screentime.controller;

import com.screentime.dao.AppSettingsDao;
import com.screentime.dao.MonitoredAppDao;
import com.screentime.dao.UsageRecordDao;
import com.screentime.model.MonitoredApp;
import com.screentime.service.ForegroundMonitorService;
import com.screentime.util.IconUtil;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
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
    private final AppSettingsDao settingsDao = new AppSettingsDao();
    private final ForegroundMonitorService monitorService = ForegroundMonitorService.getInstance();
    private final MonitoredAppDao monitoredAppDao = new MonitoredAppDao();

    private final ObservableList<TodayRow> todayRows = FXCollections.observableArrayList();
    private final ObservableList<HistoryRow> historyRows = FXCollections.observableArrayList();

    private Timeline refreshTimer;

    @FXML
    public void initialize() {
        tblToday.setItems(todayRows);
        tblHistory.setItems(historyRows);

        // 今日表格 — "应用"列用自定义 cell（图标 + 名称）
        colTodayApp.setCellFactory(col -> new TableCell<>() {
            private final ImageView iconView = new ImageView();
            private final Label nameLabel = new Label();
            private final HBox box = new HBox(8, iconView, nameLabel);

            {
                iconView.setFitWidth(20);
                iconView.setFitHeight(20);
                iconView.setPreserveRatio(true);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    TodayRow row = getTableView().getItems().get(getIndex());
                    if (row.getIcon() != null) {
                        iconView.setImage(row.getIcon());
                    } else {
                        iconView.setImage(null);
                    }
                    nameLabel.setText(item);
                    setGraphic(box);
                }
            }
        });

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

        // 从设置读取统计刷新间隔
        int refreshSeconds = getRefreshInterval();
        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(refreshSeconds), e -> refreshData()));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
    }

    /**
     * 重新设定刷新间隔（设置变更时从外部调用，或在下次导航时生效）。
     */
    private int getRefreshInterval() {
        try {
            int val = Integer.parseInt(settingsDao.get("stat_refresh_interval_seconds", "15"));
            return Math.max(1, Math.min(99, val));
        } catch (NumberFormatException e) {
            return 15;
        }
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
                int appId = entry.getKey();
                String appName = getAppName(appId);
                Image icon = getAppIcon(appId);
                todayRows.add(new TodayRow(icon, appName, formatDuration(seconds)));
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
        for (MonitoredApp app : monitoredAppDao.findAll()) {
            if (app.getId() == appId) {
                return app.getAppName();
            }
        }
        return "应用 #" + appId;
    }

    /**
     * 获取应用图标：优先从监控引擎缓存获取，否则尝试用进程名搜索。
     */
    private Image getAppIcon(int appId) {
        // 优先从监控引擎的缓存路径获取
        Image icon = monitorService.getAppIcon(appId);
        if (icon != null) {
            return icon;
        }

        // 回退：根据进程名搜索当前运行的进程
        for (MonitoredApp app : monitoredAppDao.findAll()) {
            if (app.getId() == appId) {
                String foundPath = findProcessPath(app.getProcessName());
                if (foundPath != null) {
                    monitorService.cacheAppPath(appId, foundPath);
                    return monitorService.getAppIcon(appId);
                }
                break;
            }
        }
        return null;
    }

    /**
     * 在系统当前进程中搜索匹配的进程名，返回其完整路径。
     */
    private String findProcessPath(String processName) {
        return ProcessHandle.allProcesses()
                .flatMap(ph -> ph.info().command().stream())
                .filter(path -> {
                    int sep = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
                    String name = sep >= 0 ? path.substring(sep + 1) : path;
                    return name.equalsIgnoreCase(processName);
                })
                .findFirst()
                .orElse(null);
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
        private final Image icon;
        private final String appName;
        private final String duration;

        public TodayRow(Image icon, String appName, String duration) {
            this.icon = icon;
            this.appName = appName;
            this.duration = duration;
        }

        public Image getIcon() { return icon; }
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
