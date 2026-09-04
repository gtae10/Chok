package com.project.Chok.dto;

import java.util.List;

public class PerformanceSummaryResponse {

    private int totalCount;
    private Double winRate;
    private Double avgReturnRate;
    private List<PerformanceItemResponse> items;

    public PerformanceSummaryResponse(int totalCount, Double winRate, Double avgReturnRate,
                                       List<PerformanceItemResponse> items) {
        this.totalCount = totalCount;
        this.winRate = winRate;
        this.avgReturnRate = avgReturnRate;
        this.items = items;
    }

    public int getTotalCount() { return totalCount; }
    public Double getWinRate() { return winRate; }
    public Double getAvgReturnRate() { return avgReturnRate; }
    public List<PerformanceItemResponse> getItems() { return items; }
}
