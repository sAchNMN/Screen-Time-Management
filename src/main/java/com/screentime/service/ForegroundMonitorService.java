/* ============================================================
 *  ForegroundMonitorService.java — 前台窗口监控引擎
 *  后台定时检测用户当前活动窗口是否属于被监控应用：
 *    - 通过 WindowsNativeUtil 获取前台窗口进程名和完整路径
 *    - 与内存中缓存的监控列表对比（O(1) HashMap 查找）
 *    - 在焦点切换时写入 usage_records（开始/结束会话）
 *    - 每天凌晨自动将昨天数据聚合写入 daily_summary
 *    - 轮询间隔可由用户设置（1-60 秒，默认 15 秒）
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

    /* 调度器在 start() 中懒创建，stop() 后重建，不会出现 shutdown 后无法重启 */
    private ScheduledExecutorService scheduler;

    // 当前正在使用的被监控应用（null 表示前台不是被监控应用）
    private volatile Integer currentActiveAppId = null;

    // 被监控应用内存缓存（小写进程名 → MonitoredApp，O(1) 查找）
    private volatile Map<String, MonitoredApp> cachedAppMap = Map.of();
    private volatile LocalDateTime lastCacheRefresh = LocalDateTime.MIN;

    // 应用完整路径缓存 (appId → fullPath)，供图标提取
    private final Map<Integer, String> appFullPathCache = new ConcurrentHashMap<>();

    // 可动态调整的轮询间隔（秒）
    private volatile int pollIntervalSeconds = 15;
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private volatile ScheduledFuture<?> pollingTask;
    private volatile ScheduledFuture<?> dailyFlushTask;

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

        // 如果 scheduler 已被 shutdown，重建一个
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

        monitoredAppDao.initTable();
        usageRecordDao.initTable();
        refreshCache();

        // 启动主轮询任务
        startPolling();

        // 每日汇总刷新任务（每天 00:01 执行，带时区感知）
        long delayToMidnight = computeDelayToMidnight();
        // 使用 scheduleWithFixedDelay 而非 scheduleAtFixedRate，
        // 防止 flush 执行超时导致后续跳过
        dailyFlushTask = scheduler.scheduleWithFixedDelay(
                usageRecordDao::flushYesterday,
                delayToMidnight,
                24 * 60 * 60,
                TimeUnit.SECONDS
        );

        System.out.println("[ForegroundMonitorService] 监控引擎已启动，轮询间隔 " + pollIntervalSeconds + " 秒");
    }

    /**
     * 停止后台监控引擎。
     * 先取消轮询任务，再关闭当前会话，最后 shutdown 调度器。
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        // 先取消轮询任务，防止与下面的会话关闭操作冲突
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }
        if (dailyFlushTask != null) {
            dailyFlushTask.cancel(false);
            dailyFlushTask = null;
        }

        // 关闭当前会话（只执行一次）
        if (currentActiveAppId != null) {
            usageRecordDao.endSession(currentActiveAppId, LocalDateTime.now());
            currentActiveAppId = null;
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
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

        if (running && scheduler != null && !scheduler.isShutdown()) {
            startPolling(); // 取消旧任务，按新间隔启动
        }
        System.out.println("[ForegroundMonitorService] 轮询间隔已更新为 " + seconds + " 秒");
    }

    @SuppressWarnings("unused")
    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    /**
     * 启动/重启主轮询任务。
     * initialDelay 设为 1 秒而非 pollIntervalSeconds，
     * 让设置变更后能快速生效。
     */
    private void startPolling() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        if (scheduler == null || scheduler.isShutdown()) return;
        pollingTask = scheduler.scheduleWithFixedDelay(
                this::poll,
                1,                       // initialDelay = 1s (快速响应)
                pollIntervalSeconds,     // delay between polls
                TimeUnit.SECONDS
        );
    }

    // ---- 公共查询方法（供 StatisticsController / SettingsController 使用） ----

    /**
     * 获取当前正在追踪的 appId（null 表示未追踪）。
     */
    public Integer getCurrentActiveAppId() {
        return currentActiveAppId;
    }

    /**
     * 获取某个被监控应用的完整路径（用于图标提取和调试）。
     */
    public String getAppFullPath(int appId) {
        return appFullPathCache.get(appId);
    }

    /**
     * 获取某个被监控应用的图标。
     * IconUtil.getIcon 线程安全（内部使用 ConcurrentHashMap 缓存）。
     * 返回的 Image 对象由 BufferedImage 直接生成，无需 FX 线程加载。
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
        // 启动防护：如果引擎已停止，不再执行
        if (!running) return;

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

            // O(1) HashMap 查找
            MonitoredApp matched = cachedAppMap.get(foregroundProc);

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
            System.err.println("[ForegroundMonitorService] 轮询异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 切换到新的被监控应用。
     */
    private void switchToApp(MonitoredApp app) {
        System.out.println("[ForegroundMonitorService] switchToApp: 切换到应用 '" + app.getAppName() + "' (ID=" + app.getId() + ")");
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
            try {
                usageRecordDao.endSession(currentActiveAppId, LocalDateTime.now());
            } catch (Exception e) {
                System.err.println("[ForegroundMonitorService] closeCurrentSession 失败: " + e.getMessage());
            }
            currentActiveAppId = null;
        }
    }

    /**
     * 刷新监控列表缓存。
     * 构建 O(1) 小写进程名 → MonitoredApp 的 HashMap。
     * 同时清理 appFullPathCache 中已不存在的条目。
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

            // 清理路径缓存：去掉不再被监控的 app 的条目
            if (!appFullPathCache.isEmpty()) {
                var currentIds = apps.stream().map(MonitoredApp::getId).collect(Collectors.toSet());
                appFullPathCache.keySet().retainAll(currentIds);
            }
        } catch (Exception e) {
            System.err.println("[ForegroundMonitorService] 刷新监控列表缓存失败: " + e.getMessage());
        }
    }

    /**
     * 计算距下次凌晨 00:01 的秒数。
     * 使用 ZonedDateTime 显式绑定时区，避免跨时区部署时偏移。
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
