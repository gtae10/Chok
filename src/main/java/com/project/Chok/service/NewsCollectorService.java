package com.project.Chok.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.dto.NewsArticle;
import org.jsoup.HttpStatusException;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class NewsCollectorService {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectorService.class);

    // 2026-09-21: finance.naver.com/item/news_news.naver 가 410 Gone으로 폐지되어
    // 네이버 신규 모바일 API로 교체
    private static final String NEWS_URL_TEMPLATE =
            "https://m.stock.naver.com/api/news/stock/%s?pageSize=%d&page=%d";
    private static final int PAGE_SIZE = 20;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public List<NewsArticle> fetchRecentNews(String ticker, int maxCount) {
        List<NewsArticle> articles = fetchPage(ticker, 1, maxCount);
        return articles.size() > maxCount ? articles.subList(0, maxCount) : articles;
    }

    /**
     * since 날짜(포함) 이후 기사를 페이지를 넘기며 가져온다. 앱이 꺼져 있던 기간을 메우는 용도.
     * 페이지 기사가 전부 since보다 오래됐거나 빈 페이지가 나오면 멈춘다.
     * 중간 페이지가 실패하면 예외를 던진다 - 일부만 저장하면 최신 뉴스 날짜가 오늘로 뛰어 공백이 영구화된다.
     */
    public List<NewsArticle> fetchNewsSince(String ticker, LocalDate since, int maxPages) {
        List<NewsArticle> result = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            List<NewsArticle> batch = fetchPage(ticker, page, PAGE_SIZE);
            if (batch.isEmpty()) return result;
            batch.stream().filter(a -> !a.getDate().isBefore(since)).forEach(result::add);
            if (batch.stream().allMatch(a -> a.getDate().isBefore(since))) return result;
        }
        log.warn("뉴스 따라잡기 {}페이지 상한 도달 (ticker={}, since={}) - 그 이전 기사는 빠짐", maxPages, ticker, since);
        return result;
    }

    /** 실패(HTTP 오류·응답 형식 변경)는 예외로 던진다 - 빈 리스트는 "뉴스 없음"만 뜻한다. */
    private List<NewsArticle> fetchPage(String ticker, int page, int pageSize) {
        List<NewsArticle> articles = new ArrayList<>();
        String url = String.format(NEWS_URL_TEMPLATE, ticker, pageSize, page);

        JsonNode groups;
        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://m.stock.naver.com/")
                    .ignoreContentType(true)
                    .timeout(7000)
                    .execute();
            groups = objectMapper.readTree(response.body());
        } catch (HttpStatusException e) {
            // 네이버는 마지막 페이지를 넘기면 빈 배열 대신 400을 준다 (2026-11-10 확인) - 2페이지부터는 "끝"으로 본다
            if (e.getStatusCode() == 400 && page > 1) {
                log.info("뉴스 마지막 페이지 넘김 (HTTP 400, ticker={}, page={})", ticker, page);
                return articles;
            }
            throw new IllegalStateException("뉴스 수집 실패 (ticker=" + ticker + ", page=" + page + "): HTTP " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new IllegalStateException("뉴스 수집 실패 (ticker=" + ticker + ", page=" + page + "): " + e.getMessage(), e);
        }
        if (!groups.isArray()) {
            throw new IllegalStateException("뉴스 응답 형식이 바뀜 (ticker=" + ticker + "): 최상위가 배열이 아님");
        }

        int badDate = 0;
        for (JsonNode group : groups) {
            JsonNode items = group.path("items");
            if (!items.isArray()) {
                throw new IllegalStateException("뉴스 응답 형식이 바뀜 (ticker=" + ticker + "): items가 배열이 아님");
            }
            for (JsonNode item : items) {
                String headline = item.path("title").asText("").trim();
                String fullUrl = item.path("mobileNewsUrl").asText("");
                if (headline.isEmpty() || fullUrl.isEmpty()) continue;

                LocalDate date;
                try {
                    date = LocalDate.parse(item.path("datetime").asText().substring(0, 8),
                            DateTimeFormatter.BASIC_ISO_DATE);
                } catch (Exception e) {
                    badDate++;
                    continue;
                }

                articles.add(new NewsArticle(headline, fullUrl, date,
                        org.jsoup.parser.Parser.unescapeEntities(item.path("body").asText(""), false)));
            }
        }
        if (badDate > 0) {
            log.warn("날짜를 읽지 못한 뉴스 {}건 건너뜀 (ticker={}, page={})", badDate, ticker, page);
        }

        return articles;
    }
}
