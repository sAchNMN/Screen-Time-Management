/* ============================================================
 *  ForegroundMonitorService.java — 前台窗口监控引擎
 *  后台定时检测用户当前活动窗口是否属于被监控应用：
 *    - 通过 WindowsNativeUtil 获取前台窗口进程名和完整路径
 *    - 与内存中缓存的监控列表对比（O(1) HashMap 查找）
 *    - 在焦点切换时写入 usage_records（开始/结束会话）
 *    - 短时停留自动丢弃（用户可自设阈值，默认 30 秒）
 *    - 每天凌晨自动将昨天数据聚合写入 daily_summary
 *
 *  性能设计：
 *    - 仅 1 个守护线程
 *    - 每次轮询仅 1 次 JNA 调用 + 1 次 ProcessHandle.of()
 *    - DB 写入仅在焦点切换时发生
 *    - 监控列表缓存于内存（Map O(1) 查找），每 30 秒刷新一次
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ForegroundMonitorService {

    private static final ForegroundMonitorService INSTANCE = new ForegroundMonitorService();

    private final UsageRecordDao usageRecordDao = new UsageRecordDao();
    private final MonitoredAppDao monitoredAppDao = new MonitoredAppDao();
    private final AppSettingsDao settingsDao = new AppSettingsDao();

    /* 调度器在 start() 中懒创建，stop() 后重建 */
    private ScheduledExecutorService scheduler;

    // 当前正在使用的被监控应用（null 表示前台不是被监控应用）
    private volatile Integer currentActiveAppId = null;
    private volatile LocalDateTime currentSessionStart = null;

    // 被监控应用内存缓存（小写进程名 → MonitoredApp，O(1) 查找）
    private volatile Map<String, MonitoredApp> cachedAppMap = Map.of();
    private volatile LocalDateTime lastCacheRefresh = LocalDateTime.MIN;

    // 应用完整路径缓存 (appId → fullPath)，供图标提取
    private final Map<Integer, String> appFullPathCache = new ConcurrentHashMap<>();

    // 可动态调整的轮询间隔（秒）
    private volatile int pollIntervalSeconds = 15;
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    // 短时运行过滤阈值（秒），停留小于此值的记录自动丢弃
    private volatile int minSessionSeconds = 30;

    private static final long SECONDS_PER_DAY = 24 * 60 * 60;
    private static final long CLEANUP_INTERVAL_SECONDS = 5 * 60;

    private volatile ScheduledFuture<?> pollingTask;
    private volatile ScheduledFuture<?> dailyFlushTask;
    private volatile ScheduledFuture<?> cleanupTask;

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

        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.out.println("[ForegroundMonitorService] 非 Windows 系统，监控引擎未启动");
            return;
        }

        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "foreground-monitor");
                t.setDaemon(true);
                return t;
            });
        }

        running = true;

        // 从设置读取轮询间隔
        try {
            pollIntervalSeconds = Integer.parseInt(settingsDao.get("poll_interval_seconds", "15"));
            pollIntervalSeconds = Math.max(1, Math.min(60, pollIntervalSeconds));
        } catch (NumberFormatException e) {
            pollIntervalSeconds = 15;
        }

        // 从设置读取短时运行过滤阈值
        try {
            minSessionSeconds = Integer.parseInt(settingsDao.get("min_session_seconds", "30"));
            minSessionSeconds = Math.max(1, minSessionSeconds);
        } catch (NumberFormatException e) {
            minSessionSeconds = 30;
        }

        monitoredAppDao.initTable();
        usageRecordDao.initTable();
        refreshCache();

        startPolling();

        long delayToMidnight = computeDelayToMidnight();
        dailyFlushTask = scheduler.scheduleWithFixedDelay(
                usageRecordDao::flushYesterday,
                delayToMidnight,
                SECONDS_PER_DAY,
                TimeUnit.SECONDS
        );

        // 过期数据清理任务（每 5 分钟检查一次）
        cleanupTask = scheduler.scheduleWithFixedDelay(
                monitoredAppDao::cleanupExpiredNonPermanent,
                5,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        System.out.println("[ForegroundMonitorService] 监控引擎已启动，轮询间隔 " + pollIntervalSeconds + " 秒");
    }

    /**
     * 停止后台监控引擎。
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }
        if (dailyFlushTask != null) {
            dailyFlushTask.cancel(false);
            dailyFlushTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }

        // 关闭当前会话（短时过滤）
        closeCurrentSession();

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        System.out.println("[ForegroundMonitorService] 监控引擎已停止");
    }

    // ---- 公共 API ----

    public Image getAppIcon(int appId) {
        String path = appFullPathCache.get(appId);
        if (path != null) {
            return IconUtil.getIcon(path);
        }
        return null;
    }

    public void cacheAppPath(int appId, String fullPath) {
        if (fullPath != null && !fullPath.isBlank()) {
            appFullPathCache.put(appId, fullPath);
        }
    }

    /**
     * 设置短时运行过滤阈值（秒）。
     */
    public synchronized void setMinSessionSeconds(int seconds) {
        seconds = Math.max(1, seconds);
        if (this.minSessionSeconds == seconds) return;
        this.minSessionSeconds = seconds;
        settingsDao.set("min_session_seconds", String.valueOf(seconds));
    }

    public int getMinSessionSeconds() {
        return minSessionSeconds;
    }

    // ---- 核心轮询逻辑 ----

    private void poll() {
        if (!running) return;

        try {
            if (Duration.between(lastCacheRefresh, LocalDateTime.now()).toSeconds() > CACHE_TTL.toSeconds()) {
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

            MonitoredApp matched = cachedAppMap.get(foregroundProc);

            if (matched != null) {
                cacheAppPath(matched.getId(), info.fullPath());

                if (currentActiveAppId == null || !currentActiveAppId.equals(matched.getId())) {
                    switchToApp(matched);
                }
            } else {
                closeCurrentSession();
            }
        } catch (Exception e) {
            System.err.println("[ForegroundMonitorService] 轮询异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 切换到新的被监控应用。
     * 如果上一会话停留时间 < minSessionSeconds，则直接删除该记录，不保留。
     */
    private void switchToApp(MonitoredApp app) {
        closeCurrentSession();

        LocalDateTime now = LocalDateTime.now();
        usageRecordDao.startSession(app.getId(), now);
        currentActiveAppId = app.getId();
        currentSessionStart = now;
    }

    /**
     * 关闭当前会话。如果停留时间 < minSessionSeconds 则丢弃。
     */
    private void closeCurrentSession() {
        if (currentActiveAppId == null || currentSessionStart == null) return;
        long elapsed = Duration.between(currentSessionStart, LocalDateTime.now()).getSeconds();
        if (elapsed < minSessionSeconds) {
            usageRecordDao.deleteOpenSession(currentActiveAppId);
        } else {
            try {
                usageRecordDao.endSession(currentActiveAppId, LocalDateTime.now());
            } catch (Exception e) {
                System.err.println("[ForegroundMonitorService] closeCurrentSession 失败: " + e.getMessage());
            }
        }
        currentActiveAppId = null;
        currentSessionStart = null;
    }

    // ---- 内部方法 ----

    private void startPolling() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        if (scheduler == null || scheduler.isShutdown()) return;
        pollingTask = scheduler.scheduleWithFixedDelay(
                this::poll,
                1,
                pollIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void refreshCache() {
        try {
            List<MonitoredApp> apps = monitoredAppDao.findAll();
            Map<String, MonitoredApp> map = new HashMap<>();
            for (MonitoredApp app : apps) {
                if (app.getProcessName() != null) {
                    map.put(app.getProcessName().toLowerCase(), app);
                }
            }
            cachedAppMap = map;
            lastCacheRefresh = LocalDateTime.now();

            if (!appFullPathCache.isEmpty()) {
                var currentIds = apps.stream().map(MonitoredApp::getId).collect(Collectors.toSet());
                appFullPathCache.keySet().retainAll(currentIds);
            }
        } catch (Exception e) {
            System.err.println("[ForegroundMonitorService] 刷新监控列表缓存失败: " + e.getMessage());
        }
    }

    private long computeDelayToMidnight() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime nextFlush = now.toLocalDate().plusDays(1)
                .atTime(0, 1)
                .atZone(zone);
        return Duration.between(now, nextFlush).getSeconds();
    }
}
