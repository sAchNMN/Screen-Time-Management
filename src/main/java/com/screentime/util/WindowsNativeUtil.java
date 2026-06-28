/* ============================================================
 *  WindowsNativeUtil.java — Windows 原生 API 工具
 *  通过 JNA 调用 user32.dll 获取当前前台活动窗口的进程名：
 *    - GetForegroundWindow():  获取前台窗口句柄
 *    - GetWindowThreadProcessId(): 从窗口句柄获取 PID
 *    - ProcessHandle.of(pid):  纯 Java 提取进程可执行文件名
 *  用于后台监控引擎判断用户当前正在使用哪个软件
 * ============================================================ */
package com.screentime.util;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.Optional;

public class WindowsNativeUtil {

    private WindowsNativeUtil() {
    }

    /**
     * 前台进程信息：进程名和完整路径。
     */
    public record ForegroundProcessInfo(String processName, String fullPath) {
    }

    /**
     * 获取当前前台活动窗口所属进程的可执行文件名和完整路径。
     * 例如用户正在用 Chrome，返回 processName="chrome.exe", fullPath="C:\...\chrome.exe"
     *
     * @return ForegroundProcessInfo；如果无法获取则返回 Optional.empty()
     */
    public static Optional<ForegroundProcessInfo> getForegroundProcessInfo() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) {
                return Optional.empty();
            }

            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();
            if (pid == 0) {
                return Optional.empty();
            }

            Optional<String> nativePath = queryProcessImagePath(pid);
            if (nativePath.isPresent()) {
                String fullPath = nativePath.get();
                return Optional.of(new ForegroundProcessInfo(extractFileName(fullPath), fullPath));
            }

            return ProcessHandle.of(pid)
                    .flatMap(ph -> ph.info().command())
                    .map(fullPath -> new ForegroundProcessInfo(extractFileName(fullPath), fullPath));
        } catch (Exception e) {
            System.err.println("[WindowsNativeUtil] 获取前台窗口失败: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<Long> getIdleSeconds() {
        try {
            WinUser.LASTINPUTINFO info = new WinUser.LASTINPUTINFO();
            if (!User32.INSTANCE.GetLastInputInfo(info)) {
                return Optional.empty();
            }
            long now = Integer.toUnsignedLong(Kernel32.INSTANCE.GetTickCount());
            long lastInput = Integer.toUnsignedLong(info.dwTime);
            long idleMillis = now >= lastInput ? now - lastInput : 0;
            return Optional.of(idleMillis / 1000);
        } catch (Exception e) {
            System.err.println("[WindowsNativeUtil] failed to query idle time: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> queryProcessImagePath(int pid) {
        WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_LIMITED_INFORMATION,
                false,
                pid
        );
        if (process == null) {
            return Optional.empty();
        }

        try {
            char[] buffer = new char[WinDef.MAX_PATH * 4];
            IntByReference size = new IntByReference(buffer.length);
            boolean ok = Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, buffer, size);
            if (!ok || size.getValue() <= 0) {
                return Optional.empty();
            }
            return Optional.of(Native.toString(buffer));
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    /**
     * 从完整路径中提取文件名（最后一个 \ 或 / 之后的部分）。
     * "C:\Program Files\Google\Chrome\Application\chrome.exe" → "chrome.exe"
     */
    private static String extractFileName(String fullPath) {
        int lastSep = Math.max(fullPath.lastIndexOf('\\'), fullPath.lastIndexOf('/'));
        if (lastSep >= 0) {
            return fullPath.substring(lastSep + 1);
        }
        return fullPath;
    }
}
