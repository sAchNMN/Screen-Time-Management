/* ============================================================
 *  StartupUtil.java — 开机自启管理
 *  通过 Windows 注册表 HKCU\...\Run 实现开机自启
 * ============================================================ */
package com.screentime.util;

import java.io.*;
import java.nio.file.*;

public class StartupUtil {

    private static final String APP_NAME = "ScreenTimeManager";
    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), "ScreenTime");
    private static final Path LAUNCHER_PATH = DATA_DIR.resolve("launcher.bat");
    private static final String REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private StartupUtil() {}

    public static boolean isStartupEnabled() {
        try {
            Process p = Runtime.getRuntime().exec("reg query " + REG_KEY + " /v " + APP_NAME);
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.contains(APP_NAME);
        } catch (Exception e) { return false; }
    }

    public static void setStartupEnabled(boolean enabled) {
        try {
            if (enabled) {
                ensureLauncher();
                Runtime.getRuntime().exec("reg add " + REG_KEY + " /v " + APP_NAME + " /t REG_SZ /d \"" + LAUNCHER_PATH + "\" /f");
            } else {
                Runtime.getRuntime().exec("reg delete " + REG_KEY + " /v " + APP_NAME + " /f");
            }
        } catch (Exception e) {
            System.err.println("[StartupUtil] " + e.getMessage());
        }
    }

    private static void ensureLauncher() throws IOException {
        Files.createDirectories(DATA_DIR);
        String cp = System.getProperty("java.class.path", "");
        StringBuilder mp = new StringBuilder();
        for (String e : cp.split(";")) {
            String l = e.toLowerCase();
            if (l.contains("javafx") || l.contains("jna")) { if (mp.length() > 0) mp.append(";"); mp.append(e); }
        }
        String content = "@echo off\r\nstart /B javaw --module-path \"" + mp.toString() + "\""
                + " --add-modules=javafx.base,javafx.graphics,javafx.controls,javafx.fxml,javafx.swing"
                + " -cp \"" + cp + "\" com.screentime.Main\r\n";
        Files.writeString(LAUNCHER_PATH, content);
    }
}
