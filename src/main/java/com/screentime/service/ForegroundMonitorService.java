/* ============================================================
 *  ForegroundMonitorService.java — 前台窗口监控引擎
 *  后台定时检测用户当前活动窗口是否属于被监控应用：
 *    - 通过 WindowsNativeUtil 获取前台窗口进程名和完整路径
 *    - 与内存中缓存的监控列表对比
 *    - 在焦点切换时写入 usage_records（开始/结束会话）
 *    - 每天凌晨自动将昨天数据聚合写入 daily_summary
 *    - 轮询间隔可由用户设置（1-60 秒，默认 15 秒）
 *
 *  性能设计：
 *    - 仅 1 个守护线程
 *    - 每次轮询仅 1 次 JNA 调用 + 1 次 ProcessHandle.of()
 *    - DB 写入仅在焦点切换时发生
 *    - 监控列表缓存于内存，每 30 秒刷新一次
 * ============================================================ */
package com.screentime.service;

import com.screentime.dao.AppSettingsDao;
import com.screentime.dao.MonitoredAppDao;
import com.screentime.dao.UsageRecordDao;
import com.screentime.model.MonitoredApp;
import com.screentime.util.IconUtil;
import com.screentime.util.WindowsNativeUtil;

import javafx.scene.image.Image;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ForegroundMonitorService {

    private static final ForegroundMonitorService INSTANCE = new ForegroundMonitorService();

    private final UsageRecordDao usageRecordDao = new UsageRecordDao();
    private final MonitoredAppDao monitoredAppDao = new MonitoredAppDao();
    private final AppSettingsDao settingsDao = new AppSettingsDao();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "foreground-monitor");
                t.setDaemon(true);
                return t;
            });

    // 当前正在使用的被监控应用（null 表示前台不是被监控应用）
    private volatile Integer currentActiveAppId = null;

    // 被监控应用内存缓存
    private volatile List<MonitoredApp> cachedApps = List.of();
    private volatile LocalDateTime lastCacheRefresh = LocalDateTime.MIN;

    // 应用完整路径缓存 (appId → fullPath)，供图标提取
    private final Map<Integer, String> appFullPathCache = new ConcurrentHashMap<>();

    // 可动态调整的轮询间隔（秒）
    private volatile int pollIntervalSeconds = 15;
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private volatile ScheduledFuture<?> pollingTask;

    private boolean running = false;

    private ForegroundMonitorService() {
    }

    public static ForegroundMonitorService getInstance() {
        return INSTANCE;
    }

    /**
     * 启动后台监控引擎。
     */
    public synchronized void start() {
        if (running) return;

        // 非 Windows 系统不支持前台窗口监控
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.out.println("[ForegroundMonitorService] 非 Windows 系统，监控引擎未启动");
            return;
        }

        running = true;

        // 从设置读取轮询间隔
        try {
            pollIntervalSeconds = Integer.parseInt(settingsDao.get("poll_interval_seconds", "15"));
            pollIntervalSeconds = Math.max(1, Math.min(60, pollIntervalSeconds));
        } catch (NumberFormatException e) {
            pollIntervalSeconds = 15;
        }

        monitoredAppDao.initTable();
        refreshCache();

        // 启动主轮询任务
        startPolling();

        // 每日汇总刷新任务（每天 00:01 执行）
        long delayToMidnight = computeDelayToMidnight();
        scheduler.scheduleAtFixedRate(
                usageRecordDao::flushYesterday,
                delayToMidnight,
                24 * 60 * 60,
                TimeUnit.SECONDS
        );

        System.out.println("[ForegroundMonitorService] 监控引擎已启动，轮询间隔 " + pollIntervalSeconds + " 秒");
    }

    /**
     * 停止后台监控引擎。
     */
    public synchronized void stop() {
        running = false;

        // 关闭当前会话
        if (currentActiveAppId != null) {
            usageRecordDao.endSession(currentActiveAppId, LocalDateTime.now());
            currentActiveAppId = null;
        }

        scheduler.shutdownNow();
        System.out.println("[ForegroundMonitorService] 监控引擎已停止");
    }

    // ---- 动态轮询间隔 ----

    /**
     * 设置轮询间隔（秒），会自动重启轮询任务。
     * 有效范围 1-60。
     */
    public synchronized void setPollIntervalSeconds(int seconds) {
        seconds = Math.max(1, Math.min(60, seconds));
        if (this.pollIntervalSeconds == seconds) return;
        this.pollIntervalSeconds = seconds;
        settingsDao.set("poll_interval_seconds", String.valueOf(seconds));

        if (running) {
            startPolling(); // 取消旧任务，按新间隔启动
        }
        System.out.println("[ForegroundMonitorService] 轮询间隔已更新为 " + seconds + " 秒");
    }

    @SuppressWarnings("unused")
    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    private void startPolling() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        pollingTask = scheduler.scheduleWithFixedDelay(
                this::poll,
                pollIntervalSeconds,
                pollIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    // ---- 公共查询方法（供 StatisticsController 使用） ----

    /**
     * 获取某个被监控应用的图标。
     */
    public Image getAppIcon(int appId) {
        String path = appFullPathCache.get(appId);
        if (path != null) {
            return IconUtil.getIcon(path);
        }
        return null;
    }

    /**
     * 缓存某个 appId 的完整路径。
     */
    public void cacheAppPath(int appId, String fullPath) {
        if (fullPath != null && !fullPath.isBlank()) {
            appFullPathCache.put(appId, fullPath);
        }
    }

    // ---- 核心轮询逻辑 ----

    private void poll() {
        try {
            // 定期刷新监控列表缓存
            if (Duration.between(lastCacheRefresh, LocalDateTime.now()).compareTo(CACHE_TTL) > 0) {
                refreshCache();
            }

            Optional<WindowsNativeUtil.ForegroundProcessInfo> infoOpt =
                    WindowsNativeUtil.getForegroundProcessInfo();
            if (infoOpt.isEmpty()) {
                closeCurrentSession();
                return;
            }

            WindowsNativeUtil.ForegroundProcessInfo info = infoOpt.get();
            String foregroundProc = info.processName().toLowerCase();
            MonitoredApp matched = findMatch(foregroundProc);

            if (matched != null) {
                // 缓存完整路径供图标提取
                cacheAppPath(matched.getId(), info.fullPath());

                // 前台窗口属于被监控应用
                if (currentActiveAppId == null || !currentActiveAppId.equals(matched.getId())) {
                    // 切换到新的被监控应用
                    switchToApp(matched);
                }
                // else: 同一个应用，不做任何事
            } else {
                // 前台窗口不属于任何被监控应用
                closeCurrentSession();
            }
        } catch (Exception e) {
            System.err.println("[ForegroundMonitorService] 轮询异常: " + e.getMessage());
        }
    }

    private void switchToApp(MonitoredApp app) {
        LocalDateTime now = LocalDateTime.now();

        // 关闭上一个应用的会话
        if (currentActiveAppId != null) {
            usageRecordDao.endSession(currentActiveAppId, now);
        }

        // 开启新会话
        usageRecordDao.startSession(app.getId(), now);
        currentActiveAppId = app.getId();
    }

    private void closeCurrentSession() {
        if (currentActiveAppId != null) {
            usageRecordDao.endSession(currentActiveAppId, LocalDateTime.now());
            currentActiveAppId = null;
        }
    }

    private void refreshCache() {
        try {
            cachedApps = monitoredAppDao.findAll();
            lastCacheRefresh = LocalDateTime.now();
        } catch (Exception e) {
            System.err.println("[ForegroundMonitorService] 刷新监控列表缓存失败: " + e.getMessage());
        }
    }

    private MonitoredApp findMatch(String foregroundProc) {
        for (MonitoredApp app : cachedApps) {
            if (app.getProcessName().toLowerCase().equals(foregroundProc)) {
                return app;
            }
        }
        return null;
    }

    private long computeDelayToMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atTime(LocalTime.of(0, 1));
        return Duration.between(now, nextMidnight).getSeconds();
    }
}
