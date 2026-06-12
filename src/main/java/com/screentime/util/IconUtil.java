/* ============================================================
 *  IconUtil.java — 应用图标提取工具
 *  从 Windows 可执行文件（.exe）中提取系统图标：
 *    - 使用 Swing FileSystemView.getSystemIcon()
 *    - 将 Swing Icon 转为 JavaFX Image
 *    - 带有 ConcurrentHashMap 缓存，避免重复提取
 *    - 找不到图标时返回 16x16 占位图
 * ============================================================ */
package com.screentime.util;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.swing.filechooser.FileSystemView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 Windows 可执行文件中提取图标的工具类。
 *
 * 缓存使用 LRU 策略且上限 200 项，防止内存无限增长（进程列表扫描可能引入大量路径）。
 */
public class IconUtil {

    private static final FileSystemView fileSystemView = FileSystemView.getFileSystemView();
    private static final int CACHE_MAX_SIZE = 200;
    private static final Map<String, Image> iconCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > CACHE_MAX_SIZE;
        }
    };

    private static final Image DEFAULT_ICON = createDefaultIcon();

    /**
     * 返回默认占位图标。
     */
    public static Image getDefaultIcon() {
        return DEFAULT_ICON;
    }

    private IconUtil() {
    }

    /**
     * 根据 exe 文件路径获取图标，带缓存。
     */
    public static Image getIcon(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return DEFAULT_ICON;
        }
        // 手动实现 computeIfAbsent + LRU 淘汰（配置了 access-order 的 LinkedHashMap）
        synchronized (iconCache) {
            Image cached = iconCache.get(filePath);
            if (cached != null) return cached;
        }
        Image extracted = extractIcon(filePath);
        synchronized (iconCache) {
            iconCache.put(filePath, extracted);
        }
        return extracted;
    }

    private static Image extractIcon(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return DEFAULT_ICON;
            }
            javax.swing.Icon swingIcon = fileSystemView.getSystemIcon(file);
            if (swingIcon == null) {
                return DEFAULT_ICON;
            }
            // 将 Swing Icon 转为 BufferedImage
            BufferedImage bufferedImage = new BufferedImage(
                    swingIcon.getIconWidth(),
                    swingIcon.getIconHeight(),
                    BufferedImage.TYPE_INT_ARGB
            );
            swingIcon.paintIcon(null, bufferedImage.getGraphics(), 0, 0);
            return SwingFXUtils.toFXImage(bufferedImage, null);
        } catch (Exception e) {
            return DEFAULT_ICON;
        }
    }

    /**
     * 创建一个 16x16 的绿色圆点占位图标（替代空白图）。
     */
    private static Image createDefaultIcon() {
        BufferedImage bi = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2d = bi.createGraphics();
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(new java.awt.Color(231, 76, 60));
        g2d.fillOval(5, 5, 6, 6);
        g2d.dispose();
        return SwingFXUtils.toFXImage(bi, null);
    }

    /**
     * 清空图标缓存。
     */
    public static void clearCache() {
        synchronized (iconCache) {
            iconCache.clear();
        }
    }
}
