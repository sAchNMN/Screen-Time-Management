/* ============================================================
 *  UsageRecord.java — 单次使用记录模型
 *  记录被监控应用的一次连续使用：
 *    - appId:          被监控应用 ID
 *    - startTime:      本次使用开始时间
 *    - endTime:        本次使用结束时间
 *    - durationSeconds: 持续秒数
 * ============================================================ */
package com.screentime.model;

import java.time.LocalDateTime;

public class UsageRecord {

    private Integer id;
    private Integer appId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationSeconds;

    public UsageRecord() {
    }

    public UsageRecord(Integer appId, LocalDateTime startTime, LocalDateTime endTime, Integer durationSeconds) {
        this.appId = appId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationSeconds = durationSeconds;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getAppId() {
        return appId;
    }

    public void setAppId(Integer appId) {
        this.appId = appId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }
}
