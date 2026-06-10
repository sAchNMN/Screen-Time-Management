/* ============================================================
 *  ProcessInfo.java — 运行时进程信息模型
 *  表示系统当前运行的一个进程的快照：
 *    - name:     进程名（如 "chrome.exe"）
 *    - fullPath: 可执行文件的完整路径
 *    - icon:     进程图标（延迟加载，异步获取）
 * ============================================================ */
package com.screentime.model;

import javafx.scene.image.Image;

/**
 * 进程信息，包含可执行文件名、完整路径、图标。
 */
public class ProcessInfo {

    private final String name;       // 进程名，如 chrome.exe
    private final String fullPath;   // 完整路径，如 C:\Program Files\Google\Chrome\chrome.exe
    private volatile Image icon;     // 图标，延迟加载

    public ProcessInfo(String name, String fullPath) {
        this.name = name;
        this.fullPath = fullPath;
    }

    public String getName() {
        return name;
    }

    public String getFullPath() {
        return fullPath;
    }

    public Image getIcon() {
        return icon;
    }

    public void setIcon(Image icon) {
        this.icon = icon;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProcessInfo that = (ProcessInfo) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
