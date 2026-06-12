/* ============================================================
 *  ChartController.java — 统计图表页面控制器
 *  展示使用数据的图表（柱状图 + 饼图 + 排行表）
 * ============================================================ */
package com.screentime.controller;

import com.screentime.dao.MonitoredAppDao;
import com.screentime.dao.UsageRecordDao;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.*;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

import java.time.LocalDate;
import java.util.*;

public class ChartController {

    @FXML
    private Button btnRefresh;
    @FXML
    private BarChart<String, Number> barChart;
    @FXML
    private PieChart pieChart;
    @FXML
    private TableView<RankRow> tblRanking;
    @FXML
    private TableColumn<RankRow, String> colRankApp;
    @FXML
    private TableColumn<RankRow, Integer> colRankToday;
    @FXML
    private TableColumn<RankRow, Integer> colRankWeek;
    @FXML
    private TableColumn<RankRow, Integer> colRankMonth;

    private final UsageRecordDao usageRecordDao = new UsageRecordDao();
    private final MonitoredAppDao appDao = new MonitoredAppDao();
    private final ObservableList<RankRow> rankData = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        colRankApp.setCellValueFactory(new PropertyValueFactory<>("appName"));
        colRankToday.setCellValueFactory(new PropertyValueFactory<>("todayMinutes"));
        colRankWeek.setCellValueFactory(new PropertyValueFactory<>("weekMinutes"));
        colRankMonth.setCellValueFactory(new PropertyValueFactory<>("monthMinutes"));
        tblRanking.setItems(rankData);
        tblRanking.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        btnRefresh.setOnAction(e -> refreshData());

        refreshData();
    }

    private void refreshData() {
        // 刷新柱状图（近7天）
        barChart.getData().clear();
        Map<String, Map<LocalDate, Integer>> history = usageRecordDao.getRecentUsageHistory();
        for (var entry : history.entrySet()) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(entry.getKey());
            for (var day : entry.getValue().entrySet()) {
                series.getData().add(new XYChart.Data<>(day.getKey().toString(),
                        day.getValue() / 60)); // 转为分钟
            }
            barChart.getData().add(series);
        }

        // 刷新饼图（今日占比）
        pieChart.getData().clear();
        Map<Integer, Integer> todayById = usageRecordDao.getTodayUsage();
        if (todayById.isEmpty()) {
            pieChart.getData().add(new PieChart.Data("(暂无数据)", 1));
        } else {
            Map<Integer, String> nameMap = getAppNameMap();
            for (var entry : todayById.entrySet()) {
                String name = nameMap.getOrDefault(entry.getKey(), "应用#" + entry.getKey());
                pieChart.getData().add(new PieChart.Data(name, entry.getValue() / 60));
            }
        }

        // 刷新排行表（基于近7天 + 本月数据）
        rankData.setAll(computeRanking(history));
    }

    private Map<Integer, String> getAppNameMap() {
        Map<Integer, String> map = new HashMap<>();
        var apps = appDao.findAll();
        for (var app : apps) {
            map.put(app.getId(), app.getAppName());
        }
        return map;
    }

    /**
     * 从历史数据中计算排行（今日/本周/本月分钟数）。
     */
    private List<RankRow> computeRanking(Map<String, Map<LocalDate, Integer>> history) {
        List<RankRow> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(6);

        for (var appEntry : history.entrySet()) {
            String appName = appEntry.getKey();
            int todayMin = 0;
            int weekMin = 0;
            int monthMin = 0;

            for (var dayEntry : appEntry.getValue().entrySet()) {
                LocalDate date = dayEntry.getKey();
                int min = dayEntry.getValue() / 60;

                if (date.equals(today)) {
                    todayMin += min;
                }
                if (!date.isBefore(weekStart) && !date.isAfter(today)) {
                    weekMin += min;
                }
                if (date.getMonth().equals(today.getMonth()) && date.getYear() == today.getYear()) {
                    monthMin += min;
                }
            }

            result.add(new RankRow(appName, todayMin, weekMin, monthMin));
        }

        return result;
    }

    public static class RankRow {
        private final String appName;
        private final int todayMinutes;
        private final int weekMinutes;
        private final int monthMinutes;

        public RankRow(String appName, int todayMinutes, int weekMinutes, int monthMinutes) {
            this.appName = appName;
            this.todayMinutes = todayMinutes;
            this.weekMinutes = weekMinutes;
            this.monthMinutes = monthMinutes;
        }

        public String getAppName() { return appName; }
        public int getTodayMinutes() { return todayMinutes; }
        public int getWeekMinutes() { return weekMinutes; }
        public int getMonthMinutes() { return monthMinutes; }
    }
}
