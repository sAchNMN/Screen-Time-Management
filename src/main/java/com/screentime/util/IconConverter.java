/* ============================================================
 *  IconConverter.java — PNG 转 ICO 工具
 *  将 icon.png 转换为 icon.ico，供 jpackage 打包 exe 使用
 * ============================================================ */
package com.screentime.util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

public class IconConverter {

    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args.length > 0 ? args[0] : "src/main/resources/icon.png");
        Path output = Paths.get(args.length > 1 ? args[1] : "target/icon.ico");

        BufferedImage image = ImageIO.read(input.toFile());
        if (image == null) {
            System.err.println("无法读取图标: " + input.toAbsolutePath());
            System.exit(1);
        }

        BufferedImage scaled = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        var g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, 32, 32, null);
        g.dispose();

        try (OutputStream os = Files.newOutputStream(output)) {
            writeLE(os, 0, 2); writeLE(os, 1, 2); writeLE(os, 1, 2);
            int dataSize = 32 * 32 * 4 + 40;
            writeLE(os, 32, 1); writeLE(os, 32, 1); writeLE(os, 0, 1); writeLE(os, 0, 1);
            writeLE(os, 1, 2); writeLE(os, 32, 2); writeLE(os, dataSize, 4); writeLE(os, 22, 4);
            writeLE(os, 40, 4); writeLE(os, 32, 4); writeLE(os, 64, 4); writeLE(os, 1, 2);
            writeLE(os, 32, 2); writeLE(os, 0, 4); writeLE(os, dataSize - 40, 4);
            writeLE(os, 0, 4); writeLE(os, 0, 4); writeLE(os, 0, 4); writeLE(os, 0, 4);
            for (int y = 31; y >= 0; y--) {
                for (int x = 0; x < 32; x++) {
                    int argb = scaled.getRGB(x, y);
                    os.write(argb & 0xFF); os.write((argb >> 8) & 0xFF);
                    os.write((argb >> 16) & 0xFF); os.write((argb >> 24) & 0xFF);
                }
            }
            for (int i = 0; i < 32; i++) os.write(0);
        }
        System.out.println("ICO 图标已生成: " + output.toAbsolutePath());
    }

    private static void writeLE(OutputStream os, int value, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) os.write((value >> (i * 8)) & 0xFF);
    }
}
