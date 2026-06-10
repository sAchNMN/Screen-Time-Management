/* ============================================================
 *  App.java — 应用主入口
 *  职责：
 *    - 启动 JavaFX 窗口，加载主界面 FXML
 *    - 初始化系统托盘（最小化到托盘 / 托盘右键菜单）
 *    - 读取/保存窗口大小（settings 表持久化）
 *    - 管理"关闭到托盘"行为
 * ============================================================ */
package com.screentime;

import com.screentime.controller.MainController;
import com.screentime.dao.AppSettingsDao;
import com.screentime.service.ForegroundMonitorService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;

public class App extends Application {

    private static volatile boolean closeToTray = true;
    private static final AppSettingsDao settingsDao = new AppSettingsDao();
    private TrayIcon trayIcon;
    private boolean traySupported = true;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 初始化设置表
        settingsDao.initTable();
        closeToTray = !"false".equals(settingsDao.get("close_to_tray", "true"));

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Scene scene = new Scene(loader.load());
        primaryStage.setScene(scene);
        primaryStage.setTitle("屏幕时间管理");
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(650);

        // 恢复上次关闭时的窗口大小，首次启动使用默认值 900x650
        double savedWidth = getSavedSize("window_width", 900);
        double savedHeight = getSavedSize("window_height", 650);
        primaryStage.setWidth(savedWidth);
        primaryStage.setHeight(savedHeight);

        // 设置窗口图标
        try (InputStream is = getClass().getResourceAsStream("/icon.png")) {
            if (is != null) {
                primaryStage.getIcons().add(new javafx.scene.image.Image(is));
            }
        }

        MainController controller = loader.getController();
        controller.setStage(primaryStage);

        // 系统托盘
        setupTray(primaryStage);

        // 关闭窗口时的处理
        primaryStage.setOnCloseRequest(event -> {
            // 保存当前窗口大小
            saveWindowSize(primaryStage);
            if (closeToTray && traySupported) {
                event.consume();
                primaryStage.hide();
            } else {
                ForegroundMonitorService.getInstance().stop();
                Platform.exit();
                System.exit(0);
            }
        });

        // 防止窗口隐藏后 JFX 自动退出
        Platform.setImplicitExit(false);

        // 启动后台监控引擎
        ForegroundMonitorService.getInstance().start();

        primaryStage.show();
    }

    private double getSavedSize(String key, double defaultVal) {
        try {
            String val = settingsDao.get(key, null);
            if (val != null) {
                return Double.parseDouble(val);
            }
        } catch (NumberFormatException ignored) {
        }
        return defaultVal;
    }

    private void saveWindowSize(Stage stage) {
        settingsDao.set("window_width", String.valueOf((int) stage.getWidth()));
        settingsDao.set("window_height", String.valueOf((int) stage.getHeight()));
    }

    private void setupTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            traySupported = false;
            return;
        }

        try {
            // 从资源加载托盘图标
            BufferedImage image;
            try (InputStream is = getClass().getResourceAsStream("/icon.png")) {
                if (is != null) {
                    image = ImageIO.read(is);
                } else {
                    image = createFallbackIcon();
                }
            }

            // 右键弹出菜单
            PopupMenu popup = new PopupMenu();
            Font trayFont = new Font("Microsoft YaHei", Font.PLAIN, 12);
            if (!"Microsoft YaHei".equals(trayFont.getFamily())) {
                trayFont = new Font("SimSun", Font.PLAIN, 12);
            }
            popup.setFont(trayFont);

            MenuItem showItem = new MenuItem("屏幕时间管理");
            showItem.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));

            MenuItem exitItem = new MenuItem("关闭软件");
            exitItem.addActionListener(e -> {
                ForegroundMonitorService.getInstance().stop();
                Platform.exit();
                SystemTray.getSystemTray().remove(trayIcon);
                System.exit(0);
            });

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, "屏幕时间管理", popup);
            trayIcon.setImageAutoSize(true);

            // 双击托盘图标显示窗口
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));

            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            traySupported = false;
            System.err.println("无法创建系统托盘图标: " + e.getMessage());
        }
    }

    /**
     * 后备图标（图片加载失败时使用）
     */
    private BufferedImage createFallbackIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(new Color(52, 152, 219));
        g2d.fillOval(2, 2, 12, 12);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 9));
        g2d.drawString("T", 5, 12);
        g2d.dispose();
        return image;
    }

    @SuppressWarnings("unused")
    public static boolean isCloseToTray() {
        return closeToTray;
    }

    public static void setCloseToTray(boolean value) {
        closeToTray = value;
    }

    @SuppressWarnings("unused")
    public static void main(String[] args) {
        launch(args);
    }
}
