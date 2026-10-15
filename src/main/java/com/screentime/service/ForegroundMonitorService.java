/* ============================================================
 * ForegroundMonitorService.java - 前台窗口监控服务
 * 轮询 Windows 前台进程，并仅针对受监控的应用程序记录其聚焦使用时长。
 * 数据规则：
 *   - 当受监控应用切换至前台时，开启一个会话；
 *   - 若同一应用持续保持前台状态，则定期更新心跳记录；
 *   - 当发生应用切换、空闲超时、轮询停滞、
 *   - 系统时钟回拨或监控服务关闭时，结束当前会话；
 *   - 将已完成的会话按午夜时间点进行拆分，以便生成每日统计摘要。
 * 运行时设计：采用单个守护调度线程，缓存受监控应用的查询结果，
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

    /* Created in start(); rebuilt after stop(). */
    private ScheduledExecutorService scheduler;

    // Currently focused monitored app; null when foreground is not monitored.
    private volatile Integer currentActiveAppId = null;
    private volatile LocalDateTime currentSessionStart = null;

    // Lower-case process name -> monitored app for O(1) foreground matching.
    private volatile Map<String, MonitoredApp> cachedAppMap = Map.of();
    private volatile LocalDateTime lastCacheRefresh = LocalDateTime.MIN;

    // Full executable paths used for icon extraction.
    private final Map<Integer, String> appFullPathCache = new ConcurrentHashMap<>();

    private volatile int pollIntervalSeconds = 15;
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final long MIN_STALE_POLL_GAP_SECONDS = 120;
    private volatile LocalDateTime lastPollAt = null;
    private volatile long lastPollNano = 0L;

    // Kept for settings compatibility; capture stores real short sessions.
    private volatile int minSessionSeconds = 30;
    private volatile int idleTimeoutSeconds = 300;

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
     * 启动后台监控器。
     *
     * 主要步骤：
     * 1. 读取轮询间隔、最短会话、空闲超时等设置。
     * 2. 初始化数据库表并恢复上次异常退出留下的未关闭会话。
     * 3. 定时轮询前台窗口，同时安排每日汇总和过期数据清理任务。
     */
    public synchronized void start() {
        if (running) return;

        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.out.println("[ForegroundMonitorService] monitor is disabled on non-Windows systems");
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

        // Poll interval setting.
        try {
            pollIntervalSeconds = Integer.parseInt(settingsDao.get("poll_interval_seconds", "15"));
            pollIntervalSeconds = Math.max(1, Math.min(60, pollIntervalSeconds));
        } catch (NumberFormatException e) {
            pollIntervalSeconds = 15;
        }

        // Minimum session setting retained for UI compatibility.
        try {
            minSessionSeconds = Integer.parseInt(settingsDao.get("min_session_seconds", "30"));
            minSessionSeconds = Math.max(1, minSessionSeconds);
        } catch (NumberFormatException e) {
            minSessionSeconds = 30;
        }

        try {
            idleTimeoutSeconds = Integer.parseInt(settingsDao.get("idle_timeout_seconds", "300"));
            idleTimeoutSeconds = Math.max(30, idleTimeoutSeconds);
        } catch (NumberFormatException e) {
            idleTimeoutSeconds = 300;
        }

        monitoredAppDao.initTable();
        usageRecordDao.initTable();
        int recoveredOpenSessions = usageRecordDao.recoverOpenSessions(0);
        if (recoveredOpenSessions > 0) {
            System.out.println("[ForegroundMonitorService] recovered " + recoveredOpenSessions + " open usage sessions");
        }
        refreshCache();

        startPolling();

        long delayToMidnight = computeDelayToMidnight();
        dailyFlushTask = scheduler.scheduleWithFixedDelay(
                usageRecordDao::flushYesterday,
                delayToMidnight,
                SECONDS_PER_DAY,
                TimeUnit.SECONDS
        );

        // Expired app cleanup task.
        cleanupTask = scheduler.scheduleWithFixedDelay(
                monitoredAppDao::cleanupExpiredNonPermanent,
                5,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        System.out.println("[ForegroundMonitorService] monitor started, poll interval " + pollIntervalSeconds + " seconds");
    }

    /**
     * 停止后台监控器。
     *
     * 停止前必须关闭当前会话，否则最后一段使用时间会一直处于 end_time=NULL 状态。
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

        // Close the current session before shutting down.
        closeCurrentSession(LocalDateTime.now());
        lastPollAt = null;
        lastPollNano = 0L;

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        System.out.println("[ForegroundMonitorService] monitor stopped");
    }

    // ---- Public API ----

    /**
     * 供统计页面显示图标使用。
     * 监控线程在轮询前台窗口时会缓存 appId -> exe 路径，这里再从路径提取图标。
     */
    public Image getAppIcon(int appId) {
        String path = appFullPathCache.get(appId);
        if (path != null) {
            return IconUtil.getIcon(path);
        }
        return null;
    }

    /**
     * 缓存应用的完整可执行文件路径。
     * 数据库只保存进程名，完整路径来自 Windows 当前运行进程信息。
     */
    public void cacheAppPath(int appId, String fullPath) {
        if (fullPath != null && !fullPath.isBlank()) {
            appFullPathCache.put(appId, fullPath);
        }
    }

    /**
     * Updates the minimum session setting.
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

    // ---- Polling logic ----

    private void poll() {
        if (!running) return;

        try {
            // 轮询是监控状态机的核心：拿到当前前台进程，再决定“开始、延续、结束”哪个动作。
            LocalDateTime now = LocalDateTime.now();
            long nanoNow = System.nanoTime();
            if (clockMovedBackwards(now)) {
                closeCurrentSession(lastPollAt);
                lastPollAt = now;
                lastPollNano = nanoNow;
                return;
            }
            closeSessionIfPollingStalled(now, nanoNow);
            lastPollAt = now;
            lastPollNano = nanoNow;

            if (Duration.between(lastCacheRefresh, now).toSeconds() > CACHE_TTL.toSeconds()) {
                refreshCache();
            }

            Optional<Long> idleSeconds = WindowsNativeUtil.getIdleSeconds();
            if (idleSeconds.filter(seconds -> seconds >= idleTimeoutSeconds).isPresent()) {
                // 用户长时间没有键鼠输入时，按最后输入时间截断会话，而不是继续累计到当前时刻。
                closeCurrentSession(now.minusSeconds(idleSeconds.orElse((long) idleTimeoutSeconds)));
                return;
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
                    // 前台切换到新的被监控应用：先关闭旧会话，再开启新会话。
                    switchToApp(matched, now);
                } else {
                    // 仍然是同一个应用：只更新 last_seen_time，表示会话还活着。
                    usageRecordDao.heartbeatSession(matched.getId(), now);
                }
            } else {
                // 前台不是被监控应用，当前会话结束。
                closeCurrentSession(now);
            }
        } catch (Exception e) {
            System.err.println("[ForegroundMonitorService] polling failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            try {
                closeCurrentSession(LocalDateTime.now());
            } catch (Exception closeError) {
                System.err.println("[ForegroundMonitorService] failed to close session after polling error: " + closeError.getMessage());
            }
        }
    }

    /**
     * 切换到新聚焦的被监控应用。
     * 数据库层只允许一个未关闭会话，因此这里先收尾旧会话，再创建新会话。
     */

    private void switchToApp(MonitoredApp app, LocalDateTime now) {
        closeCurrentSession(now);

        usageRecordDao.startSession(app.getId(), now);
        currentActiveAppId = app.getId();
        currentSessionStart = now;
    }

    /**
     * Closes the current session, clamping rollback times to the start time.
     */
    private void closeCurrentSession() {
        closeCurrentSession(LocalDateTime.now());
    }

    private void closeCurrentSession(LocalDateTime endTime) {
        if (currentActiveAppId == null || currentSessionStart == null) return;
        if (endTime.isBefore(currentSessionStart)) {
            endTime = currentSessionStart;
        }
        try {
            usageRecordDao.endSession(currentActiveAppId, endTime);
        } catch (Exception e) {
            System.err.println("[ForegroundMonitorService] closeCurrentSession failed: " + e.getMessage());
        }
        currentActiveAppId = null;
        currentSessionStart = null;
    }

    private boolean clockMovedBackwards(LocalDateTime now) {
        return lastPollAt != null && now.isBefore(lastPollAt.minusSeconds(5));
    }

    /**
     * 处理电脑休眠、程序暂停等导致的轮询间隔异常变长。
     * 如果继续按当前时间关闭，会把休眠期间也算成使用时间，所以这里用 lastPollAt 截断。
     */
    private void closeSessionIfPollingStalled(LocalDateTime now, long nanoNow) {
        if (lastPollAt == null || lastPollNano == 0L) return;
        long gapSeconds = TimeUnit.NANOSECONDS.toSeconds(nanoNow - lastPollNano);
        long maxExpectedGap = Math.max(MIN_STALE_POLL_GAP_SECONDS, pollIntervalSeconds * 3L);
        if (gapSeconds > maxExpectedGap) {
            closeCurrentSession(lastPollAt);
            System.out.println("[ForegroundMonitorService] polling stalled for " + gapSeconds + " seconds; current session truncated");
        }
    }

    // ---- Internal helpers ----

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

    /**
     * 从数据库刷新被监控应用列表，构建“进程名 -> 应用对象”的快速查找表。
     * 缓存 30 秒刷新一次，避免每次轮询都访问数据库。
     */
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
            System.err.println("[ForegroundMonitorService] failed to refresh monitored app cache: " + e.getMessage());
            cachedAppMap = Map.of();
            closeCurrentSession(LocalDateTime.now());
        }
    }

    /**
     * 计算距离明天 00:01 的秒数，用于安排每日汇总任务。
     */
    private long computeDelayToMidnight() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime nextFlush = now.toLocalDate().plusDays(1)
                .atTime(0, 1)
                .atZone(zone);
        return Duration.between(now, nextFlush).getSeconds();
    }
}
