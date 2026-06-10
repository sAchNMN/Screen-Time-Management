-- ============================================================
--  schema.sql — 数据库建表 DDL
--  包含全部 4 张业务表：
--    - monitored_apps:  被监控的应用清单
--    - usage_records:   单次使用时间段记录
--    - daily_summary:   按天汇总的使用时长
--    - app_settings:    通用键值对设置
--  由 MonitoredAppDao.initTable() 和
--  AppSettingsDao.initTable() 在运行时自动执行
-- ============================================================

CREATE TABLE IF NOT EXISTS monitored_apps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    app_name    TEXT NOT NULL,
    process_name TEXT NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS usage_records (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    app_id      INTEGER NOT NULL,
    start_time  TIMESTAMP NOT NULL,
    end_time    TIMESTAMP,
    duration_seconds INTEGER,
    FOREIGN KEY (app_id) REFERENCES monitored_apps(id)
);

CREATE TABLE IF NOT EXISTS daily_summary (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    app_id      INTEGER NOT NULL,
    date        DATE NOT NULL,
    total_seconds INTEGER DEFAULT 0,
    FOREIGN KEY (app_id) REFERENCES monitored_apps(id),
    UNIQUE(app_id, date)
);

CREATE TABLE IF NOT EXISTS app_settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
