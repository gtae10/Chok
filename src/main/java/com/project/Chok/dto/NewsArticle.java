package com.project.Chok.dto;

import java.time.LocalDate;

public class NewsArticle {

    private String headline;
    private String url;
    private LocalDate date;
    private String body; // 본문 앞부분(API 제공, 없을 수 있음) - 종목 관련성 판정에만 쓰고 저장하지 않는다

    public NewsArticle(String headline, String url, LocalDate date) {
        this(headline, url, date, null);
    }

    public NewsArticle(String headline, String url, LocalDate date, String body) {
        this.headline = headline;
        this.url = url;
        this.date = date;
        this.body = body;
    }

    public String getBody() { return body; }

    public String getHeadline() { return headline; }
    public String getUrl() { return url; }
    public LocalDate getDate() { return date; }
}