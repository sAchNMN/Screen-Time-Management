package com.screentime;

import com.screentime.controller.MainController;
import com.screentime.dao.AppSettingsDao;
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
        primaryStage.setTitle("屏幕使用时间");
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
                Platform.exit();
                System.exit(0);
            }
        });

        // 防止窗口隐藏后 JFX 自动退出
        Platform.setImplicitExit(false);

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

            MenuItem showItem = new MenuItem("屏幕使用时间");
            showItem.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));

            MenuItem exitItem = new MenuItem("\u9000\u51fa");
            exitItem.addActionListener(e -> {
                Platform.exit();
                SystemTray.getSystemTray().remove(trayIcon);
                System.exit(0);
            });

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, "\u5c4f\u5e55\u65f6\u95f4\u7ba1\u7406", popup);
            trayIcon.setImageAutoSize(true);

            // 双击托盘图标显示窗口
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));

            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            traySupported = false;
            System.err.println("\u65e0\u6cd5\u521b\u5efa\u7cfb\u7edf\u6258\u76d8\u56fe\u6807: " + e.getMessage());
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

    public static boolean isCloseToTray() {
        return closeToTray;
    }

    public static void setCloseToTray(boolean value) {
        closeToTray = value;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
