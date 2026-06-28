/* ============================================================
 *  MainController.java - main window router
 *  Loads page FXML into contentArea and updates navigation state.
 * ============================================================ */
package com.screentime.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    private static Stage stage;

    private final Map<String, Button> navButtons = new HashMap<>();
    private static final String NAV_MONITOR = "monitor";
    private static final String NAV_STATISTICS = "statistics";
    private static final String NAV_CHART = "chart";
    private static final String NAV_HISTORY = "history";
    private static final String NAV_SETTINGS = "settings";
    private AutoCloseable currentController;

    @FXML
    public void initialize() {
        navButtons.put(NAV_MONITOR, btnMonitorList);
        navButtons.put(NAV_STATISTICS, btnStatistics);
        navButtons.put(NAV_CHART, btnChart);
        navButtons.put(NAV_HISTORY, btnHistory);
        navButtons.put(NAV_SETTINGS, btnSettings);

        btnMonitorList.setOnAction(e -> navigateTo(NAV_MONITOR));
        btnStatistics.setOnAction(e -> navigateTo(NAV_STATISTICS));
        btnChart.setOnAction(e -> navigateTo(NAV_CHART));
        btnHistory.setOnAction(e -> navigateTo(NAV_HISTORY));
        btnSettings.setOnAction(e -> navigateTo(NAV_SETTINGS));

        navigateTo(NAV_MONITOR);
    }

    public void setStage(Stage stage) {
        MainController.stage = stage;
    }

    public static Stage getStage() {
        return stage;
    }

    private void navigateTo(String navKey) {
        String fxmlFile = switch (navKey) {
            case NAV_MONITOR -> "/fxml/monitor-list.fxml";
            case NAV_STATISTICS -> "/fxml/statistics.fxml";
            case NAV_CHART -> "/fxml/chart.fxml";
            case NAV_HISTORY -> "/fxml/history.fxml";
            case NAV_SETTINGS -> "/fxml/settings.fxml";
            default -> "/fxml/monitor-list.fxml";
        };

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlFile));
            Node page = loader.load();
            closeCurrentController();
            contentArea.getChildren().setAll(page);
            Object controller = loader.getController();
            currentController = controller instanceof AutoCloseable closeable ? closeable : null;
            updateNavSelection(navKey);
        } catch (Exception e) {
            System.err.println("[MainController] page load failed " + fxmlFile + ": " + e.getMessage());
            Label error = new Label("Page load failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            error.setWrapText(true);
            error.setStyle("-fx-padding: 20; -fx-text-fill: #c0392b; -fx-font-size: 13px;");
            contentArea.getChildren().setAll(error);
        }
    }

    private void updateNavSelection(String navKey) {
        for (Map.Entry<String, Button> entry : navButtons.entrySet()) {
            Button btn = entry.getValue();
            if (entry.getKey().equals(navKey)) {
                btn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-size: 14px;");
            } else {
                btn.setStyle("-fx-background-color: #34495e; -fx-text-fill: white; -fx-font-size: 14px;");
            }
        }
    }

    private void closeCurrentController() {
        if (currentController == null) return;
        try {
            currentController.close();
        } catch (Exception e) {
            System.err.println("[MainController] failed to close page controller: " + e.getMessage());
        } finally {
            currentController = null;
        }
    }
}
