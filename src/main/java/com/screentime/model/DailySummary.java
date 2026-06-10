/* ============================================================
 *  DailySummary.java — 日汇总模型
 *  记录某个被监控应用在某一天的总使用时长：
 *    - appId:        被监控应用 ID
 *    - date:         日期
 *    - totalSeconds: 当天累计使用秒数
 * ============================================================ */
package com.screentime.model;

import java.time.LocalDate;

public class DailySummary {

    private Integer id;
    private Integer appId;
    private LocalDate date;
    private Integer totalSeconds;

    public DailySummary() {
    }

    public DailySummary(Integer appId, LocalDate date, Integer totalSeconds) {
        this.appId = appId;
        this.date = date;
        this.totalSeconds = totalSeconds;
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

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getTotalSeconds() {
        return totalSeconds;
    }

    public void setTotalSeconds(Integer totalSeconds) {
        this.totalSeconds = totalSeconds;
    }
}
