/* ============================================================
 *  App.java - JavaFX application entry point
 *  Initializes the main window, foreground monitor, and AWT tray.
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

    private static final String APP_TITLE = "屏幕时间管理";
    private static final String TRAY_TITLE = "Screen Time Management";
    private static volatile boolean closeToTray = true;
    private static final AppSettingsDao settingsDao = new AppSettingsDao();
    private TrayIcon trayIcon;
    private boolean traySupported = true;

    @Override
    public void start(Stage primaryStage) throws Exception {
        settingsDao.initTable();
        closeToTray = !"false".equals(settingsDao.get("close_to_tray", "true"));

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Scene scene = new Scene(loader.load());
        primaryStage.setScene(scene);
        primaryStage.setTitle(APP_TITLE);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(650);

        double savedWidth = getSavedSize("window_width", 900);
        double savedHeight = getSavedSize("window_height", 650);
        primaryStage.setWidth(savedWidth);
        primaryStage.setHeight(savedHeight);

        try (InputStream is = getClass().getResourceAsStream("/icon.png")) {
            if (is != null) {
                primaryStage.getIcons().add(new javafx.scene.image.Image(is));
            }
        }

        MainController controller = loader.getController();
        controller.setStage(primaryStage);

        setupTray(primaryStage);

        primaryStage.setOnCloseRequest(event -> {
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

        Platform.setImplicitExit(false);
        ForegroundMonitorService.getInstance().start();

        primaryStage.show();

        if (traySupported && "true".equals(settingsDao.get("start_minimized", "false"))) {
            primaryStage.hide();
        }
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
            BufferedImage image;
            try (InputStream is = getClass().getResourceAsStream("/icon.png")) {
                image = is != null ? ImageIO.read(is) : createFallbackIcon();
            }

            PopupMenu popup = new PopupMenu();
            Font trayFont = new Font("Dialog", Font.PLAIN, 12);
            popup.setFont(trayFont);

            MenuItem showItem = new MenuItem(TRAY_TITLE);
            showItem.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                ForegroundMonitorService.getInstance().stop();
                Platform.exit();
                SystemTray.getSystemTray().remove(trayIcon);
                System.exit(0);
            });

            popup.add(showItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, TRAY_TITLE, popup);
            trayIcon.setImageAutoSize(true);

            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));

            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            traySupported = false;
            System.err.println("Failed to create system tray icon: " + e.getMessage());
        }
    }

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
