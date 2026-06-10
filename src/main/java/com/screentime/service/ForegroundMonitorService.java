/* ============================================================
 *  ForegroundMonitorService.java — 前台窗口监控引擎
 *  后台定时（每15秒）检测用户当前活动窗口是否属于被监控应用：
 *    - 通过 WindowsNativeUtil 获取前台窗口进程名
 *    - 与内存中缓存的监控列表对比
 *    - 在焦点切换时写入 usage_records（开始/结束会话）
 *    - 每天凌晨自动将昨天数据聚合写入 daily_summary
 *
 *  性能设计：
 *    - 仅 1 个守护线程，15 秒轮询间隔
 *    - 每次轮询仅 1 次 JNA 调用 + 1 次 ProcessHandle.of()
 *    - DB 写入仅在焦点切换时发生
 *    - 监控列表缓存于内存，每 30 秒刷新一次
 * ============================================================ */
package com.screentime.service;

import com.screentime.dao.MonitoredAppDao;
import com.screentime.dao.UsageRecordDao;
import com.screentime.model.MonitoredApp;
import com.screentime.util.WindowsNativeUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ForegroundMonitorService {

    private static final ForegroundMonitorService INSTANCE = new ForegroundMonitorService();

    private final UsageRecordDao usageRecordDao = new UsageRecordDao();
    private final MonitoredAppDao monitoredAppDao = new MonitoredAppDao();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "foreground-monitor");
                t.setDaemon(true);
                return t;
            });

    // 当前正在使用的被监控应用（null 表示前台不是被监控应用）
    private volatile Integer currentActiveAppId = null;
    private volatile LocalDateTime currentSessionStart = null;

    // 被监控应用内存缓存
    private volatile List<MonitoredApp> cachedApps = List.of();
    private volatile LocalDateTime lastCacheRefresh = LocalDateTime.MIN;

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(15);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

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

        monitoredAppDao.initTable();
        refreshCache();

        // 主轮询任务
        scheduler.scheduleWithFixedDelay(
                this::poll,
                2,                      // 首次延迟 2 秒
                POLL_INTERVAL.getSeconds(),
                TimeUnit.SECONDS
        );

        // 每日汇总刷新任务（每天 00:01 执行）
        long delayToMidnight = computeDelayToMidnight();
        scheduler.scheduleAtFixedRate(
                usageRecordDao::flushYesterday,
                delayToMidnight,
                24 * 60 * 60,
                TimeUnit.SECONDS
        );

        System.out.println("[ForegroundMonitorService] 监控引擎已启动，轮询间隔 " + POLL_INTERVAL.getSeconds() + " 秒");
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
            currentSessionStart = null;
        }

        scheduler.shutdownNow();
        System.out.println("[ForegroundMonitorService] 监控引擎已停止");
    }

    // ---- 核心轮询逻辑 ----

    private void poll() {
        try {
            // 定期刷新监控列表缓存
            if (Duration.between(lastCacheRefresh, LocalDateTime.now()).compareTo(CACHE_TTL) > 0) {
                refreshCache();
            }

            Optional<String> foregroundProcOpt = WindowsNativeUtil.getForegroundProcessName();
            if (foregroundProcOpt.isEmpty()) {
                closeCurrentSession();
                return;
            }

            String foregroundProc = foregroundProcOpt.get().toLowerCase();
            MonitoredApp matched = findMatch(foregroundProc);

            if (matched != null) {
                // 前台窗口属于被监控应用
                if (currentActiveAppId == null || currentActiveAppId != matched.getId()) {
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
        currentSessionStart = now;
    }

    private void closeCurrentSession() {
        if (currentActiveAppId != null) {
            usageRecordDao.endSession(currentActiveAppId, LocalDateTime.now());
            currentActiveAppId = null;
            currentSessionStart = null;
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

    // ---- 公共查询方法（供 StatisticsController 使用） ----

    public UsageRecordDao getUsageRecordDao() {
        return usageRecordDao;
    }

    public MonitoredAppDao getMonitoredAppDao() {
        return monitoredAppDao;
    }
}
