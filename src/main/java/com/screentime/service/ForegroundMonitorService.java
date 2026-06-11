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

    // 每日使用上限（分钟），0 表示不限
    private volatile int dailyLimitMinutes = 0;
    // 是否启用每日上限提醒
    private volatile boolean limitEnabled = false;
    // 休息时长（分钟）
    private volatile int restDurationMinutes = 5;
    // 是否正在休息中
    private volatile boolean isResting = false;
    // 休息结束时间
    private volatile LocalDateTime restUntil = null;

    private volatile ScheduledFuture<?> pollingTask;
    private volatile ScheduledFuture<?> dailyFlushTask;
    private volatile ScheduledFuture<?> cleanupTask;
    private volatile ScheduledFuture<?> restEndTask;

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

        // 从设置读取每日使用上限
        try {
            dailyLimitMinutes = Integer.parseInt(settingsDao.get("daily_limit_minutes", "0"));
            dailyLimitMinutes = Math.max(0, dailyLimitMinutes);
        } catch (NumberFormatException e) {
            dailyLimitMinutes = 0;
        }
        try {
            restDurationMinutes = Integer.parseInt(settingsDao.get("rest_duration_minutes", "5"));
            restDurationMinutes = Math.max(1, restDurationMinutes);
        } catch (NumberFormatException e) {
            restDurationMinutes = 5;
        }
        limitEnabled = "true".equals(settingsDao.get("limit_enabled", "false"));

        monitoredAppDao.initTable();
        usageRecordDao.initTable();
        refreshCache();

        startPolling();

        long delayToMidnight = computeDelayToMidnight();
        dailyFlushTask = scheduler.scheduleWithFixedDelay(
                usageRecordDao::flushYesterday,
                delayToMidnight,
                24 * 60 * 60,
                TimeUnit.SECONDS
        );

        // 过期数据清理任务（每 5 分钟检查一次）
        cleanupTask = scheduler.scheduleWithFixedDelay(
                monitoredAppDao::cleanupExpiredNonPermanent,
                5,
                5 * 60,
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
        if (restEndTask != null) {
            restEndTask.cancel(false);
            restEndTask = null;
        }

        // 关闭当前会话（短时过滤）
        closeCurrentSession();

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        System.out.println("[ForegroundMonitorService] 监控引擎已停止");
    }

    // ---- 公共 API ----

    public Integer getCurrentActiveAppId() {
        return currentActiveAppId;
    }

    @SuppressWarnings("unused")
    public String getAppFullPath(int appId) {
        return appFullPathCache.get(appId);
    }

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

    // ---- 每日使用上限 / 休息机制 ----

    public int getDailyLimitMinutes() { return dailyLimitMinutes; }

    public void setDailyLimitMinutes(int minutes) {
        minutes = Math.max(0, minutes);
        if (this.dailyLimitMinutes == minutes) return;
        this.dailyLimitMinutes = minutes;
        settingsDao.set("daily_limit_minutes", String.valueOf(minutes));
        if (minutes == 0) {
            isResting = false;
            restUntil = null;
            cancelRestEndTask();
        }
    }

    public int getRestDurationMinutes() { return restDurationMinutes; }

    public void setRestDurationMinutes(int minutes) {
        minutes = Math.max(1, minutes);
        if (this.restDurationMinutes == minutes) return;
        this.restDurationMinutes = minutes;
        settingsDao.set("rest_duration_minutes", String.valueOf(minutes));
    }

    public boolean isResting() { return isResting; }
    public LocalDateTime getRestUntil() { return restUntil; }
    public void endRest() {
        isResting = false;
        restUntil = null;
        cancelRestEndTask();
    }

    public boolean isLimitEnabled() { return limitEnabled; }

    public void setLimitEnabled(boolean enabled) {
        if (this.limitEnabled == enabled) return;
        this.limitEnabled = enabled;
        settingsDao.set("limit_enabled", String.valueOf(enabled));
        if (!enabled) {
            isResting = false;
            restUntil = null;
            cancelRestEndTask();
        }
    }

    // ---- 核心轮询逻辑 ----

    private void poll() {
        if (!running) return;

        try {
            // 检查每日使用上限
            if (limitEnabled && dailyLimitMinutes > 0) {
                if (isResting) {
                    if (restUntil != null && LocalDateTime.now().isAfter(restUntil)) {
                        isResting = false;
                        restUntil = null;
                        cancelRestEndTask();
                    } else {
                        return; // 还在休息中，跳过
                    }
                } else {
                    int totalSec = usageRecordDao.getTodayTotalSeconds();
                    if (totalSec >= dailyLimitMinutes * 60) {
                        // 达到上限，关闭会话并进入休息模式
                        closeCurrentSession();
                        isResting = true;
                        restUntil = LocalDateTime.now().plusMinutes(restDurationMinutes);
                        // 在休息结束时安排任务，立即恢复监控
                        cancelRestEndTask();
                        restEndTask = scheduler.schedule(
                                () -> {
                                    if (running && isResting) {
                                        isResting = false;
                                        restUntil = null;
                                    }
                                },
                                restDurationMinutes,
                                TimeUnit.MINUTES
                        );
                        // 弹窗提醒
                        javafx.application.Platform.runLater(() -> {
                            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                                    javafx.scene.control.Alert.AlertType.WARNING,
                                    "今日已使用 " + dailyLimitMinutes + " 分钟，已达设置上限。\n\n请休息一下，"
                                    + restDurationMinutes + " 分钟后自动恢复记录。",
                                    javafx.scene.control.ButtonType.OK);
                            alert.setTitle("使用时间已达上限");
                            alert.setHeaderText("休息提醒");
                            alert.showAndWait();
                        });
                        return;
                    }
                }
            }

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
        LocalDateTime now = LocalDateTime.now();

        // 关闭上一个应用的会话（先判断是否太短）
        if (currentActiveAppId != null && currentSessionStart != null) {
            long elapsed = Duration.between(currentSessionStart, now).getSeconds();
            if (elapsed < minSessionSeconds) {
                // 停留太短，直接丢弃这条记录
                usageRecordDao.deleteOpenSession(currentActiveAppId);
            } else {
                usageRecordDao.endSession(currentActiveAppId, now);
            }
        }

        // 开启新会话
        usageRecordDao.startSession(app.getId(), now);
        currentActiveAppId = app.getId();
        currentSessionStart = now;
    }

    /**
     * 关闭当前会话。如果停留时间 < minSessionSeconds 则丢弃。
     */
    private void closeCurrentSession() {
        if (currentActiveAppId != null && currentSessionStart != null) {
            LocalDateTime now = LocalDateTime.now();
            long elapsed = Duration.between(currentSessionStart, now).getSeconds();
            if (elapsed < minSessionSeconds) {
                usageRecordDao.deleteOpenSession(currentActiveAppId);
            } else {
                try {
                    usageRecordDao.endSession(currentActiveAppId, now);
                } catch (Exception e) {
                    System.err.println("[ForegroundMonitorService] closeCurrentSession 失败: " + e.getMessage());
                }
            }
            currentActiveAppId = null;
            currentSessionStart = null;
        }
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

    private void cancelRestEndTask() {
        if (restEndTask != null) {
            restEndTask.cancel(false);
            restEndTask = null;
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
