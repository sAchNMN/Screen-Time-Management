/* ============================================================
 *  TimeUtil.java — 时间格式化工具
 *  提供时长格式化等与时间相关的通用方法
 * ============================================================ */
package com.screentime.util;

public class TimeUtil {

    private TimeUtil() {
    }

    /**
     * 将秒数格式化为中文时长字符串。
     * < 60 秒 → "X 秒"，
     * ≥ 60 秒 → "X 小时 Y 分钟" 或 "Y 分钟"。
     */
    public static String formatDuration(int totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + " 秒";
        }
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        return minutes + " 分钟";
    }
}
