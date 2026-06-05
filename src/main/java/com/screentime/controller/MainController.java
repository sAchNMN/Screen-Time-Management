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
    private Button btnSettings;

    private static Stage stage;

    private final Map<String, Button> navButtons = new HashMap<>();
    private static final String NAV_MONITOR = "monitor";
    private static final String NAV_STATISTICS = "statistics";
    private static final String NAV_SETTINGS = "settings";

    @FXML
    public void initialize() {
        navButtons.put(NAV_MONITOR, btnMonitorList);
        navButtons.put(NAV_STATISTICS, btnStatistics);
        navButtons.put(NAV_SETTINGS, btnSettings);

        btnMonitorList.setOnAction(e -> navigateTo(NAV_MONITOR));
        btnStatistics.setOnAction(e -> navigateTo(NAV_STATISTICS));
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
            case NAV_SETTINGS -> "/fxml/settings.fxml";
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
