/* ============================================================
 *  MonitorListController.java — 监控软件管理页面控制器
 *  管理"添加/移除被监控应用"的核心交互：
 *    - 枚举系统所有运行中的进程（后台线程）
 *    - 为每个进程异步加载图标
 *    - 支持多选添加进程到监控列表
 *    - 支持从监控列表中移除选中项
 * ============================================================ */
package com.screentime.controller;

import com.screentime.dao.MonitoredAppDao;
import com.screentime.model.MonitoredApp;
import com.screentime.model.ProcessInfo;
import com.screentime.util.IconUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;

import java.util.List;

public class MonitorListController {

    @FXML
    private ListView<ProcessInfo> lvProcessList;
    @FXML
    private ListView<String> lvMonitoredApps;
    @FXML
    private Button btnRefreshProcesses;
    @FXML
    private Button btnAddSelected;
    @FXML
    private Button btnRemoveSelected;
    @FXML
    private Label lblStatus;
    @FXML
    private SplitPane splitPane;

    private final MonitoredAppDao dao = new MonitoredAppDao();
    private final ObservableList<ProcessInfo> processItems = FXCollections.observableArrayList();
    private final ObservableList<String> monitoredItems = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        // 初始化数据库表
        dao.initTable();

        // 绑定列表
        lvProcessList.setItems(processItems);
        lvMonitoredApps.setItems(monitoredItems);
        lvProcessList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // 自定义进程列表单元格：图标 + 进程名
        lvProcessList.setCellFactory(list -> new ListCell<>() {
            private final ImageView imageView = new ImageView();

            {
                imageView.setFitWidth(16);
                imageView.setFitHeight(16);
                imageView.setPreserveRatio(true);
            }

            @Override
            protected void updateItem(ProcessInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getName());
                    // 图标异步加载
                    if (item.getIcon() != null) {
                        imageView.setImage(item.getIcon());
                    } else {
                        imageView.setImage(null);
                        loadIconAsync(item);
                    }
                    setGraphic(imageView);
                }
            }
        });

        // 按钮事件
        btnRefreshProcesses.setOnAction(e -> refreshProcesses());
        btnAddSelected.setOnAction(e -> addSelectedToMonitor());
        btnRemoveSelected.setOnAction(e -> removeSelected());

        // 启动时加载数据
        refreshProcesses();
        refreshMonitoredList();
    }

    private void loadIconAsync(ProcessInfo item) {
        new Thread(() -> {
            var icon = IconUtil.getIcon(item.getFullPath());
            Platform.runLater(() -> {
                item.setIcon(icon);
                // 触发列表刷新该项
                int index = processItems.indexOf(item);
                if (index >= 0) {
                    lvProcessList.refresh();
                }
            });
        }).start();
    }

    private void refreshProcesses() {
        setStatus("正在刷新进程列表...");
        processItems.clear();
        IconUtil.clearCache();

        // 在后台线程获取进程列表
        new Thread(() -> {
            List<ProcessInfo> processes = ProcessHandle.allProcesses()
                    .map(ph -> {
                        String fullPath = ph.info().command().orElse("");
                        String name = fullPath;
                        int lastSep = Math.max(fullPath.lastIndexOf('\\'), fullPath.lastIndexOf('/'));
                        if (lastSep >= 0) {
                            name = fullPath.substring(lastSep + 1);
                        }
                        return new ProcessInfo(name, fullPath);
                    })
                    .filter(p -> !p.getName().isBlank())
                    .distinct()
                    .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()))
                    .toList();

            Platform.runLater(() -> {
                processItems.addAll(processes);
                setStatus("进程列表已刷新，共 " + processes.size() + " 个进程");
            });
        }).start();
    }

    private void refreshMonitoredList() {
        monitoredItems.clear();
        List<MonitoredApp> apps = dao.findAll();
        monitoredItems.addAll(apps.stream()
                .map(MonitoredApp::toString)
                .toList());
    }

    private void addSelectedToMonitor() {
        ObservableList<ProcessInfo> selected = lvProcessList.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) {
            showAlert("请先在进程列表中选中要监控的软件");
            return;
        }

        int addedCount = 0;
        for (ProcessInfo info : selected) {
            String processName = info.getName();
            if (dao.existsByProcessName(processName)) {
                continue;
            }
            String appName = processName;
            if (appName.toLowerCase().endsWith(".exe")) {
                appName = appName.substring(0, appName.length() - 4);
            }
            dao.insert(new MonitoredApp(appName, processName));
            addedCount++;
        }

        if (addedCount > 0) {
            refreshMonitoredList();
            setStatus("已添加 " + addedCount + " 个软件到监控列表");
        } else {
            setStatus("选中的软件已在监控列表中，无需重复添加");
        }
    }

    private void removeSelected() {
        String selected = lvMonitoredApps.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("请先在监控列表中选中要移除的软件");
            return;
        }

        List<MonitoredApp> apps = dao.findAll();
        for (MonitoredApp app : apps) {
            if (app.toString().equals(selected)) {
                dao.delete(app.getId());
                refreshMonitoredList();
                setStatus("已移除: " + selected);
                return;
            }
        }
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> lblStatus.setText(msg));
    }

    private void showAlert(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
            alert.showAndWait();
        });
    }
}
