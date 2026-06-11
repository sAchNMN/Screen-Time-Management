/* ============================================================
 *  StatisticsController.java — 今日使用时长页面控制器
 *  展示被监控应用今日的使用时长统计：
 *    - 应用图标 + 名称 + 时长
 *    - 点击右上角「刷新数据」按钮手动更新
 * ============================================================ */
package com.screentime.controller;

import com.screentime.dao.MonitoredAppDao;
import com.screentime.dao.UsageRecordDao;
import com.screentime.model.MonitoredApp;
import com.screentime.service.ForegroundMonitorService;
import com.screentime.util.IconUtil;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;

import java.util.LinkedHashMap;
import java.util.Map;

public class StatisticsController {

    @FXML
    private TableView<TodayRow> tblToday;
    @FXML
    private TableColumn<TodayRow, String> colTodayApp;
    @FXML
    private TableColumn<TodayRow, String> colTodayDuration;
    @FXML
    private Label lblTodayHint;
    @FXML
    private Button btnRefresh;

    private final UsageRecordDao dao = new UsageRecordDao();
    private final MonitoredAppDao monitoredAppDao = new MonitoredAppDao();
    private final ForegroundMonitorService monitorService = ForegroundMonitorService.getInstance();

    private final ObservableList<TodayRow> todayRows = FXCollections.observableArrayList();
    private final Map<Integer, Image> appIconCache = new LinkedHashMap<>();

    @FXML
    public void initialize() {
        tblToday.setItems(todayRows);
        tblToday.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // 今日表格：图标 + 名称
        colTodayApp.setCellValueFactory(new PropertyValueFactory<>("appName"));
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
                    Image icon = row.getIcon();
                    iconView.setImage(icon != null ? icon : IconUtil.getDefaultIcon());
                    nameLabel.setText(item);
                    setGraphic(box);
                }
            }
        });
        colTodayDuration.setCellValueFactory(new PropertyValueFactory<>("duration"));

        colTodayApp.prefWidthProperty().bind(tblToday.widthProperty().multiply(0.55));
        colTodayDuration.prefWidthProperty().bind(tblToday.widthProperty().multiply(0.43));

        // 刷新按钮
        btnRefresh.setOnAction(e -> refreshData());

        // 首次加载
        refreshData();
    }

    private void refreshData() {
        appIconCache.clear();
        refreshToday();
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

    private String getAppName(int appId) {
        for (MonitoredApp app : monitoredAppDao.findAll()) {
            if (app.getId() == appId) {
                return app.getAppName();
            }
        }
        return "应用 #" + appId;
    }

    private Image getAppIcon(int appId) {
        Image cached = appIconCache.get(appId);
        if (cached != null) return cached;

        Image icon = monitorService.getAppIcon(appId);
        if (icon != null) {
            appIconCache.put(appId, icon);
            return icon;
        }

        for (MonitoredApp app : monitoredAppDao.findAll()) {
            if (app.getId() == appId) {
                String foundPath = findProcessPath(app.getProcessName());
                if (foundPath != null) {
                    monitorService.cacheAppPath(appId, foundPath);
                    icon = monitorService.getAppIcon(appId);
                    if (icon != null) {
                        appIconCache.put(appId, icon);
                        return icon;
                    }
                }
                break;
            }
        }
        appIconCache.put(appId, IconUtil.getDefaultIcon());
        return IconUtil.getDefaultIcon();
    }

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
}
