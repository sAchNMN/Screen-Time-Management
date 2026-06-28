package com.screentime.dao;

import com.screentime.model.MonitoredApp;
import com.screentime.util.DatabaseUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageRecordDaoTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void useTemporaryDatabase() {
        System.setProperty("screentime.db.path", tempDir.resolve("screentime.db").toString());
        DatabaseUtil.consumeDatabaseRecreatedFlag();
    }

    @Test
    void startSessionDiscardsStaleOpenSessions() {
        MonitoredAppDao appDao = new MonitoredAppDao();
        UsageRecordDao usageDao = new UsageRecordDao();
        appDao.initTable();
        usageDao.initTable();
        usageDao.discardOpenSessions();

        MonitoredApp oldApp = appDao.insert(new MonitoredApp("Old App", "old.exe"));
        MonitoredApp foregroundApp = appDao.insert(new MonitoredApp("Foreground App", "foreground.exe"));

        usageDao.startSession(oldApp.getId(), LocalDateTime.now().minusHours(4));
        assertEquals(1, usageDao.countOpenSessions());
        assertEquals(1, usageDao.countOpenSessionsForApp(oldApp.getId()));

        usageDao.startSession(foregroundApp.getId(), LocalDateTime.now().minusMinutes(5));

        assertEquals(1, usageDao.countOpenSessions());
        assertEquals(0, usageDao.countOpenSessionsForApp(oldApp.getId()));
        assertEquals(1, usageDao.countOpenSessionsForApp(foregroundApp.getId()));

        Map<Integer, Integer> todayUsage = usageDao.getTodayUsage();
        assertEquals(0, todayUsage.get(oldApp.getId()));
        assertTrue(todayUsage.get(foregroundApp.getId()) > 0);
    }

    @Test
    void discardOpenSessionsRemovesLegacyActiveSessions() {
        MonitoredAppDao appDao = new MonitoredAppDao();
        UsageRecordDao usageDao = new UsageRecordDao();
        appDao.initTable();
        usageDao.initTable();
        usageDao.discardOpenSessions();

        MonitoredApp app = appDao.insert(new MonitoredApp("Legacy App", "legacy.exe"));
        usageDao.startSession(app.getId(), LocalDateTime.now().minusHours(2));

        assertEquals(1, usageDao.discardOpenSessions());
        assertEquals(0, usageDao.countOpenSessions());
        assertEquals(0, usageDao.getTodayUsage().get(app.getId()));
    }

    @Test
    void recoverOpenSessionsClosesAtLastHeartbeat() {
        MonitoredAppDao appDao = new MonitoredAppDao();
        UsageRecordDao usageDao = new UsageRecordDao();
        appDao.initTable();
        usageDao.initTable();

        MonitoredApp app = appDao.insert(new MonitoredApp("Recovered App", "recovered.exe"));
        LocalDateTime start = LocalDate.now().atTime(9, 0);
        usageDao.startSession(app.getId(), start);
        usageDao.heartbeatSession(app.getId(), start.plusMinutes(5));

        assertEquals(1, usageDao.recoverOpenSessions(0));
        assertEquals(0, usageDao.countOpenSessions());
        assertEquals(300, usageDao.getTodayUsage().get(app.getId()));
    }

    @Test
    void databaseIsRecreatedAfterFileDeletion() throws Exception {
        Path dbPath = tempDir.resolve("screentime.db");
        MonitoredAppDao appDao = new MonitoredAppDao();
        appDao.initTable();
        appDao.insert(new MonitoredApp("Deleted Db App", "deleted-db.exe"));
        DatabaseUtil.consumeDatabaseRecreatedFlag();

        Files.deleteIfExists(dbPath);

        assertTrue(appDao.findAll().isEmpty());
        assertTrue(DatabaseUtil.consumeDatabaseRecreatedFlag());
    }

    @Test
    void endSessionSplitsUsageAcrossMidnight() {
        MonitoredAppDao appDao = new MonitoredAppDao();
        UsageRecordDao usageDao = new UsageRecordDao();
        appDao.initTable();
        usageDao.initTable();

        MonitoredApp app = appDao.insert(new MonitoredApp("Cross Day App", "cross-day.exe"));
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(1).atTime(23, 50);
        LocalDateTime end = today.atTime(0, 10);

        usageDao.startSession(app.getId(), start);
        usageDao.endSession(app.getId(), end);

        assertEquals(600, usageDao.getTodayUsage().get(app.getId()));
    }

    @Test
    void recentHistoryDoesNotDoubleCountFlushedRecords() {
        MonitoredAppDao appDao = new MonitoredAppDao();
        UsageRecordDao usageDao = new UsageRecordDao();
        appDao.initTable();
        usageDao.initTable();

        MonitoredApp app = appDao.insert(new MonitoredApp("Flushed App", "flushed.exe"));
        LocalDate yesterday = LocalDate.now().minusDays(1);
        usageDao.startSession(app.getId(), yesterday.atTime(10, 0));
        usageDao.endSession(app.getId(), yesterday.atTime(10, 10));
        usageDao.flushYesterday();

        Map<String, Map<LocalDate, Integer>> history = usageDao.getRecentUsageHistory();
        assertEquals(600, history.get("Flushed App").get(yesterday));
    }

    @Test
    void importRowsSkipsRowsWithoutEndTime() {
        MonitoredAppDao appDao = new MonitoredAppDao();
        UsageRecordDao usageDao = new UsageRecordDao();
        appDao.initTable();
        usageDao.initTable();

        LocalDateTime start = LocalDateTime.now().minusMinutes(5);
        int imported = usageDao.importRows(List.of(
                new String[]{"应用名", "日期", "开始时间", "结束时间", "持续秒数", "持续时长"},
                new String[]{"Imported App", start.toLocalDate().toString(), start.toString(), "", "", ""}
        ));

        assertEquals(0, imported);
        assertEquals(0, usageDao.countOpenSessions());
        assertEquals(0, appDao.findAll().size());
    }

    @Test
    void importRowsDetailedReportsSkippedRows() {
        MonitoredAppDao appDao = new MonitoredAppDao();
        UsageRecordDao usageDao = new UsageRecordDao();
        appDao.initTable();
        usageDao.initTable();

        LocalDateTime start = LocalDateTime.now().minusMinutes(5);
        UsageRecordDao.ImportResult result = usageDao.importRowsDetailed(List.of(
                new String[]{"app", "date", "start", "end", "seconds", "duration"},
                new String[]{"Imported App", start.toLocalDate().toString(), start.toString(), "", "", ""}
        ));

        assertEquals(0, result.imported());
        assertEquals(1, result.skipped());
        assertTrue(result.warnings().getFirst().contains("missing end time"));
    }
}
