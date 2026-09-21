package com.project.Chok.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.dto.NewsArticle;
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
            "https://m.stock.naver.com/api/news/stock/%s?pageSize=%d&page=1";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public List<NewsArticle> fetchRecentNews(String ticker, int maxCount) {
        List<NewsArticle> articles = new ArrayList<>();
        String url = String.format(NEWS_URL_TEMPLATE, ticker, maxCount);

        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://m.stock.naver.com/")
                    .ignoreContentType(true)
                    .timeout(7000)
                    .execute();

            JsonNode groups = objectMapper.readTree(response.body());

            for (JsonNode group : groups) {
                for (JsonNode item : group.path("items")) {
                    if (articles.size() >= maxCount) break;

                    String headline = item.path("title").asText("").trim();
                    String fullUrl = item.path("mobileNewsUrl").asText("");
                    if (headline.isEmpty() || fullUrl.isEmpty()) continue;

                    LocalDate date;
                    try {
                        date = LocalDate.parse(item.path("datetime").asText().substring(0, 8),
                                DateTimeFormatter.BASIC_ISO_DATE);
                    } catch (Exception e) {
                        date = LocalDate.now();
                    }

                    articles.add(new NewsArticle(headline, fullUrl, date));
                }
            }

        } catch (Exception e) {
            log.error("뉴스 수집 실패 (ticker={}): {}", ticker, e.getMessage());
        }

        return articles;
    }
}
