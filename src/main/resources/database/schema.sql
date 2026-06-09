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
