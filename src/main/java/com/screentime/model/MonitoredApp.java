/* ============================================================
 *  MonitoredApp.java — 被监控应用模型
 *  表示用户添加的要监控的软件：
 *    - appName:    应用显示名称（如 "Google Chrome"）
 *    - processName: 进程名（如 "chrome.exe"）
 *    - createdAt:  添加时间
 *    - isPermanent: 是否永久保留数据（最多 3 个）
 *    - permanentCancelledAt: 取消永久显示的时间（用于 30 分钟倒计时后清理）
 * ============================================================ */
package com.screentime.model;

import java.time.LocalDateTime;

public class MonitoredApp {

    private Integer id;
    private String appName;
    private String processName;
    private LocalDateTime createdAt;
    private boolean isPermanent;
    private LocalDateTime permanentCancelledAt;

    public MonitoredApp() {
    }

    public MonitoredApp(String appName, String processName) {
        this.appName = appName;
        this.processName = processName;
    }

    public MonitoredApp(Integer id, String appName, String processName, LocalDateTime createdAt) {
        this.id = id;
        this.appName = appName;
        this.processName = processName;
        this.createdAt = createdAt;
    }

    public MonitoredApp(Integer id, String appName, String processName, LocalDateTime createdAt,
                        boolean isPermanent, LocalDateTime permanentCancelledAt) {
        this.id = id;
        this.appName = appName;
        this.processName = processName;
        this.createdAt = createdAt;
        this.isPermanent = isPermanent;
        this.permanentCancelledAt = permanentCancelledAt;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getProcessName() { return processName; }
    public void setProcessName(String processName) { this.processName = processName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public boolean isPermanent() { return isPermanent; }
    public void setPermanent(boolean permanent) { isPermanent = permanent; }
    public LocalDateTime getPermanentCancelledAt() { return permanentCancelledAt; }
    public void setPermanentCancelledAt(LocalDateTime v) { this.permanentCancelledAt = v; }

    @Override
    public String toString() {
        return appName + " (" + processName + ")" + (isPermanent ? " ★" : "");
    }
}
