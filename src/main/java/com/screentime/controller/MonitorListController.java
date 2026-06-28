/* ============================================================
 *  MonitorListController.java — 监控软件管理页面控制器
 *  管理"添加/移除被监控应用"的核心交互：
 *    - 枚举系统所有运行中的进程（后台线程）
 *    - 为每个进程异步加载图标
 *    - 支持多选添加进程到监控列表
 *    - 支持从监控列表中移除选中项
 *    - 每个应用可设置"永久显示"（最多 3 个），保留 31 天前数据
 * ============================================================ */
package com.screentime.controller;

import com.screentime.dao.MonitoredAppDao;
import com.screentime.model.MonitoredApp;
import com.screentime.model.ProcessInfo;
import com.screentime.util.DatabaseUtil;
import com.screentime.util.IconUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MonitorListController implements AutoCloseable {

    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "monitor-list-worker");
        t.setDaemon(true);
        return t;
    });

    @FXML
    private ListView<ProcessInfo> lvProcessList;
    @FXML
    private ListView<MonitoredApp> lvMonitoredApps;
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
    private final ObservableList<MonitoredApp> monitoredItems = FXCollections.observableArrayList();
    private final Set<String> loadingIconPaths = ConcurrentHashMap.newKeySet();
    private final Set<String> failedIconPaths = ConcurrentHashMap.newKeySet();
    private final AtomicInteger refreshGeneration = new AtomicInteger();
    private volatile boolean closed = false;

    @FXML
    public void initialize() {
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
                if (empty || item == null) { setText(null); setGraphic(null); return; }
                setText(item.getName());
                if (item.getIcon() != null) {
                    imageView.setImage(item.getIcon());
                } else {
                    imageView.setImage(null);
                    loadIconAsync(item);
                }
                setGraphic(imageView);
            }
        });

        // 已监控列表自定义单元格：应用名 + "永久显示" 复选框
        lvMonitoredApps.setCellFactory(list -> new ListCell<>() {
            private final CheckBox cbPermanent = new CheckBox("永久显示");
            private final Label nameLabel = new Label();
            private final HBox box = new HBox(10, nameLabel, cbPermanent);
            {
                cbPermanent.setStyle("-fx-font-size: 11px; -fx-text-fill: #e67e22;");
                nameLabel.setStyle("-fx-font-size: 13px;");
                nameLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(nameLabel, javafx.scene.layout.Priority.ALWAYS);
                cbPermanent.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            }
            @Override
            protected void updateItem(MonitoredApp app, boolean empty) {
                super.updateItem(app, empty);
                cbPermanent.setOnAction(null);
                if (empty || app == null) {
                    nameLabel.setText(null);
                    cbPermanent.setSelected(false);
                    setGraphic(null);
                    return;
                }
                nameLabel.setText(app.getAppName() + " (" + app.getProcessName() + ")");

                cbPermanent.setSelected(app.isPermanent());
                cbPermanent.setOnAction(event -> {
                    boolean selected = cbPermanent.isSelected();
                    if (selected && dao.countPermanent() >= 3) {
                        // 检查当前应用是否已经是永久（这样取消再勾选不算新增）
                        if (!app.isPermanent()) {
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.WARNING,
                                        "永久显示最多只能设置 3 个应用", ButtonType.OK);
                                alert.showAndWait();
                            });
                            cbPermanent.setSelected(false);
                            return;
                        }
                    }
                    dao.setPermanent(app.getId(), selected);
                    app.setPermanent(selected);
                    app.setPermanentCancelledAt(selected ? null : java.time.LocalDateTime.now());
                });
                setGraphic(box);
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
        String path = item.getFullPath();
        if (closed || path == null || path.isBlank() || failedIconPaths.contains(path) || !loadingIconPaths.add(path)) {
            return;
        }
        BACKGROUND_EXECUTOR.execute(() -> {
            try {
                var icon = IconUtil.getIcon(path);
                Platform.runLater(() -> {
                    if (closed) return;
                    if (icon == null) {
                        failedIconPaths.add(path);
                        return;
                    }
                    item.setIcon(icon);
                    int index = processItems.indexOf(item);
                    if (index >= 0) lvProcessList.refresh();
                });
            } catch (Exception e) {
                failedIconPaths.add(path);
            } finally {
                loadingIconPaths.remove(path);
            }
        });
    }

    private void refreshProcesses() {
        if (closed) return;
        int generation = refreshGeneration.incrementAndGet();
        setStatus("正在刷新进程列表...");
        btnRefreshProcesses.setDisable(true);
        processItems.clear();
        IconUtil.clearCache();

        BACKGROUND_EXECUTOR.execute(() -> {
            List<ProcessInfo> processes = ProcessHandle.allProcesses()
                    .map(ph -> {
                        String fullPath = ph.info().command().orElse("");
                        String name = fullPath;
                        int lastSep = Math.max(fullPath.lastIndexOf('\\'), fullPath.lastIndexOf('/'));
                        if (lastSep >= 0) name = fullPath.substring(lastSep + 1);
                        return new ProcessInfo(name, fullPath);
                    })
                    .filter(p -> !p.getName().isBlank())
                    .distinct()
                    .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()))
                    .toList();

            Platform.runLater(() -> {
                if (closed || generation != refreshGeneration.get()) return;
                processItems.addAll(processes);
                btnRefreshProcesses.setDisable(false);
                setStatus("进程列表已刷新，共 " + processes.size() + " 个进程");
            });
        });
    }

    private void refreshMonitoredList() {
        monitoredItems.clear();
        monitoredItems.addAll(dao.findAll());
        if (DatabaseUtil.consumeDatabaseRecreatedFlag()) {
            setStatus("数据库文件已被重建，原有监控列表为空，请重新添加应用");
        }
    }

    private void addSelectedToMonitor() {
        var selected = lvProcessList.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) {
            showAlert("请先在进程列表中选中要监控的软件");
            return;
        }

        int addedCount = 0;
        for (ProcessInfo info : selected) {
            String processName = info.getName();
            if (dao.existsByProcessName(processName)) continue;
            String appName = processName;
            if (appName.toLowerCase().endsWith(".exe")) appName = appName.substring(0, appName.length() - 4);
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
        MonitoredApp selected = lvMonitoredApps.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("请先在监控列表中选中要移除的软件");
            return;
        }
        dao.delete(selected.getId());
        refreshMonitoredList();
        setStatus("已移除: " + selected.getAppName());
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

    @Override
    public void close() {
        closed = true;
        refreshGeneration.incrementAndGet();
        loadingIconPaths.clear();
    }
}
