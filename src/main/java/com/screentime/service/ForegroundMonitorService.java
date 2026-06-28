/* ============================================================
 *  ForegroundMonitorService.java - foreground window monitor
 *  Polls the Windows foreground process and records focused usage
 *  time for monitored applications only.
 *
 *  Data rules:
 *    - start a session when a monitored app becomes foreground
 *    - update a heartbeat while the same app stays foreground
 *    - close sessions on app switch, idle timeout, polling stalls,
 *      clock rollback, or monitor shutdown
 *    - split completed sessions across midnight for daily summaries
 *
 *  Runtime design: one daemon scheduler thread, cached monitored app
 *  lookup, and database writes only when state changes or heartbeats.
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
     * Starts the background foreground monitor.
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
     * Stops the background foreground monitor.
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
                    switchToApp(matched, now);
                } else {
                    usageRecordDao.heartbeatSession(matched.getId(), now);
                }
            } else {
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
     * Switches to a newly focused monitored app.
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

    private long computeDelayToMidnight() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime nextFlush = now.toLocalDate().plusDays(1)
                .atTime(0, 1)
                .atZone(zone);
        return Duration.between(now, nextFlush).getSeconds();
    }
}
