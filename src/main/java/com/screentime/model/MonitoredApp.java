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
