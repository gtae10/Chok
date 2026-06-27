package com.project.Chok.dto;

import java.time.LocalDate;

public class NewsArticle {

    private String headline;
    private String url;
    private LocalDate date;

    public NewsArticle(String headline, String url, LocalDate date) {
        this.headline = headline;
        this.url = url;
        this.date = date;
    }

    public String getHeadline() { return headline; }
    public String getUrl() { return url; }
    public LocalDate getDate() { return date; }
}