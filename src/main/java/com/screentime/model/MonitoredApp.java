/* ============================================================
 *  MonitoredApp.java — 被监控应用模型
 *  表示用户添加的要监控的软件：
 *    - appName:    应用显示名称（如 "Google Chrome"）
 *    - processName: 进程名（如 "chrome.exe"）
 *    - createdAt:  添加时间
 * ============================================================ */
package com.screentime.model;

import java.time.LocalDateTime;

public class MonitoredApp {

    private Integer id;
    private String appName;
    private String processName;
    private LocalDateTime createdAt;

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

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getProcessName() {
        return processName;
    }

    public void setProcessName(String processName) {
        this.processName = processName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return appName + " (" + processName + ")";
    }
}
