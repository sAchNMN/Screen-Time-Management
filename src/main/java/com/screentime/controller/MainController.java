/* ============================================================
 *  MainController.java — 主界面框架控制器
 *  提供左侧导航栏 + 右侧内容区的单页应用（SPA）式路由：
 *    - 点击导航按钮切换内容面板
 *    - 动态加载对应的 FXML 到 contentArea
 *    - 高亮当前选中的导航按钮
 *  导航项：监控软件管理 / 使用统计 / 设置
 * ============================================================ */
package com.screentime.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.Map;

public class MainController {

    @FXML
    private BorderPane root;
    @FXML
    private VBox navBar;
    @FXML
    private StackPane contentArea;
    @FXML
    private Button btnMonitorList;
    @FXML
    private Button btnStatistics;
    @FXML
    private Button btnChart;
    @FXML
    private Button btnHistory;
    @FXML
    private Button btnSettings;
    @FXML
    private Button btnDebug;

    private static Stage stage;

    private final Map<String, Button> navButtons = new HashMap<>();
    private static final String NAV_MONITOR = "monitor";
    private static final String NAV_STATISTICS = "statistics";
    private static final String NAV_CHART = "chart";
    private static final String NAV_HISTORY = "history";
    private static final String NAV_SETTINGS = "settings";
    private static final String NAV_DEBUG = "debug";

    @FXML
    public void initialize() {
        navButtons.put(NAV_MONITOR, btnMonitorList);
        navButtons.put(NAV_STATISTICS, btnStatistics);
        navButtons.put(NAV_CHART, btnChart);
        navButtons.put(NAV_HISTORY, btnHistory);
        navButtons.put(NAV_SETTINGS, btnSettings);
        navButtons.put(NAV_DEBUG, btnDebug);

        btnMonitorList.setOnAction(e -> navigateTo(NAV_MONITOR));
        btnStatistics.setOnAction(e -> navigateTo(NAV_STATISTICS));
        btnChart.setOnAction(e -> navigateTo(NAV_CHART));
        btnHistory.setOnAction(e -> navigateTo(NAV_HISTORY));
        btnSettings.setOnAction(e -> navigateTo(NAV_SETTINGS));
        btnDebug.setOnAction(e -> navigateTo(NAV_DEBUG));

        navigateTo(NAV_MONITOR);
    }

    public void setStage(Stage stage) {
        MainController.stage = stage;
    }

    public static Stage getStage() {
        return stage;
    }

    private void navigateTo(String navKey) {
        for (Map.Entry<String, Button> entry : navButtons.entrySet()) {
            Button btn = entry.getValue();
            if (entry.getKey().equals(navKey)) {
                btn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-size: 14px;");
            } else {
                btn.setStyle("-fx-background-color: #34495e; -fx-text-fill: white; -fx-font-size: 14px;");
            }
        }

        String fxmlFile = switch (navKey) {
            case NAV_MONITOR -> "/fxml/monitor-list.fxml";
            case NAV_STATISTICS -> "/fxml/statistics.fxml";
            case NAV_CHART -> "/fxml/chart.fxml";
            case NAV_HISTORY -> "/fxml/history.fxml";
            case NAV_SETTINGS -> "/fxml/settings.fxml";
            case NAV_DEBUG -> "/fxml/debug.fxml";
            default -> "/fxml/monitor-list.fxml";
        };

        try {
            Node page = FXMLLoader.load(getClass().getResource(fxmlFile));
            contentArea.getChildren().setAll(page);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
