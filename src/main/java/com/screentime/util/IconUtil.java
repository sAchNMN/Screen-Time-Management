package com.screentime.util;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.swing.filechooser.FileSystemView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 Windows 可执行文件中提取图标的工具类。
 */
public class IconUtil {

    private static final FileSystemView fileSystemView = FileSystemView.getFileSystemView();
    private static final Map<String, Image> iconCache = new ConcurrentHashMap<>();

    private static final Image DEFAULT_ICON = createDefaultIcon();

    private IconUtil() {
    }

    /**
     * 根据 exe 文件路径获取图标，带缓存。
     */
    public static Image getIcon(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return DEFAULT_ICON;
        }
        return iconCache.computeIfAbsent(filePath, IconUtil::extractIcon);
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
     * 创建一个 16x16 的空白占位图标。
     */
    private static Image createDefaultIcon() {
        BufferedImage bi = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        return SwingFXUtils.toFXImage(bi, null);
    }

    /**
     * 清空图标缓存。
     */
    public static void clearCache() {
        iconCache.clear();
    }
}
