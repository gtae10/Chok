package com.project.Chok.dto;

public class StockReportResponse {

    private final String reportText;
    private final boolean cached;
    private final String generatedAt;

    public StockReportResponse(String reportText, boolean cached, String generatedAt) {
        this.reportText = reportText;
        this.cached = cached;
        this.generatedAt = generatedAt;
    }

    public String getReportText() { return reportText; }
    public boolean isCached() { return cached; }
    public String getGeneratedAt() { return generatedAt; }
}
